package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Camera view with object-detection bounding boxes.
 *
 * <p>Until a camera and model are connected, it draws a stylised street scene and a set of
 * simulated detections that jitter while "live".
 */
public class DetectionView extends View {

    public static final class Detection {
        public final String label;
        public final int color;
        public float confidence;
        final RectF box; // normalized 0..1

        Detection(String label, int color, float confidence, float l, float t, float r, float b) {
            this.label = label;
            this.color = color;
            this.confidence = confidence;
            this.box = new RectF(l, t, r, b);
        }
    }

    public interface OnDetectionsChangedListener {
        void onDetectionsChanged(List<Detection> visible);
    }

    private static final long TICK_MS = 700;

    private final List<Detection> detections = new ArrayList<>();
    private final Random random = new Random();
    private final Paint scenePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float dp;

    private Shader skyShader;
    private float threshold = 0.5f;
    private boolean showBoxes = true;
    private boolean live = true;
    private OnDetectionsChangedListener listener;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            for (Detection d : detections) {
                d.confidence = clamp(d.confidence + (random.nextFloat() - 0.5f) * 0.08f, 0.2f, 0.99f);
                float shift = (random.nextFloat() - 0.5f) * 0.01f;
                d.box.offset(shift, 0);
            }
            notifyChanged();
            invalidate();
            if (live) postDelayed(this, TICK_MS);
        }
    };

    public DetectionView(Context context) {
        this(context, null);
    }

    public DetectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;

        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2 * dp);
        labelText.setColor(0xFFFFFFFF);
        labelText.setTextSize(11 * dp);

        detections.add(new Detection("person", 0xFF3B82F6, 0.92f, 0.07f, 0.34f, 0.19f, 0.86f));
        detections.add(new Detection("person", 0xFF3B82F6, 0.87f, 0.22f, 0.40f, 0.33f, 0.90f));
        detections.add(new Detection("car", 0xFF22C55E, 0.87f, 0.42f, 0.40f, 0.62f, 0.66f));
        detections.add(new Detection("bicycle", 0xFFF59E0B, 0.76f, 0.68f, 0.48f, 0.88f, 0.92f));
        detections.add(new Detection("traffic light", 0xFF22D3EE, 0.41f, 0.50f, 0.08f, 0.54f, 0.24f));
        detections.add(new Detection("sign", 0xFFEF4444, 0.34f, 0.90f, 0.18f, 0.97f, 0.32f));
    }

    /** Category names in display order, each with its box color. */
    public List<Detection> getCategories() {
        List<Detection> categories = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Detection d : detections) {
            if (!seen.contains(d.label)) {
                seen.add(d.label);
                categories.add(d);
            }
        }
        return Collections.unmodifiableList(categories);
    }

    public List<Detection> getVisibleDetections() {
        List<Detection> visible = new ArrayList<>();
        for (Detection d : detections) {
            if (d.confidence >= threshold) visible.add(d);
        }
        return visible;
    }

    public void setOnDetectionsChangedListener(OnDetectionsChangedListener listener) {
        this.listener = listener;
        notifyChanged();
    }

    public void setThreshold(float threshold) {
        this.threshold = threshold;
        notifyChanged();
        invalidate();
    }

    public void setShowBoxes(boolean showBoxes) {
        this.showBoxes = showBoxes;
        invalidate();
    }

    public void setLive(boolean live) {
        if (this.live == live) return;
        this.live = live;
        removeCallbacks(tick);
        if (live && isAttachedToWindow()) postDelayed(tick, TICK_MS);
    }

    private void notifyChanged() {
        if (listener != null) listener.onDetectionsChanged(getVisibleDetections());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (live) postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        skyShader = new LinearGradient(0, 0, 0, h * 0.5f, 0xFF3A4C66, 0xFF1C2636, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();

        canvas.save();
        path.reset();
        rect.set(0, 0, w, h);
        path.addRoundRect(rect, 10 * dp, 10 * dp, Path.Direction.CW);
        canvas.clipPath(path);

        drawScene(canvas, w, h);

        if (showBoxes) {
            for (Detection d : detections) {
                if (d.confidence < threshold) continue;
                rect.set(d.box.left * w, d.box.top * h, d.box.right * w, d.box.bottom * h);
                boxPaint.setColor(d.color);
                canvas.drawRect(rect, boxPaint);

                String text = String.format(Locale.US, "%s %.2f", d.label, d.confidence);
                float tw = labelText.measureText(text) + 8 * dp;
                float th = 16 * dp;
                float top = Math.max(0, rect.top - th);
                labelBg.setColor(d.color);
                canvas.drawRect(rect.left, top, rect.left + tw, top + th, labelBg);
                canvas.drawText(text, rect.left + 4 * dp, top + th - 4 * dp, labelText);
            }
        }
        canvas.restore();
    }

    /** Placeholder street: sky, buildings, road and lane markings. */
    private void drawScene(Canvas canvas, float w, float h) {
        float horizon = h * 0.5f;

        scenePaint.setShader(skyShader);
        canvas.drawRect(0, 0, w, horizon, scenePaint);
        scenePaint.setShader(null);

        scenePaint.setColor(0xFF1A2330);
        float[][] buildings = {{0f, 0.12f, 0.2f}, {0.18f, 0.25f, 0.34f}, {0.66f, 0.18f, 0.8f}, {0.78f, 0.05f, 1f}};
        for (float[] b : buildings) {
            canvas.drawRect(b[0] * w, b[1] * h, b[2] * w, horizon, scenePaint);
        }

        scenePaint.setColor(0xFF2A2F37);
        canvas.drawRect(0, horizon, w, h, scenePaint);

        scenePaint.setColor(0xFF363C46);
        path.reset();
        path.moveTo(w * 0.44f, horizon);
        path.lineTo(w * 0.56f, horizon);
        path.lineTo(w * 0.95f, h);
        path.lineTo(w * 0.05f, h);
        path.close();
        canvas.drawPath(path, scenePaint);

        scenePaint.setColor(0x99FFFFFF);
        for (int i = 0; i < 5; i++) {
            float t0 = horizon + (h - horizon) * (i / 5f + 0.04f);
            float t1 = t0 + (h - horizon) * 0.08f;
            float half = (1f + i) * dp;
            canvas.drawRect(w * 0.5f - half, t0, w * 0.5f + half, t1, scenePaint);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
