package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Camera frame with object-detection bounding boxes and labels.
 *
 * <p>Until a camera and model are connected, it shows a still image with simulated detections
 * whose confidence and position jitter slightly while "real-time inference" is on.
 */
public class DetectionView extends View {

    public static final class Detection {
        public final String label;
        public final int color;
        /** Confidence reported by the model (0..1), updated while live. */
        public float confidence;
        final float baseConfidence;
        final RectF base;   // normalized 0..1 in image coordinates
        final RectF box;    // current (jittered) box

        public Detection(String label, int color, float confidence, float left, float top, float right, float bottom) {
            this.label = label;
            this.color = color;
            this.confidence = confidence;
            this.baseConfidence = confidence;
            this.base = new RectF(left, top, right, bottom);
            this.box = new RectF(base);
        }
    }

    public interface OnDetectionsChangedListener {
        void onDetectionsChanged(List<Detection> visible);
    }

    private static final long TICK_MS = 600;

    private final List<Detection> detections = new ArrayList<>();
    private final Random random = new Random();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Matrix imageMatrix = new Matrix();
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final float dp;

    private Bitmap image;
    private float imageScale = 1f;
    private float imageDx;
    private float imageDy;
    private float threshold = 0.5f;
    private boolean detecting = true;
    private boolean showBoxes = true;
    private boolean showLabels = true;
    private boolean live = true;
    private OnDetectionsChangedListener listener;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            for (Detection d : detections) {
                d.confidence = clamp(d.baseConfidence + (random.nextFloat() - 0.5f) * 0.06f, 0.05f, 0.99f);
                float jx = (random.nextFloat() - 0.5f) * 0.006f;
                float jy = (random.nextFloat() - 0.5f) * 0.006f;
                d.box.set(d.base.left + jx, d.base.top + jy, d.base.right + jx, d.base.bottom + jy);
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
        boxPaint.setStrokeWidth(2.5f * dp);
        labelText.setColor(0xFFFFFFFF);
        labelText.setTextSize(12 * dp);
        labelText.setFakeBoldText(true);
        dim.setColor(0x99000000);
    }

    /** Frame to analyse (a still placeholder until the camera stream is connected). */
    public void setImage(int drawableRes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        image = BitmapFactory.decodeResource(getResources(), drawableRes, options);
        updateImageMatrix();
        invalidate();
    }

    public void setDetections(List<Detection> list) {
        detections.clear();
        detections.addAll(list);
        notifyChanged();
        invalidate();
    }

    /** Detections at or above the confidence threshold (none while detection is disabled). */
    public List<Detection> getVisibleDetections() {
        List<Detection> visible = new ArrayList<>();
        if (!detecting) return visible;
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

    public void setDetecting(boolean detecting) {
        this.detecting = detecting;
        notifyChanged();
        invalidate();
    }

    public void setShowBoxes(boolean showBoxes) {
        this.showBoxes = showBoxes;
        invalidate();
    }

    public void setShowLabels(boolean showLabels) {
        this.showLabels = showLabels;
        invalidate();
    }

    /** Real-time inference: when off, the last result stays frozen. */
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
        updateImageMatrix();
    }

    /** Center-crop the frame into the view. */
    private void updateImageMatrix() {
        if (image == null || getWidth() == 0 || getHeight() == 0) return;
        imageScale = Math.max(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
        imageDx = (getWidth() - image.getWidth() * imageScale) / 2f;
        imageDy = (getHeight() - image.getHeight() * imageScale) / 2f;
        imageMatrix.setScale(imageScale, imageScale);
        imageMatrix.postTranslate(imageDx, imageDy);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        canvas.save();
        clip.reset();
        rect.set(0, 0, w, h);
        clip.addRoundRect(rect, 10 * dp, 10 * dp, Path.Direction.CW);
        canvas.clipPath(clip);

        if (image != null) {
            canvas.drawBitmap(image, imageMatrix, imagePaint);
        } else {
            canvas.drawColor(0xFF0A1428);
        }
        if (!detecting) {
            canvas.drawRect(0, 0, w, h, dim);
        } else if (showBoxes && image != null) {
            float iw = image.getWidth() * imageScale;
            float ih = image.getHeight() * imageScale;
            for (Detection d : detections) {
                if (d.confidence < threshold) continue;
                rect.set(imageDx + d.box.left * iw, imageDy + d.box.top * ih,
                        imageDx + d.box.right * iw, imageDy + d.box.bottom * ih);
                boxPaint.setColor(d.color);
                canvas.drawRect(rect, boxPaint);
                if (showLabels) drawLabel(canvas, d, rect);
            }
        }
        canvas.restore();
    }

    private void drawLabel(Canvas canvas, Detection d, RectF box) {
        String text = String.format(Locale.US, "%s %.2f", d.label, d.confidence);
        float pad = 5 * dp;
        float tw = labelText.measureText(text) + pad * 2;
        float th = 18 * dp;
        float left = Math.min(box.left - boxPaint.getStrokeWidth() / 2f, getWidth() - tw);
        float top = box.top - th;
        if (top < 0) top = box.top; // no room above: draw inside the box
        labelBg.setColor(d.color);
        canvas.drawRect(left, top, left + tw, top + th, labelBg);
        canvas.drawText(text, left + pad, top + th - 5 * dp, labelText);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
