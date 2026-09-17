package com.falcon.robot.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Rotating translucent scan beam drawn over the LiDAR radar artwork. */
public class RadarSweepView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float angle;
    private boolean sweeping = true;

    public RadarSweepView(Context context) {
        this(context, null);
    }

    public RadarSweepView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setSweeping(boolean sweeping) {
        this.sweeping = sweeping;
        if (animator == null) return;
        if (sweeping && !animator.isStarted()) animator.start();
        else if (!sweeping) animator.cancel();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // bright leading edge at 0°, fading out over the trailing ~60°
        paint.setShader(new SweepGradient(w / 2f, h / 2f,
                new int[] {0x00000000, 0x00000000, 0x2222D3EE, 0x7722D3EE},
                new float[] {0f, 0.82f, 0.97f, 1f}));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!sweeping) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.92f;
        canvas.save();
        canvas.rotate(angle, cx, cy);
        canvas.drawCircle(cx, cy, r, paint);
        canvas.restore();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(3000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            invalidate();
        });
        if (sweeping) animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
