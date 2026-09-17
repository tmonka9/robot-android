package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Animated audio-level bars shown while voice recognition is listening. */
public class WaveformView extends View {

    private static final int BAR_COUNT = 25;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float barWidth;
    private final float barGap;

    private boolean active = true;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        barWidth = 3 * dp;
        barGap = 3.5f * dp;
    }

    public void setActive(boolean active) {
        this.active = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float cy = h / 2f;
        float total = BAR_COUNT * barWidth + (BAR_COUNT - 1) * barGap;
        float x = (getWidth() - total) / 2f;
        double t = SystemClock.uptimeMillis() / 1000.0;
        int mid = BAR_COUNT / 2;

        for (int i = 0; i < BAR_COUNT; i++) {
            // taller in the middle, tapering to the edges
            float envelope = 1f - Math.abs(i - mid) / (float) (mid + 1);
            float level;
            if (active) {
                double wave = Math.sin(t * 7 + i * 0.9) * 0.5 + Math.sin(t * 11.3 + i * 1.7) * 0.5;
                level = 0.2f + 0.8f * envelope * (float) Math.abs(wave);
            } else {
                level = 0.08f;
            }
            float barH = Math.max(barWidth, h * level);

            int alpha = (int) (110 + 145 * envelope);
            barPaint.setColor((alpha << 24) | (active ? 0x8A6DFF : 0x5D6A82));

            bar.set(x, cy - barH / 2f, x + barWidth, cy + barH / 2f);
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, barPaint);
            x += barWidth + barGap;
        }

        if (active && isShown()) postInvalidateOnAnimation();
    }
}
