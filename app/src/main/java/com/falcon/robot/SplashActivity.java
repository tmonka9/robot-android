package com.falcon.robot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ProgressBar;

public class SplashActivity extends Activity {

    private static final long LOADING_DURATION_MS = 2200;

    private ValueAnimator loader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View content = findViewById(R.id.splash_content);
        content.setAlpha(0f);
        content.setTranslationY(40f);
        content.animate().alpha(1f).translationY(0f).setDuration(700).start();

        final ProgressBar progress = findViewById(R.id.splash_progress);
        loader = ValueAnimator.ofInt(0, 100);
        loader.setDuration(LOADING_DURATION_MS);
        loader.setInterpolator(new AccelerateDecelerateInterpolator());
        loader.addUpdateListener(animation -> progress.setProgress((Integer) animation.getAnimatedValue()));
        loader.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || isFinishing()) return;
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        });
        loader.start();
    }

    @Override
    protected void onDestroy() {
        if (loader != null) loader.cancel();
        super.onDestroy();
    }
}
