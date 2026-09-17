package com.falcon.robot;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.falcon.robot.widget.WaveformView;

import java.util.Locale;

/** Voice recognition page. Speech results are simulated until a recognizer is wired up. */
public class VoiceRecognitionActivity extends BaseActivity {

    private static final String[] COMMANDS = {
            "Move forward", "Turn left", "Turn right", "Stop",
            "Stand up", "Sit down", "Wave hello", "Dance",
    };

    private final boolean[] options = {true, true}; // wake word, voice feedback

    private TextView voiceStatus;
    private TextView recognizedText;
    private TextView toggleButton;
    private WaveformView waveform;
    private WaveformView recognizedWave;
    private View pulseRing;
    private ObjectAnimator micPulse;
    private boolean listening;
    private int nextCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_voice_recognition, R.id.nav_voice, R.string.nav_voice);
        setupColumns();

        voiceStatus = findViewById(R.id.voice_status);
        recognizedText = findViewById(R.id.recognized_text);
        toggleButton = findViewById(R.id.btn_voice_toggle);
        waveform = findViewById(R.id.waveform);
        recognizedWave = findViewById(R.id.recognized_wave);
        pulseRing = findViewById(R.id.mic_pulse);
        micPulse = createPulse(pulseRing);

        View.OnClickListener toggle = v -> {
            if (listening) {
                // Stopping ends the utterance: pretend we recognized the next sample command.
                onRecognized(COMMANDS[nextCommand++ % COMMANDS.length]);
            }
            setListening(!listening);
        };
        findViewById(R.id.btn_mic).setOnClickListener(toggle);
        toggleButton.setOnClickListener(toggle);
        findViewById(R.id.btn_voice_settings).setOnClickListener(v -> showSettings());

        buildCommandGrid();
        recognizedWave.setActive(false);
        setListening(true);
    }

    private void setListening(boolean listening) {
        this.listening = listening;
        waveform.setActive(listening);
        voiceStatus.setText(listening ? R.string.status_listening : R.string.status_paused);
        toggleButton.setText(listening ? R.string.stop : R.string.start);
        toggleButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                listening ? R.drawable.ic_stop : R.drawable.ic_mic_small, 0, 0, 0);
        if (listening) {
            micPulse.start();
        } else {
            micPulse.cancel();
            resetPulse(pulseRing);
        }
    }

    private void onRecognized(String text) {
        recognizedText.setText(text);
        recognizedWave.setActive(true);
        recognizedWave.postDelayed(() -> recognizedWave.setActive(false), 1500);
        RobotSession.get().send("VOICE " + text.toUpperCase(Locale.US));
    }

    /** Two-column grid of tappable commands (tapping simulates saying the command). */
    private void buildCommandGrid() {
        LinearLayout grid = findViewById(R.id.command_grid);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        LinearLayout row = null;
        for (int i = 0; i < COMMANDS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = gap;
                grid.addView(row, rowLp);
            }
            final String command = COMMANDS[i];
            TextView chip = new TextView(this);
            chip.setText(command);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(color(R.color.text_primary));
            chip.setTextSize(13);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(gap, gap + gap / 2, gap, gap + gap / 2);
            chip.setOnClickListener(v -> onRecognized(command));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) lp.setMarginStart(gap);
            row.addView(chip, lp);
        }
    }

    private void showSettings() {
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.voice_settings)
                .setMultiChoiceItems(new CharSequence[] {
                        getString(R.string.opt_wake_word), getString(R.string.opt_voice_feedback),
                }, options, (dialog, which, checked) -> options[which] = checked)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        micPulse.cancel();
        super.onDestroy();
    }
}
