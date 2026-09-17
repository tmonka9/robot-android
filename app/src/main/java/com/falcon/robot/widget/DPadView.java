package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Circular directional pad with four arrows and a center "stop" button. */
public class DPadView extends View {

    public enum Direction { NONE, UP, DOWN, LEFT, RIGHT, CENTER }

    public interface OnDirectionListener {
        /** Called when the pressed direction changes; {@link Direction#NONE} on release. */
        void onDirection(Direction direction);
    }

    private final Paint outerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arcRect = new RectF();

    private Direction pressed = Direction.NONE;
    private OnDirectionListener listener;

    public DPadView(Context context) {
        this(context, null);
    }

    public DPadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;

        outerStroke.setStyle(Paint.Style.STROKE);
        outerStroke.setStrokeWidth(2 * dp);
        outerStroke.setColor(0xFF2A3E66);

        glowStroke.setStyle(Paint.Style.STROKE);
        glowStroke.setStrokeWidth(6 * dp);
        glowStroke.setColor(0x222F80FF);

        ringFill.setColor(0xFF0E1830);

        divider.setStyle(Paint.Style.STROKE);
        divider.setStrokeWidth(1.5f * dp);
        divider.setColor(0xFF1F2D4A);

        pressedFill.setColor(0x552F80FF);

        centerFill.setColor(0xFF0A1222);
        centerStroke.setStyle(Paint.Style.STROKE);
        centerStroke.setStrokeWidth(1.5f * dp);
        centerStroke.setColor(0xFF2A3E66);

        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(2.5f * dp);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        chevron.setColor(0xFFE8EEF8);

        dot.setColor(0xFFE8EEF8);

        setClickable(true);
    }

    public void setOnDirectionListener(OnDirectionListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - glowStroke.getStrokeWidth();
        float ringR = r * 0.9f;
        float centerR = r * 0.36f;

        canvas.drawCircle(cx, cy, r, glowStroke);
        canvas.drawCircle(cx, cy, r, outerStroke);
        canvas.drawCircle(cx, cy, ringR, ringFill);

        if (pressed != Direction.NONE && pressed != Direction.CENTER) {
            arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            canvas.drawArc(arcRect, startAngle(pressed), 90, true, pressedFill);
        }

        // diagonal separators between the four segments
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + 90 * i);
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            canvas.drawLine(cx + cos * centerR, cy + sin * centerR,
                    cx + cos * ringR, cy + sin * ringR, divider);
        }
        canvas.drawCircle(cx, cy, ringR, centerStroke);

        centerFill.setColor(pressed == Direction.CENTER ? 0xFF16325E : 0xFF0A1222);
        canvas.drawCircle(cx, cy, centerR, centerFill);
        canvas.drawCircle(cx, cy, centerR, centerStroke);
        canvas.drawCircle(cx, cy, r * 0.045f, dot);

        float d = (centerR + ringR) / 2f;
        float s = r * 0.09f;
        drawChevron(canvas, cx, cy - d, 0, -1, s);
        drawChevron(canvas, cx, cy + d, 0, 1, s);
        drawChevron(canvas, cx - d, cy, -1, 0, s);
        drawChevron(canvas, cx + d, cy, 1, 0, s);
    }

    /** Draws a chevron centered at (x, y) pointing toward (dx, dy). */
    private void drawChevron(Canvas canvas, float x, float y, int dx, int dy, float s) {
        path.reset();
        float tipX = x + dx * s * 0.5f;
        float tipY = y + dy * s * 0.5f;
        float baseX = x - dx * s * 0.5f;
        float baseY = y - dy * s * 0.5f;
        path.moveTo(baseX - dy * s, baseY - dx * s);
        path.lineTo(tipX, tipY);
        path.lineTo(baseX + dy * s, baseY + dx * s);
        canvas.drawPath(path, chevron);
    }

    private static float startAngle(Direction direction) {
        switch (direction) {
            case RIGHT: return -45;
            case DOWN: return 45;
            case LEFT: return 135;
            default: return 225; // UP
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true); // don't let the ScrollView steal drags
                // fall through
            case MotionEvent.ACTION_MOVE:
                setPressedDirection(hitTest(event.getX(), event.getY()));
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                setPressedDirection(Direction.NONE);
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressedDirection(Direction.NONE);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private Direction hitTest(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - glowStroke.getStrokeWidth();
        float dx = x - cx;
        float dy = y - cy;
        double dist = Math.hypot(dx, dy);
        if (dist > r) return Direction.NONE;
        if (dist < r * 0.36f) return Direction.CENTER;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? Direction.RIGHT : Direction.LEFT;
        return dy > 0 ? Direction.DOWN : Direction.UP;
    }

    private void setPressedDirection(Direction direction) {
        if (direction == pressed) return;
        pressed = direction;
        invalidate();
        if (listener != null) listener.onDirection(direction);
    }
}
