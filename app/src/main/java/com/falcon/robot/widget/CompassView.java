package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Small compass: ring with N/E/S/W and an arrow pointing along the robot's heading. */
public class CompassView extends View {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;
    private float heading; // degrees, 0 = north, clockwise

    public CompassView(Context context) {
        this(context, null);
    }

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * dp);
        ring.setColor(0xFF2F80FF);
        tick.setStrokeWidth(1f * dp);
        tick.setColor(0x884FA8FF);
        letters.setColor(0xFFB4C0D6);
        letters.setTextAlign(Paint.Align.CENTER);
        letters.setTextSize(9 * dp);
        arrow.setColor(0xFF4FA8FF);
    }

    public void setHeading(float degrees) {
        heading = degrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 12 * dp;
        canvas.drawCircle(cx, cy, r, ring);
        for (int i = 0; i < 36; i++) {
            double a = Math.toRadians(i * 10);
            float inner = r - (i % 9 == 0 ? 6 : 3) * dp;
            canvas.drawLine(cx + (float) Math.sin(a) * inner, cy - (float) Math.cos(a) * inner,
                    cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, tick);
        }
        float lr = r + 7 * dp;
        float off = letters.getTextSize() / 3f;
        canvas.drawText("N", cx, cy - lr + off, letters);
        canvas.drawText("S", cx, cy + lr + off, letters);
        canvas.drawText("E", cx + lr, cy + off, letters);
        canvas.drawText("W", cx - lr, cy + off, letters);

        canvas.save();
        canvas.rotate(heading, cx, cy);
        float s = r * 0.62f;
        path.reset();
        path.moveTo(cx, cy - s);
        path.lineTo(cx + s * 0.5f, cy + s * 0.55f);
        path.lineTo(cx, cy + s * 0.25f);
        path.lineTo(cx - s * 0.5f, cy + s * 0.55f);
        path.close();
        canvas.drawPath(path, arrow);
        canvas.restore();
    }
}
