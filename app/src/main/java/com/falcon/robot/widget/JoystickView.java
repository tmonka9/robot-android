package com.falcon.robot.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Analog joystick: drag the knob inside the ring; springs back to center on release. */
public class JoystickView extends View {

    public interface OnMoveListener {
        /** x and y are in [-1, 1]; y is positive upwards. (0, 0) on release. */
        void onMove(float x, float y);
    }

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float knobX;
    private float knobY;
    private ValueAnimator springBack;
    private OnMoveListener listener;

    public JoystickView(Context context) {
        this(context, null);
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(8 * dp);
        glow.setColor(0x262F80FF);

        ringFill.setColor(0xFF0B1528);

        ringStroke.setStyle(Paint.Style.STROKE);
        ringStroke.setStrokeWidth(2 * dp);
        ringStroke.setColor(0xFF2A4A80);

        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(2 * dp);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        chevron.setColor(0xFFB4C0D6);

        knobStroke.setStyle(Paint.Style.STROKE);
        knobStroke.setStrokeWidth(3 * dp);
        knobStroke.setColor(0xFF9CC6FF);
    }

    public void setOnMoveListener(OnMoveListener listener) {
        this.listener = listener;
    }

    /**
     * Moves the knob without touch, so a connected gamepad's stick shows here too. Values are in
     * [-1, 1] with y positive upwards; the listener is not called, since whoever drives this
     * already knows the direction.
     */
    public void setDirection(float x, float y) {
        if (springBack != null) springBack.cancel();
        float length = (float) Math.hypot(x, y);
        if (length > 1f) {
            x /= length;
            y /= length;
        }
        float reach = radius() - knobRadius();
        knobX = x * reach;
        knobY = -y * reach;
        invalidate();
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - glow.getStrokeWidth();
    }

    private float knobRadius() {
        return radius() * 0.3f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float kr = Math.min(w, h) * 0.15f;
        knobFill.setShader(new RadialGradient(0, -kr * 0.3f, kr * 1.2f,
                0xFF6FB5FF, 0xFF1F5FD8, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = radius();

        canvas.drawCircle(cx, cy, r, glow);
        canvas.drawCircle(cx, cy, r, ringFill);
        canvas.drawCircle(cx, cy, r, ringStroke);
        canvas.drawCircle(cx, cy, r * 0.62f, ringStroke);

        float d = r * 0.81f;
        float s = r * 0.08f;
        drawChevron(canvas, cx, cy - d, 0, -1, s);
        drawChevron(canvas, cx, cy + d, 0, 1, s);
        drawChevron(canvas, cx - d, cy, -1, 0, s);
        drawChevron(canvas, cx + d, cy, 1, 0, s);

        float kx = cx + knobX;
        float ky = cy + knobY;
        canvas.save();
        canvas.translate(kx, ky);
        canvas.drawCircle(0, 0, knobRadius(), knobFill);
        canvas.drawCircle(0, 0, knobRadius(), knobStroke);
        canvas.restore();
    }

    private void drawChevron(Canvas canvas, float x, float y, int dx, int dy, float s) {
        path.reset();
        path.moveTo(x - dx * s * 0.5f - dy * s, y - dy * s * 0.5f - dx * s);
        path.lineTo(x + dx * s * 0.5f, y + dy * s * 0.5f);
        path.lineTo(x - dx * s * 0.5f + dy * s, y - dy * s * 0.5f + dx * s);
        canvas.drawPath(path, chevron);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (springBack != null) springBack.cancel();
                getParent().requestDisallowInterceptTouchEvent(true); // don't let the ScrollView steal drags
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - getWidth() / 2f;
                float dy = event.getY() - getHeight() / 2f;
                float max = radius() - knobRadius();
                float dist = (float) Math.hypot(dx, dy);
                if (dist > max && dist > 0) {
                    dx = dx / dist * max;
                    dy = dy / dist * max;
                }
                setKnob(dx, dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
                performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                animateBack();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void animateBack() {
        final float startX = knobX;
        final float startY = knobY;
        springBack = ValueAnimator.ofFloat(1f, 0f);
        springBack.setDuration(140);
        springBack.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            setKnob(startX * f, startY * f);
        });
        springBack.start();
    }

    private void setKnob(float x, float y) {
        knobX = x;
        knobY = y;
        invalidate();
        if (listener != null) {
            float max = radius() - knobRadius();
            if (max <= 0) return;
            listener.onMove(x / max, -y / max);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (springBack != null) springBack.cancel();
        super.onDetachedFromWindow();
    }
}
