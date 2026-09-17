package com.falcon.robot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SplashActivity extends BaseActivity {

    private static final long LOADING_DELAY_MS = 400;
    private static final long LOADING_DURATION_MS = 3000;

    /** {title, description, icon, background, icon tint (0 = keep original colors)} */
    private static final int[][] CARDS = {
            {R.string.nav_robot, R.string.card_robot_desc, R.drawable.ic_robot,
                    R.drawable.bg_home_card_blue, 0xFF8FD0FF},
            {R.string.nav_face, R.string.card_face_desc, R.drawable.ic_face_id,
                    R.drawable.bg_home_card_purple, 0xFFF2C6FF},
            {R.string.nav_voice, R.string.card_voice_desc, R.drawable.ic_mic,
                    R.drawable.bg_home_card_green, 0xFF7CF5CF},
            {R.string.nav_object, R.string.card_object_desc, R.drawable.ic_cube_scan,
                    R.drawable.bg_home_card_orange, 0},
            {R.string.nav_lidar, R.string.card_lidar_desc, R.drawable.ic_lidar,
                    R.drawable.bg_home_card_indigo, 0xFF8FB8FF},
            {R.string.nav_remote, R.string.card_remote_desc, R.drawable.ic_gamepad,
                    R.drawable.bg_home_card_navy, 0xFFFFFFFF},
    };

    private ValueAnimator loader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        boolean wide = getResources().getBoolean(R.bool.two_columns);
        findViewById(R.id.splash_lidar).setVisibility(wide ? View.VISIBLE : View.GONE);

        applyTitleGradients();
        buildCards();
        playEntrance();
        startLoading();
    }

    /** Glossy blue "AI" and silver "ROBOT CONTROL", as in the design. */
    private void applyTitleGradients() {
        final TextView ai = findViewById(R.id.splash_ai);
        final TextView title = findViewById(R.id.splash_title);
        ai.post(() -> {
            ai.getPaint().setShader(new LinearGradient(0, 0, 0, ai.getHeight(),
                    new int[] {0xFFB5ECFF, 0xFF3A8CFF, 0xFF6A4BFF}, null, Shader.TileMode.CLAMP));
            title.getPaint().setShader(new LinearGradient(0, 0, 0, title.getHeight(),
                    new int[] {0xFFFFFFFF, 0xFFC9D3E3, 0xFF7E8CA3}, null, Shader.TileMode.CLAMP));
            ai.invalidate();
            title.invalidate();
        });
    }

    /** One row of six cards on landscape screens, otherwise two rows of three. */
    private void buildCards() {
        LinearLayout container = findViewById(R.id.splash_cards);
        Configuration config = getResources().getConfiguration();
        int columns = config.orientation == Configuration.ORIENTATION_LANDSCAPE ? CARDS.length : 3;
        int gap = Math.round(12 * getResources().getDisplayMetrics().density);
        LayoutInflater inflater = LayoutInflater.from(this);

        LinearLayout row = null;
        for (int i = 0; i < CARDS.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = gap;
                container.addView(row, rowLp);
            }
            int[] card = CARDS[i];
            View view = inflater.inflate(R.layout.item_splash_card, row, false);
            view.setBackgroundResource(card[3]);
            ImageView icon = view.findViewById(R.id.card_icon);
            icon.setImageResource(card[2]);
            if (card[4] != 0) icon.setColorFilter(card[4], PorterDuff.Mode.SRC_IN);
            ((TextView) view.findViewById(R.id.card_title)).setText(card[0]);
            ((TextView) view.findViewById(R.id.card_desc)).setText(card[1]);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    view.getLayoutParams().height, 1f);
            if (i % columns > 0) lp.setMarginStart(gap);
            row.addView(view, lp);
        }
    }

    private void playEntrance() {
        float dp = getResources().getDisplayMetrics().density;
        slideIn(findViewById(R.id.splash_title_block), -40 * dp, 0, 0);
        slideIn(findViewById(R.id.splash_robot), 0, 30 * dp, 120);
        slideIn(findViewById(R.id.splash_lidar), 40 * dp, 0, 240);

        LinearLayout cards = findViewById(R.id.splash_cards);
        long delay = 300;
        for (int r = 0; r < cards.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) cards.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                slideIn(row.getChildAt(c), 0, 30 * dp, delay);
                delay += 70;
            }
        }
        slideIn(findViewById(R.id.splash_loader), 0, 0, delay);
    }

    private static void slideIn(View view, float fromX, float fromY, long delay) {
        view.setAlpha(0f);
        view.setTranslationX(fromX);
        view.setTranslationY(fromY);
        view.animate().alpha(1f).translationX(0f).translationY(0f)
                .setStartDelay(delay).setDuration(600)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void startLoading() {
        final ProgressBar progress = findViewById(R.id.splash_progress);
        loader = ValueAnimator.ofInt(0, 100);
        loader.setStartDelay(LOADING_DELAY_MS);
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
                overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out);
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
