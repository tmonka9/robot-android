package com.falcon.robot;

import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.WaveformView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Voice Recognition page (design/voice.png): listening visual, recognition result with command
 * action, voice profiles, voice control switches, command history and advanced ASR settings.
 *
 * <p>Speech recognition is simulated: while listening, a sample command is "heard" periodically.
 * Replace {@link #simulateRecognition} with results from the real recognizer. Playback of results
 * uses Android's built-in text-to-speech.
 */
public class VoiceRecognitionActivity extends BaseActivity {

    private static final long RECOGNITION_INTERVAL_MS = 7000;
    private static final int HISTORY_ROWS = 7;

    /** Artwork coordinates (voice_visual.png pixels) for the live overlays. */
    private static final RectF MIC_RECT = new RectF(205, 75, 405, 275);
    private static final RectF HINT_RECT = new RectF(180, 292, 430, 326);
    private static final RectF WAVE_RECT = new RectF(170, 342, 450, 400);

    /** Robot command per entry of R.array.voice_phrases (null = answered by voice, no robot action). */
    private static final String[] ROBOT_COMMANDS = {
            "GO_HOME", "MOVE FORWARD", "TURN LEFT", "STOP", null, "DOOR OPEN", "SLAM MAPPING START", "POSE WAVE",
    };
    private static final int[] ACTION_ICONS = {
            R.drawable.ic_home, R.drawable.ic_arrow_up, R.drawable.ic_arrow_left, R.drawable.ic_square,
            R.drawable.ic_history, R.drawable.ic_lock_open, R.drawable.ic_map, R.drawable.ic_pose_wave,
    };

    /** Advanced settings: per tab, rows of {label, options array}. */
    private static final int[][][] ADVANCED = {
            {
                    {R.string.adv_model_type, R.array.adv_model_type_options},
                    {R.string.adv_engine, R.array.adv_engine_options},
                    {R.string.adv_sample_rate, R.array.adv_sample_rate_options},
                    {R.string.adv_audio_input, R.array.adv_audio_input_options},
            },
            {
                    {R.string.adv_recognition_language, R.array.adv_language_options},
                    {R.string.adv_response_language, R.array.adv_language_options},
                    {R.string.adv_auto_detect, R.array.adv_on_off_options},
                    {R.string.adv_punctuation, R.array.adv_on_off_options},
            },
            {
                    {R.string.adv_hotword_engine, R.array.adv_hotword_engine_options},
                    {R.string.adv_sensitivity, R.array.adv_sensitivity_options},
                    {R.string.adv_timeout, R.array.adv_timeout_options},
                    {R.string.adv_confirm_sound, R.array.adv_confirm_sound_options},
            },
            {
                    {R.string.adv_noise_suppression, R.array.adv_noise_options},
                    {R.string.adv_echo_cancel, R.array.adv_on_off_options},
                    {R.string.adv_gain, R.array.adv_gain_options},
                    {R.string.adv_output_volume, R.array.adv_volume_options},
            },
    };

    private static final class Profile {
        final String name;
        final String id;
        final int photo;
        boolean active = true;

        Profile(String name, String id, int photo) {
            this.name = name;
            this.id = id;
            this.photo = photo;
        }
    }

    private static final class HistoryEntry {
        final long time;
        final String command;
        final int result; // string resource
        final float confidence;

        HistoryEntry(long time, String command, int result, float confidence) {
            this.time = time;
            this.command = command;
            this.result = result;
            this.confidence = confidence;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final List<Profile> profiles = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final int[][] advancedSelection = new int[ADVANCED.length][4];

    private String[] phrases;
    private String[] actions;
    private String[] descriptions;
    private int currentCommand;
    private int nextCommand = 1;
    private int nextVoiceId = 128;
    private int advancedTab;
    private boolean listening = true;

    private TextToSpeech tts;
    private boolean ttsReady;

    private CoverImageView visual;
    private View micHit;
    private TextView speakHint;
    private WaveformView liveWave;
    private TextView listenState;
    private TextView micDevice;
    private LinearLayout profileList;
    private EditText profileSearch;
    private LinearLayout historyList;
    private LinearLayout advancedRows;
    private TextView[] advancedTabs;
    private Switch commandSwitch;
    private Switch wakeSwitch;
    private Switch continuousSwitch;
    private Switch noiseSwitch;
    private TextView wakeWord;
    private TextView wakeTitle;

    private final Runnable recognitionLoop = new Runnable() {
        @Override
        public void run() {
            if (listening) simulateRecognition();
            handler.postDelayed(this, RECOGNITION_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_voice_recognition, R.id.nav_voice, 0);
        setupRichHeader(R.drawable.ic_mic, R.string.nav_voice, R.string.voice_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        phrases = getResources().getStringArray(R.array.voice_phrases);
        actions = getResources().getStringArray(R.array.voice_actions);
        descriptions = getResources().getStringArray(R.array.voice_action_descriptions);

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(Locale.US);
        });

        setupListening();
        setupResult();
        setupProfiles();
        setupVoiceControl();
        setupHistory();
        setupAdvanced();

        showRecognized(0, 98.6f, System.currentTimeMillis());
        setListening(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRichHeader();
        handler.removeCallbacks(recognitionLoop);
        handler.postDelayed(recognitionLoop, RECOGNITION_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(recognitionLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onConnectionChanged() {
        refreshRichHeader();
    }

    // ---- listening visual ------------------------------------------------------------------

    private void setupListening() {
        visual = findViewById(R.id.voice_visual);
        visual.setFocus(0.2f, 0f, 0.8f, 1f); // keep the microphone and its waveforms centred
        findViewById(R.id.listen_panel).setClipToOutline(true);
        micHit = findViewById(R.id.mic_hit);
        speakHint = findViewById(R.id.speak_hint);
        liveWave = findViewById(R.id.live_wave);
        liveWave.setBarColor(0x22D3EE);
        listenState = findViewById(R.id.listen_state);
        micDevice = findViewById(R.id.mic_device);
        micDevice.setText(getResources().getStringArray(R.array.microphones)[0]);
        ((ImageView) findViewById(R.id.mic_selector_icon)).setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);

        micHit.setOnClickListener(v -> setListening(!listening));
        findViewById(R.id.mic_selector).setOnClickListener(this::showMicrophoneMenu);
        visual.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeListeningOverlays));
    }

    private void placeListeningOverlays() {
        RectF rect = new RectF();
        if (!visual.mapImageRect(MIC_RECT, rect)) return;
        place(micHit, rect);
        visual.mapImageRect(HINT_RECT, rect);
        place(speakHint, rect);
        speakHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, rect.height() * 0.62f);
        visual.mapImageRect(WAVE_RECT, rect);
        place(liveWave, rect);
    }

    private static void place(View view, RectF rect) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = Math.round(rect.width());
        lp.height = Math.round(rect.height());
        view.setLayoutParams(lp);
        view.setX(rect.left);
        view.setY(rect.top);
    }

    private void setListening(boolean on) {
        listening = on;
        listenState.setText(on ? R.string.status_listening : R.string.status_paused);
        listenState.setTextColor(color(on ? R.color.teal : R.color.text_secondary));
        findViewById(R.id.listen_dot).setBackgroundResource(on ? R.drawable.dot_teal : R.drawable.dot_gray);
        speakHint.setText(on ? R.string.speak_now : R.string.tap_mic_to_listen);
        liveWave.setActive(on);
        visual.animate().alpha(on ? 1f : 0.55f).setDuration(250).start();
        RobotSession.get().send("VOICE LISTEN " + (on ? "ON" : "OFF"));
    }

    private void showMicrophoneMenu(View anchor) {
        final String[] devices = getResources().getStringArray(R.array.microphones);
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < devices.length; i++) menu.getMenu().add(0, i, i, devices[i]);
        menu.setOnMenuItemClickListener(item -> {
            micDevice.setText(devices[item.getItemId()]);
            RobotSession.get().send("VOICE MIC " + devices[item.getItemId()]);
            return true;
        });
        menu.show();
    }

    // ---- recognition -----------------------------------------------------------------------

    /** Simulated recognizer: "hears" the next sample command. */
    private void simulateRecognition() {
        if (!commandSwitch.isChecked()) return;
        int index = nextCommand++ % phrases.length;
        float confidence = 90f + random.nextFloat() * 9.5f;
        long now = System.currentTimeMillis();
        showRecognized(index, confidence, now);
        history.add(new HistoryEntry(now, phrases[index], runCommand(index), confidence));
        renderHistory();
        if (!continuousSwitch.isChecked()) setListening(false); // single-shot listening
    }

    /** Executes a recognized command and returns the history result string. */
    private int runCommand(int index) {
        String command = ROBOT_COMMANDS[index];
        if (command == null) {
            speak(getString(R.string.time_answer, new SimpleDateFormat("h:mm a", Locale.US).format(new Date())));
            return R.string.result_answered;
        }
        return RobotSession.get().send("VOICE_COMMAND " + command) ? R.string.result_executed : R.string.result_not_sent;
    }

    private void showRecognized(int index, float confidence, long time) {
        currentCommand = index;
        ((TextView) findViewById(R.id.result_quote)).setText(getString(R.string.quoted, phrases[index]));
        ((TextView) findViewById(R.id.result_text)).setText(phrases[index]);
        ((TextView) findViewById(R.id.result_confidence)).setText(getString(R.string.similarity_value, confidence));
        ((ProgressBar) findViewById(R.id.result_confidence_bar)).setProgress(Math.round(confidence * 10));
        ((TextView) findViewById(R.id.result_language)).setText(
                getResources().getStringArray(R.array.adv_language_options)[advancedSelection[1][0]]);
        ((TextView) findViewById(R.id.result_time)).setText(dateTime.format(new Date(time)));

        SpannableStringBuilder name = new SpannableStringBuilder(getString(R.string.execute_label));
        int start = name.length();
        name.append(actions[index]);
        name.setSpan(new ForegroundColorSpan(color(R.color.teal)), start, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView) findViewById(R.id.action_name)).setText(name);
        ((TextView) findViewById(R.id.action_desc)).setText(descriptions[index]);
        ImageView icon = findViewById(R.id.action_icon);
        icon.setImageResource(ACTION_ICONS[index]);
        icon.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
    }

    private void speak(String text) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-result");
    }

    // ---- result panel ----------------------------------------------------------------------

    private void setupResult() {
        setIcon(findViewById(R.id.result_title), R.drawable.ic_check_circle, 24, color(R.color.teal), Gravity.START);
        setIcon(findViewById(R.id.action_title), R.drawable.ic_robot, 24, color(R.color.cyan), Gravity.START);
        int white = color(R.color.text_primary);
        ((ImageView) findViewById(R.id.btn_speak)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        ((ImageView) findViewById(R.id.btn_execute)).setColorFilter(white, PorterDuff.Mode.SRC_IN);

        findViewById(R.id.btn_speak).setOnClickListener(v -> speak(phrases[currentCommand]));
        findViewById(R.id.btn_execute).setOnClickListener(v -> {
            String command = ROBOT_COMMANDS[currentCommand];
            if (command == null) {
                runCommand(currentCommand);
                return;
            }
            if (sendCommand("VOICE_COMMAND " + command)) {
                toast(getString(R.string.sent_command, actions[currentCommand]));
                history.add(new HistoryEntry(System.currentTimeMillis(), phrases[currentCommand],
                        R.string.result_executed, -1f));
                renderHistory();
            }
        });
    }

    // ---- voice profiles --------------------------------------------------------------------

    private void setupProfiles() {
        setIcon(findViewById(R.id.voice_db_title), R.drawable.ic_mic, 22, color(R.color.cyan), Gravity.START);
        TextView add = findViewById(R.id.btn_add_voice);
        setIcon(add, R.drawable.ic_add, 16, color(R.color.text_primary), Gravity.START);
        add.setOnClickListener(v -> showAddVoiceDialog());

        profiles.add(new Profile("Emma Wilson", "00123", R.drawable.face_portrait_emma));
        profiles.add(new Profile("James Miller", "00124", R.drawable.face_avatar_james));
        profiles.add(new Profile("Sophia Davis", "00125", R.drawable.face_avatar_sophia));
        profiles.add(new Profile("Daniel Brown", "00126", R.drawable.face_avatar_daniel));
        profiles.add(new Profile("Olivia Taylor", "00127", R.drawable.face_avatar_olivia));

        profileList = findViewById(R.id.voice_db_list);
        profileSearch = findViewById(R.id.voice_search);
        setIcon(profileSearch, R.drawable.ic_search, 18, color(R.color.text_secondary), Gravity.START);
        profileSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderProfiles();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        renderProfiles();
    }

    private void renderProfiles() {
        profileList.removeAllViews();
        String query = profileSearch.getText().toString().trim().toLowerCase(Locale.US);
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = Math.round(6 * getResources().getDisplayMetrics().density);
        for (final Profile profile : profiles) {
            if (!query.isEmpty() && !profile.name.toLowerCase(Locale.US).contains(query) && !profile.id.contains(query)) {
                continue;
            }
            // reuses the Face Database row: name / ID / active chip
            View row = inflater.inflate(R.layout.item_face_db_row, profileList, false);
            bindPhoto(row.findViewById(R.id.db_photo), profile.photo);
            ((TextView) row.findViewById(R.id.db_name)).setText(profile.name);
            row.findViewById(R.id.db_id).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.db_department)).setText(getString(R.string.id_value, profile.id));
            TextView active = row.findViewById(R.id.db_active);
            active.setText(profile.active ? R.string.active : R.string.inactive);
            active.setTextColor(color(profile.active ? R.color.teal : R.color.text_muted));
            active.setBackgroundResource(profile.active ? R.drawable.bg_chip_green : R.drawable.bg_table_box);
            row.setOnClickListener(v -> showProfileMenu(v, profile));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (profileList.getChildCount() > 0) lp.topMargin = gap;
            profileList.addView(row, lp);
        }
    }

    private void bindPhoto(ImageView view, int photo) {
        view.setClipToOutline(true);
        if (photo != 0) {
            view.setImageResource(photo);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.clearColorFilter();
        } else {
            view.setImageResource(R.drawable.ic_mic);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            view.setColorFilter(color(R.color.blue_light), PorterDuff.Mode.SRC_IN);
        }
    }

    private void showProfileMenu(View anchor, final Profile profile) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.toggle_active);
        menu.getMenu().add(0, 2, 1, R.string.remove_from_database);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                profile.active = !profile.active;
            } else {
                profiles.remove(profile);
                toast(getString(R.string.voice_profile_removed, profile.name));
            }
            renderProfiles();
            return true;
        });
        menu.show();
    }

    private void showAddVoiceDialog() {
        final EditText input = textInput(null);
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.add_voice)
                .setView(wrapInput(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError(getString(R.string.enter_name));
                return;
            }
            Profile profile = new Profile(name, String.format(Locale.US, "%05d", nextVoiceId++), 0);
            profiles.add(profile);
            RobotSession.get().send("VOICE ENROLL " + profile.id);
            toast(getString(R.string.voice_profile_added, name));
            renderProfiles();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private EditText textInput(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(color(R.color.text_primary));
        input.setBackgroundResource(R.drawable.bg_input);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        if (value != null) {
            input.setText(value);
            input.setSelection(value.length());
        }
        return input;
    }

    private View wrapInput(EditText input) {
        FrameLayout frame = new FrameLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);
        return frame;
    }

    // ---- voice control ---------------------------------------------------------------------

    private void setupVoiceControl() {
        setIcon(findViewById(R.id.control_title), R.drawable.ic_settings, 22, color(R.color.text_primary), Gravity.START);
        LinearLayout toggles = findViewById(R.id.voice_toggles);
        commandSwitch = addToggle(toggles, R.drawable.ic_mic, R.string.vc_command, R.string.vc_command_sub, true, "VOICE COMMANDS");
        wakeSwitch = addToggle(toggles, R.drawable.ic_bolt, R.string.vc_wake, R.string.vc_wake_sub, true, "VOICE WAKE_WORD");
        wakeTitle = (TextView) ((View) wakeSwitch.getParent()).findViewById(R.id.toggle_title);
        continuousSwitch = addToggle(toggles, R.drawable.ic_refresh, R.string.vc_continuous, R.string.vc_continuous_sub, false, "VOICE CONTINUOUS");
        noiseSwitch = addToggle(toggles, R.drawable.ic_tune, R.string.vc_noise, R.string.vc_noise_sub, true, "VOICE NOISE_REDUCTION");

        wakeWord = findViewById(R.id.wake_word);
        setWakeWord("Hey Robot");
        ((ImageView) findViewById(R.id.btn_edit_wake)).setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        findViewById(R.id.btn_edit_wake).setOnClickListener(v -> {
            final EditText input = textInput(wakeWord.getText().toString());
            new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                    .setTitle(R.string.wake_word)
                    .setView(wrapInput(input))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String word = input.getText().toString().trim();
                        if (word.isEmpty()) return;
                        setWakeWord(word);
                        RobotSession.get().send("VOICE WAKE_WORD_TEXT " + word);
                    })
                    .show();
        });
    }

    private void setWakeWord(String word) {
        wakeWord.setText(word);
        wakeTitle.setText(getString(R.string.wake_word_title, word));
    }

    private Switch addToggle(LinearLayout parent, int icon, int title, int subtitle, boolean checked, final String command) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_voice_toggle_row, parent, false);
        ImageView iconView = row.findViewById(R.id.toggle_icon);
        iconView.setImageResource(icon);
        iconView.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        ((TextView) row.findViewById(R.id.toggle_title)).setText(title);
        ((TextView) row.findViewById(R.id.toggle_subtitle)).setText(subtitle);
        Switch toggle = row.findViewById(R.id.toggle_switch);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((b, on) -> RobotSession.get().send(command + " " + (on ? "ON" : "OFF")));
        parent.addView(row);
        return toggle;
    }

    // ---- history ---------------------------------------------------------------------------

    private void setupHistory() {
        setIcon(findViewById(R.id.history_title), R.drawable.ic_history, 22, color(R.color.text_primary), Gravity.START);
        historyList = findViewById(R.id.voice_history_list);
        findViewById(R.id.btn_clear_history).setOnClickListener(v -> {
            history.clear();
            renderHistory();
        });

        long now = System.currentTimeMillis();
        float[] confidences = {90.5f, 94.3f, 92.7f, 99.1f, 95.8f, 96.2f, 98.6f};
        int[] commandIndex = {6, 5, 4, 3, 2, 1, 0};
        long[] ago = {802_000, 609_000, 424_000, 221_000, 126_000, 33_000, 0};
        for (int i = 0; i < commandIndex.length; i++) {
            int index = commandIndex[i];
            int result = ROBOT_COMMANDS[index] == null ? R.string.result_answered : R.string.result_executed;
            history.add(new HistoryEntry(now - ago[i], phrases[index], result, confidences[i]));
        }
        renderHistory();
    }

    private void renderHistory() {
        historyList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = history.size() - 1; i >= 0 && historyList.getChildCount() < HISTORY_ROWS; i--) {
            HistoryEntry entry = history.get(i);
            View row = inflater.inflate(R.layout.item_voice_history_row, historyList, false);
            ((TextView) row.findViewById(R.id.vh_time)).setText(dateTime.format(new Date(entry.time)));
            ((TextView) row.findViewById(R.id.vh_command)).setText(entry.command);
            TextView result = row.findViewById(R.id.vh_result);
            result.setText(entry.result);
            result.setTextColor(color(entry.result == R.string.result_executed ? R.color.teal
                    : entry.result == R.string.result_answered ? R.color.blue_light : R.color.amber));
            ((TextView) row.findViewById(R.id.vh_confidence)).setText(entry.confidence >= 0
                    ? getString(R.string.similarity_value, entry.confidence) : "-");
            historyList.addView(row);
        }
    }

    // ---- advanced settings -----------------------------------------------------------------

    private void setupAdvanced() {
        setIcon(findViewById(R.id.advanced_title), R.drawable.ic_tune, 22, color(R.color.text_primary), Gravity.START);
        advancedRows = findViewById(R.id.adv_rows);
        advancedTabs = new TextView[] {
                findViewById(R.id.adv_tab_model), findViewById(R.id.adv_tab_language),
                findViewById(R.id.adv_tab_hotword), findViewById(R.id.adv_tab_audio),
        };
        for (int i = 0; i < advancedTabs.length; i++) {
            final int tab = i;
            advancedTabs[i].setOnClickListener(v -> renderAdvancedTab(tab));
        }
        TextView apply = findViewById(R.id.btn_apply_settings);
        setIcon(apply, R.drawable.ic_check, 18, color(R.color.white), Gravity.START);
        apply.setOnClickListener(v -> {
            StringBuilder command = new StringBuilder("VOICE CONFIG");
            for (int t = 0; t < ADVANCED.length; t++) {
                for (int r = 0; r < ADVANCED[t].length; r++) {
                    command.append(' ').append(t).append('.').append(r).append('=').append(advancedSelection[t][r]);
                }
            }
            RobotSession.get().send(command.toString());
            ((TextView) findViewById(R.id.result_language)).setText(
                    getResources().getStringArray(R.array.adv_language_options)[advancedSelection[1][0]]);
            toast(R.string.settings_applied);
        });
        renderAdvancedTab(0);
    }

    private void renderAdvancedTab(final int tab) {
        advancedTab = tab;
        for (int i = 0; i < advancedTabs.length; i++) advancedTabs[i].setSelected(i == tab);
        advancedRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int r = 0; r < ADVANCED[tab].length; r++) {
            final int rowIndex = r;
            View row = inflater.inflate(R.layout.item_setting_dropdown_row, advancedRows, false);
            ((TextView) row.findViewById(R.id.setting_label)).setText(ADVANCED[tab][r][0]);
            Spinner spinner = row.findViewById(R.id.setting_spinner);
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, ADVANCED[tab][r][1], R.layout.item_spinner);
            adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            spinner.setAdapter(adapter);
            spinner.setSelection(advancedSelection[tab][r]);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (advancedTab == tab) advancedSelection[tab][rowIndex] = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            advancedRows.addView(row);
        }
    }
}
