package com.falcon.robot.detect;

import android.content.Context;
import android.graphics.Color;

import com.falcon.robot.R;

/**
 * COCO class names (the classes YOLOv8 is trained on) and a stable colour for each.
 *
 * <p>{@link #init} replaces the names with the translated ones for the app language; the English
 * list below is the fallback and stays the wording used in commands sent to the robot.
 */
public final class CocoLabels {

    private static final String[] ENGLISH = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
    };

    private static final int[] COLORS = new int[ENGLISH.length];

    /** Read by the analysis thread, replaced by the UI thread in {@link #init}. */
    private static volatile String[] names = ENGLISH;

    static {
        // spread the hues with the golden ratio so neighbouring classes stay distinguishable
        float hue = 0.08f;
        for (int i = 0; i < COLORS.length; i++) {
            COLORS[i] = Color.HSVToColor(new float[] {hue * 360f, 0.72f, 1f});
            hue = (hue + 0.618033f) % 1f;
        }
    }

    private CocoLabels() {
    }

    /** Loads the class names in the app language; call it before showing detections. */
    public static void init(Context context) {
        String[] translated = context.getResources().getStringArray(R.array.coco_labels);
        names = translated.length == ENGLISH.length ? translated : ENGLISH;
    }

    /** Class name, or a generic one for models trained on something other than COCO. */
    public static String name(int classId) {
        String[] current = names;
        return classId >= 0 && classId < current.length ? current[classId] : "class " + classId;
    }

    public static int color(int classId) {
        return classId >= 0 && classId < COLORS.length ? COLORS[classId] : 0xFF22D3EE;
    }
}
