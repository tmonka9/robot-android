package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.falcon.robot.face.FaceAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws face boxes (corner brackets) and name / similarity labels over a CameraX
 * {@code PreviewView} using its default FILL_CENTER scaling.
 */
public class FaceOverlayView extends View {

    private static final int COLOR_RECOGNIZED = 0xFF1ED9A4;
    private static final int COLOR_UNKNOWN = 0xFFFF4D5E;
    private static final int COLOR_DETECTED = 0xFF22D3EE;

    private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float dp;

    private List<FaceAnalyzer.FrameFace> faces = new ArrayList<>();
    private int imageWidth;
    private int imageHeight;
    private boolean mirrored;
    private String unknownLabel = "Unknown";
    private String faceLabel = "Face";

    public FaceOverlayView(Context context) {
        this(context, null);
    }

    public FaceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        corner.setStyle(Paint.Style.STROKE);
        corner.setStrokeWidth(3 * dp);
        corner.setStrokeCap(Paint.Cap.ROUND);
        labelText.setColor(0xFFFFFFFF);
        labelText.setTextSize(13 * dp);
        labelText.setFakeBoldText(true);
    }

    public void setLabels(String unknown, String face) {
        unknownLabel = unknown;
        faceLabel = face;
    }

    /** Faces in upright image coordinates; {@code mirrored} for the front camera. */
    public void setFaces(List<FaceAnalyzer.FrameFace> faces, int imageWidth, int imageHeight, boolean mirrored) {
        this.faces = faces;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.mirrored = mirrored;
        invalidate();
    }

    public void clear() {
        faces = new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (imageWidth == 0 || imageHeight == 0 || faces.isEmpty()) return;
        float scale = Math.max(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float dx = (getWidth() - imageWidth * scale) / 2f;
        float dy = (getHeight() - imageHeight * scale) / 2f;

        for (FaceAnalyzer.FrameFace face : faces) {
            float left = face.box.left * scale + dx;
            float right = face.box.right * scale + dx;
            if (mirrored) {
                float l = getWidth() - right;
                right = getWidth() - left;
                left = l;
            }
            rect.set(left, face.box.top * scale + dy, right, face.box.bottom * scale + dy);

            int color = face.similarity < 0 ? COLOR_DETECTED : face.recognized ? COLOR_RECOGNIZED : COLOR_UNKNOWN;
            corner.setColor(color);
            drawCorners(canvas, rect);

            String text;
            if (face.similarity < 0) {
                text = faceLabel;
            } else if (face.recognized) {
                text = String.format(Locale.US, "%s  %.1f%%", face.label, face.similarity);
            } else {
                text = unknownLabel;
            }
            float pad = 6 * dp;
            float tw = labelText.measureText(text) + pad * 2;
            float th = 22 * dp;
            float lx = Math.max(0, Math.min(rect.centerX() - tw / 2, getWidth() - tw));
            float ly = rect.bottom + 6 * dp;
            if (ly + th > getHeight()) ly = rect.top - th - 6 * dp;
            labelBg.setColor((color & 0x00FFFFFF) | 0xE6000000);
            canvas.drawRoundRect(new RectF(lx, ly, lx + tw, ly + th), 5 * dp, 5 * dp, labelBg);
            canvas.drawText(text, lx + pad, ly + th - 7 * dp, labelText);
        }
    }

    private void drawCorners(Canvas canvas, RectF r) {
        float len = Math.min(r.width(), r.height()) * 0.25f;
        path.reset();
        path.moveTo(r.left, r.top + len);
        path.lineTo(r.left, r.top);
        path.lineTo(r.left + len, r.top);
        path.moveTo(r.right - len, r.top);
        path.lineTo(r.right, r.top);
        path.lineTo(r.right, r.top + len);
        path.moveTo(r.right, r.bottom - len);
        path.lineTo(r.right, r.bottom);
        path.lineTo(r.right - len, r.bottom);
        path.moveTo(r.left + len, r.bottom);
        path.lineTo(r.left, r.bottom);
        path.lineTo(r.left, r.bottom - len);
        canvas.drawPath(path, corner);
    }
}
