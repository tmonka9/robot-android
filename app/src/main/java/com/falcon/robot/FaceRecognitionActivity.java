package com.falcon.robot;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.DonutChartView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Face Recognition page (design/face-rec.png): live camera with recognition overlay, result
 * details, face database, recognition history, statistics and detection settings.
 *
 * <p>Recognition is simulated: the feed is the design's camera artwork, and events are generated
 * periodically while detection and recognition are enabled. Replace {@link #simulateEvent} with
 * results from the real face engine.
 */
public class FaceRecognitionActivity extends BaseActivity {

    private static final long EVENT_INTERVAL_MS = 6000;
    private static final int HISTORY_ROWS = 5;

    /** Artwork coordinates (face_camera_feed.png pixels) of the labels around the face box. */
    private static final RectF FEED_CHIP_RECT = new RectF(298, 28, 384, 56);
    private static final RectF FEED_ID_RECT = new RectF(254, 252, 430, 282);

    private static final class Person {
        final String name;
        final String id;
        final String department;
        final String position;
        final int photo; // 0 = no photo
        boolean active = true;

        Person(String name, String id, String department, String position, int photo) {
            this.name = name;
            this.id = id;
            this.department = department;
            this.position = position;
            this.photo = photo;
        }
    }

    private static final class HistoryEntry {
        final long time;
        final Person person; // null = unknown face
        final float similarity; // < 0 = none

        HistoryEntry(long time, Person person, float similarity) {
            this.time = time;
            this.person = person;
            this.similarity = similarity;
        }
    }

    private final List<Person> people = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private int totalFaces = 1248;
    private int recognizedFaces = 1082;
    private int nextId = 128;

    private Person selected;      // person shown in the result panel
    private float lastSimilarity; // similarity of the last event for `selected`, < 0 = profile view
    private long lastAccess;
    private boolean fullscreen;
    private int eventCount;

    private CoverImageView feed;
    private View scanLine;
    private TextView feedChip;
    private TextView feedIdLabel;
    private TextView feedSource;
    private LinearLayout dbList;
    private EditText dbSearch;
    private LinearLayout historyList;
    private Switch detectionSwitch;
    private Switch recognitionSwitch;
    private SeekBar threshold;
    private Spinner cameraSpinner;
    private Spinner databaseSpinner;
    private ObjectAnimator scanAnimator;

    private final Runnable eventLoop = new Runnable() {
        @Override
        public void run() {
            simulateEvent();
            handler.postDelayed(this, EVENT_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_face_recognition, R.id.nav_face, 0);
        setupRichHeader(R.drawable.ic_face_id, R.string.nav_face, R.string.face_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        seedData();
        setupCamera();
        setupResultPanel();
        setupDatabase();
        setupHistory();
        setupSettings();

        showResult(people.get(0), 98.7f, System.currentTimeMillis());
        renderStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRichHeader();
        handler.removeCallbacks(eventLoop);
        handler.postDelayed(eventLoop, EVENT_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(eventLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (scanAnimator != null) scanAnimator.cancel();
        super.onDestroy();
    }

    @Override
    protected void onConnectionChanged() {
        refreshRichHeader();
    }

    // ---- data ------------------------------------------------------------------------------

    private void seedData() {
        people.add(new Person("Emma Wilson", "00123", "Marketing", "Manager", R.drawable.face_portrait_emma));
        people.add(new Person("James Miller", "00124", "IT", "Engineer", R.drawable.face_avatar_james));
        people.add(new Person("Sophia Davis", "00125", "HR", "Specialist", R.drawable.face_avatar_sophia));
        people.add(new Person("Daniel Brown", "00126", "Finance", "Analyst", R.drawable.face_avatar_daniel));
        people.add(new Person("Olivia Taylor", "00127", "Operations", "Coordinator", R.drawable.face_avatar_olivia));

        long now = System.currentTimeMillis();
        history.add(new HistoryEntry(now - 492_000, null, -1f));
        history.add(new HistoryEntry(now - 347_000, people.get(3), 93.6f));
        history.add(new HistoryEntry(now - 210_000, people.get(2), 97.1f));
        history.add(new HistoryEntry(now - 77_000, people.get(1), 96.3f));
        history.add(new HistoryEntry(now, people.get(0), 98.7f));
    }

    /**
     * One simulated detection: usually the person in the camera artwork (Emma) with a jittering
     * similarity; sometimes an unknown passer-by. Faces below the threshold count as unrecognized.
     */
    private void simulateEvent() {
        if (!detectionSwitch.isChecked()) return;
        eventCount++;
        long now = System.currentTimeMillis();
        totalFaces++;

        if (!recognitionSwitch.isChecked() || eventCount % 4 == 0) {
            history.add(new HistoryEntry(now, null, -1f));
        } else {
            Person emma = people.isEmpty() ? null : people.get(0);
            float similarity = 94f + random.nextFloat() * 5.5f;
            if (emma != null && similarity >= thresholdPercent()) {
                recognizedFaces++;
                history.add(new HistoryEntry(now, emma, similarity));
                showResult(emma, similarity, now);
                RobotSession.get().send("FACE RECOGNIZED " + emma.id);
            } else {
                history.add(new HistoryEntry(now, null, similarity));
                showFeedLabels(null, similarity);
            }
        }
        playScan();
        renderHistory();
        renderStats();
    }

    private int thresholdPercent() {
        return 50 + threshold.getProgress();
    }

    // ---- live camera -----------------------------------------------------------------------

    private void setupCamera() {
        feed = findViewById(R.id.face_feed);
        feed.setFocus(0.3f, 0.05f, 0.7f, 0.8f); // keep the face and its labels in view
        findViewById(R.id.feed_frame).setClipToOutline(true);
        scanLine = findViewById(R.id.face_scan_line);
        feedChip = findViewById(R.id.feed_chip);
        feedIdLabel = findViewById(R.id.feed_id_label);
        feedSource = findViewById(R.id.feed_source);
        ((TextView) findViewById(R.id.feed_resolution)).setText(
                getResources().getStringArray(R.array.camera_resolution_sizes)[0]);

        // keep the live labels glued to the face box whenever the feed is resized
        feed.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeFeedLabels));

        int white = color(R.color.text_primary);
        for (int id : new int[] {R.id.tool_capture, R.id.tool_rescan, R.id.tool_source, R.id.tool_fullscreen, R.id.feed_fullscreen}) {
            ((ImageView) findViewById(id)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        }
        findViewById(R.id.tool_capture).setOnClickListener(v -> {
            RobotSession.get().send("CAMERA SNAPSHOT");
            toast(R.string.snapshot_saved);
        });
        findViewById(R.id.tool_rescan).setOnClickListener(v -> {
            handler.removeCallbacks(eventLoop);
            eventCount = 0; // next event recognizes the face in view
            simulateEvent();
            handler.postDelayed(eventLoop, EVENT_INTERVAL_MS);
        });
        findViewById(R.id.tool_source).setOnClickListener(this::showCameraMenu);
        feedSource.setOnClickListener(this::showCameraMenu);
        findViewById(R.id.tool_fullscreen).setOnClickListener(v -> toggleFullscreen());
        findViewById(R.id.feed_fullscreen).setOnClickListener(v -> toggleFullscreen());
    }

    private void showCameraMenu(View anchor) {
        final String[] sources = getResources().getStringArray(R.array.camera_sources);
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < sources.length; i++) menu.getMenu().add(0, i, i, sources[i]);
        menu.setOnMenuItemClickListener(item -> {
            cameraSpinner.setSelection(item.getItemId());
            return true;
        });
        menu.show();
    }

    /** Hides every panel except the live camera, or restores them. */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        int visibility = fullscreen ? View.GONE : View.VISIBLE;
        findViewById(R.id.panel_result).setVisibility(visibility);
        findViewById(R.id.panel_database).setVisibility(visibility);
        findViewById(R.id.columns_bottom).setVisibility(visibility);
        int icon = fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen;
        ((ImageView) findViewById(R.id.tool_fullscreen)).setImageResource(icon);
        ((ImageView) findViewById(R.id.feed_fullscreen)).setImageResource(icon);
    }

    /** Updates the labels drawn over the artwork's face box. {@code person == null} = not recognized. */
    private void showFeedLabels(Person person, float similarity) {
        boolean recognized = person != null;
        feedChip.setText(recognized ? R.string.recognized : R.string.unrecognized);
        feedChip.setBackgroundResource(recognized ? R.drawable.bg_feed_chip_green : R.drawable.bg_feed_chip_red);
        feedIdLabel.setBackgroundResource(recognized ? R.drawable.bg_feed_chip_green : R.drawable.bg_feed_chip_red);
        feedIdLabel.setTextColor(color(recognized ? R.color.teal : R.color.red));
        feedIdLabel.setText(getString(R.string.feed_id_label,
                recognized ? person.id : "-----",
                getString(R.string.similarity_value, similarity)));
        placeFeedLabels();
    }

    private void placeFeedLabels() {
        placeOnFeed(feedChip, FEED_CHIP_RECT, 0.55f);
        placeOnFeed(feedIdLabel, FEED_ID_RECT, 0.55f);
    }

    /** Sizes and positions {@code label} to cover {@code imageRect} of the feed artwork. */
    private void placeOnFeed(TextView label, RectF imageRect, float textRatio) {
        RectF rect = new RectF();
        if (!feed.mapImageRect(imageRect, rect)) return;
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, rect.height() * textRatio);
        int pad = Math.round(rect.height() * 0.2f);
        label.setPadding(pad * 2, 0, pad * 2, 0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) label.getLayoutParams();
        lp.width = Math.round(rect.width());
        lp.height = Math.round(rect.height());
        label.setLayoutParams(lp);
        label.setX(feed.getLeft() + rect.left);
        label.setY(feed.getTop() + rect.top);
        label.setVisibility(detectionSwitch == null || detectionSwitch.isChecked() ? View.VISIBLE : View.INVISIBLE);
    }

    private void playScan() {
        if (scanAnimator != null && scanAnimator.isRunning()) return;
        View frame = findViewById(R.id.feed_frame);
        scanAnimator = ObjectAnimator.ofFloat(scanLine, View.TRANSLATION_Y, 0f, frame.getHeight() - scanLine.getHeight());
        scanAnimator.setDuration(700);
        scanAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                scanLine.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                scanLine.setVisibility(View.INVISIBLE);
            }
        });
        scanAnimator.start();
    }

    // ---- result panel ----------------------------------------------------------------------

    private void setupResultPanel() {
        int white = color(R.color.white);
        TextView openDoor = findViewById(R.id.btn_open_door);
        setIcon(openDoor, R.drawable.ic_lock_open, 20, white, Gravity.START);
        openDoor.setOnClickListener(v -> {
            if (selected == null) return;
            if (sendCommand("DOOR OPEN " + selected.id)) toast(getString(R.string.door_opened, selected.name));
        });

        TextView addLog = findViewById(R.id.btn_add_log);
        setIcon(addLog, R.drawable.ic_note, 18, white, Gravity.START);
        addLog.setOnClickListener(v -> {
            if (selected == null) return;
            history.add(new HistoryEntry(System.currentTimeMillis(), selected,
                    lastSimilarity >= 0 ? lastSimilarity : -1f));
            renderHistory();
            toast(getString(R.string.added_to_log, selected.name));
        });

        TextView more = findViewById(R.id.btn_more);
        setIcon(more, R.drawable.ic_more, 18, white, Gravity.START);
        more.setOnClickListener(this::showMoreMenu);
    }

    private void showMoreMenu(View anchor) {
        if (selected == null) return;
        final Person person = selected;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.toggle_active);
        menu.getMenu().add(0, 2, 1, R.string.remove_from_database);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                person.active = !person.active;
            } else {
                people.remove(person);
                toast(getString(R.string.face_removed, person.name));
                if (!people.isEmpty()) showProfile(people.get(0));
            }
            renderDatabase();
            return true;
        });
        menu.show();
    }

    /** Shows a recognition result (from the camera). */
    private void showResult(Person person, float similarity, long time) {
        selected = person;
        lastSimilarity = similarity;
        lastAccess = time;
        renderResult(true);
        showFeedLabels(person, similarity);
        renderDatabase();
    }

    /** Shows a database profile (picked from the list). */
    private void showProfile(Person person) {
        selected = person;
        lastSimilarity = -1f;
        lastAccess = lastSeen(person);
        renderResult(false);
        renderDatabase();
    }

    private void renderResult(boolean fromCamera) {
        TextView title = findViewById(R.id.result_title);
        title.setText(fromCamera ? R.string.recognized : R.string.profile);
        title.setTextColor(color(fromCamera ? R.color.teal : R.color.blue_light));
        setIcon(title, fromCamera ? R.drawable.ic_check_circle : R.drawable.ic_person, 26,
                color(fromCamera ? R.color.teal : R.color.blue_light), Gravity.START);

        Person p = selected;
        ImageView photo = findViewById(R.id.result_photo);
        bindPhoto(photo, p == null ? 0 : p.photo);
        ((TextView) findViewById(R.id.result_name)).setText(p == null ? "" : p.name);
        ((TextView) findViewById(R.id.result_id)).setText(p == null ? "" : p.id);
        ((TextView) findViewById(R.id.result_similarity)).setText(lastSimilarity >= 0
                ? getString(R.string.similarity_value, lastSimilarity) : getString(R.string.placeholder_value));
        ((ProgressBar) findViewById(R.id.result_similarity_bar)).setProgress(Math.round(Math.max(0, lastSimilarity) * 10));
        ((TextView) findViewById(R.id.result_department)).setText(p == null ? "" : p.department);
        ((TextView) findViewById(R.id.result_position)).setText(p == null ? "" : p.position);
        ((TextView) findViewById(R.id.result_access_time)).setText(lastAccess > 0
                ? dateTime.format(new Date(lastAccess)) : getString(R.string.placeholder_value));
    }

    private long lastSeen(Person person) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).person == person) return history.get(i).time;
        }
        return 0;
    }

    private void bindPhoto(ImageView view, int photo) {
        view.setClipToOutline(true);
        if (photo != 0) {
            view.setImageResource(photo);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.clearColorFilter();
            view.setPadding(0, 0, 0, 0);
        } else {
            view.setImageResource(R.drawable.ic_person);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setColorFilter(color(R.color.blue_light), PorterDuff.Mode.SRC_IN);
            int pad = Math.round(6 * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
        }
    }

    // ---- database --------------------------------------------------------------------------

    private void setupDatabase() {
        setIcon(findViewById(R.id.db_title), R.drawable.ic_face_id, 22, color(R.color.blue_light), Gravity.START);
        ((ImageView) findViewById(R.id.db_add)).setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        findViewById(R.id.db_add).setOnClickListener(v -> showAddFaceDialog());

        dbList = findViewById(R.id.db_list);
        dbSearch = findViewById(R.id.db_search);
        setIcon(dbSearch, R.drawable.ic_search, 18, color(R.color.text_secondary), Gravity.START);
        dbSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderDatabase();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void renderDatabase() {
        if (dbList == null) return;
        dbList.removeAllViews();
        String query = dbSearch.getText().toString().trim().toLowerCase(Locale.US);
        // "Visitors" has no entries yet: everyone in the seed data is an employee
        boolean visitorsOnly = databaseSpinner != null && databaseSpinner.getSelectedItemPosition() == 2;
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = Math.round(6 * getResources().getDisplayMetrics().density);

        for (final Person person : people) {
            if (visitorsOnly) break;
            if (!query.isEmpty() && !person.name.toLowerCase(Locale.US).contains(query) && !person.id.contains(query)) {
                continue;
            }
            View row = inflater.inflate(R.layout.item_face_db_row, dbList, false);
            bindPhoto(row.findViewById(R.id.db_photo), person.photo);
            ((TextView) row.findViewById(R.id.db_name)).setText(person.name);
            ((TextView) row.findViewById(R.id.db_id)).setText(person.id);
            ((TextView) row.findViewById(R.id.db_department)).setText(person.department);
            TextView active = row.findViewById(R.id.db_active);
            active.setText(person.active ? R.string.active : R.string.inactive);
            active.setTextColor(color(person.active ? R.color.teal : R.color.text_muted));
            active.setBackgroundResource(person.active ? R.drawable.bg_chip_green : R.drawable.bg_table_box);
            row.setSelected(person == selected);
            row.setOnClickListener(v -> showProfile(person));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (dbList.getChildCount() > 0) lp.topMargin = gap;
            dbList.addView(row, lp);
        }
    }

    private void showAddFaceDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_face, null);
        final EditText name = view.findViewById(R.id.input_name);
        final EditText department = view.findViewById(R.id.input_department);
        final EditText position = view.findViewById(R.id.input_position);
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.add_face)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                name.setError(getString(R.string.enter_name));
                return;
            }
            Person person = new Person(n, String.format(Locale.US, "%05d", nextId++),
                    orDash(department.getText().toString()), orDash(position.getText().toString()), 0);
            people.add(person);
            RobotSession.get().send("FACE REGISTER " + person.id);
            toast(getString(R.string.face_registered, person.name));
            dialog.dismiss();
            showProfile(person);
        }));
        dialog.show();
    }

    private String orDash(String value) {
        String v = value.trim();
        return v.isEmpty() ? getString(R.string.placeholder_value) : v;
    }

    // ---- history & statistics ------------------------------------------------------------

    private void setupHistory() {
        setIcon(findViewById(R.id.history_title), R.drawable.ic_face_id, 22, color(R.color.cyan), Gravity.START);
        setIcon(findViewById(R.id.stats_title), R.drawable.ic_bar_chart, 22, color(R.color.blue_light), Gravity.START);
        setIcon(findViewById(R.id.today_title), R.drawable.ic_scan, 20, color(R.color.cyan), Gravity.START);
        ((ImageView) findViewById(R.id.today_recognized_icon)).setColorFilter(color(R.color.blue_light), PorterDuff.Mode.SRC_IN);
        historyList = findViewById(R.id.history_list);
        findViewById(R.id.btn_view_all).setOnClickListener(v -> showAllHistory());
        renderHistory();
    }

    private void renderHistory() {
        historyList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = history.size() - 1; i >= 0 && historyList.getChildCount() < HISTORY_ROWS; i--) {
            HistoryEntry entry = history.get(i);
            View row = inflater.inflate(R.layout.item_face_history_row, historyList, false);
            boolean recognized = entry.person != null;
            ((TextView) row.findViewById(R.id.history_time)).setText(dateTime.format(new Date(entry.time)));
            bindPhoto(row.findViewById(R.id.history_photo), recognized ? entry.person.photo : 0);
            ((TextView) row.findViewById(R.id.history_name)).setText(
                    recognized ? entry.person.name : getString(R.string.unknown_person));
            ((TextView) row.findViewById(R.id.history_similarity)).setText(recognized && entry.similarity >= 0
                    ? getString(R.string.similarity_value, entry.similarity) : "-");
            TextView status = row.findViewById(R.id.history_status);
            status.setText(recognized ? R.string.recognized : R.string.unrecognized);
            status.setTextColor(color(recognized ? R.color.teal : R.color.red));
            status.setBackgroundResource(recognized ? R.drawable.bg_chip_green : R.drawable.bg_chip_red);
            status.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    recognized ? R.drawable.dot_teal : R.drawable.dot_red, 0, 0, 0);
            historyList.addView(row);
        }
    }

    private void showAllHistory() {
        CharSequence[] lines = new CharSequence[history.size()];
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry entry = history.get(history.size() - 1 - i);
            boolean recognized = entry.person != null;
            lines[i] = getString(R.string.history_line,
                    dateTime.format(new Date(entry.time)),
                    recognized ? entry.person.name : getString(R.string.unknown_person),
                    recognized && entry.similarity >= 0 ? getString(R.string.similarity_value, entry.similarity) : "-",
                    getString(recognized ? R.string.recognized : R.string.unrecognized));
        }
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.recognition_history)
                .setItems(lines, null)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void renderStats() {
        int unrecognized = totalFaces - recognizedFaces;
        float recognizedShare = totalFaces == 0 ? 0f : recognizedFaces / (float) totalFaces;
        ((DonutChartView) findViewById(R.id.stats_donut)).setFraction(recognizedShare);
        ((TextView) findViewById(R.id.stats_total)).setText(String.format(Locale.US, "%,d", totalFaces));
        ((TextView) findViewById(R.id.stats_recognized)).setText(String.format(Locale.US, "%,d", recognizedFaces));
        ((TextView) findViewById(R.id.stats_unrecognized)).setText(String.format(Locale.US, "%,d", unrecognized));
        ((TextView) findViewById(R.id.stats_recognized_pct)).setText(getString(R.string.similarity_value, recognizedShare * 100));
        ((TextView) findViewById(R.id.stats_unrecognized_pct)).setText(getString(R.string.similarity_value, (1 - recognizedShare) * 100));
        ((TextView) findViewById(R.id.today_recognized)).setText(String.format(Locale.US, "%,d", recognizedFaces));
        ((TextView) findViewById(R.id.today_unrecognized)).setText(String.format(Locale.US, "%,d", unrecognized));
    }

    // ---- settings --------------------------------------------------------------------------

    private void setupSettings() {
        setIcon(findViewById(R.id.settings_title), R.drawable.ic_settings, 22, color(R.color.blue_light), Gravity.START);
        int cyan = color(R.color.cyan);
        ((ImageView) findViewById(R.id.icon_detection)).setColorFilter(cyan, PorterDuff.Mode.SRC_IN);
        ((ImageView) findViewById(R.id.icon_recognition)).setColorFilter(cyan, PorterDuff.Mode.SRC_IN);

        detectionSwitch = findViewById(R.id.sw_face_detection);
        recognitionSwitch = findViewById(R.id.sw_face_recognition);
        detectionSwitch.setOnCheckedChangeListener((b, on) -> {
            RobotSession.get().send("FACE DETECTION " + (on ? "ON" : "OFF"));
            findViewById(R.id.feed_dot).setBackgroundResource(on ? R.drawable.dot_teal : R.drawable.dot_gray);
            placeFeedLabels();
        });
        recognitionSwitch.setOnCheckedChangeListener((b, on) ->
                RobotSession.get().send("FACE RECOGNITION " + (on ? "ON" : "OFF")));

        threshold = findViewById(R.id.seek_threshold);
        final TextView thresholdValue = findViewById(R.id.value_threshold);
        thresholdValue.setText(getString(R.string.percent, thresholdPercent()));
        threshold.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                thresholdValue.setText(getString(R.string.percent, thresholdPercent()));
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                RobotSession.get().send("FACE THRESHOLD " + thresholdPercent());
            }
        });

        bindSettingSpinner(R.id.spinner_mode, R.array.recognition_modes, "FACE MODE ");
        databaseSpinner = bindSettingSpinner(R.id.spinner_database, R.array.face_databases, "FACE DATABASE ");
        cameraSpinner = bindSettingSpinner(R.id.spinner_camera, R.array.camera_sources, "FACE CAMERA ");
        updateCameraSource();
    }

    private Spinner bindSettingSpinner(int id, int entries, final String command) {
        Spinner spinner = findViewById(id);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entries, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initialized;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
                if (initialized) RobotSession.get().send(command + parent.getItemAtPosition(position));
                initialized = true;
                if (parent == cameraSpinner) updateCameraSource();
                if (parent == databaseSpinner) renderDatabase();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return spinner;
    }

    private void updateCameraSource() {
        if (cameraSpinner == null) return;
        feedSource.setText(getString(R.string.live_camera, cameraSpinner.getSelectedItem()));
    }
}
