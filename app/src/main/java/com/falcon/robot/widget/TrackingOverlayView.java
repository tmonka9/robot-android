package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.falcon.robot.detect.CocoLabels;
import com.falcon.robot.detect.ObjectTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws segmentation masks, boxes, track ids and motion trails over a CameraX {@code PreviewView}
 * using its default FILL_CENTER scaling.
 */
public class TrackingOverlayView extends View {

    private static final int MASK_ALPHA = 0x66;
    private static final int TRAIL_ALPHA = 0xCC;

    private final Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF maskRect = new RectF();
    private final RectF chip = new RectF();
    private final Path trail = new Path();
    private final float dp;

    private List<ObjectTracker.Snapshot> objects = new ArrayList<>();
    private int imageWidth;
    private int imageHeight;
    private boolean mirrored;
    private boolean showMasks = true;
    private boolean showBoxes = true;
    private boolean showLabels = true;
    private boolean showTrails = true;

    public TrackingOverlayView(Context context) {
        this(context, null);
    }

    public TrackingOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2.5f * dp);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(2.5f * dp);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        labelText.setColor(0xFF07121F);
        labelText.setTextSize(12 * dp);
        labelText.setFakeBoldText(true);
    }

    /** Objects in upright frame coordinates; {@code mirrored} for the front camera. */
    public void setObjects(List<ObjectTracker.Snapshot> objects, int imageWidth, int imageHeight, boolean mirrored) {
        this.objects = objects;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.mirrored = mirrored;
        invalidate();
    }

    public void clear() {
        objects = new ArrayList<>();
        invalidate();
    }

    public void setShowMasks(boolean show) {
        showMasks = show;
        invalidate();
    }

    public void setShowBoxes(boolean show) {
        showBoxes = show;
        invalidate();
    }

    public void setShowLabels(boolean show) {
        showLabels = show;
        invalidate();
    }

    public void setShowTrails(boolean show) {
        showTrails = show;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (imageWidth == 0 || imageHeight == 0 || objects.isEmpty()) return;
        float scale = Math.max(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float dx = (getWidth() - imageWidth * scale) / 2f;
        float dy = (getHeight() - imageHeight * scale) / 2f;

        for (ObjectTracker.Snapshot object : objects) {
            int color = CocoLabels.color(object.classId);
            mapRect(object.box, scale, dx, dy, rect);

            if (showMasks && object.mask != null && object.maskBox != null && !object.mask.isRecycled()) {
                // the mask has its own rectangle: it covers whole mask pixels, not exactly the box
                mapRect(object.maskBox, scale, dx, dy, maskRect);
                maskPaint.setColor((color & 0x00FFFFFF) | (MASK_ALPHA << 24));
                canvas.save();
                // the rectangle is already mirrored, so the mask content has to follow inside it
                if (mirrored) canvas.scale(-1f, 1f, maskRect.centerX(), maskRect.centerY());
                canvas.drawBitmap(object.mask, null, maskRect, maskPaint);
                canvas.restore();
            }
            if (showBoxes) {
                boxPaint.setColor(color);
                canvas.drawRoundRect(rect, 4 * dp, 4 * dp, boxPaint);
            }
            if (showTrails && object.trail.length >= 4) drawTrail(canvas, object, color, scale, dx, dy);
            if (showLabels) drawLabel(canvas, object, color);
        }
    }

    /** Frame rectangle to screen, flipped for a mirrored preview. */
    private void mapRect(RectF box, float scale, float dx, float dy, RectF out) {
        float left = box.left * scale + dx;
        float right = box.right * scale + dx;
        if (mirrored) {
            float mirroredLeft = getWidth() - right;
            right = getWidth() - left;
            left = mirroredLeft;
        }
        out.set(left, box.top * scale + dy, right, box.bottom * scale + dy);
    }

    /** Path through the object's past centres, fading out towards the oldest point. */
    private void drawTrail(Canvas canvas, ObjectTracker.Snapshot object, int color, float scale, float dx, float dy) {
        trail.reset();
        for (int i = 0; i < object.trail.length; i += 2) {
            float x = object.trail[i] * scale + dx;
            if (mirrored) x = getWidth() - x;
            float y = object.trail[i + 1] * scale + dy;
            if (i == 0) trail.moveTo(x, y);
            else trail.lineTo(x, y);
        }
        trailPaint.setColor((color & 0x00FFFFFF) | (TRAIL_ALPHA << 24));
        canvas.drawPath(trail, trailPaint);
    }

    private void drawLabel(Canvas canvas, ObjectTracker.Snapshot object, int color) {
        String text = object.id > 0
                ? String.format(Locale.US, "#%d %s %.0f%%", object.id, object.label, object.score * 100f)
                : String.format(Locale.US, "%s %.0f%%", object.label, object.score * 100f);
        float padding = 6 * dp;
        float width = labelText.measureText(text) + padding * 2;
        float height = 19 * dp;
        float left = Math.max(0, Math.min(rect.left, getWidth() - width));
        float top = rect.top - height - 2 * dp;
        if (top < 0) top = Math.min(rect.top + 2 * dp, getHeight() - height);
        chip.set(left, top, left + width, top + height);
        labelBg.setColor((color & 0x00FFFFFF) | 0xF2000000);
        canvas.drawRoundRect(chip, 4 * dp, 4 * dp, labelBg);
        canvas.drawText(text, left + padding, chip.bottom - 5.5f * dp, labelText);
    }
}
