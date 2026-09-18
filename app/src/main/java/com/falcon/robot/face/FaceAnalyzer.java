package com.falcon.robot.face;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * CameraX analyzer: detects faces with ML Kit, embeds them with MobileFaceNet and matches them
 * against the {@link FaceDatabase}.
 *
 * <p>Faces are followed across frames with ML Kit tracking IDs; each tracked face is reported
 * once through {@link Listener#onFaceEvent} — as soon as it is recognized, or as unknown after
 * {@link #UNKNOWN_AFTER_FRAMES} frames without a match. All listener calls run on the main thread.
 */
public final class FaceAnalyzer {

    private static final String TAG = "FaceAnalyzer";

    /** A recognized face needs this many matching frames before it is reported (avoids flicker). */
    private static final int RECOGNIZED_AFTER_FRAMES = 2;
    private static final int UNKNOWN_AFTER_FRAMES = 12;
    private static final long TRACK_TIMEOUT_MS = 2000;
    /** Extra margin around ML Kit's box before cropping (MobileFaceNet expects a slightly loose crop). */
    private static final float CROP_MARGIN = 0.12f;

    /** A face in the current frame, in image coordinates. */
    public static final class FrameFace {
        public final RectF box;
        public final String label;
        public final float similarity; // percent, < 0 when not computed
        public final boolean recognized;

        FrameFace(RectF box, String label, float similarity, boolean recognized) {
            this.box = box;
            this.label = label;
            this.similarity = similarity;
            this.recognized = recognized;
        }
    }

    /** One reported identification (per tracked face). */
    public static final class FaceEvent {
        public final FaceDatabase.Record record; // null = unknown face
        public final float similarity;           // best similarity seen, percent
        public final Bitmap crop;
        public final long time;

        FaceEvent(FaceDatabase.Record record, float similarity, Bitmap crop, long time) {
            this.record = record;
            this.similarity = similarity;
            this.crop = crop;
            this.time = time;
        }
    }

    public interface Listener {
        /** Faces in the latest analysed frame ({@code width} x {@code height} upright image). */
        void onFrame(List<FrameFace> faces, int width, int height, long inferenceMs);

        void onFaceEvent(FaceEvent event);

        /** Registration finished: {@code embedding} is null when no face was captured in time. */
        void onRegistrationResult(float[] embedding, Bitmap crop);
    }

    private static final class Track {
        int frames;
        FaceDatabase.Record best;
        float bestSimilarity = -1f;
        float lastSimilarity = -1f;
        FaceDatabase.Record lastMatch;
        boolean reported;
        long lastSeen;
        Bitmap crop;
    }

    private final FaceDatabase database;
    private volatile FaceEmbedder embedder; // null = detection only (model missing or still loading)
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Track> tracks = new HashMap<>();

    private FaceDetector detector;
    private boolean accurate;
    private volatile boolean detectionEnabled = true;
    private volatile boolean recognitionEnabled = true;
    private volatile float thresholdPercent = 80f;
    private volatile int frameSkip;
    private volatile boolean resetRequested;
    private int frameCounter;

    // registration
    private volatile int registrationSamples;
    private volatile long registrationDeadline;
    private final List<float[]> registrationEmbeddings = new ArrayList<>();
    private Bitmap registrationCrop;

    public FaceAnalyzer(FaceDatabase database, FaceEmbedder embedder, Listener listener) {
        this.database = database;
        this.embedder = embedder;
        this.listener = listener;
        setAccurate(true);
    }

    public boolean canRecognize() {
        return embedder != null;
    }

    public void setDetectionEnabled(boolean enabled) {
        detectionEnabled = enabled;
    }

    public void setRecognitionEnabled(boolean enabled) {
        recognitionEnabled = enabled;
    }

    public void setThresholdPercent(float percent) {
        thresholdPercent = percent;
    }

    /** High accuracy: accurate detector on every frame; otherwise fast detector, optionally skipping frames. */
    public synchronized void setMode(boolean accurateDetector, int skipFrames) {
        setAccurate(accurateDetector);
        frameSkip = Math.max(0, skipFrames);
    }

    /** Forget tracked faces so everyone in view is identified (and reported) again. */
    public void resetTracks() {
        resetRequested = true;
    }

    /** Collects {@code samples} embeddings of the largest face and averages them. */
    public void startRegistration(int samples, long timeoutMs) {
        synchronized (registrationEmbeddings) {
            registrationEmbeddings.clear();
            registrationCrop = null;
        }
        registrationDeadline = SystemClock.elapsedRealtime() + timeoutMs;
        registrationSamples = samples;
    }

    private synchronized void setAccurate(boolean value) {
        if (detector != null && accurate == value) return;
        FaceDetector old = detector;
        accurate = value;
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(value ? FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                        : FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.12f)
                .enableTracking()
                .build());
        if (old != null) old.close();
    }

    public synchronized void close() {
        if (detector != null) detector.close();
    }

    /** Replaces the embedding model, once it has finished loading. */
    public void setEmbedder(FaceEmbedder embedder) {
        this.embedder = embedder;
    }

    /**
     * Analyses one upright frame. The caller keeps ownership of the bitmap, so the same frame can
     * be given to more than one model.
     */
    public void process(Bitmap frame) {
        try {
            if (!detectionEnabled && registrationSamples == 0) {
                final int width = frame.getWidth();
                final int height = frame.getHeight();
                post(() -> listener.onFrame(new ArrayList<>(), width, height, 0));
                return;
            }
            if (frameSkip > 0 && (frameCounter++ % (frameSkip + 1)) != 0) return;
            if (resetRequested) {
                tracks.clear();
                resetRequested = false;
            }
            detect(frame);
        } catch (Exception e) {
            Log.w(TAG, "Frame analysis failed", e);
        }
    }

    private void detect(Bitmap frame) throws Exception {
        long start = SystemClock.elapsedRealtime();
        FaceDetector current;
        synchronized (this) {
            current = detector;
        }
        List<Face> faces = Tasks.await(current.process(InputImage.fromBitmap(frame, 0)));
        long now = SystemClock.elapsedRealtime();

        boolean recognize = recognitionEnabled && embedder != null;
        Face largest = null;
        List<FrameFace> frameFaces = new ArrayList<>();
        List<FaceEvent> events = new ArrayList<>();

        for (Face face : faces) {
            if (largest == null || area(face.getBoundingBox()) > area(largest.getBoundingBox())) largest = face;
            RectF box = new RectF(face.getBoundingBox());
            if (!recognize) {
                frameFaces.add(new FrameFace(box, null, -1f, false));
                continue;
            }
            Bitmap crop = crop(frame, face.getBoundingBox());
            if (crop == null) continue;
            float[] embedding = embedder.embed(crop);
            FaceDatabase.Match match = database.findBest(embedding);
            boolean matched = match != null && match.similarity >= thresholdPercent;

            Integer id = face.getTrackingId();
            Track track = id == null ? new Track() : tracks.get(id);
            if (track == null) {
                track = new Track();
                tracks.put(id, track);
            }
            track.frames++;
            track.lastSeen = now;
            track.lastSimilarity = match != null ? match.similarity : -1f;
            track.lastMatch = matched ? match.record : null;
            if (matched && match.similarity > track.bestSimilarity) {
                track.best = match.record;
                track.bestSimilarity = match.similarity;
                track.crop = crop;
            } else if (track.crop == null) {
                track.crop = crop;
            } else if (crop != track.crop) {
                crop.recycle();
            }
            if (!matched && track.best == null && match != null) {
                track.bestSimilarity = Math.max(track.bestSimilarity, match.similarity);
            }

            if (!track.reported) {
                if (track.best != null && track.frames >= RECOGNIZED_AFTER_FRAMES) {
                    track.reported = true;
                    events.add(new FaceEvent(track.best, track.bestSimilarity, copy(track.crop), System.currentTimeMillis()));
                } else if (track.best == null && track.frames >= UNKNOWN_AFTER_FRAMES) {
                    track.reported = true;
                    events.add(new FaceEvent(null, track.bestSimilarity, copy(track.crop), System.currentTimeMillis()));
                }
            }

            String label = track.lastMatch != null ? track.lastMatch.name : null;
            frameFaces.add(new FrameFace(box, label, track.lastSimilarity, track.lastMatch != null));
        }

        // drop faces that left the view
        for (Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().lastSeen > TRACK_TIMEOUT_MS) it.remove();
        }

        handleRegistration(frame, largest, now);

        final long inferenceMs = SystemClock.elapsedRealtime() - start;
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        post(() -> {
            listener.onFrame(frameFaces, width, height, inferenceMs);
            for (FaceEvent event : events) listener.onFaceEvent(event);
        });
    }

    private void handleRegistration(Bitmap frame, Face largest, long now) {
        if (registrationSamples <= 0) return;
        if (now > registrationDeadline) {
            finishRegistration();
            return;
        }
        if (largest == null || embedder == null) return;
        Bitmap crop = crop(frame, largest.getBoundingBox());
        if (crop == null) return;
        synchronized (registrationEmbeddings) {
            registrationEmbeddings.add(embedder.embed(crop));
            if (registrationCrop == null) registrationCrop = crop;
            else crop.recycle();
            if (registrationEmbeddings.size() >= registrationSamples) finishRegistration();
        }
    }

    private void finishRegistration() {
        final float[] embedding;
        final Bitmap crop;
        synchronized (registrationEmbeddings) {
            registrationSamples = 0;
            embedding = registrationEmbeddings.isEmpty() ? null : FaceMath.average(registrationEmbeddings);
            crop = embedding == null ? null : registrationCrop;
            registrationEmbeddings.clear();
            registrationCrop = null;
        }
        post(() -> listener.onRegistrationResult(embedding, crop));
    }

    /** Square crop around the face with a small margin, clamped to the frame. */
    private static Bitmap crop(Bitmap frame, Rect box) {
        float size = Math.max(box.width(), box.height()) * (1f + CROP_MARGIN * 2);
        float cx = box.exactCenterX();
        float cy = box.exactCenterY();
        int left = Math.max(0, Math.round(cx - size / 2));
        int top = Math.max(0, Math.round(cy - size / 2));
        int right = Math.min(frame.getWidth(), Math.round(cx + size / 2));
        int bottom = Math.min(frame.getHeight(), Math.round(cy + size / 2));
        if (right - left < 20 || bottom - top < 20) return null;
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top);
    }

    private static Bitmap copy(Bitmap bitmap) {
        return bitmap == null ? null : bitmap.copy(Bitmap.Config.ARGB_8888, false);
    }

    private static int area(Rect r) {
        return r.width() * r.height();
    }

    private void post(Runnable runnable) {
        main.post(runnable);
    }
}
