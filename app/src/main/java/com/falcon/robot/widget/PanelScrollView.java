package com.falcon.robot.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * A scrolling panel inside a scrolling page.
 *
 * <p>A panel squeezed to the height of the page ({@link FitScrollView}) can be shorter than the
 * controls in it — the FPS dropdown at the foot of the camera settings was simply cut off. An
 * ordinary {@code ScrollView} is not enough on its own: the page's scroll view takes the drag over
 * as soon as it passes the touch slop, so the panel scrolls a few pixels and then hands the gesture
 * away. This claims the gesture for the panel whenever there is something to scroll to, which is
 * what lets a drag inside it — with a finger, or with a mouse button held down — reach the last row.
 */
public class PanelScrollView extends ScrollView {

    public PanelScrollView(Context context) {
        this(context, null);
    }

    public PanelScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // ACTION_DOWN always reaches here, whichever child ends up with the rest of the gesture
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && scrollable()) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        }
        return super.onInterceptTouchEvent(event);
    }

    /** True while the panel has more content than it can show. */
    private boolean scrollable() {
        return canScrollVertically(1) || canScrollVertically(-1);
    }
}
