package com.falcon.robot.voice;

import android.content.Context;
import android.util.Log;

import ai.moonshine.voice.JNI;
import ai.moonshine.voice.Transcriber;
import ai.moonshine.voice.Transcript;
import ai.moonshine.voice.TranscriptLine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * English speech, from Moonshine.
 *
 * <p>Where {@link WhisperEngine} needs whisper.cpp cloned and compiled by whoever builds the app,
 * this arrives as an ordinary Gradle dependency with its own native library, so there is no NDK
 * step and no "speech engine not built". The model — 42 MB of it — ships in the APK, so it works
 * on a tablet that has never seen a network.
 *
 * <p>English only, on purpose: Moonshine's other languages are released under a non-commercial
 * licence, while the English models are MIT. Chinese, Japanese and Korean stay with whisper (or
 * with a command model trained on recordings, which needs no licence at all).
 *
 * <p>All calls must be made from a background thread.
 */
public final class MoonshineEngine {

    private static final String TAG = "MoonshineEngine";

    /** The bundled model: a folder of .ort files, as Moonshine's downloader lays them out. */
    public static final String MODEL = "tiny-kp";
    private static final String ASSETS = "moonshine";
    private static final int ARCH = JNI.MOONSHINE_MODEL_ARCH_TINY;
    private static final String[] FILES = {
            "encoder_model.ort", "decoder_model_merged.ort", "tokenizer.bin",
    };

    /**
     * Moonshine drops the last word when the audio stops dead — measured, repeatedly — so a little
     * silence is added on the end. {@link SpeechRecorder} already leaves some there; this is for
     * the times it does not.
     */
    private static final int TAIL_SAMPLES = SpeechRecorder.SAMPLE_RATE / 2;

    private final Context context;
    private Transcriber transcriber;
    private String loadError;

    public MoonshineEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isReady() {
        return transcriber != null;
    }

    /** Why the last load failed, or null. */
    public String getLoadError() {
        return loadError;
    }

    /** True when there is a model to load, without loading it. */
    public static boolean isBundled(Context context) {
        if (new File(models(context), FILES[0]).exists()) return true;
        try {
            String[] names = context.getAssets().list(ASSETS + "/" + MODEL);
            return names != null && names.length > 0;
        } catch (IOException notBundled) {
            return false;
        }
    }

    /** The folder the model is read from: the device's copy, filled from the APK the first time. */
    private static File models(Context context) {
        return new File(WhisperEngine.getModelDir(context), ASSETS + "/" + MODEL);
    }

    /** Loads the model, which takes a moment the first time because the APK copy is unpacked. */
    public synchronized boolean load() {
        if (transcriber != null) return true;
        loadError = null;
        try {
            File folder = unpack();
            Transcriber loaded = new Transcriber();
            loaded.loadFromFiles(folder.getAbsolutePath(), ARCH);
            transcriber = loaded;
            return true;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            Log.w(TAG, "Could not load Moonshine", e);
            loadError = e.getMessage() != null ? e.getMessage() : e.toString();
            return false;
        }
    }

    /**
     * One utterance of 16 kHz mono audio as text, or an empty string when nothing was said.
     *
     * <p>Confidence is not reported: unlike whisper, Moonshine does not hand back a probability,
     * and inventing one would only make the number on screen look meaningful.
     */
    public String transcribe(float[] samples) {
        Transcriber current = transcriber;
        if (current == null || samples == null || samples.length == 0) return "";

        float[] padded = new float[samples.length + TAIL_SAMPLES];
        System.arraycopy(samples, 0, padded, 0, samples.length);

        Transcript transcript = current.transcribeWithoutStreaming(padded,
                SpeechRecorder.SAMPLE_RATE);
        if (transcript == null || transcript.lines == null) return "";
        StringBuilder text = new StringBuilder();
        for (TranscriptLine line : transcript.lines) {
            if (line == null || line.text == null || line.text.trim().isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(line.text.trim());
        }
        return text.toString();
    }

    /** Copies the model out of the APK the first time; later runs find it already there. */
    private File unpack() throws IOException {
        File folder = models(context);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        for (String name : FILES) {
            File file = new File(folder, name);
            if (file.exists() && file.length() > 0) continue;
            File partial = new File(folder, name + ".part");
            try (InputStream in = context.getAssets().open(ASSETS + "/" + MODEL + "/" + name);
                 OutputStream out = new FileOutputStream(partial)) {
                byte[] chunk = new byte[256 * 1024];
                int read;
                while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            }
            if (!partial.renameTo(file)) {
                throw new IOException("could not put " + name + " in " + folder);
            }
        }
        return folder;
    }

    /** Frees the model; the next {@link #load()} brings it back. */
    public synchronized void close() {
        if (transcriber != null) {
            transcriber.close();
            transcriber = null;
        }
    }
}
