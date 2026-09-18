package com.falcon.robot.voice;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.falcon.robot.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * whisper.cpp speech recognition (see {@code app/src/main/cpp}).
 *
 * <p>Models are ggml files such as {@code ggml-small.bin}, read from {@link #getModelDir} on the
 * device:
 *
 * <pre>
 *   adb push ggml-small.bin /sdcard/Android/data/com.falcon.robot/files/models/
 * </pre>
 *
 * <p>A model shipped in {@code app/src/main/assets} is used as it is: the native side maps it
 * straight out of the APK (which is why {@code build.gradle} keeps {@code .bin} files
 * uncompressed), so it needs no copy and no extra room on the device. The APK grows by the size
 * of the model.
 *
 * <p>All calls must be made from a background thread.
 */
public final class WhisperEngine {

    private static final String TAG = "WhisperEngine";
    /** What the stub reports when whisper.cpp was not compiled in (see cpp/whisper_jni_stub.cpp). */
    private static final String STUB_MARKER = "whisper.cpp not built";
    public static final String DEFAULT_MODEL = "ggml-small.bin";

    private static boolean libraryLoaded;

    private String loadError;

    static {
        try {
            System.loadLibrary("whisper_jni");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libwhisper_jni.so is missing", e);
        }
    }

    public static final class Result {
        public final String text;
        /** Average token probability reported by whisper, 0..1. */
        public final float confidence;

        Result(String text, float confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    private final Context context;
    private long handle;
    private String loadedModel;

    public WhisperEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean isLibraryAvailable() {
        return libraryLoaded;
    }

    /**
     * False when the app was built without the whisper.cpp sources: the library then contains the
     * stub, which loads but transcribes nothing. Worth telling apart from a missing model file,
     * because the fix is a clone and a rebuild rather than another download.
     */
    public static boolean isEngineBuilt() {
        return libraryLoaded && !systemInfo().startsWith(STUB_MARKER);
    }

    /** Folder the models are read from (created if missing). */
    public static File getModelDir(Context context) {
        File external = context.getExternalFilesDir(null);
        File dir = new File(external != null ? external : context.getFilesDir(), "models");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** ggml model files found on the device, newest first. */
    public static List<String> listModels(Context context) {
        List<String> names = new ArrayList<>();
        File[] files = getModelDir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".bin")) names.add(f.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** ggml models bundled in {@code app/src/main/assets}. */
    public static List<String> listAssetModels(Context context) {
        List<String> names = new ArrayList<>();
        try {
            String[] files = context.getAssets().list("");
            if (files != null) {
                for (String name : files) {
                    // the build tools also put folders here ("images", "webkit"), which never match
                    if (name != null && name.endsWith(".bin")) names.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not list the assets", e);
        }
        Collections.sort(names);
        return names;
    }

    /** Everything the user can pick: models on the device plus the ones bundled in the APK. */
    public static List<String> listAvailableModels(Context context) {
        List<String> names = listModels(context);
        for (String name : listAssetModels(context)) {
            if (!names.contains(name)) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    public static boolean isBundled(Context context, String modelName) {
        return listAssetModels(context).contains(modelName);
    }

    public boolean isReady() {
        return handle != 0;
    }

    public String getLoadedModel() {
        return loadedModel;
    }

    /** Loads a model; returns false when the library, the file or the model itself is unusable. */
    public boolean load(String modelName) {
        loadError = null;
        if (!libraryLoaded) {
            loadError = context.getString(R.string.engine_unavailable);
            return false;
        }
        if (!isEngineBuilt()) {
            loadError = context.getString(R.string.engine_not_built);
            return false;
        }
        if (handle != 0 && modelName.equals(loadedModel)) return true;
        release();

        File file = new File(getModelDir(context), modelName);
        long size = file.exists() ? file.length() : assetSize(modelName);
        if (size > 0 && !hasRoomFor(size)) return false; // loadError says how much was missing

        if (file.exists()) {
            handle = nativeInit(file.getAbsolutePath());
        } else if (isBundled(context, modelName)) {
            // read straight out of the APK, so a bundled model costs nothing extra on the device
            handle = nativeInitAsset(context.getAssets(), modelName);
        } else {
            Log.w(TAG, "Model not found: " + file + " and not bundled in the APK");
            loadError = context.getString(R.string.model_not_found, modelName,
                    getModelDir(context).getAbsolutePath());
            return false;
        }
        loadedModel = handle != 0 ? modelName : null;
        if (handle == 0 && loadError == null) {
            loadError = context.getString(R.string.model_load_failed, modelName);
        }
        return handle != 0;
    }

    /** Why the last {@link #load} failed, in words for the operator, or null after a good load. */
    public String getLoadError() {
        return loadError;
    }

    /**
     * whisper reads the whole model into memory and needs room to work on top of it, so a large
     * model on a small tablet dies inside the native code with nothing useful to show. Checking
     * first turns that into a message that says what to do.
     */
    private boolean hasRoomFor(long modelBytes) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return true;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        long needed = modelBytes + modelBytes / 4; // weights plus whisper's own buffers
        if (memory.availMem >= needed) return true;
        loadError = context.getString(R.string.model_too_large,
                needed / (1024 * 1024), memory.availMem / (1024 * 1024));
        Log.w(TAG, "Not enough memory: needs " + needed + " bytes, " + memory.availMem + " free");
        return false;
    }

    /** Size of a bundled model, or 0 when it is not in the APK. */
    private long assetSize(String modelName) {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(modelName)) {
            return descriptor.getLength();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Transcribes 16 kHz mono audio.
     *
     * @param language whisper language code ("en", "ko", …) or "auto"
     */
    public Result transcribe(float[] audio, String language, boolean translate, int threads) {
        if (handle == 0) return null;
        String raw = nativeTranscribe(handle, audio, threads, language, translate);
        if (raw == null) return null;
        int tab = raw.indexOf('\t');
        float confidence = 0f;
        String text = raw;
        if (tab >= 0) {
            try {
                confidence = Float.parseFloat(raw.substring(0, tab));
            } catch (NumberFormatException ignored) {
                // keep 0
            }
            text = raw.substring(tab + 1);
        }
        return new Result(text.trim(), confidence);
    }

    public void release() {
        if (handle != 0) {
            nativeRelease(handle);
            handle = 0;
            loadedModel = null;
        }
    }

    public static String systemInfo() {
        return libraryLoaded ? nativeSystemInfo() : "whisper_jni not loaded";
    }

    private static native long nativeInit(String modelPath);

    private static native long nativeInitAsset(android.content.res.AssetManager assets, String assetName);

    private static native void nativeRelease(long handle);

    private static native String nativeTranscribe(long handle, float[] audio, int threads,
                                                  String language, boolean translate);

    private static native String nativeSystemInfo();
}
