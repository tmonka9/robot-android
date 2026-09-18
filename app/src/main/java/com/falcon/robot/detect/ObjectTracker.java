package com.falcon.robot.detect;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Multi-object tracking over the detections of {@link YoloSegmenter}: gives every object a stable
 * id for as long as it stays in view.
 *
 * <p>Detections are associated with the existing tracks by overlap, in two rounds like ByteTrack:
 * confident detections first, then the weak ones, which are usually a partly hidden object rather
 * than noise and keep its id alive through the occlusion. Each track carries a constant-velocity
 * estimate, so the overlap is measured against where the object is expected to be rather than
 * where it was, and a track survives {@link #MAX_MISSES} frames without a detection before it is
 * dropped.
 *
 * <p>Belongs to the analysis thread. The UI reads {@link Snapshot}s instead.
 */
public final class ObjectTracker {

    /** Detections at least this confident start new tracks and are matched first. */
    private static final float HIGH_SCORE = 0.5f;
    private static final float MATCH_IOU = 0.3f;
    private static final float MATCH_IOU_WEAK = 0.2f;
    /** Frames a track may go unmatched before it is forgotten. */
    private static final int MAX_MISSES = 20;
    /** Frames a track must be seen before it is shown (suppresses one-frame false positives). */
    private static final int CONFIRM_HITS = 2;
    private static final int TRAIL_LENGTH = 40;
    private static final float VELOCITY_SMOOTHING = 0.6f;
    private static final float BOX_SMOOTHING = 0.5f;

    /** A tracked object as the UI sees it: plain values, safe to hand to the main thread. */
    public static final class Snapshot {
        public final int id;
        public final int classId;
        public final String label;
        public final float score;
        public final RectF box;
        public final android.graphics.Bitmap mask;
        /** Frame rectangle the mask covers; null when there is no mask. */
        public final RectF maskBox;
        /** Centre points, oldest first, as x, y pairs in frame pixels. */
        public final float[] trail;
        public final int frames;

        Snapshot(Track track) {
            id = track.id;
            classId = track.classId;
            label = CocoLabels.name(track.classId);
            score = track.score;
            box = new RectF(track.box);
            mask = track.mask;
            maskBox = track.maskBox == null ? null : new RectF(track.maskBox);
            trail = new float[track.trail.size()];
            for (int i = 0; i < track.trail.size(); i++) trail[i] = track.trail.get(i);
            frames = track.hits;
        }

        Snapshot(YoloSegmenter.Detection detection) {
            id = 0; // no id: this detection is not being followed
            classId = detection.classId;
            label = CocoLabels.name(detection.classId);
            score = detection.score;
            box = detection.box;
            mask = detection.mask;
            maskBox = detection.maskBox;
            trail = new float[0];
            frames = 1;
        }
    }

    /** A detection shown as it came out of the model, for when tracking is switched off. */
    public static Snapshot untracked(YoloSegmenter.Detection detection) {
        return new Snapshot(detection);
    }

    private static final class Track {
        final int id;
        final RectF box = new RectF();
        final List<Float> trail = new ArrayList<>();
        int classId;
        float score;
        android.graphics.Bitmap mask;
        RectF maskBox;
        float vx, vy;
        int hits;
        int misses;

        Track(int id) {
            this.id = id;
        }

        void pushTrail() {
            trail.add(box.centerX());
            trail.add(box.centerY());
            while (trail.size() > TRAIL_LENGTH * 2) {
                trail.remove(0);
                trail.remove(0);
            }
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private int totalSeen;

    /** Ids handed out since the last {@link #reset()}. */
    public int getTotalSeen() {
        return totalSeen;
    }

    public void reset() {
        tracks.clear();
        nextId = 1;
        totalSeen = 0;
    }

    /** Feeds one frame of detections and returns the tracks that are currently visible. */
    public List<Snapshot> update(List<YoloSegmenter.Detection> detections) {
        for (Track track : tracks) {
            // where the object should be in this frame
            track.box.offset(track.vx, track.vy);
            track.misses++;
        }

        boolean[] used = new boolean[detections.size()];
        associate(detections, used, HIGH_SCORE, Float.MAX_VALUE, MATCH_IOU);
        associate(detections, used, 0f, HIGH_SCORE, MATCH_IOU_WEAK);

        for (int i = 0; i < detections.size(); i++) {
            YoloSegmenter.Detection detection = detections.get(i);
            if (used[i] || detection.score < HIGH_SCORE) continue;
            Track track = new Track(nextId++);
            totalSeen++;
            track.box.set(detection.box);
            track.classId = detection.classId;
            track.score = detection.score;
            track.mask = detection.mask;
            track.maskBox = detection.maskBox;
            track.hits = 1;
            track.misses = 0;
            track.pushTrail();
            tracks.add(track);
        }

        for (Iterator<Track> it = tracks.iterator(); it.hasNext(); ) {
            if (it.next().misses > MAX_MISSES) it.remove();
        }

        List<Snapshot> visible = new ArrayList<>();
        for (Track track : tracks) {
            if (track.misses == 0 && track.hits >= CONFIRM_HITS) visible.add(new Snapshot(track));
        }
        Collections.sort(visible, (a, b) -> Float.compare(b.score, a.score));
        return visible;
    }

    /**
     * Greedy overlap matching for the detections scoring in [{@code minScore}, {@code maxScore}):
     * the best pair is taken first, so a strong overlap is never lost to a weaker one.
     */
    private void associate(List<YoloSegmenter.Detection> detections, boolean[] used,
                           float minScore, float maxScore, float minIou) {
        while (true) {
            float bestIou = minIou;
            Track bestTrack = null;
            int bestIndex = -1;
            for (Track track : tracks) {
                if (track.misses == 0) continue; // already matched in an earlier round
                for (int i = 0; i < detections.size(); i++) {
                    YoloSegmenter.Detection detection = detections.get(i);
                    if (used[i] || detection.score < minScore || detection.score >= maxScore) continue;
                    if (detection.classId != track.classId) continue;
                    float iou = iou(track.box, detection.box);
                    if (iou > bestIou) {
                        bestIou = iou;
                        bestTrack = track;
                        bestIndex = i;
                    }
                }
            }
            if (bestTrack == null) return;
            used[bestIndex] = true;
            apply(bestTrack, detections.get(bestIndex));
        }
    }

    private static void apply(Track track, YoloSegmenter.Detection detection) {
        float dx = detection.box.centerX() - track.box.centerX();
        float dy = detection.box.centerY() - track.box.centerY();
        track.vx = track.vx * VELOCITY_SMOOTHING + dx * (1 - VELOCITY_SMOOTHING);
        track.vy = track.vy * VELOCITY_SMOOTHING + dy * (1 - VELOCITY_SMOOTHING);
        // ease the box towards the detection so the overlay does not jitter frame to frame
        track.box.set(
                mix(track.box.left, detection.box.left),
                mix(track.box.top, detection.box.top),
                mix(track.box.right, detection.box.right),
                mix(track.box.bottom, detection.box.bottom));
        track.score = detection.score;
        track.mask = detection.mask;
        track.maskBox = detection.maskBox;
        track.hits++;
        track.misses = 0;
        track.pushTrail();
    }

    private static float mix(float previous, float current) {
        return previous * BOX_SMOOTHING + current * (1 - BOX_SMOOTHING);
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0f;
        float intersection = (right - left) * (bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return union <= 0 ? 0f : intersection / union;
    }
}
