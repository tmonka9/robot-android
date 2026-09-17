package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Static space backdrop for the splash screen: deep-blue gradient, stars, a glowing planet
 * with city lights in the bottom-left, a light band behind the cards and perspective
 * light streaks in the bottom-right.
 */
public class SplashBackgroundView extends View {

    private static final int STAR_COUNT = 140;
    private static final int CITY_LIGHT_COUNT = 220;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    private Shader base;
    private Shader topGlow;
    private Shader band;
    private Shader planet;
    private float[] stars;       // x, y, radius, alpha
    private float[] cityLights;  // x, y, radius, color-index
    private float planetX;
    private float planetY;
    private float planetR;

    public SplashBackgroundView(Context context) {
        this(context, null);
    }

    public SplashBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        Random random = new Random(7); // fixed seed: same backdrop every launch

        base = new LinearGradient(0, 0, 0, h,
                new int[] {0xFF020817, 0xFF061636, 0xFF030A1C, 0xFF01040C},
                new float[] {0f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP);
        topGlow = new RadialGradient(w * 0.55f, h * 0.28f, Math.max(w, h) * 0.45f,
                0x552F80FF, 0x002F80FF, Shader.TileMode.CLAMP);
        band = new LinearGradient(0, h * 0.62f, 0, h * 0.86f,
                new int[] {0x002F80FF, 0x442F80FF, 0x002F80FF}, null, Shader.TileMode.CLAMP);

        planetR = Math.max(w, h) * 0.55f;
        planetX = w * 0.12f;
        planetY = h + planetR * 0.62f;
        planet = new RadialGradient(planetX, planetY - planetR * 0.3f, planetR,
                0xFF0A1E44, 0xFF020714, Shader.TileMode.CLAMP);

        stars = new float[STAR_COUNT * 4];
        for (int i = 0; i < STAR_COUNT; i++) {
            stars[i * 4] = random.nextFloat() * w;
            stars[i * 4 + 1] = random.nextFloat() * h * 0.7f;
            stars[i * 4 + 2] = (0.4f + random.nextFloat()) * dp;
            stars[i * 4 + 3] = 40 + random.nextInt(140);
        }

        cityLights = new float[CITY_LIGHT_COUNT * 4];
        for (int i = 0; i < CITY_LIGHT_COUNT; i++) {
            // clustered near the lit top edge of the planet
            double angle = Math.toRadians(-100 + random.nextFloat() * 75);
            float r = planetR * (0.8f + 0.19f * (float) Math.sqrt(random.nextFloat()));
            cityLights[i * 4] = planetX + (float) Math.cos(angle) * r;
            cityLights[i * 4 + 1] = planetY + (float) Math.sin(angle) * r;
            cityLights[i * 4 + 2] = (0.6f + random.nextFloat() * 1.4f) * dp;
            cityLights[i * 4 + 3] = random.nextInt(5); // 0..3 amber, 4 blue
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (base == null) return;

        paint.setShader(base);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(topGlow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(band);
        canvas.drawRect(0, h * 0.62f, w, h * 0.86f, paint);
        paint.setShader(null);

        paint.setColor(0xFFFFFFFF);
        for (int i = 0; i < STAR_COUNT; i++) {
            paint.setAlpha((int) stars[i * 4 + 3]);
            canvas.drawCircle(stars[i * 4], stars[i * 4 + 1], stars[i * 4 + 2], paint);
        }
        paint.setAlpha(255);

        drawStreaks(canvas, w, h);
        drawPlanet(canvas);
    }

    /** Light rays converging toward a vanishing point, bottom-right. */
    private void drawStreaks(Canvas canvas, float w, float h) {
        float vx = w * 0.6f;
        float vy = h * 0.66f;
        for (int i = 0; i < 9; i++) {
            float ex = w * (0.55f + i * 0.11f);
            float startT = 0.45f;
            float sx = vx + (ex - vx) * startT;
            float sy = vy + (h - vy) * startT;
            boolean bright = i % 3 == 1;
            stroke.setStrokeWidth((bright ? 3f : 1.5f) * dp);
            stroke.setShader(new LinearGradient(sx, sy, ex, h,
                    0x002F80FF, bright ? 0xCC4FA8FF : 0x552F80FF, Shader.TileMode.CLAMP));
            canvas.drawLine(sx, sy, ex, h, stroke);
        }
        stroke.setShader(null);
    }

    /** Dark planet with a glowing atmosphere rim and city lights. */
    private void drawPlanet(Canvas canvas) {
        paint.setShader(planet);
        canvas.drawCircle(planetX, planetY, planetR, paint);
        paint.setShader(null);

        // soft rim glow: several strokes, widest and faintest first
        int[] alphas = {20, 40, 80, 200};
        float[] widths = {22f, 12f, 6f, 2f};
        for (int i = 0; i < alphas.length; i++) {
            stroke.setStrokeWidth(widths[i] * dp);
            stroke.setColor((alphas[i] << 24) | 0x3FA0FF);
            canvas.drawCircle(planetX, planetY, planetR, stroke);
        }

        for (int i = 0; i < CITY_LIGHT_COUNT; i++) {
            boolean blue = cityLights[i * 4 + 3] >= 4;
            paint.setColor(blue ? 0xCC6FB5FF : 0xDDFFC266);
            canvas.drawCircle(cityLights[i * 4], cityLights[i * 4 + 1], cityLights[i * 4 + 2], paint);
        }

        // a few network arcs between lights
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(0x553FA0FF);
        for (int i = 0; i + 40 < CITY_LIGHT_COUNT; i += 37) {
            float x0 = cityLights[i * 4];
            float y0 = cityLights[i * 4 + 1];
            float x1 = cityLights[(i + 40) * 4];
            float y1 = cityLights[(i + 40) * 4 + 1];
            path.reset();
            path.moveTo(x0, y0);
            path.quadTo((x0 + x1) / 2f, Math.min(y0, y1) - 40 * dp, x1, y1);
            canvas.drawPath(path, stroke);
        }
    }
}
