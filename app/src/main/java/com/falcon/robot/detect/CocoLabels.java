package com.falcon.robot.detect;

import android.graphics.Color;

/** COCO class names (the classes YOLOv8 is trained on) and a stable colour for each. */
public final class CocoLabels {

    public static final String[] NAMES = {
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

    private static final int[] COLORS = new int[NAMES.length];

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

    /** Class name, or a generic one for models trained on something other than COCO. */
    public static String name(int classId) {
        return classId >= 0 && classId < NAMES.length ? NAMES[classId] : "class " + classId;
    }

    public static int color(int classId) {
        return classId >= 0 && classId < COLORS.length ? COLORS[classId] : 0xFF22D3EE;
    }
}
