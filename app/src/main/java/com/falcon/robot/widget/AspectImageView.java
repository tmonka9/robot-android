package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

/** ImageView whose height follows its width using the image's own aspect ratio (no cropping). */
public class AspectImageView extends ImageView {

    public AspectImageView(Context context) {
        this(context, null);
    }

    public AspectImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.FIT_XY); // exact aspect, so nothing is actually distorted
        setAdjustViewBounds(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable d = getDrawable();
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        if (d == null || d.getIntrinsicWidth() <= 0 || View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int height = Math.round(width * (float) d.getIntrinsicHeight() / d.getIntrinsicWidth());
        setMeasuredDimension(width, height);
    }
}
