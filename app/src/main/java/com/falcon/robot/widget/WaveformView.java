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

    private final float[] levels = new float[BAR_COUNT];
    private int levelIndex;
    private boolean liveLevels;
    private boolean active = true;
    private int barColor = 0x8A6DFF;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        barWidth = 3 * dp;
        barGap = 3.5f * dp;
    }

    /** Sets the bar color (RGB; alpha is applied per bar). */
    public void setBarColor(int rgb) {
        barColor = rgb & 0xFFFFFF;
        invalidate();
    }

    public void setActive(boolean active) {
        this.active = active;
        invalidate();
    }

    /**
     * Feeds a real microphone level (0..1). The first call switches the view from its animated
     * placeholder to showing measured levels, scrolling right to left.
     */
    public void pushLevel(float level) {
        liveLevels = true;
        levels[levelIndex % levels.length] = Math.max(0f, Math.min(1f, level));
        levelIndex++;
        invalidate();
    }

    /** Back to the animated placeholder (e.g. when the microphone stops). */
    public void clearLevels() {
        liveLevels = false;
        levelIndex = 0;
        java.util.Arrays.fill(levels, 0f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float cy = h / 2f;
        // fit as many bars as the width allows (small inline waveforms get fewer bars)
        int count = Math.max(1, Math.min(BAR_COUNT, (int) ((getWidth() + barGap) / (barWidth + barGap))));
        float total = count * barWidth + (count - 1) * barGap;
        float x = (getWidth() - total) / 2f;
        double t = SystemClock.uptimeMillis() / 1000.0;
        int mid = count / 2;

        for (int i = 0; i < count; i++) {
            // taller in the middle, tapering to the edges
            float envelope = 1f - Math.abs(i - mid) / (float) (mid + 1);
            float level;
            if (liveLevels) {
                // oldest sample on the left, newest on the right
                int slot = (levelIndex - count + i + levels.length * 2) % levels.length;
                level = 0.06f + 0.94f * levels[slot];
                envelope = 1f;
            } else if (active) {
                double wave = Math.sin(t * 7 + i * 0.9) * 0.5 + Math.sin(t * 11.3 + i * 1.7) * 0.5;
                level = 0.2f + 0.8f * envelope * (float) Math.abs(wave);
            } else {
                level = 0.08f;
            }
            float barH = Math.max(barWidth, h * level);

            int alpha = (int) (110 + 145 * envelope);
            barPaint.setColor((alpha << 24) | (active ? barColor : 0x5D6A82));

            bar.set(x, cy - barH / 2f, x + barWidth, cy + barH / 2f);
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, barPaint);
            x += barWidth + barGap;
        }

        if (active && !liveLevels && isShown()) postInvalidateOnAnimation();
    }
}
