package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/** Ring chart showing one fraction (e.g. recognized / total faces); center text is laid out by the parent. */
public class DonutChartView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float fraction;

    public DonutChartView(Context context) {
        this(context, null);
    }

    public DonutChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(12 * dp);
        track.setColor(0xFF3A1C2A); // the unrecognized share reads as a dark red remainder
        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeWidth(12 * dp);
        progress.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Fraction 0..1 drawn as the bright arc. */
    public void setFraction(float fraction) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        progress.setShader(new SweepGradient(w / 2f, h / 2f,
                new int[] {0xFF22D3EE, 0xFF1ED9A4, 0xFF22D3EE}, null));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float half = track.getStrokeWidth() / 2f;
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f + half;
        float top = (getHeight() - size) / 2f + half;
        oval.set(left, top, left + size - 2 * half, top + size - 2 * half);
        canvas.drawArc(oval, 0, 360, false, track);
        canvas.save();
        canvas.rotate(-90, oval.centerX(), oval.centerY());
        canvas.drawArc(oval, 0, 360 * fraction, false, progress);
        canvas.restore();
    }
}
