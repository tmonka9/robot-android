package com.falcon.robot.detect;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * CameraX analyzer that runs {@link YoloSegmenter} on each frame and follows the results with
 * {@link ObjectTracker}.
 *
 * <p>Frames arrive faster than the model can run; CameraX is configured to keep only the latest,
 * so the pipeline simply runs as fast as the device allows. Listener calls are on the main thread.
 */
public final class DetectionAnalyzer {

    private static final String TAG = "DetectionAnalyzer";

    public interface Listener {
        /**
         * Tracked objects in the frame just analysed.
         *
         * @param width       upright frame width in pixels (the coordinate space of the boxes)
         * @param inferenceMs detection and tracking time for this frame
         */
        void onFrame(List<ObjectTracker.Snapshot> objects, int width, int height, long inferenceMs);
    }

    private final YoloSegmenter segmenter; // null = no model loaded
    private final ObjectTracker tracker = new ObjectTracker();
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean detectionEnabled = true;
    private volatile boolean masksEnabled = true;
    private volatile boolean trackingEnabled = true;
    private volatile float confidence = 0.5f;
    private volatile float iou = 0.45f;
    private volatile boolean resetRequested;

    public DetectionAnalyzer(YoloSegmenter segmenter, Listener listener) {
        this.segmenter = segmenter;
        this.listener = listener;
    }

    public boolean canDetect() {
        return segmenter != null;
    }

    public boolean hasMasks() {
        return segmenter != null && segmenter.hasMasks();
    }

    public String describeModel() {
        return segmenter == null ? null : segmenter.describe();
    }

    public void setDetectionEnabled(boolean enabled) {
        detectionEnabled = enabled;
    }

    public void setMasksEnabled(boolean enabled) {
        masksEnabled = enabled;
    }

    /** Off means every frame is detected from scratch: no ids, no trails, no smoothing. */
    public void setTrackingEnabled(boolean enabled) {
        trackingEnabled = enabled;
        resetRequested = true;
    }

    public void setConfidence(float value) {
        confidence = value;
    }

    /** Number of objects given an id since the page opened. */
    public int getTotalSeen() {
        return tracker.getTotalSeen();
    }

    public void reset() {
        resetRequested = true;
    }

    public void close() {
        if (segmenter != null) segmenter.close();
    }

    /**
     * Analyses one upright frame. The caller keeps ownership of the bitmap, so the same frame can
     * be given to more than one model.
     */
    public void process(Bitmap frame) {
        try {
            final int width = frame.getWidth();
            final int height = frame.getHeight();
            if (!detectionEnabled || segmenter == null) {
                post(() -> listener.onFrame(new ArrayList<>(), width, height, 0));
                return;
            }
            if (resetRequested) {
                tracker.reset();
                resetRequested = false;
            }
            long start = SystemClock.elapsedRealtime();
            List<YoloSegmenter.Detection> detections =
                    segmenter.detect(frame, confidence, iou, masksEnabled);
            final List<ObjectTracker.Snapshot> objects =
                    trackingEnabled ? tracker.update(detections) : untracked(detections);
            final long inferenceMs = SystemClock.elapsedRealtime() - start;
            post(() -> listener.onFrame(objects, width, height, inferenceMs));
        } catch (Exception e) {
            Log.w(TAG, "Frame analysis failed", e);
        }
    }

    /** Detections as they are, without ids or history, for when tracking is switched off. */
    private static List<ObjectTracker.Snapshot> untracked(List<YoloSegmenter.Detection> detections) {
        List<ObjectTracker.Snapshot> objects = new ArrayList<>(detections.size());
        for (YoloSegmenter.Detection detection : detections) {
            objects.add(ObjectTracker.untracked(detection));
        }
        return objects;
    }

    private void post(Runnable runnable) {
        main.post(runnable);
    }
}
