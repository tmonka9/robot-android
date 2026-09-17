package com.falcon.robot.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decorative isometric LiDAR point-cloud map: rainbow wireframe buildings, a scanned floor,
 * a planned path and a robot marker with pulsing scan rings.
 */
public class LidarMapView extends View {

    /** World-space size of the map (both axes). */
    private static final float WORLD = 12f;

    /** Buildings as {x, y, width, depth, height} in world units. */
    private static final float[][] BOXES = {
            {0.5f, 1.0f, 2.2f, 1.8f, 2.4f},
            {3.5f, 0.3f, 1.6f, 2.4f, 3.2f},
            {6.2f, 0.4f, 2.4f, 1.6f, 1.6f},
            {9.0f, 0.8f, 2.4f, 2.6f, 2.8f},
            {0.6f, 4.8f, 1.8f, 2.2f, 1.4f},
            {1.0f, 8.8f, 2.6f, 2.2f, 2.2f},
            {9.4f, 5.2f, 2.0f, 2.2f, 3.6f},
            {7.0f, 9.6f, 2.8f, 1.8f, 1.8f},
            {4.2f, 4.0f, 1.2f, 1.2f, 4.2f},
    };

    /** Planned path in world units; the robot sits at the last point. */
    private static final float[][] ROUTE = {
            {4.8f, 4.8f}, {5.6f, 6.0f}, {5.0f, 7.0f}, {6.4f, 7.6f}, {7.2f, 8.6f},
    };

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath = new Path();
    private final float dp;

    private final List<float[]> edgeLines = new ArrayList<>();   // per box: drawLines quads
    private final List<float[]> pointClouds = new ArrayList<>(); // per cluster: drawPoints pairs
    private final List<Integer> colors = new ArrayList<>();      // per box, then per floor cluster
    private float[] gridLines;
    private float robotX;
    private float robotY;

    private float scale;
    private float originX;
    private float originY;
    private float phase;
    private ValueAnimator animator;
    private boolean scanning = true;
    private boolean mapVisible = true;
    private boolean showRoute = true;

    public LidarMapView(Context context) {
        this(context, null);
    }

    public LidarMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.2f * dp);

        pointPaint.setStrokeCap(Paint.Cap.ROUND);
        pointPaint.setStrokeWidth(2f * dp);

        gridPaint.setStrokeWidth(1f * dp);
        gridPaint.setColor(0x223FA0FF);

        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(3f * dp);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setColor(0xFF4FD8FF);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * dp);
    }

    private float sx(float x, float y) {
        return originX + (x - y) * scale * 0.866f;
    }

    private float sy(float x, float y, float z) {
        return originY + (x + y) * scale * 0.5f - z * scale;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // fit the diamond (width 2*WORLD*0.866, height WORLD + tallest box) into the view
        float maxHeight = 4.5f;
        scale = Math.min(w / (2 * WORLD * 0.866f), h / (WORLD + maxHeight)) * 0.95f;
        originX = w / 2f;
        originY = (h - (WORLD + maxHeight) * scale) / 2f + maxHeight * scale;
        build();
    }

    private void build() {
        Random random = new Random(3);
        edgeLines.clear();
        pointClouds.clear();
        colors.clear();

        for (float[] b : BOXES) {
            float x = b[0], y = b[1], wd = b[2], dd = b[3], ht = b[4];
            float hue = 300f * (x + y) / (2 * WORLD); // rainbow across the map
            colors.add(Color.HSVToColor(230, new float[] {hue, 0.85f, 1f}));

            float[][] corners = {
                    {x, y}, {x + wd, y}, {x + wd, y + dd}, {x, y + dd},
            };
            List<Float> lines = new ArrayList<>();
            List<Float> points = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                float[] a = corners[i];
                float[] c = corners[(i + 1) % 4];
                addEdge(lines, points, random, a[0], a[1], 0, c[0], c[1], 0);      // floor
                addEdge(lines, points, random, a[0], a[1], ht, c[0], c[1], ht);    // roof
                addEdge(lines, points, random, a[0], a[1], 0, a[0], a[1], ht);     // pillar
            }
            // scattered returns on the faces
            for (int i = 0; i < 70; i++) {
                boolean alongX = random.nextBoolean();
                float px = alongX ? x + random.nextFloat() * wd : (random.nextBoolean() ? x : x + wd);
                float py = alongX ? (random.nextBoolean() ? y : y + dd) : y + random.nextFloat() * dd;
                float pz = random.nextFloat() * ht;
                points.add(sx(px, py));
                points.add(sy(px, py, pz));
            }
            edgeLines.add(toArray(lines));
            pointClouds.add(toArray(points));
        }

        // floor scan in three color bands
        int[] floorColors = {0xAA2BE0A8, 0xAA3FA0FF, 0xAA7BE05A};
        for (int c = 0; c < floorColors.length; c++) {
            List<Float> points = new ArrayList<>();
            for (int i = 0; i < 160; i++) {
                float px = random.nextFloat() * WORLD;
                float py = random.nextFloat() * WORLD;
                points.add(sx(px, py));
                points.add(sy(px, py, 0));
            }
            pointClouds.add(toArray(points));
            colors.add(floorColors[c]);
        }

        List<Float> grid = new ArrayList<>();
        for (int i = 0; i <= WORLD; i += 2) {
            addLine(grid, i, 0, i, WORLD);
            addLine(grid, 0, i, WORLD, i);
        }
        gridLines = toArray(grid);

        routePath.reset();
        for (int i = 0; i < ROUTE.length; i++) {
            float px = sx(ROUTE[i][0], ROUTE[i][1]);
            float py = sy(ROUTE[i][0], ROUTE[i][1], 0);
            if (i == 0) routePath.moveTo(px, py);
            else routePath.lineTo(px, py);
        }
        float[] end = ROUTE[ROUTE.length - 1];
        robotX = sx(end[0], end[1]);
        robotY = sy(end[0], end[1], 0);
    }

    private void addEdge(List<Float> lines, List<Float> points, Random random,
                         float x0, float y0, float z0, float x1, float y1, float z1) {
        lines.add(sx(x0, y0));
        lines.add(sy(x0, y0, z0));
        lines.add(sx(x1, y1));
        lines.add(sy(x1, y1, z1));
        for (int i = 0; i < 10; i++) {
            float t = random.nextFloat();
            float px = x0 + (x1 - x0) * t;
            float py = y0 + (y1 - y0) * t;
            float pz = z0 + (z1 - z0) * t;
            points.add(sx(px, py));
            points.add(sy(px, py, pz));
        }
    }

    private void addLine(List<Float> out, float x0, float y0, float x1, float y1) {
        out.add(sx(x0, y0));
        out.add(sy(x0, y0, 0));
        out.add(sx(x1, y1));
        out.add(sy(x1, y1, 0));
    }

    private static float[] toArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (gridLines == null) return;

        canvas.drawLines(gridLines, gridPaint);

        int boxes = mapVisible ? BOXES.length : 0;
        for (int i = BOXES.length; mapVisible && i < pointClouds.size(); i++) { // floor first
            pointPaint.setColor(colors.get(i));
            canvas.drawPoints(pointClouds.get(i), pointPaint);
        }

        if (showRoute) {
            routePaint.setAlpha(255);
            canvas.drawPath(routePath, routePaint);
        }

        for (int i = 0; i < boxes; i++) {
            int color = colors.get(i);
            // gentle shimmer as if the scan is refreshing
            int alpha = (int) (150 + 90 * Math.abs(Math.sin((phase + i * 0.13f) * Math.PI)));
            edgePaint.setColor(color);
            edgePaint.setAlpha(alpha);
            canvas.drawLines(edgeLines.get(i), edgePaint);
            pointPaint.setColor(color);
            canvas.drawPoints(pointClouds.get(i), pointPaint);
        }

        // pulsing scan rings (flattened to match the isometric floor)
        for (int r = 0; r < 3; r++) {
            float p = (phase + r / 3f) % 1f;
            float radius = (6 + p * 40) * dp;
            ringPaint.setColor(0x4FD8FF);
            ringPaint.setAlpha((int) (220 * (1 - p)));
            canvas.save();
            canvas.scale(1f, 0.5f, robotX, robotY);
            canvas.drawCircle(robotX, robotY, radius, ringPaint);
            canvas.restore();
        }

        // robot marker
        fillPaint.setColor(0xFF4FD8FF);
        canvas.drawCircle(robotX, robotY - 6 * dp, 6 * dp, fillPaint);
        fillPaint.setColor(0xFFE8EEF8);
        canvas.drawRect(robotX - 4 * dp, robotY - 16 * dp, robotX + 4 * dp, robotY - 9 * dp, fillPaint);
    }

    /** Pauses/resumes the scan animation (rings and shimmer). */
    public void setScanning(boolean scanning) {
        this.scanning = scanning;
        if (animator == null) return;
        if (scanning && !animator.isStarted()) animator.start();
        else if (!scanning) animator.cancel();
    }

    /** Shows or hides the mapped buildings and point cloud (e.g. after "clear map"). */
    public void setMapVisible(boolean mapVisible) {
        this.mapVisible = mapVisible;
        invalidate();
    }

    public void setShowRoute(boolean showRoute) {
        this.showRoute = showRoute;
        invalidate();
    }

    /** Number of points currently drawn. */
    public int getPointCount() {
        if (!mapVisible) return 0;
        int count = 0;
        for (float[] cloud : pointClouds) count += cloud.length / 2;
        return count;
    }

    /** Mapped area in square world units (1 unit ≈ 1 m). */
    public float getMapArea() {
        return mapVisible ? WORLD * WORLD : 0f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        if (scanning) animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
