package com.falcon.robot;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.os.Bundle;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.falcon.robot.voice.CustomPhrases;
import com.falcon.robot.voice.SpeechRecorder;
import com.falcon.robot.voice.VoiceCommands;
import com.falcon.robot.voice.WhisperEngine;
import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.WaveformView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice Recognition page: records at 16 kHz, transcribes with whisper.cpp
 * ({@code ggml-small.bin}, see {@link WhisperEngine}) and matches robot commands in the
 * transcript with {@link VoiceCommands}.
 *
 * <p>Without the native library or a model file the page still shows the microphone level and
 * commands can be run by tapping them, but nothing is transcribed.
 */
public class VoiceRecognitionActivity extends BaseActivity {

    private static final int MAX_HISTORY = 100;

    /** Artwork coordinates (voice_visual.png pixels) for the live overlays. */
    private static final RectF MIC_RECT = new RectF(205, 75, 405, 275);
    private static final RectF HINT_RECT = new RectF(180, 292, 430, 326);
    private static final RectF WAVE_RECT = new RectF(170, 342, 450, 400);

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

    private static final class HistoryEntry {
        final long time;
        final String command;
        final int result; // string resource
        final float confidence; // percent, < 0 = unknown

        HistoryEntry(long time, String command, int result, float confidence) {
            this.time = time;
            this.command = command;
            this.result = result;
            this.confidence = confidence;
        }
    }

    private final SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
    private final List<HistoryEntry> history = new ArrayList<>();
    private final int[][] advancedSelection = new int[ADVANCED.length][4];
    private final ExecutorService transcriber = Executors.newSingleThreadExecutor();

    private WhisperEngine engine;
    private SpeechRecorder recorder;
    private CustomPhrases customPhrases;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean listening;
    private boolean transcribing;
    private int advancedTab;
    private String wakeWordText = "Hey Robot";
    private String transcript = "";
    private VoiceCommands.Action currentAction;

    private CoverImageView visual;
    private View micHit;
    private TextView speakHint;
    private WaveformView liveWave;
    private TextView listenState;
    private TextView micDevice;
    private LinearLayout commandList;
    private EditText commandSearch;
    private LinearLayout historyList;
    private LinearLayout advancedRows;
    private TextView[] advancedTabs;
    private Switch commandSwitch;
    private Switch wakeSwitch;
    private Switch continuousSwitch;
    private Switch noiseSwitch;
    private TextView wakeWord;
    private TextView wakeTitle;
    private Spinner modelSpinner;

    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) setListening(true);
                else toast(R.string.mic_permission_needed);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_voice_recognition, R.id.nav_voice, 0);
        setupRichHeader(R.drawable.ic_mic, R.string.nav_voice, R.string.voice_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        engine = new WhisperEngine(this);
        recorder = new SpeechRecorder(recorderListener);
        customPhrases = new CustomPhrases(this);
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(Locale.US);
        });

        setupListening();
        setupResult();
        setupCommands();
        setupVoiceControl();
        setupHistory();
        setupAdvanced();

        renderResult();
        renderHistory();
        renderCommands();
        loadSelectedModel();
        setListening(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRichHeader();
    }

    @Override
    protected void onPause() {
        setListening(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) recorder.stop();
        if (tts != null) tts.shutdown();
        transcriber.execute(() -> engine.release());
        transcriber.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onConnectionChanged() {
        refreshRichHeader();
    }

    // ---- microphone ------------------------------------------------------------------------

    private void setupListening() {
        visual = findViewById(R.id.voice_visual);
        micHit = findViewById(R.id.mic_hit);
        speakHint = findViewById(R.id.speak_hint);
        liveWave = findViewById(R.id.live_wave);
        listenState = findViewById(R.id.listen_state);
        micDevice = findViewById(R.id.mic_device);
        micDevice.setText(getResources().getStringArray(R.array.microphones)[0]);
        ((ImageView) findViewById(R.id.mic_selector_icon))
                .setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);

        micHit.setOnClickListener(v -> toggleListening());
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

    private void toggleListening() {
        if (listening) {
            setListening(false);
            return;
        }
        if (!engine.isReady()) {
            toast(engineProblem());
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            setListening(true);
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void setListening(boolean on) {
        listening = on;
        if (on) {
            recorder.setNoiseSuppression(noiseSwitch == null || noiseSwitch.isChecked());
            recorder.start();
        } else {
            recorder.stop();
            liveWave.clearLevels();
        }
        liveWave.setActive(on);
        visual.animate().alpha(on ? 1f : 0.6f).setDuration(250).start();
        updateStatus();
        sendCommand("VOICE LISTEN " + (on ? "ON" : "OFF"));
    }

    private void updateStatus() {
        int text;
        int colorRes;
        if (transcribing) {
            text = R.string.status_processing;
            colorRes = R.color.amber;
        } else if (listening) {
            text = R.string.status_listening;
            colorRes = R.color.teal;
        } else {
            text = R.string.status_paused;
            colorRes = R.color.text_secondary;
        }
        listenState.setText(text);
        listenState.setTextColor(color(colorRes));
        findViewById(R.id.listen_dot).setBackgroundResource(listening ? R.drawable.dot_teal : R.drawable.dot_gray);
        speakHint.setText(listening ? R.string.speak_now : R.string.tap_mic_to_speak);
    }

    private String engineProblem() {
        if (!WhisperEngine.isLibraryAvailable()) return getString(R.string.engine_unavailable);
        return getString(R.string.model_not_found, selectedModel(),
                WhisperEngine.getModelDir(this).getAbsolutePath());
    }

    private final SpeechRecorder.Listener recorderListener = new SpeechRecorder.Listener() {
        @Override
        public void onLevel(float level, boolean speaking) {
            liveWave.pushLevel(level);
        }

        @Override
        public void onUtterance(float[] samples) {
            transcribe(samples);
        }

        @Override
        public void onError(String message) {
            listening = false;
            updateStatus();
            toast(getString(R.string.mic_error, message));
        }
    };

    /** Runs whisper on one utterance; only one at a time, speech during processing is dropped. */
    private void transcribe(final float[] samples) {
        if (transcribing || !engine.isReady()) return;
        transcribing = true;
        updateStatus();
        final String language = languageCode();
        final int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        transcriber.execute(() -> {
            final WhisperEngine.Result result = engine.transcribe(samples, language, false, threads);
            runOnUiThread(() -> {
                transcribing = false;
                updateStatus();
                if (result != null && !result.text.isEmpty()) {
                    handleTranscript(result.text, result.confidence * 100f, true);
                }
            });
        });
    }

    /** Applies the wake word, matches a command and runs it. */
    private void handleTranscript(String text, float confidence, boolean spoken) {
        if (spoken && wakeSwitch.isChecked()) {
            if (!VoiceCommands.containsWakeWord(text, wakeWordText)) return; // not addressed to the robot
            String rest = VoiceCommands.stripWakeWord(text, wakeWordText);
            if (rest != null && !rest.isEmpty()) text = rest;
        }
        transcript = text;
        VoiceCommands.Action action = customPhrases.match(text);
        if (action == null) action = VoiceCommands.match(text);
        currentAction = action;

        int result;
        if (action == null) {
            result = R.string.result_no_match;
        } else if (action.command == null) {
            speak(getString(R.string.time_answer, clock.format(new Date())));
            result = R.string.result_answered;
        } else if (!commandSwitch.isChecked()) {
            result = R.string.result_not_sent; // voice command control is off
        } else {
            result = RobotSession.get().send("VOICE_COMMAND " + action.command)
                    ? R.string.result_executed : R.string.result_not_sent;
        }

        addHistory(text, result, confidence);
        renderResult(confidence);
        if (spoken && !continuousSwitch.isChecked()) setListening(false);
    }

    private void addHistory(String command, int result, float confidence) {
        history.add(new HistoryEntry(System.currentTimeMillis(), command, result, confidence));
        while (history.size() > MAX_HISTORY) history.remove(0);
        renderHistory();
    }

    private void speak(String text) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-result");
    }

    private void showMicrophoneMenu(View anchor) {
        final String[] devices = getResources().getStringArray(R.array.microphones);
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < devices.length; i++) menu.getMenu().add(0, i, i, devices[i]);
        menu.setOnMenuItemClickListener(item -> {
            micDevice.setText(devices[item.getItemId()]);
            return true;
        });
        menu.show();
    }

    // ---- result + command action -----------------------------------------------------------

    private void setupResult() {
        setIcon(findViewById(R.id.result_title), R.drawable.ic_check_circle, 22, color(R.color.teal), Gravity.START);
        setIcon(findViewById(R.id.action_title), R.drawable.ic_robot, 22, color(R.color.cyan), Gravity.START);
        int white = color(R.color.text_primary);
        ((ImageView) findViewById(R.id.btn_speak)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        ((ImageView) findViewById(R.id.btn_execute)).setColorFilter(white, PorterDuff.Mode.SRC_IN);

        findViewById(R.id.btn_speak).setOnClickListener(v -> {
            if (!transcript.isEmpty()) speak(transcript);
        });
        findViewById(R.id.btn_execute).setOnClickListener(v -> {
            if (currentAction == null) return;
            if (currentAction.command == null) {
                speak(getString(R.string.time_answer, clock.format(new Date())));
                return;
            }
            if (sendCommand("VOICE_COMMAND " + currentAction.command)) {
                toast(getString(R.string.sent_command, currentAction.name));
                addHistory(transcript, R.string.result_executed, -1f);
            }
        });
    }

    private void renderResult() {
        renderResult(-1f);
    }

    private void renderResult(float confidence) {
        String dash = getString(R.string.placeholder_value);
        ((TextView) findViewById(R.id.result_quote)).setText(
                transcript.isEmpty() ? getString(R.string.tap_mic_to_speak) : getString(R.string.quoted, transcript));
        ((TextView) findViewById(R.id.result_text)).setText(transcript.isEmpty() ? dash : transcript);
        ((TextView) findViewById(R.id.result_confidence)).setText(confidence >= 0
                ? getString(R.string.similarity_value, confidence) : dash);
        ((ProgressBar) findViewById(R.id.result_confidence_bar)) // max is 1000
                .setProgress(Math.round(Math.max(0f, confidence) * 10f));
        String[] languages = getResources().getStringArray(R.array.adv_language_options);
        ((TextView) findViewById(R.id.result_language)).setText(languages[advancedSelection[1][0]]);
        ((TextView) findViewById(R.id.result_time)).setText(
                transcript.isEmpty() ? dash : dateTime.format(new Date()));

        TextView name = findViewById(R.id.action_name);
        TextView description = findViewById(R.id.action_desc);
        ImageView icon = findViewById(R.id.action_icon);
        if (currentAction == null) {
            name.setText(transcript.isEmpty() ? getString(R.string.tap_mic_to_speak)
                    : getString(R.string.result_no_match));
            description.setText("");
            icon.setImageResource(R.drawable.ic_mic);
        } else {
            SpannableStringBuilder label = new SpannableStringBuilder(getString(R.string.execute_label));
            int start = label.length();
            label.append(currentAction.name);
            label.setSpan(new ForegroundColorSpan(color(R.color.teal)), start, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            name.setText(label);
            description.setText(currentAction.description);
            icon.setImageResource(iconFor(currentAction));
        }
        icon.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
    }

    private static int iconFor(VoiceCommands.Action action) {
        String command = action.command;
        if (command == null) return R.drawable.ic_history;
        if (command.startsWith("MOVE FORWARD")) return R.drawable.ic_arrow_up;
        if (command.startsWith("MOVE BACKWARD")) return R.drawable.ic_arrow_down;
        if (command.startsWith("TURN LEFT")) return R.drawable.ic_arrow_left;
        if (command.startsWith("TURN RIGHT")) return R.drawable.ic_arrow_right;
        if (command.startsWith("STOP")) return R.drawable.ic_square;
        if (command.startsWith("GO_HOME")) return R.drawable.ic_home;
        if (command.startsWith("DOOR")) return R.drawable.ic_lock_open;
        if (command.startsWith("SLAM")) return R.drawable.ic_map;
        if (command.startsWith("FOLLOW")) return R.drawable.ic_follow;
        return R.drawable.ic_robot;
    }

    // ---- voice command list ----------------------------------------------------------------

    private void setupCommands() {
        setIcon(findViewById(R.id.voice_db_title), R.drawable.ic_mic, 22, color(R.color.cyan), Gravity.START);
        TextView add = findViewById(R.id.btn_add_voice);
        setIcon(add, R.drawable.ic_add, 16, color(R.color.text_primary), Gravity.START);
        add.setOnClickListener(v -> showAddPhraseDialog());

        commandList = findViewById(R.id.voice_db_list);
        commandSearch = findViewById(R.id.voice_search);
        setIcon(commandSearch, R.drawable.ic_search, 18, color(R.color.text_secondary), Gravity.START);
        commandSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderCommands();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    /** Built-in commands plus the user's own phrases; tapping a row runs it. */
    private void renderCommands() {
        commandList.removeAllViews();
        String query = commandSearch.getText().toString().trim().toLowerCase(Locale.US);
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = Math.round(6 * getResources().getDisplayMetrics().density);

        for (final CustomPhrases.Phrase phrase : customPhrases.getAll()) {
            if (!matches(query, phrase.text, phrase.action.name)) continue;
            View row = addCommandRow(inflater, gap, phrase.text, phrase.action, false);
            row.setOnLongClickListener(v -> {
                customPhrases.remove(phrase);
                renderCommands();
                return true;
            });
        }
        for (final VoiceCommands.Action action : VoiceCommands.actions()) {
            String phrase = VoiceCommands.examplePhrase(action);
            if (!matches(query, phrase, action.name)) continue;
            addCommandRow(inflater, gap, phrase, action, true);
        }
    }

    private static boolean matches(String query, String phrase, String name) {
        return query.isEmpty() || phrase.toLowerCase(Locale.US).contains(query)
                || name.toLowerCase(Locale.US).contains(query);
    }

    /** Reuses the face database row: icon / phrase / action name / built-in chip. */
    private View addCommandRow(LayoutInflater inflater, int gap, final String phrase,
                               final VoiceCommands.Action action, boolean builtIn) {
        View row = inflater.inflate(R.layout.item_face_db_row, commandList, false);
        ImageView icon = row.findViewById(R.id.db_photo);
        icon.setImageResource(iconFor(action));
        icon.setColorFilter(color(R.color.blue_light), PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ((TextView) row.findViewById(R.id.db_name)).setText(phrase);
        row.findViewById(R.id.db_id).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.db_department)).setText(action.name);
        TextView chip = row.findViewById(R.id.db_active);
        chip.setText(builtIn ? R.string.built_in : R.string.custom_phrase);
        chip.setTextColor(color(builtIn ? R.color.text_muted : R.color.teal));
        chip.setBackgroundResource(builtIn ? R.drawable.bg_table_box : R.drawable.bg_chip_green);
        row.setOnClickListener(v -> handleTranscript(phrase, -1f, false));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        if (commandList.getChildCount() > 0) lp.topMargin = gap;
        commandList.addView(row, lp);
        return row;
    }

    private void showAddPhraseDialog() {
        final List<VoiceCommands.Action> actions = VoiceCommands.actions();
        final EditText input = textInput(null);
        input.setHint(R.string.enter_phrase);
        final Spinner spinner = new Spinner(this);
        List<CharSequence> names = new ArrayList<>();
        for (VoiceCommands.Action a : actions) names.add(a.name);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this, R.layout.item_spinner, names);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setBackgroundResource(R.drawable.bg_input);

        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * density);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(46 * density));
        lp.topMargin = Math.round(10 * density);
        content.addView(spinner, lp);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.add_phrase)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                input.setError(getString(R.string.enter_phrase));
                return;
            }
            customPhrases.add(text, actions.get(spinner.getSelectedItemPosition()));
            renderCommands();
            dialog.dismiss();
        }));
        dialog.show();
    }

    // ---- voice control ---------------------------------------------------------------------

    private void setupVoiceControl() {
        setIcon(findViewById(R.id.control_title), R.drawable.ic_settings, 22, color(R.color.text_primary), Gravity.START);
        LinearLayout toggles = findViewById(R.id.voice_toggles);
        commandSwitch = addToggle(toggles, R.drawable.ic_mic, R.string.vc_command, R.string.vc_command_sub, true);
        wakeSwitch = addToggle(toggles, R.drawable.ic_bolt, R.string.vc_wake, R.string.vc_wake_sub, false);
        wakeTitle = ((View) wakeSwitch.getParent()).findViewById(R.id.toggle_title);
        continuousSwitch = addToggle(toggles, R.drawable.ic_refresh, R.string.vc_continuous, R.string.vc_continuous_sub, true);
        noiseSwitch = addToggle(toggles, R.drawable.ic_tune, R.string.vc_noise, R.string.vc_noise_sub, true);
        noiseSwitch.setOnCheckedChangeListener((b, on) -> {
            recorder.setNoiseSuppression(on);
            if (listening) { // restart so the effect applies to a new recording session
                setListening(false);
                setListening(true);
            }
        });

        wakeWord = findViewById(R.id.wake_word);
        setWakeWord(wakeWordText);
        ((ImageView) findViewById(R.id.btn_edit_wake)).setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        findViewById(R.id.btn_edit_wake).setOnClickListener(v -> {
            final EditText input = textInput(wakeWordText);
            new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                    .setTitle(R.string.wake_word)
                    .setView(wrapInput(input))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String word = input.getText().toString().trim();
                        if (!word.isEmpty()) setWakeWord(word);
                    })
                    .show();
        });
    }

    private void setWakeWord(String word) {
        wakeWordText = word;
        wakeWord.setText(word);
        wakeTitle.setText(getString(R.string.wake_word_title, word));
    }

    private Switch addToggle(LinearLayout parent, int icon, int title, int subtitle, boolean checked) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_voice_toggle_row, parent, false);
        ImageView iconView = row.findViewById(R.id.toggle_icon);
        iconView.setImageResource(icon);
        iconView.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        ((TextView) row.findViewById(R.id.toggle_title)).setText(title);
        ((TextView) row.findViewById(R.id.toggle_subtitle)).setText(subtitle);
        Switch toggle = row.findViewById(R.id.toggle_switch);
        toggle.setChecked(checked);
        parent.addView(row);
        return toggle;
    }

    private EditText textInput(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(color(R.color.text_primary));
        input.setHintTextColor(color(R.color.text_muted));
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

    // ---- history ---------------------------------------------------------------------------

    private void setupHistory() {
        setIcon(findViewById(R.id.history_title), R.drawable.ic_history, 22, color(R.color.text_primary), Gravity.START);
        historyList = findViewById(R.id.voice_history_list);
        findViewById(R.id.btn_clear_history).setOnClickListener(v -> {
            history.clear();
            renderHistory();
        });
    }

    private void renderHistory() {
        historyList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoryEntry entry = history.get(i);
            View row = inflater.inflate(R.layout.item_voice_history_row, historyList, false);
            ((TextView) row.findViewById(R.id.vh_time)).setText(dateTime.format(new Date(entry.time)));
            ((TextView) row.findViewById(R.id.vh_command)).setText(entry.command);
            TextView result = row.findViewById(R.id.vh_result);
            result.setText(entry.result);
            result.setTextColor(color(entry.result == R.string.result_executed ? R.color.teal
                    : entry.result == R.string.result_answered ? R.color.blue_light : R.color.amber));
            ((TextView) row.findViewById(R.id.vh_confidence)).setText(entry.confidence >= 0
                    ? getString(R.string.similarity_value, entry.confidence)
                    : getString(R.string.placeholder_value));
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
        apply.setOnClickListener(v -> loadSelectedModel());
        renderAdvancedTab(0);
    }

    private void renderAdvancedTab(final int tab) {
        advancedTab = tab;
        for (int i = 0; i < advancedTabs.length; i++) advancedTabs[i].setSelected(i == tab);
        advancedRows.removeAllViews();
        if (tab != 0) modelSpinner = null;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int r = 0; r < ADVANCED[tab].length; r++) {
            final int rowIndex = r;
            View row = inflater.inflate(R.layout.item_setting_dropdown_row, advancedRows, false);
            ((TextView) row.findViewById(R.id.setting_label)).setText(ADVANCED[tab][r][0]);
            Spinner spinner = row.findViewById(R.id.setting_spinner);
            ArrayAdapter<CharSequence> adapter;
            if (tab == 0 && r == 0) {
                // the model list comes from the device, not from resources
                List<CharSequence> models = new ArrayList<>(WhisperEngine.listAvailableModels(this));
                if (models.isEmpty()) models.add(WhisperEngine.DEFAULT_MODEL);
                adapter = new ArrayAdapter<>(this, R.layout.item_spinner, models);
                modelSpinner = spinner;
            } else {
                adapter = ArrayAdapter.createFromResource(this, ADVANCED[tab][r][1], R.layout.item_spinner);
            }
            adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            spinner.setAdapter(adapter);
            if (advancedSelection[tab][r] < adapter.getCount()) spinner.setSelection(advancedSelection[tab][r]);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (advancedTab != tab) return;
                    advancedSelection[tab][rowIndex] = position;
                    if (tab == 1 && rowIndex == 0) renderResult();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            advancedRows.addView(row);
        }
    }

    private String selectedModel() {
        if (modelSpinner != null && modelSpinner.getSelectedItem() != null) {
            return modelSpinner.getSelectedItem().toString();
        }
        List<String> models = WhisperEngine.listAvailableModels(this);
        return models.isEmpty() ? WhisperEngine.DEFAULT_MODEL : models.get(0);
    }

    /** whisper language code from the Language tab. */
    private String languageCode() {
        String[] codes = getResources().getStringArray(R.array.adv_language_codes);
        int index = advancedSelection[1][0];
        return index < codes.length ? codes[index] : "auto";
    }

    /** Loads the selected ggml model in the background (a few seconds for ggml-small). */
    private void loadSelectedModel() {
        if (!WhisperEngine.isLibraryAvailable()) {
            toast(R.string.engine_unavailable);
            return;
        }
        final String model = selectedModel();
        final File file = new File(WhisperEngine.getModelDir(this), model);
        final boolean install = !file.exists();
        if (install && !WhisperEngine.isBundled(this, model)) {
            toast(getString(R.string.model_not_found, model, file.getParent()));
            return;
        }
        toast(getString(install ? R.string.installing_model : R.string.loading_model, model));
        transcriber.execute(() -> {
            // a bundled model is copied out of the APK once, before it can be loaded
            final boolean ok = (!install || engine.installFromAssets(model)) && engine.load(model);
            runOnUiThread(() -> toast(ok ? getString(R.string.model_loaded, model)
                    : getString(R.string.model_load_failed, model)));
        });
    }
}
