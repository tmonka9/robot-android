package com.falcon.robot.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * Page container that keeps the sections of a page at the height their weights ask for.
 *
 * <p>An ordinary {@code ScrollView} measures its content without a height limit, so a panel grows
 * with whatever is inside it and the whole page ends up scrolling. Here the content is measured to
 * exactly the viewport whenever the screen is tall enough for the design, which fixes every
 * section's height and leaves the lists inside them to scroll on their own.
 *
 * <p>The designs are drawn for a tablet. On anything smaller — a phone, a small window, a short
 * landscape screen — squeezing them in would make the panels unusable, so there the content keeps
 * its natural height and this behaves like the plain scroll view it extends.
 */
public class FitScrollView extends ScrollView {

    /** Below this viewport height the page scrolls instead of being squeezed. */
    private static final float MIN_FIT_HEIGHT_DP = 520f;

    private final boolean tabletLayout;
    private final int minFitHeight;
    private boolean fitContent;
    private int viewportHeight;

    public FitScrollView(Context context) {
        this(context, null);
    }

    public FitScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        minFitHeight = Math.round(MIN_FIT_HEIGHT_DP * getResources().getDisplayMetrics().density);
        tabletLayout = getResources().getBoolean(com.falcon.robot.R.bool.two_columns);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        viewportHeight = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
        fitContent = tabletLayout && mode != MeasureSpec.UNSPECIFIED && viewportHeight >= minFitHeight;
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void measureChild(View child, int parentWidthMeasureSpec, int parentHeightMeasureSpec) {
        if (!fitContent) {
            super.measureChild(child, parentWidthMeasureSpec, parentHeightMeasureSpec);
            return;
        }
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        child.measure(getChildMeasureSpec(parentWidthMeasureSpec,
                        getPaddingLeft() + getPaddingRight(), lp.width),
                MeasureSpec.makeMeasureSpec(viewportHeight, MeasureSpec.EXACTLY));
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
                                           int parentHeightMeasureSpec, int heightUsed) {
        if (!fitContent) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
            return;
        }
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int width = getChildMeasureSpec(parentWidthMeasureSpec,
                getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin + widthUsed,
                lp.width);
        int height = Math.max(0, viewportHeight - lp.topMargin - lp.bottomMargin);
        child.measure(width, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
