package com.falcon.robot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.falcon.robot.widget.DPadView;
import com.falcon.robot.widget.WaveformView;

public class MainActivity extends Activity {

    private TextView robotStatus;
    private TextView faceStatus;
    private View faceScanLine;
    private TextView voiceStatus;
    private TextView voiceHint;
    private WaveformView waveform;
    private ObjectAnimator micPulse;
    private ObjectAnimator scanAnimator;
    private boolean listening;
    private TextView[] navItems;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindFeatureCard(R.id.card_robot, R.drawable.bg_card_blue, R.drawable.accent_blue,
                R.drawable.ic_robot, R.drawable.bg_icon_circle_blue,
                R.string.robot_control, R.string.robot_control_sub,
                R.color.text_secondary, R.drawable.bg_circle_arrow_blue);
        bindFeatureCard(R.id.card_face, R.drawable.bg_card_teal, R.drawable.accent_teal,
                R.drawable.ic_person, R.drawable.face_frame,
                R.string.facial_recognition, R.string.facial_recognition_sub,
                R.color.teal, R.drawable.bg_circle_arrow_teal);
        bindFeatureCard(R.id.card_voice, R.drawable.bg_card_purple, R.drawable.accent_purple,
                R.drawable.ic_mic, R.drawable.bg_icon_circle_purple,
                R.string.voice_recognition, R.string.voice_recognition_sub,
                R.color.text_secondary, R.drawable.bg_circle_arrow_purple);

        setupRobotPanel();
        setupFacePanel();
        setupVoicePanel();
        setupBottomNav();

        findViewById(R.id.btn_menu).setOnClickListener(v -> toast(R.string.menu));
        findViewById(R.id.btn_settings).setOnClickListener(v -> toast(R.string.nav_settings));
    }

    private void bindFeatureCard(int cardId, int background, int accent, int icon, int iconBackground,
                                 int title, int subtitle, int subtitleColor, int arrowBackground) {
        View card = findViewById(cardId);
        card.setBackgroundResource(background);
        card.findViewById(R.id.feature_accent_top).setBackgroundResource(accent);
        card.findViewById(R.id.feature_accent_bottom).setBackgroundResource(accent);

        ImageView iconView = card.findViewById(R.id.feature_icon);
        iconView.setImageResource(icon);
        iconView.setBackgroundResource(iconBackground);
        int pad = Math.round(iconView.getLayoutParams().width * 0.22f);
        iconView.setPadding(pad, pad, pad, pad);

        ((TextView) card.findViewById(R.id.feature_title)).setText(title);
        TextView sub = card.findViewById(R.id.feature_subtitle);
        sub.setText(subtitle);
        sub.setTextColor(getResources().getColor(subtitleColor));
        card.findViewById(R.id.feature_arrow).setBackgroundResource(arrowBackground);
        card.setOnClickListener(v -> toast(title));
    }

    private void setupRobotPanel() {
        robotStatus = findViewById(R.id.robot_status);

        DPadView dpad = findViewById(R.id.dpad);
        dpad.setOnDirectionListener(direction -> {
            switch (direction) {
                case UP: robotStatus.setText(R.string.moving_forward); break;
                case DOWN: robotStatus.setText(R.string.moving_backward); break;
                case LEFT: robotStatus.setText(R.string.turning_left); break;
                case RIGHT: robotStatus.setText(R.string.turning_right); break;
                case CENTER: robotStatus.setText(R.string.stopped); break;
                default: robotStatus.setText(R.string.status_connected); break;
            }
        });

        View.OnClickListener pose = v -> {
            String name = ((TextView) v).getText().toString();
            robotStatus.setText(getString(R.string.status_with_pose, name));
        };
        findViewById(R.id.pose_stand).setOnClickListener(pose);
        findViewById(R.id.pose_sit).setOnClickListener(pose);
        findViewById(R.id.pose_wave).setOnClickListener(pose);

        final TextView speedValue = findViewById(R.id.speed_value);
        SeekBar speed = findViewById(R.id.speed_seek);
        speedValue.setText(getString(R.string.percent, speed.getProgress()));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(getString(R.string.percent, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void setupFacePanel() {
        faceStatus = findViewById(R.id.face_status);
        faceScanLine = findViewById(R.id.face_scan_line);
        final View preview = findViewById(R.id.face_preview);

        findViewById(R.id.btn_scan_face).setOnClickListener(v -> {
            if (scanAnimator != null && scanAnimator.isRunning()) return;
            faceStatus.setText(R.string.status_scanning);
            faceScanLine.setVisibility(View.VISIBLE);
            float travel = preview.getHeight() - faceScanLine.getHeight();
            scanAnimator = ObjectAnimator.ofFloat(faceScanLine, View.TRANSLATION_Y, 0f, travel);
            scanAnimator.setDuration(900);
            scanAnimator.setRepeatMode(ValueAnimator.REVERSE);
            scanAnimator.setRepeatCount(3);
            scanAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    faceScanLine.setVisibility(View.INVISIBLE);
                    faceStatus.setText(R.string.status_ready);
                }
            });
            scanAnimator.start();
        });
    }

    private void setupVoicePanel() {
        voiceStatus = findViewById(R.id.voice_status);
        voiceHint = findViewById(R.id.voice_hint);
        waveform = findViewById(R.id.waveform);

        View pulse = findViewById(R.id.mic_pulse);
        micPulse = ObjectAnimator.ofPropertyValuesHolder(pulse,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.05f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.05f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f));
        micPulse.setDuration(1100);
        micPulse.setRepeatMode(ValueAnimator.REVERSE);
        micPulse.setRepeatCount(ValueAnimator.INFINITE);

        findViewById(R.id.btn_mic).setOnClickListener(v -> setListening(!listening));
        setListening(true);
    }

    private void setListening(boolean listening) {
        this.listening = listening;
        waveform.setActive(listening);
        voiceStatus.setText(listening ? R.string.status_listening : R.string.status_idle);
        voiceStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
                listening ? R.drawable.dot_purple : R.drawable.dot_gray, 0, 0, 0);
        voiceHint.setText(listening ? R.string.tap_to_stop : R.string.tap_to_start);
        if (listening) {
            micPulse.start();
        } else {
            micPulse.cancel();
            View pulse = findViewById(R.id.mic_pulse);
            pulse.setScaleX(1f);
            pulse.setScaleY(1f);
            pulse.setAlpha(0.5f);
        }
    }

    private void setupBottomNav() {
        navItems = new TextView[] {
                findViewById(R.id.nav_home),
                findViewById(R.id.nav_log),
                findViewById(R.id.nav_settings),
        };
        for (TextView item : navItems) {
            item.setOnClickListener(v -> selectNav((TextView) v));
        }
        selectNav(navItems[0]);
    }

    private void selectNav(TextView selected) {
        for (TextView item : navItems) {
            boolean isSelected = item == selected;
            item.setSelected(isSelected);
            int color = getResources().getColor(isSelected ? R.color.blue : R.color.text_secondary);
            Drawable icon = item.getCompoundDrawables()[1];
            if (icon != null) icon.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    private void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (micPulse != null) micPulse.cancel();
        if (scanAnimator != null) scanAnimator.cancel();
        super.onDestroy();
    }
}
