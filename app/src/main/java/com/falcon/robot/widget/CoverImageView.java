package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Shows an image like {@code centerCrop}, but never crops into a "focus" region of the image
 * (e.g. the title text and the robot of an artwork). If covering the view would cut into the
 * focus region, the image is scaled down to fit it and centered instead.
 */
public class CoverImageView extends ImageView {

    private final RectF focus = new RectF(0f, 0f, 1f, 1f); // fractions of the image size
    private final Matrix matrix = new Matrix();

    public CoverImageView(Context context) {
        this(context, null);
    }

    public CoverImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
    }

    /** Region (fractions 0..1 of the image) that must stay visible. */
    public void setFocus(float left, float top, float right, float bottom) {
        focus.set(left, top, right, bottom);
        updateMatrix();
    }

    /**
     * Maps a rectangle given in image pixels to view coordinates (for placing overlays on the
     * artwork). Returns false until the view is laid out.
     */
    public boolean mapImageRect(RectF imageRect, RectF out) {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return false;
        out.set(imageRect);
        matrix.mapRect(out);
        return true;
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        updateMatrix();
        return changed;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateMatrix();
    }

    private void updateMatrix() {
        Drawable d = getDrawable();
        int vw = getWidth();
        int vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        float iw = d.getIntrinsicWidth();
        float ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return;

        float scale = Math.max(vw / iw, vh / ih);
        scale = Math.min(scale, vw / (focus.width() * iw));
        scale = Math.min(scale, vh / (focus.height() * ih));

        float dx = place(vw, iw * scale, focus.centerX() * iw * scale);
        float dy = place(vh, ih * scale, focus.centerY() * ih * scale);
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx), Math.round(dy));
        setImageMatrix(matrix);
    }

    /** Offset centering the focus, clamped so the image still covers the view when it can. */
    private static float place(float view, float image, float focusCenter) {
        if (image < view) return (view - image) / 2f;
        return Math.max(view - image, Math.min(0f, view / 2f - focusCenter));
    }
}
