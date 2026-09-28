package com.falcon.robot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCase;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.falcon.robot.detect.DetectionAnalyzer;
import com.falcon.robot.detect.ObjectTracker;
import com.falcon.robot.detect.YoloSegmenter;
import com.falcon.robot.face.FaceAnalyzer;
import com.falcon.robot.face.FaceDatabase;
import com.falcon.robot.face.FaceEmbedder;
import com.falcon.robot.voice.CommandRecognizer;
import com.falcon.robot.voice.CustomPhrases;
import com.falcon.robot.voice.MoonshineEngine;
import com.falcon.robot.voice.SpeechRecorder;
import com.falcon.robot.voice.VoiceCommands;
import com.falcon.robot.voice.WhisperEngine;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the recognition features, so they keep working while the operator is on another page, in
 * another app, or with the screen off.
 *
 * <p>It owns the camera, the microphone and the three models, and reports results to whichever
 * pages are open. Each feature has its own switch ({@link #setFaceEnabled},
 * {@link #setDetectionEnabled}, {@link #setVoiceEnabled}); the service runs in the foreground —
 * with the notification Android requires for background camera and microphone access — while at
 * least one is on, and shuts itself down when the last one goes off.
 *
 * <p>Face recognition and object detection share one camera stream: a frame is decoded once and
 * handed to both, so turning both on halves the frame rate rather than needing two cameras. They
 * also share the lens, which the Face page can switch.
 */
public class RobotService extends Service implements LifecycleOwner {

    private static final String TAG = "RobotService";

    /** What a spoken order goes out as: the prefix, then the command the button would send. */
    public static final String VOICE_PREFIX = "VOICE_COMMAND ";
    private static final String CHANNEL_ID = "robot_recognition";
    private static final int NOTIFICATION_ID = 42;
    /** Sent by the notification's Stop action. */
    public static final String ACTION_STOP = "com.falcon.robot.action.STOP";

    /** Default frame size for the models; the Settings page can change it. */
    private static final Size ANALYSIS_SIZE = new Size(640, 480);

    private static final String PREFS = "settings";
    private static final String KEY_FACE = "face_enabled";
    private static final String KEY_DETECTION = "detection_enabled";
    private static final String KEY_VOICE = "voice_enabled";
    private static final String KEY_LANGUAGE = "voice_language";
    private static final String KEY_LENS = "lens_facing";
    private static final String KEY_ANALYSIS_WIDTH = "analysis_width";
    private static final String KEY_ANALYSIS_HEIGHT = "analysis_height";

    /** What the pages listen to. Every call is on the main thread. */
    public interface Listener {
        /** Faces in the latest frame, in upright image coordinates. */
        void onFaceFrame(List<FaceAnalyzer.FrameFace> faces, int width, int height, long inferenceMs);

        /** A face was identified (or given up on as unknown). */
        void onFaceEvent(FaceAnalyzer.FaceEvent event);

        /** Enrolment finished; {@code embedding} is null when no face was captured in time. */
        void onRegistrationResult(float[] embedding, Bitmap crop);

        /** Tracked objects in the latest frame. */
        void onObjects(List<ObjectTracker.Snapshot> objects, int width, int height, long inferenceMs);

        /** Microphone level, 0..1, about every 20 ms. */
        void onVoiceLevel(float level);

        /** A transcript and the command it matched, if any. */
        void onTranscript(String text, float confidence, VoiceCommands.Action action, int result);

        /** A feature was switched on or off, or a model finished loading. */
        void onServiceState();

        /** Something the operator should be told: a model loaded, a device failed. */
        void onMessage(String text);
    }

    /** Listener with nothing to do: pages override only the parts they show. */
    public static class Adapter implements Listener {
        @Override
        public void onFaceFrame(List<FaceAnalyzer.FrameFace> faces, int width, int height, long inferenceMs) {
        }

        @Override
        public void onFaceEvent(FaceAnalyzer.FaceEvent event) {
        }

        @Override
        public void onRegistrationResult(float[] embedding, Bitmap crop) {
        }

        @Override
        public void onObjects(List<ObjectTracker.Snapshot> objects, int width, int height, long inferenceMs) {
        }

        @Override
        public void onVoiceLevel(float level) {
        }

        @Override
        public void onTranscript(String text, float confidence, VoiceCommands.Action action, int result) {
        }

        @Override
        public void onServiceState() {
        }

        @Override
        public void onMessage(String text) {
        }
    }

    public final class LocalBinder extends Binder {
        public RobotService getService() {
            return RobotService.this;
        }
    }

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private boolean faceEnabled;
    private boolean detectionEnabled;
    private boolean voiceEnabled;
    private boolean foreground;
    private boolean started; // true once onStartCommand has run, so the service outlives its pages
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private Size analysisSize = ANALYSIS_SIZE;

    // vision
    private ProcessCameraProvider cameraProvider;
    private FaceDatabase faceDatabase;
    private FaceEmbedder embedder;
    private FaceAnalyzer faceAnalyzer;
    private DetectionAnalyzer detectionAnalyzer;
    private Preview.SurfaceProvider surfaceProvider;
    private String detectorModel;
    private String detectorError;
    private boolean loadingDetector;

    // voice
    private WhisperEngine engine;
    /** The small command model when one is installed; whisper is only the fallback. */
    private MoonshineEngine moonshine;
    private volatile CommandRecognizer commandRecognizer;
    private volatile boolean loadingCommandModel;
    private float[] pendingCommandSamples;   // said before the model was ready, main thread
    private SpeechRecorder recorder;
    private CustomPhrases customPhrases;
    private String speechModel;
    private boolean loadingSpeechModel;
    private boolean transcribing;
    private float[] pendingSamples; // the utterance heard while the last one was transcribing
    private String language; // set from the app language in onCreate
    private boolean commandControl = true;
    private TextToSpeech tts;
    private boolean ttsReady;

    // latest results, so a page that opens mid-stream has something to draw
    private List<FaceAnalyzer.FrameFace> lastFaces = new ArrayList<>();
    private List<ObjectTracker.Snapshot> lastObjects = new ArrayList<>();
    private int frameWidth;
    private int frameHeight;
    private long lastInferenceMs;

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        faceEnabled = prefs.getBoolean(KEY_FACE, false);
        detectionEnabled = prefs.getBoolean(KEY_DETECTION, false);
        voiceEnabled = prefs.getBoolean(KEY_VOICE, false);
        lensFacing = prefs.getInt(KEY_LENS, CameraSelector.LENS_FACING_FRONT);
        analysisSize = new Size(prefs.getInt(KEY_ANALYSIS_WIDTH, ANALYSIS_SIZE.getWidth()),
                prefs.getInt(KEY_ANALYSIS_HEIGHT, ANALYSIS_SIZE.getHeight()));

        faceDatabase = new FaceDatabase(this);
        customPhrases = new CustomPhrases(this);
        engine = new WhisperEngine(this);
        moonshine = new MoonshineEngine(this);
        // the language chosen last time, or the app language until one is chosen
        language = recognisedLanguage(prefs.getString(KEY_LANGUAGE,
                LocaleHelper.effectiveLanguage(this)));
        recorder = new SpeechRecorder(recorderListener);
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) setSpeechVoiceLanguage();
        });

        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        if (!idle()) updateForeground(); // the notification has to exist before the camera opens
        if (faceEnabled || detectionEnabled) startVision();
        if (voiceEnabled) startVoice();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setFaceEnabled(false);
            setDetectionEnabled(false);
            setVoiceEnabled(false);
            return START_NOT_STICKY;
        }
        started = true;
        updateForeground();
        // a restart would have to start the camera from the background, which Android forbids;
        // the pages bring the features back instead, from the remembered toggles
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true; // so onRebind is called when a page comes back
    }

    @Override
    public void onDestroy() {
        stopVision();
        stopVoice();
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        cameraExecutor.execute(() -> {
            if (faceAnalyzer != null) faceAnalyzer.close();
            if (embedder != null) embedder.close();
            if (detectionAnalyzer != null) detectionAnalyzer.close();
        });
        if (tts != null) tts.shutdown();
        releaseAck();
        final CommandRecognizer recognizer = commandRecognizer;
        commandRecognizer = null;
        speechExecutor.execute(() -> {
            engine.release();
            moonshine.close();
            if (recognizer != null) recognizer.close();
        });
        cameraExecutor.shutdown();
        speechExecutor.shutdown();
        super.onDestroy();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    // ---- feature switches ------------------------------------------------------------------

    public boolean isFaceEnabled() {
        return faceEnabled;
    }

    public boolean isDetectionEnabled() {
        return detectionEnabled;
    }

    public boolean isVoiceEnabled() {
        return voiceEnabled;
    }

    public void setFaceEnabled(boolean enabled) {
        if (faceEnabled == enabled) return;
        faceEnabled = enabled;
        prefs.edit().putBoolean(KEY_FACE, enabled).apply();
        if (faceAnalyzer != null) faceAnalyzer.setDetectionEnabled(enabled);
        applyVisionState();
    }

    public void setDetectionEnabled(boolean enabled) {
        if (detectionEnabled == enabled) return;
        detectionEnabled = enabled;
        prefs.edit().putBoolean(KEY_DETECTION, enabled).apply();
        if (detectionAnalyzer != null) detectionAnalyzer.setDetectionEnabled(enabled);
        applyVisionState();
    }

    public void setVoiceEnabled(boolean enabled) {
        if (voiceEnabled == enabled) return;
        voiceEnabled = enabled;
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply();
        if (enabled) startVoice();
        else stopVoice();
        updateForeground();
        notifyState();
    }

    private void applyVisionState() {
        if (faceEnabled || detectionEnabled) startVision();
        else stopVision();
        updateForeground();
        notifyState();
    }

    /** True while nothing is switched on, so the service can be left to stop. */
    private boolean idle() {
        // a page showing the picture counts: the camera is running for it
        return !faceEnabled && !detectionEnabled && !voiceEnabled && surfaceProvider == null;
    }

    // ---- foreground notification -----------------------------------------------------------

    private void updateForeground() {
        if (idle()) {
            if (foreground) {
                stopForeground(true);
                foreground = false;
            }
            stopSelf(); // stays alive while a page is bound to it
            return;
        }
        if (!started) {
            // self-start, so the service outlives the pages that bound to it; onStartCommand
            // comes back here with started set
            startService(new Intent(this, RobotService.class));
            return;
        }
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = 0;
                if (faceEnabled || detectionEnabled || surfaceProvider != null) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (voiceEnabled) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTIFICATION_ID, notification, type);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
        } catch (RuntimeException e) {
            // e.g. started from the background, which Android 12 and newer refuse
            Log.w(TAG, "Could not go to the foreground", e);
            faceEnabled = detectionEnabled = voiceEnabled = false;
            stopVision();
            stopVoice();
            notifyState();
        }
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null
                && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.service_channel_description));
            manager.createNotificationChannel(channel);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RobotService.class).setAction(ACTION_STOP), flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(activeFeatures())
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }

    /** "Face recognition · Object detection" — what the notification says is running. */
    private String activeFeatures() {
        StringBuilder text = new StringBuilder();
        if (faceEnabled) text.append(getString(R.string.nav_face));
        if (detectionEnabled) {
            if (text.length() > 0) text.append("  ·  ");
            text.append(getString(R.string.nav_object));
        }
        if (voiceEnabled) {
            if (text.length() > 0) text.append("  ·  ");
            text.append(getString(R.string.nav_voice));
        }
        return text.length() == 0 ? getString(R.string.status_paused) : text.toString();
    }

    // ---- listeners -------------------------------------------------------------------------

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Tells whichever pages are open; nothing is lost when none is. */
    private void message(String text) {
        main.post(() -> {
            for (Listener listener : listeners) listener.onMessage(text);
        });
    }

    private void notifyState() {
        main.post(() -> {
            for (Listener listener : listeners) listener.onServiceState();
        });
    }

    // ---- camera ----------------------------------------------------------------------------

    /** The preview of whichever page is visible; null when none is. */
    public void attachPreview(Preview.SurfaceProvider provider) {
        surfaceProvider = provider;
        // wanting the picture is reason enough to open the camera, with or without the models
        updateForeground();
        startVision();
    }

    public void detachPreview(Preview.SurfaceProvider provider) {
        if (surfaceProvider != provider) return;
        surfaceProvider = null;
        if (cameraProvider != null) bindCamera(); // lets the camera go if nothing else wants it
        updateForeground();
    }

    /** Frame size the models see. Bigger finds smaller objects; smaller is faster. */
    public Size getAnalysisSize() {
        return analysisSize;
    }

    public void setAnalysisSize(Size size) {
        if (size == null || size.equals(analysisSize)) return;
        analysisSize = size;
        prefs.edit().putInt(KEY_ANALYSIS_WIDTH, size.getWidth())
                .putInt(KEY_ANALYSIS_HEIGHT, size.getHeight()).apply();
        if (cameraProvider != null) bindCamera();
        notifyState();
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public void setLensFacing(int facing) {
        if (lensFacing == facing) return;
        lensFacing = facing;
        prefs.edit().putInt(KEY_LENS, facing).apply();
        if (cameraProvider != null) bindCamera();
        notifyState();
    }

    private void startVision() {
        if (cameraProvider != null) {
            bindCamera();
            return;
        }
        loadVisionModels();
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Log.w(TAG, "Camera unavailable", e);
            message(getString(R.string.camera_unavailable));
            }
        }, main::post);
    }

    private void stopVision() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        lastFaces = new ArrayList<>();
        lastObjects = new ArrayList<>();
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();
        // a page showing the picture is reason enough to run the camera, even with both models off
        boolean analysing = faceEnabled || detectionEnabled;
        if (!analysing && surfaceProvider == null) return;

        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        try {
            if (!cameraProvider.hasCamera(selector)) {
                lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                        ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
                selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                if (!cameraProvider.hasCamera(selector)) return;
            }
        } catch (Exception e) {
            Log.w(TAG, "No usable camera", e);
            message(getString(R.string.camera_unavailable));
            return;
        }

        List<UseCase> uses = new ArrayList<>();
        if (surfaceProvider != null) {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(surfaceProvider);
            uses.add(preview);
        }
        if (analysing) {
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setResolutionSelector(new ResolutionSelector.Builder()
                            .setResolutionStrategy(new ResolutionStrategy(analysisSize,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build();
            analysis.setAnalyzer(cameraExecutor, this::analyze);
            uses.add(analysis);
        }

        try {
            cameraProvider.bindToLifecycle(this, selector, uses.toArray(new UseCase[0]));
        } catch (Exception e) {
            Log.w(TAG, "Could not bind the camera", e);
        }
    }

    /** One frame, decoded once and given to whichever models are on. */
    private void analyze(ImageProxy image) {
        Bitmap frame = null;
        try {
            frame = uprightBitmap(image);
            frameWidth = frame.getWidth();
            frameHeight = frame.getHeight();
            if (faceAnalyzer != null && faceEnabled) faceAnalyzer.process(frame);
            if (detectionAnalyzer != null && detectionEnabled) detectionAnalyzer.process(frame);
        } catch (Exception e) {
            Log.w(TAG, "Frame analysis failed", e);
        } finally {
            if (frame != null) frame.recycle();
            image.close();
        }
    }

    private static Bitmap uprightBitmap(ImageProxy image) {
        Bitmap bitmap = image.toBitmap();
        int rotation = image.getImageInfo().getRotationDegrees();
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.recycle();
        return rotated;
    }

    // ---- face ------------------------------------------------------------------------------

    public FaceDatabase getFaceDatabase() {
        return faceDatabase;
    }

    public FaceAnalyzer getFaceAnalyzer() {
        return faceAnalyzer;
    }

    public List<FaceAnalyzer.FrameFace> getLastFaces() {
        return lastFaces;
    }

    public void startRegistration(int samples, long timeoutMs) {
        if (faceAnalyzer != null) faceAnalyzer.startRegistration(samples, timeoutMs);
    }

    /** Loads MobileFaceNet and the detector, once, off the main thread. */
    private void loadVisionModels() {
        if (faceAnalyzer == null) {
            faceAnalyzer = new FaceAnalyzer(faceDatabase, null, faceListener);
            faceAnalyzer.setDetectionEnabled(faceEnabled);
            cameraExecutor.execute(() -> {
                FaceEmbedder loaded = null;
                try {
                    loaded = new FaceEmbedder(this, 4);
                } catch (Exception e) {
                    Log.w(TAG, "MobileFaceNet is not available", e);
                }
                final FaceEmbedder model = loaded;
                main.post(() -> {
                    embedder = model;
                    if (faceAnalyzer != null) faceAnalyzer.setEmbedder(model);
                    if (model == null) message(getString(R.string.model_missing));
                    notifyState();
                });
            });
        }
        if (detectionAnalyzer == null) loadDetector(null);
    }

    private final FaceAnalyzer.Listener faceListener = new FaceAnalyzer.Listener() {
        @Override
        public void onFrame(List<FaceAnalyzer.FrameFace> faces, int width, int height, long inferenceMs) {
            lastFaces = faces;
            frameWidth = width;
            frameHeight = height;
            for (Listener listener : listeners) listener.onFaceFrame(faces, width, height, inferenceMs);
        }

        @Override
        public void onFaceEvent(FaceAnalyzer.FaceEvent event) {
            if (event.record != null) RobotSession.get().send("FACE RECOGNIZED " + event.record.id);
            for (Listener listener : listeners) listener.onFaceEvent(event);
        }

        @Override
        public void onRegistrationResult(float[] embedding, Bitmap crop) {
            for (Listener listener : listeners) listener.onRegistrationResult(embedding, crop);
        }
    };

    // ---- object detection ------------------------------------------------------------------

    public DetectionAnalyzer getDetectionAnalyzer() {
        return detectionAnalyzer;
    }

    public String getDetectorModel() {
        return detectorModel;
    }

    public String getDetectorError() {
        return detectorError;
    }

    public boolean isLoadingDetector() {
        return loadingDetector;
    }

    public List<ObjectTracker.Snapshot> getLastObjects() {
        return lastObjects;
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public long getLastInferenceMs() {
        return lastInferenceMs;
    }

    /**
     * A YOLO model by name before anything else: the models folder is shared with the speech and
     * face models, and alphabetical order once handed the detector a command recogniser.
     */
    private static String firstDetector(List<String> models) {
        for (String name : models) {
            if (name.toLowerCase(Locale.US).contains("yolo")) return name;
        }
        return models.isEmpty() ? null : models.get(0);
    }

    /** Loads a detector model; {@code modelName} null picks the first one available. */
    public void loadDetector(String modelName) {
        List<String> models = YoloSegmenter.listModels(this);
        final String model = modelName != null ? modelName
                : models.contains(YoloSegmenter.DEFAULT_MODEL) ? YoloSegmenter.DEFAULT_MODEL
                : firstDetector(models);
        if (model == null) {
            detectorError = null;
            detectorModel = null;
            notifyState();
            return;
        }
        loadingDetector = true;
        notifyState();
        final DetectionAnalyzer previous = detectionAnalyzer;
        cameraExecutor.execute(() -> {
            if (previous != null) previous.close();
            YoloSegmenter segmenter = null;
            String error = null;
            try {
                segmenter = new YoloSegmenter(this, model, 4);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(TAG, "Could not load " + model, e);
            }
            final YoloSegmenter loaded = segmenter;
            final String message = error;
            main.post(() -> {
                loadingDetector = false;
                detectorModel = loaded != null ? model : null;
                detectorError = message;
                detectionAnalyzer = new DetectionAnalyzer(loaded, detectionListener);
                detectionAnalyzer.setDetectionEnabled(detectionEnabled);
                notifyState();
            });
        });
    }

    private final DetectionAnalyzer.Listener detectionListener = (objects, width, height, inferenceMs) -> {
        lastObjects = objects;
        frameWidth = width;
        frameHeight = height;
        lastInferenceMs = inferenceMs;
        for (Listener listener : listeners) listener.onObjects(objects, width, height, inferenceMs);
    };

    // ---- voice -----------------------------------------------------------------------------

    public WhisperEngine getEngine() {
        return engine;
    }

    public CustomPhrases getCustomPhrases() {
        return customPhrases;
    }

    public String getSpeechModel() {
        return speechModel;
    }

    public boolean isLoadingSpeechModel() {
        return loadingSpeechModel;
    }

    public boolean isTranscribing() {
        return transcribing;
    }

    /**
     * The app language when speech can be recognised in it, and English otherwise: the Voice page
     * offers the languages there are models for, and this reads the same list.
     */
    private String recognisedLanguage(String code) {
        if (code == null) return "en";
        for (String offered : getResources().getStringArray(R.array.adv_language_codes)) {
            if (offered.equals(code)) return code;
        }
        return "en";
    }

    /**
     * The recognition language the Voice page last read, for the pages that need it before they
     * have bound to the service; null until one has been chosen.
     */
    public static String savedLanguage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null);
    }

    public void setLanguage(String code) {
        boolean changed = code != null && !code.equals(language);
        language = code;
        // remembered, so the choice survives the page being left and the app being restarted
        if (code != null) prefs.edit().putString(KEY_LANGUAGE, code).apply();
        // each language has its own Moonshine model, so a change of language means a different
        // model — and possibly a different engine; loading one already loaded costs nothing
        if (changed && voiceEnabled) loadTranscriber();
    }

    public void setCommandControl(boolean enabled) {
        commandControl = enabled;
    }

    private boolean noiseSuppression = true;

    public void setNoiseSuppression(boolean enabled) {
        if (noiseSuppression == enabled) return;
        noiseSuppression = enabled;
        recorder.setNoiseSuppression(enabled);
        if (voiceEnabled) { // the effect is chosen when recording starts
            recorder.stop();
            recorder.start();
        }
    }

    private void startVoice() {
        loadCommandModel();
        // the language can have been changed while nothing was listening, which means a different
        // model; one already loaded for this language is left where it is
        if (moonshine.isReady()) loadTranscriber();
        recorder.start();
    }

    private void stopVoice() {
        recorder.stop();
    }

    /**
     * Looks for the small command model first. It is all the robot needs to answer to its own
     * commands, so whisper — several hundred megabytes of it — is only loaded when there is no
     * command model, or when the one there is does not know every command: the bundled model
     * covers the seven orders that exist as words in the corpus it was built from, and the other
     * seven stay with whisper until someone records them.
     */
    private void loadCommandModel() {
        if (commandRecognizer != null || loadingCommandModel) return;
        loadingCommandModel = CommandRecognizer.exists(this);
        final int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        speechExecutor.execute(() -> {
            final CommandRecognizer recognizer = CommandRecognizer.load(this, threads);
            main.post(() -> {
                commandRecognizer = recognizer;
                loadingCommandModel = false;
                if (recognizer == null) {
                    if (CommandRecognizer.getLoadError() != null) {
                        message(getString(R.string.command_model_failed,
                                CommandRecognizer.getLoadError()));
                    }
                    loadTranscriber();
                } else {
                    int known = recognizer.commands().size();
                    int all = VoiceCommands.actions().size();
                    if (known >= all) {
                        message(getString(R.string.command_model_ready, known));
                    } else if (canTranscribe()) {
                        message(getString(R.string.command_model_partial, known, all));
                        loadTranscriber();
                    } else {
                        // Without whisper the robot answers to what the model was trained on and
                        // nothing else — which is worth saying plainly, rather than asking for a
                        // clone of whisper.cpp that these commands do not need.
                        message(getString(R.string.command_model_only, known, all, all - known));
                    }
                }
                notifyState();
                // whatever was said while the model was still loading
                float[] waiting = pendingCommandSamples;
                pendingCommandSamples = null;
                if (waiting != null) recognise(waiting);
            });
        });
    }

    /** True when anything can turn speech into text: Moonshine for English, whisper otherwise. */
    private boolean canTranscribe() {
        return WhisperEngine.isEngineBuilt() || MoonshineEngine.isBundled(this);
    }

    /**
     * Moonshine has a model per language rather than one multilingual model, so which to load
     * depends on what the operator will be speaking — and on which models were built into the
     * APK. whisper is the fallback for a language no Moonshine model here covers. Neither engine
     * is loaded until the command model has had its say, because most utterances never get here.
     */
    private void loadTranscriber() {
        final String code = language;
        final String model = MoonshineEngine.modelFor(this, code);
        if (MoonshineEngine.isBundled(this, model)) {
            if (model.equals(moonshine.getModel())) return;
            speechExecutor.execute(() -> {
                final boolean ok = moonshine.load(code);
                main.post(() -> {
                    message(ok ? getString(R.string.moonshine_ready, model)
                            : getString(R.string.moonshine_failed, moonshine.getLoadError()));
                    notifyState();
                });
            });
            return;
        }
        if (WhisperEngine.isEngineBuilt()) {
            loadSpeechModel(null);
            return;
        }
        message(getString(R.string.moonshine_missing, model));
    }

    /** Whether this utterance goes to Moonshine rather than whisper. */
    private boolean moonshineFits() {
        String loaded = moonshine.getModel();
        if (loaded == null) return false;
        // the model for another language is still better than nothing, but only just
        return loaded.equals(MoonshineEngine.modelFor(this, language)) || !engine.isReady();
    }

    /** Loads a ggml model; {@code modelName} null picks the bundled one. */
    public void loadSpeechModel(String modelName) {
        if (!WhisperEngine.isLibraryAvailable()) return;
        List<String> models = WhisperEngine.listAvailableModels(this);
        final String model = modelName != null ? modelName : WhisperEngine.preferredModel(models);
        if (model == null || model.equals(speechModel)) return;
        loadingSpeechModel = true;
        notifyState();
        speechExecutor.execute(() -> {
            final boolean ok = engine.load(model);
            main.post(() -> {
                loadingSpeechModel = false;
                speechModel = ok ? model : null;
                message(ok ? getString(R.string.model_loaded, model)
                        : engine.getLoadError() != null ? engine.getLoadError()
                        : getString(R.string.model_load_failed, model));
                notifyState();
            });
        });
    }

    private final SpeechRecorder.Listener recorderListener = new SpeechRecorder.Listener() {
        @Override
        public void onLevel(float level, boolean speaking) {
            for (Listener listener : listeners) listener.onVoiceLevel(level);
        }

        @Override
        public void onUtterance(float[] samples) {
            recognise(samples);
        }

        @Override
        public void onError(String error) {
            Log.w(TAG, "Microphone: " + error);
            message(getString(R.string.mic_error, error));
            setVoiceEnabled(false);
        }
    };

    /**
     * One utterance. The command model gets it first, since the robot's own orders are all it has
     * to tell apart; whisper is only asked about what the command model does not know, and only
     * when a whisper model happens to be loaded.
     */
    private void recognise(final float[] samples) {
        final CommandRecognizer recognizer = commandRecognizer;
        if (recognizer == null) {
            if (loadingCommandModel) {
                // spoken to before the model finished loading: keep it rather than answering with
                // whisper's complaint about not being built, which is not what went wrong
                pendingCommandSamples = samples;
                return;
            }
            transcribe(samples);
            return;
        }
        speechExecutor.execute(() -> {
            final CommandRecognizer.Result result = recognizer.classify(samples);
            main.post(() -> {
                if (result.isCommand(recognizer.threshold())) {
                    VoiceCommands.Action action = VoiceCommands.actionByName(result.label);
                    if (action != null) {
                        runAction(action, getString(action.labelRes), result.confidence * 100f);
                        return;
                    }
                }
                if (engine.isReady()) {
                    transcribe(samples);
                    return;
                }
                // Nothing else is listening, so say what the command model nearly heard rather
                // than "nothing recognised": being told it almost had "Turn Left" is the
                // difference between saying it again and assuming the microphone is dead.
                VoiceCommands.Action guess = VoiceCommands.actionByName(result.label);
                for (Listener listener : listeners) {
                    if (guess != null) {
                        listener.onTranscript(getString(guess.labelRes), result.confidence * 100f,
                                null, R.string.command_unsure);
                    } else {
                        listener.onTranscript("", -1f, null, R.string.no_speech);
                    }
                }
            });
        });
    }

    private void transcribe(final float[] samples) {
        if (moonshineFits()) {
            transcribeWithMoonshine(samples);
            return;
        }
        if (!engine.isReady()) {
            String broken = CommandRecognizer.getLoadError();
            message(broken != null ? getString(R.string.command_model_failed, broken)
                    : engine.getLoadError() != null ? engine.getLoadError()
                    : getString(R.string.engine_not_built));
            return;
        }
        if (transcribing) {
            // a small model takes a second or two; keep the newest utterance rather than
            // dropping what was said while the last one was still running
            pendingSamples = samples;
            return;
        }
        transcribing = true;
        notifyState();
        final int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        speechExecutor.execute(() -> {
            final WhisperEngine.Result result = engine.transcribe(samples, language, false, threads);
            main.post(() -> {
                transcribing = false;
                notifyState();
                if (result != null && !result.text.isEmpty()) {
                    handleTranscript(result.text, result.confidence * 100f);
                } else {
                    // silence, noise, or a language the model did not expect
                    for (Listener listener : listeners) {
                        listener.onTranscript("", -1f, null, R.string.no_speech);
                    }
                }
                float[] queued = pendingSamples;
                pendingSamples = null;
                if (queued != null) transcribe(queued);
            });
        });
    }

    /**
     * The same as {@link #transcribe} but through Moonshine, which reports no confidence — so the
     * Voice page shows a dash there rather than a number that would mean nothing.
     */
    private void transcribeWithMoonshine(final float[] samples) {
        if (transcribing) {
            pendingSamples = samples;
            return;
        }
        transcribing = true;
        notifyState();
        speechExecutor.execute(() -> {
            final String text = moonshine.transcribe(samples);
            main.post(() -> {
                transcribing = false;
                notifyState();
                if (text != null && !text.isEmpty()) {
                    handleTranscript(text, -1f);
                } else {
                    for (Listener listener : listeners) {
                        listener.onTranscript("", -1f, null, R.string.no_speech);
                    }
                }
                float[] queued = pendingSamples;
                pendingSamples = null;
                if (queued != null) transcribe(queued);
            });
        });
    }

    /** Matches a command in the transcript and runs it, wherever the operator is in the app. */
    private void handleTranscript(String text, float confidence) {
        VoiceCommands.Action action = customPhrases.match(text);
        if (action == null) action = VoiceCommands.match(text);
        runAction(action, text, confidence);
    }

    /** Carries out a recognised action, whichever model recognised it, and tells the pages. */
    private void runAction(VoiceCommands.Action action, String text, float confidence) {
        int result;
        if (action == null) {
            result = R.string.result_no_match;
        } else if (!commandControl) {
            result = R.string.result_not_sent;
        } else {
            result = RobotSession.get().send(VOICE_PREFIX + VoiceCommands.commandFor(action))
                    ? R.string.result_executed : R.string.result_not_sent;
        }
        // the answer is to being understood, not to the robot obeying: the operator hears that
        // the order was heard whether or not there is a robot on the other end just now
        if (action != null) acknowledge();
        for (Listener listener : listeners) listener.onTranscript(text, confidence, action, result);
    }

    /**
     * Says "OK" when a command was understood. The clip is {@code assets/ok.wav}; without it the
     * speech engine says the word instead, so the robot still answers before one is recorded.
     */
    public void acknowledge() {
        if (!playAsset(OK_SOUND)) speak(getString(R.string.ack_ok));
    }

    /** Plays a sound from the assets. False when there is no such file, or it will not play. */
    private boolean playAsset(String asset) {
        AssetFileDescriptor fd = null;
        try {
            fd = getAssets().openFd(asset);
            releaseAck();
            ack = new MediaPlayer();
            ack.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ack.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            // a later sound may have taken the field over by the time this one ends
            ack.setOnCompletionListener(player -> {
                if (player == ack) {
                    releaseAck();
                } else {
                    player.release();
                }
            });
            ack.prepare();
            ack.start();
            return true;
        } catch (java.io.IOException | IllegalStateException e) {
            Log.w(TAG, "cannot play " + asset + ": " + e);
            releaseAck();
            return false;
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (java.io.IOException ignored) {
                    // the player has its own copy of the descriptor by now
                }
            }
        }
    }

    private void releaseAck() {
        if (ack == null) return;
        ack.release();
        ack = null;
    }

    /** The sound played when a command is understood, if it is in the assets. */
    private static final String OK_SOUND = "ok.wav";

    private MediaPlayer ack;

    /** Says something through the device speaker, in the app language. */
    public void speak(String text) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robot-answer");
    }

    private void setSpeechVoiceLanguage() {
        java.util.Locale locale = new java.util.Locale(LocaleHelper.effectiveLanguage(this));
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(java.util.Locale.US);
        }
    }

    // ---- binding helper ---------------------------------------------------------------------

    public static Intent intent(Context context) {
        return new Intent(context, RobotService.class);
    }
}
