package com.falcon.robot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Splash screen built on the designer's full-resolution artwork
 * ({@code res/drawable-nodpi/splash_background.png}, 1900x1200, from
 * {@code design/splash_screen_1900x1200.psd}). The baked-in loading bar and version text were
 * removed from the artwork; live versions are drawn on top at the same artwork coordinates.
 */
public class SplashActivity extends BaseActivity {

    private static final long LOADING_DELAY_MS = 500;
    private static final long LOADING_DURATION_MS = 3200;

    // Artwork size and positions (pixels in the 1900x1200 design).
    private static final float ART_W = 1900f;
    private static final float ART_H = 1200f;
    /** Region holding the logo, title, icons, loader and robot; kept on screen on any aspect ratio. */
    private static final float BAND_LEFT = 90f;
    private static final float BAND_TOP = 140f;
    private static final float BAND_RIGHT = 1660f;
    private static final float BAND_BOTTOM = 1010f;

    // Text positions are baselines; sizes are in artwork pixels.
    private static final float STATUS_X = 124f;
    private static final float STATUS_BASELINE = 832f;
    private static final float STATUS_TEXT = 36f;
    private static final float BAR_X = 124f;
    private static final float BAR_TOP = 860f;
    private static final float BAR_WIDTH = 694f;
    private static final float BAR_HEIGHT = 16f;
    private static final float PERCENT_X = 888f;
    private static final float PERCENT_BASELINE = 877f;
    private static final float PERCENT_TEXT = 27f;
    private static final float VERSION_X = 40f;
    private static final float VERSION_BASELINE = 1180f;
    private static final float VERSION_TEXT = 22f;

    private static final int[] STEPS = {
            R.string.splash_step_init,
            R.string.splash_step_models,
            R.string.splash_step_sensors,
            R.string.splash_step_services,
    };

    private ImageView image;
    private TextView status;
    private TextView percent;
    private ProgressBar progress;
    private TextView version;
    private ValueAnimator loading;
    private ValueAnimator reveal;
    private int step = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        image = findViewById(R.id.splash_image);
        status = findViewById(R.id.splash_status);
        percent = findViewById(R.id.splash_percent);
        progress = findViewById(R.id.splash_progress);
        version = findViewById(R.id.splash_version);

        version.setText(getString(R.string.splash_version, versionName()));
        percent.setText(getString(R.string.percent, 0));

        final View root = findViewById(R.id.splash_root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) layoutOverlay(r - l, b - t, 1f);
        });

        playReveal();
        startLoading();
    }

    /**
     * Scales the artwork to cover the screen (like centerCrop) while keeping the content band
     * visible, then positions the loader and version label at their artwork coordinates.
     * {@code zoom} slightly enlarges the artwork around its center for the reveal animation.
     */
    private void layoutOverlay(int viewW, int viewH, float zoom) {
        if (viewW == 0 || viewH == 0) return;

        float scale = Math.max(viewW / ART_W, viewH / ART_H);
        // Too narrow or too short to show the content band when covering: fit the band instead.
        scale = Math.min(scale, viewW / (BAND_RIGHT - BAND_LEFT));
        scale = Math.min(scale, viewH / (BAND_BOTTOM - BAND_TOP));

        float offsetX = place(viewW, ART_W * scale, (BAND_LEFT + BAND_RIGHT) / 2f * scale);
        float offsetY = place(viewH, ART_H * scale, (BAND_TOP + BAND_BOTTOM) / 2f * scale);

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(offsetX, offsetY);
        matrix.postScale(zoom, zoom, viewW / 2f, viewH / 2f);
        image.setImageMatrix(matrix);

        if (zoom != 1f) return; // overlay only needs positioning once the artwork is settled

        FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) progress.getLayoutParams();
        barLp.width = Math.round(BAR_WIDTH * scale);
        barLp.height = Math.max(4, Math.round(BAR_HEIGHT * scale));
        progress.setLayoutParams(barLp);
        progress.setX(offsetX + BAR_X * scale);
        progress.setY(offsetY + BAR_TOP * scale);

        placeText(status, offsetX + STATUS_X * scale, offsetY + STATUS_BASELINE * scale, STATUS_TEXT * scale);
        placeText(percent, offsetX + PERCENT_X * scale, offsetY + PERCENT_BASELINE * scale, PERCENT_TEXT * scale);
        placeText(version, offsetX + VERSION_X * scale,
                Math.min(viewH - 8f, offsetY + VERSION_BASELINE * scale), VERSION_TEXT * scale);
    }

    /** Sizes a text view and positions it so its first baseline lands on {@code baselineY}. */
    private static void placeText(TextView view, float x, float baselineY, float textSizePx) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        Paint.FontMetrics metrics = view.getPaint().getFontMetrics();
        view.setX(x);
        view.setY(baselineY + metrics.ascent); // ascent is negative
    }

    /** Offset that centers the band, clamped so the artwork still covers the screen when it can. */
    private static float place(float view, float art, float bandCenter) {
        float offset = view / 2f - bandCenter;
        if (art >= view) return Math.max(view - art, Math.min(0f, offset));
        return (view - art) / 2f;
    }

    /** Artwork fades in while settling from a slight zoom; the loader follows. */
    private void playReveal() {
        image.setAlpha(0f);
        for (View v : new View[] {status, progress, percent, version}) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setStartDelay(LOADING_DELAY_MS).setDuration(500).start();
        }

        reveal = ValueAnimator.ofFloat(0f, 1f);
        reveal.setDuration(1100);
        reveal.setInterpolator(new DecelerateInterpolator());
        reveal.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            image.setAlpha(f);
            View root = findViewById(R.id.splash_root);
            layoutOverlay(root.getWidth(), root.getHeight(), 1.05f - 0.05f * f);
        });
        reveal.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                View root = findViewById(R.id.splash_root);
                layoutOverlay(root.getWidth(), root.getHeight(), 1f);
            }
        });
        reveal.start();
    }

    private void startLoading() {
        loading = ValueAnimator.ofInt(0, 100);
        loading.setStartDelay(LOADING_DELAY_MS);
        loading.setDuration(LOADING_DURATION_MS);
        loading.setInterpolator(new LinearInterpolator());
        loading.addUpdateListener(animation -> {
            int value = (Integer) animation.getAnimatedValue();
            progress.setProgress(value);
            percent.setText(getString(R.string.percent, value));
            int next = Math.min(STEPS.length - 1, value * STEPS.length / 100);
            if (next != step) {
                step = next;
                status.setText(STEPS[step]);
            }
        });
        loading.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || isFinishing()) return;
                status.setText(R.string.splash_step_ready);
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out);
                finish();
            }
        });
        loading.start();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    @Override
    protected void onDestroy() {
        if (reveal != null) reveal.cancel();
        if (loading != null) loading.cancel();
        super.onDestroy();
    }
}
