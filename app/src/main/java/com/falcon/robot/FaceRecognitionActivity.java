package com.falcon.robot;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.falcon.robot.face.FaceAnalyzer;
import com.falcon.robot.face.FaceDatabase;
import com.falcon.robot.widget.DonutChartView;
import com.falcon.robot.widget.FaceOverlayView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Face Recognition page: live camera (CameraX), face detection (ML Kit), face embeddings with
 * MobileFaceNet ({@code assets/mobilefacenet.tflite}) and a face database stored on the device.
 *
 * <p>Without the model file the page still detects faces but cannot recognize or register them.
 */
public class FaceRecognitionActivity extends BaseActivity {

    private static final int MAX_HISTORY = 200;
    private static final int REGISTRATION_SAMPLES = 5;
    private static final long REGISTRATION_TIMEOUT_MS = 5000;

    private static final class HistoryEntry {
        final long time;
        final FaceDatabase.Record record; // null = unknown face
        final float similarity;           // percent, < 0 = none
        final Bitmap crop;

        HistoryEntry(long time, FaceDatabase.Record record, float similarity, Bitmap crop) {
            this.time = time;
            this.record = record;
            this.similarity = similarity;
            this.crop = crop;
        }
    }

    private enum ResultMode { EMPTY, RECOGNIZED, UNRECOGNIZED, PROFILE }

    private final SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Map<String, Bitmap> photoCache = new HashMap<>();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private FaceDatabase database; // the service's, so both see the same people
    private boolean permissionAsked;
    private boolean binding; // true while the switches are being set from the service

    private int totalFaces;
    private int recognizedFaces;

    private FaceDatabase.Record selected;
    private ResultMode resultMode = ResultMode.EMPTY;
    private float resultSimilarity = -1f;
    private long resultTime;
    private Bitmap resultCrop;
    private String[] pendingRegistration; // name, department, position
    private boolean fullscreen;

    private PreviewView previewView;
    private FaceOverlayView overlay;
    private TextView cameraMessage;
    private TextView feedSource;
    private TextView frameInfo;
    private LinearLayout dbList;
    private EditText dbSearch;
    private LinearLayout historyList;
    private Switch detectionSwitch;
    private Switch recognitionSwitch;
    private SeekBar threshold;
    private Spinner cameraSpinner;
    private Spinner databaseSpinner;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) enableFaceRecognition(true);
                else showCameraMessage(getString(R.string.camera_permission_needed));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_face_recognition, R.id.nav_face, 0);
        setupRichHeader(R.drawable.ic_face_id, R.string.nav_face, R.string.face_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        setupCamera();
        setupResultPanel();
        setupDatabase();
        setupHistory();
        setupSettings();
        renderResult();
        renderStats();

        bindRobotService(serviceListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRichHeader();
    }

    @Override
    protected void onConnectionChanged() {
        refreshRichHeader();
    }

    @Override
    protected void onPause() {
        RobotService service = getRobotService();
        if (service != null) service.detachPreview(previewView.getSurfaceProvider());
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    // ---- recognition service ---------------------------------------------------------------

    @Override
    protected void onRobotServiceReady(RobotService service) {
        database = service.getFaceDatabase();
        binding = true;
        detectionSwitch.setChecked(service.isFaceEnabled());
        cameraSpinner.setSelection(service.getLensFacing() == CameraSelector.LENS_FACING_FRONT ? 0 : 1);
        binding = false;

        applySettingsToAnalyzer();
        service.attachPreview(previewView.getSurfaceProvider());
        overlay.setFaces(service.getLastFaces(), service.getFrameWidth(), service.getFrameHeight(),
                isFrontCamera());
        renderDatabase();
        renderServiceState();
    }

    private final RobotService.Listener serviceListener = new RobotService.Adapter() {
        @Override
        public void onFaceFrame(List<FaceAnalyzer.FrameFace> faces, int width, int height, long inferenceMs) {
            overlay.setFaces(faces, width, height, isFrontCamera());
            frameInfo.setText(getString(R.string.frame_info, width, height, (int) inferenceMs));
        }

        @Override
        public void onFaceEvent(FaceAnalyzer.FaceEvent event) {
            totalFaces++;
            if (event.record != null) recognizedFaces++;
            history.add(new HistoryEntry(event.time, event.record, event.similarity, event.crop));
            while (history.size() > MAX_HISTORY) history.remove(0);

            selected = event.record;
            resultMode = event.record != null ? ResultMode.RECOGNIZED : ResultMode.UNRECOGNIZED;
            resultSimilarity = event.similarity;
            resultTime = event.time;
            resultCrop = event.crop;

            renderResult();
            renderHistory();
            renderStats();
            renderDatabase();
        }

        @Override
        public void onRegistrationResult(float[] embedding, Bitmap crop) {
            String[] info = pendingRegistration;
            pendingRegistration = null;
            if (info == null) return;
            if (embedding == null) {
                toast(R.string.registration_failed);
                return;
            }
            FaceDatabase.Record record = database.add(info[0], info[1], info[2], embedding, crop);
            if (crop != null) photoCache.put(record.id, crop);
            RobotSession.get().send("FACE REGISTER " + record.id);
            toast(getString(R.string.face_registered, record.name));
            resetTracks(); // re-identify faces in view against the new entry
            showProfile(record);
        }

        @Override
        public void onServiceState() {
            renderServiceState();
        }

        @Override
        public void onMessage(String text) {
            toast(text);
        }
    };

    /** Reflects what the service is doing: the switch, the live dot and the camera message. */
    private void renderServiceState() {
        RobotService service = getRobotService();
        if (service == null) return;
        binding = true;
        detectionSwitch.setChecked(service.isFaceEnabled());
        binding = false;
        findViewById(R.id.feed_dot).setBackgroundResource(
                service.isFaceEnabled() ? R.drawable.dot_teal : R.drawable.dot_gray);
        if (service.isFaceEnabled()) {
            cameraMessage.setVisibility(View.GONE);
        } else {
            showCameraMessage(getString(R.string.detection_disabled));
        }
        updateCameraSource();
    }

    private boolean isFrontCamera() {
        RobotService service = getRobotService();
        return service == null || service.getLensFacing() == CameraSelector.LENS_FACING_FRONT;
    }

    private FaceAnalyzer faceAnalyzer() {
        RobotService service = getRobotService();
        return service == null ? null : service.getFaceAnalyzer();
    }

    private void resetTracks() {
        FaceAnalyzer analyzer = faceAnalyzer();
        if (analyzer != null) analyzer.resetTracks();
    }

    /** Switches face recognition on in the service, asking for the camera the first time. */
    private void enableFaceRecognition(boolean on) {
        RobotService service = getRobotService();
        if (service == null) return;
        if (!on) {
            service.setFaceEnabled(false);
            overlay.clear();
            return;
        }
        ensureNotificationPermission();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            service.setFaceEnabled(true);
            service.attachPreview(previewView.getSurfaceProvider());
        } else if (!permissionAsked) {
            permissionAsked = true;
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            // the system stops showing the dialog after repeated denials: open app settings
            binding = true;
            detectionSwitch.setChecked(false);
            binding = false;
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void showCameraMessage(String message) {
        cameraMessage.setText(message);
        cameraMessage.setVisibility(View.VISIBLE);
        overlay.clear();
    }

    // ---- camera panel ----------------------------------------------------------------------

    private void setupCamera() {
        previewView = findViewById(R.id.camera_preview);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlay = findViewById(R.id.face_overlay);
        overlay.setLabels(getString(R.string.unknown_person), getString(R.string.face_label_face));
        cameraMessage = findViewById(R.id.camera_message);
        cameraMessage.setOnClickListener(v -> enableFaceRecognition(true));
        findViewById(R.id.feed_frame).setClipToOutline(true);
        feedSource = findViewById(R.id.feed_source);
        frameInfo = findViewById(R.id.feed_resolution);

        int white = color(R.color.text_primary);
        for (int id : new int[] {R.id.tool_capture, R.id.tool_rescan, R.id.tool_source, R.id.tool_fullscreen, R.id.feed_fullscreen}) {
            ((ImageView) findViewById(id)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        }
        findViewById(R.id.tool_capture).setOnClickListener(v -> saveSnapshot());
        findViewById(R.id.tool_rescan).setOnClickListener(v -> {
            resetTracks();
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

    /** Saves what the preview shows to the app's Pictures folder (no storage permission needed). */
    private void saveSnapshot() {
        final Bitmap bitmap = previewView.getBitmap();
        if (bitmap == null) {
            toast(R.string.snapshot_failed);
            return;
        }
        final File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        cameraExecutor.execute(() -> {
            File file = new File(dir != null ? dir : getFilesDir(),
                    "face_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            boolean ok;
            try (OutputStream out = new FileOutputStream(file)) {
                ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            } catch (IOException e) {
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> toast(saved ? getString(R.string.snapshot_saved_to, file.getAbsolutePath())
                    : getString(R.string.snapshot_failed)));
        });
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
            if (resultMode == ResultMode.EMPTY) return;
            history.add(new HistoryEntry(System.currentTimeMillis(), selected,
                    resultMode == ResultMode.PROFILE ? -1f : resultSimilarity, resultCrop));
            renderHistory();
            toast(getString(R.string.added_to_log,
                    selected != null ? selected.name : getString(R.string.unknown_person)));
        });

    }

    /** Activate or delete one person; opened by a long press on their row. */
    private void showRecordMenu(final FaceDatabase.Record record, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.toggle_active);
        menu.getMenu().add(0, 2, 1, R.string.remove_from_database);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                database.setActive(record, !record.active);
            } else {
                database.remove(record);
                photoCache.remove(record.id);
                toast(getString(R.string.face_removed, record.name));
                selected = null;
                resultMode = ResultMode.EMPTY;
                renderResult();
            }
            resetTracks();
            renderDatabase();
            return true;
        });
        menu.show();
    }

    private void showProfile(FaceDatabase.Record record) {
        selected = record;
        resultMode = ResultMode.PROFILE;
        resultSimilarity = -1f;
        resultCrop = null;
        resultTime = lastSeen(record);
        renderResult();
        renderDatabase();
    }

    private void renderResult() {
        TextView title = findViewById(R.id.result_title);
        int titleText;
        int titleColor;
        int titleIcon;
        switch (resultMode) {
            case RECOGNIZED:
                titleText = R.string.recognized;
                titleColor = R.color.teal;
                titleIcon = R.drawable.ic_check_circle;
                break;
            case UNRECOGNIZED:
                titleText = R.string.unrecognized;
                titleColor = R.color.red;
                titleIcon = R.drawable.ic_warning;
                break;
            case PROFILE:
                titleText = R.string.profile;
                titleColor = R.color.blue_light;
                titleIcon = R.drawable.ic_person;
                break;
            default:
                titleText = R.string.waiting_for_face;
                titleColor = R.color.text_secondary;
                titleIcon = R.drawable.ic_face_id;
                break;
        }
        title.setText(titleText);
        title.setTextColor(color(titleColor));
        setIcon(title, titleIcon, 26, color(titleColor), Gravity.START);

        FaceDatabase.Record r = selected;
        Bitmap photo = resultCrop != null ? resultCrop : r != null ? photoOf(r) : null;
        bindPhoto(findViewById(R.id.result_photo), photo);
        String dash = getString(R.string.placeholder_value);
        ((TextView) findViewById(R.id.result_name)).setText(r != null ? r.name
                : resultMode == ResultMode.UNRECOGNIZED ? getString(R.string.unknown_person) : dash);
        ((TextView) findViewById(R.id.result_id)).setText(r != null ? r.id : dash);
        ((TextView) findViewById(R.id.result_similarity)).setText(resultSimilarity >= 0
                ? getString(R.string.similarity_value, resultSimilarity) : dash);
        ((ProgressBar) findViewById(R.id.result_similarity_bar)).setProgress(Math.round(Math.max(0, resultSimilarity) * 10));
        ((TextView) findViewById(R.id.result_department)).setText(r != null && !r.department.isEmpty() ? r.department : dash);
        ((TextView) findViewById(R.id.result_position)).setText(r != null && !r.position.isEmpty() ? r.position : dash);
        ((TextView) findViewById(R.id.result_access_time)).setText(resultTime > 0
                ? dateTime.format(new Date(resultTime)) : dash);
    }

    private long lastSeen(FaceDatabase.Record record) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).record == record) return history.get(i).time;
        }
        return 0;
    }

    private Bitmap photoOf(FaceDatabase.Record record) {
        if (!photoCache.containsKey(record.id)) photoCache.put(record.id, database.loadPhoto(record));
        return photoCache.get(record.id);
    }

    private void bindPhoto(ImageView view, Bitmap photo) {
        view.setClipToOutline(true);
        if (photo != null) {
            view.setImageBitmap(photo);
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
        if (database == null) return; // filled in once the service is bound
        if (dbList == null) return;
        dbList.removeAllViews();
        String query = dbSearch.getText().toString().trim().toLowerCase(Locale.US);
        int filter = databaseSpinner == null ? 0 : databaseSpinner.getSelectedItemPosition();
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = Math.round(6 * getResources().getDisplayMetrics().density);

        for (final FaceDatabase.Record record : database.getAll()) {
            if (filter == 1 && !record.active || filter == 2 && record.active) continue;
            if (!query.isEmpty() && !record.name.toLowerCase(Locale.US).contains(query) && !record.id.contains(query)) {
                continue;
            }
            View row = inflater.inflate(R.layout.item_face_db_row, dbList, false);
            bindPhoto(row.findViewById(R.id.db_photo), photoOf(record));
            ((TextView) row.findViewById(R.id.db_name)).setText(record.name);
            ((TextView) row.findViewById(R.id.db_id)).setText(record.id);
            ((TextView) row.findViewById(R.id.db_department)).setText(record.department);
            TextView active = row.findViewById(R.id.db_active);
            active.setText(record.active ? R.string.active : R.string.inactive);
            active.setTextColor(color(record.active ? R.color.teal : R.color.text_muted));
            active.setBackgroundResource(record.active ? R.drawable.bg_chip_green : R.drawable.bg_table_box);
            row.setSelected(record == selected);
            row.setOnClickListener(v -> showProfile(record));
            // long press for the actions the More button used to hold
            row.setOnLongClickListener(v -> {
                showRecordMenu(record, v);
                return true;
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (dbList.getChildCount() > 0) lp.topMargin = gap;
            dbList.addView(row, lp);
        }
        if (dbList.getChildCount() == 0) dbList.addView(emptyText(R.string.empty_database));
    }

    private TextView emptyText(int text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color(R.color.text_secondary));
        view.setTextSize(13);
        int pad = Math.round(10 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    private void showAddFaceDialog() {
        FaceAnalyzer analyzer = faceAnalyzer();
        if (analyzer == null || !analyzer.canRecognize()) {
            toast(R.string.recognition_unavailable);
            return;
        }
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
            pendingRegistration = new String[] {n, department.getText().toString().trim(), position.getText().toString().trim()};
            RobotService service = getRobotService();
            if (service != null) service.startRegistration(REGISTRATION_SAMPLES, REGISTRATION_TIMEOUT_MS);
            toast(R.string.look_at_camera);
            dialog.dismiss();
        }));
        dialog.show();
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
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoryEntry entry = history.get(i);
            View row = inflater.inflate(R.layout.item_face_history_row, historyList, false);
            boolean recognized = entry.record != null;
            ((TextView) row.findViewById(R.id.history_time)).setText(dateTime.format(new Date(entry.time)));
            bindPhoto(row.findViewById(R.id.history_photo), entry.crop != null ? entry.crop
                    : recognized ? photoOf(entry.record) : null);
            ((TextView) row.findViewById(R.id.history_name)).setText(
                    recognized ? entry.record.name : getString(R.string.unknown_person));
            ((TextView) row.findViewById(R.id.history_similarity)).setText(entry.similarity >= 0
                    ? getString(R.string.similarity_value, entry.similarity) : "-");
            TextView status = row.findViewById(R.id.history_status);
            status.setText(recognized ? R.string.recognized : R.string.unrecognized);
            status.setTextColor(color(recognized ? R.color.teal : R.color.red));
            status.setBackgroundResource(recognized ? R.drawable.bg_chip_green : R.drawable.bg_chip_red);
            status.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    recognized ? R.drawable.dot_teal : R.drawable.dot_red, 0, 0, 0);
            historyList.addView(row);
        }
        if (history.isEmpty()) historyList.addView(emptyText(R.string.empty_history));
    }

    private void showAllHistory() {
        CharSequence[] lines = new CharSequence[history.size()];
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry entry = history.get(history.size() - 1 - i);
            boolean recognized = entry.record != null;
            lines[i] = getString(R.string.history_line,
                    dateTime.format(new Date(entry.time)),
                    recognized ? entry.record.name : getString(R.string.unknown_person),
                    entry.similarity >= 0 ? getString(R.string.similarity_value, entry.similarity) : "-",
                    getString(recognized ? R.string.recognized : R.string.unrecognized));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.recognition_history)
                .setPositiveButton(R.string.close, null);
        if (lines.length == 0) builder.setMessage(R.string.empty_history);
        else builder.setItems(lines, null);
        builder.show();
    }

    private void renderStats() {
        int unrecognized = totalFaces - recognizedFaces;
        float recognizedShare = totalFaces == 0 ? 0f : recognizedFaces / (float) totalFaces;
        ((DonutChartView) findViewById(R.id.stats_donut)).setFraction(recognizedShare);
        ((TextView) findViewById(R.id.stats_total)).setText(String.format(Locale.US, "%,d", totalFaces));
        ((TextView) findViewById(R.id.stats_recognized)).setText(String.format(Locale.US, "%,d", recognizedFaces));
        ((TextView) findViewById(R.id.stats_unrecognized)).setText(String.format(Locale.US, "%,d", unrecognized));
        ((TextView) findViewById(R.id.stats_recognized_pct)).setText(getString(R.string.similarity_value, recognizedShare * 100));
        ((TextView) findViewById(R.id.stats_unrecognized_pct)).setText(getString(R.string.similarity_value,
                totalFaces == 0 ? 0f : (1 - recognizedShare) * 100));
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
            if (binding) return; // only reflecting the service
            findViewById(R.id.feed_dot).setBackgroundResource(on ? R.drawable.dot_teal : R.drawable.dot_gray);
            enableFaceRecognition(on);
        });
        recognitionSwitch.setOnCheckedChangeListener((b, on) -> applySettingsToAnalyzer());

        threshold = findViewById(R.id.seek_threshold);
        final TextView thresholdValue = findViewById(R.id.value_threshold);
        thresholdValue.setText(getString(R.string.percent, thresholdPercent()));
        threshold.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                thresholdValue.setText(getString(R.string.percent, thresholdPercent()));
                applySettingsToAnalyzer();
            }
        });

        bindSettingSpinner(R.id.spinner_mode, R.array.recognition_modes, position -> applySettingsToAnalyzer());
        databaseSpinner = bindSettingSpinner(R.id.spinner_database, R.array.face_databases, position -> renderDatabase());
        cameraSpinner = bindSettingSpinner(R.id.spinner_camera, R.array.camera_sources, position -> {
            RobotService service = getRobotService();
            if (!binding && service != null) {
                service.setLensFacing(position == 0 ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK);
            }
            updateCameraSource();
        });
        updateCameraSource();
        renderDatabase();
    }

    private int thresholdPercent() {
        return 50 + threshold.getProgress();
    }

    private void applySettingsToAnalyzer() {
        FaceAnalyzer analyzer = faceAnalyzer();
        if (analyzer == null || detectionSwitch == null) return;
        analyzer.setRecognitionEnabled(recognitionSwitch.isChecked());
        analyzer.setThresholdPercent(thresholdPercent());
        int mode = ((Spinner) findViewById(R.id.spinner_mode)).getSelectedItemPosition();
        // 0 High Accuracy: accurate detector; 1 Balanced: fast detector; 2 Fast: fast detector, every other frame
        analyzer.setMode(mode == 0, mode == 2 ? 1 : 0);
    }

    private interface OnSelected {
        void onSelected(int position);
    }

    private Spinner bindSettingSpinner(int id, int entries, final OnSelected onSelected) {
        Spinner spinner = findViewById(id);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entries, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
                onSelected.onSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return spinner;
    }

    private void updateCameraSource() {
        if (cameraSpinner == null) return;
        int index = isFrontCamera() ? 0 : 1;
        feedSource.setText(getString(R.string.live_camera, getResources().getStringArray(R.array.camera_sources)[index]));
        if (cameraSpinner.getSelectedItemPosition() != index) cameraSpinner.setSelection(index);
    }
}
