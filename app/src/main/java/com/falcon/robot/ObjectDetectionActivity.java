package com.falcon.robot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.falcon.robot.detect.CocoLabels;
import com.falcon.robot.detect.DetectionAnalyzer;
import com.falcon.robot.detect.ObjectTracker;
import com.falcon.robot.detect.YoloSegmenter;
import com.falcon.robot.widget.TrackingOverlayView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Object Detection page: the live view of what {@link RobotService} is tracking with YOLOv8
 * ({@link YoloSegmenter}) and {@link ObjectTracker}.
 *
 * <p>The detection itself belongs to the service, so it carries on when this page is closed or
 * the app is in the background; Enable Detection here is the switch for it.
 */
public class ObjectDetectionActivity extends BaseActivity {

    private PreviewView previewView;
    private TrackingOverlayView overlay;
    private TextView cameraMessage;
    private LinearLayout resultList;
    private LinearLayout countList;
    private TextView totalCount;
    private TextView totalTrend;
    private TextView feedInfo;
    private TextView feedSource;
    private Switch enableSwitch;
    private Switch maskSwitch;
    private Switch trackSwitch;

    private boolean permissionAsked;
    private boolean fullscreen;
    private boolean lensChosen; // the operator picked a camera, so stop claiming the back one
    private boolean binding; // true while the switches are being set from the service
    private int lastTotal = -1;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) enableDetection(true);
                else {
                    setChecked(enableSwitch, false);
                    showCameraMessage(getString(R.string.camera_permission_needed));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_object_detection, R.id.nav_object, 0);
        setupRichHeader(R.drawable.ic_cube, R.string.nav_object, R.string.object_subtitle);
        ((ImageView) findViewById(R.id.header_icon)).setColorFilter(color(R.color.orange), PorterDuff.Mode.SRC_IN);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);
        CocoLabels.init(this); // class names in the app language

        resultList = findViewById(R.id.result_list);
        countList = findViewById(R.id.count_list);
        totalCount = findViewById(R.id.total_count);
        totalTrend = findViewById(R.id.total_trend);
        feedInfo = findViewById(R.id.feed_info);
        ((TextView) findViewById(R.id.feed_live))
                .setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_red, 0, 0, 0);
        setIcon(findViewById(R.id.settings_title), R.drawable.ic_crosshair, 22, 0, Gravity.START);

        previewView = findViewById(R.id.camera_preview);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlay = findViewById(R.id.tracking_overlay);
        cameraMessage = findViewById(R.id.camera_message);

        feedSource = findViewById(R.id.feed_source);
        feedSource.setOnClickListener(this::showCameraMenu);

        ImageView fullscreenButton = findViewById(R.id.feed_fullscreen);
        fullscreenButton.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());

        setupSettings();
        render(new ArrayList<>());
        bindRobotService(serviceListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRichHeader();
        RobotService service = getRobotService();
        if (service == null) return;
        // the camera is shared: claim the back lens only when nobody has chosen otherwise
        if (!lensChosen && !service.isFaceEnabled()) service.setLensFacing(CameraSelector.LENS_FACING_BACK);
        service.attachPreview(previewView.getSurfaceProvider());
    }

    @Override
    protected void onPause() {
        RobotService service = getRobotService();
        if (service != null) service.detachPreview(previewView.getSurfaceProvider());
        super.onPause();
    }

    @Override
    protected void onConnectionChanged() {
        refreshRichHeader();
    }

    // ---- service ---------------------------------------------------------------------------

    @Override
    protected void onRobotServiceReady(RobotService service) {
        binding = true;
        enableSwitch.setChecked(service.isDetectionEnabled());
        binding = false;

        applySettings(service);
        service.attachPreview(previewView.getSurfaceProvider());
        renderServiceState();
        render(service.getLastObjects());
        overlay.setObjects(service.getLastObjects(), service.getFrameWidth(), service.getFrameHeight(),
                service.getLensFacing() == CameraSelector.LENS_FACING_FRONT);
    }

    private final RobotService.Listener serviceListener = new RobotService.Adapter() {
        @Override
        public void onObjects(List<ObjectTracker.Snapshot> objects, int width, int height, long inferenceMs) {
            RobotService service = getRobotService();
            boolean mirrored = service != null
                    && service.getLensFacing() == CameraSelector.LENS_FACING_FRONT;
            overlay.setObjects(objects, width, height, mirrored);
            render(objects);
            if (service != null && !service.isLoadingDetector()) {
                feedInfo.setText(service.getDetectorModel() == null ? getString(R.string.no_detector)
                        : getString(R.string.inference_info, service.getDetectorModel(), (int) inferenceMs));
            }
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

    /** Shows what the service is doing: loading, running, or why it cannot detect. */
    private void renderServiceState() {
        RobotService service = getRobotService();
        if (service == null) return;

        binding = true;
        enableSwitch.setChecked(service.isDetectionEnabled());
        binding = false;
        findViewById(R.id.feed_live).setAlpha(service.isDetectionEnabled() ? 1f : 0.4f);
        updateCameraSource();
        if (!service.isDetectionEnabled()) overlay.clear();

        DetectionAnalyzer analyzer = service.getDetectionAnalyzer();
        if (service.isLoadingDetector()) {
            feedInfo.setText(R.string.loading_model_any);
            cameraMessage.setVisibility(View.GONE);
        } else if (service.getDetectorError() != null) {
            // a model that is there but unusable is a different problem from a missing one,
            // and the reason only shows up here
            showCameraMessage(getString(R.string.detector_failed,
                    String.valueOf(service.getDetectorModel()), service.getDetectorError()));
        } else if (YoloSegmenter.listModels(this).isEmpty()) {
            showCameraMessage(getString(R.string.detector_missing));
        } else if (!service.isDetectionEnabled()) {
            // the model is only loaded once detection is switched on
            showCameraMessage(getString(R.string.detection_disabled));
        } else {
            cameraMessage.setVisibility(View.GONE);
            if (analyzer != null) {
                maskSwitch.setEnabled(analyzer.hasMasks()); // a plain detector has no masks
            }
        }
    }

    /** Front or back camera, shared with face recognition. */
    private void showCameraMenu(View anchor) {
        final RobotService service = getRobotService();
        if (service == null) return;
        final String[] sources = getResources().getStringArray(R.array.camera_sources);
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < sources.length; i++) menu.getMenu().add(0, i, i, sources[i]);
        menu.setOnMenuItemClickListener(item -> {
            lensChosen = true;
            service.setLensFacing(item.getItemId() == 0 ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK);
            updateCameraSource();
            return true;
        });
        menu.show();
    }

    /** Names the camera in use, as the Face page does. */
    private void updateCameraSource() {
        RobotService service = getRobotService();
        if (service == null) return;
        int index = service.getLensFacing() == CameraSelector.LENS_FACING_FRONT ? 0 : 1;
        feedSource.setText(getResources().getStringArray(R.array.camera_sources)[index]);
    }

    /** Gives the camera the whole page, as the Face page does, and back again. */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        int visibility = fullscreen ? View.GONE : View.VISIBLE;
        findViewById(R.id.panel_result).setVisibility(visibility);
        findViewById(R.id.columns_bottom).setVisibility(visibility);
        ((ImageView) findViewById(R.id.feed_fullscreen)).setImageResource(
                fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    private void showCameraMessage(String message) {
        cameraMessage.setText(message);
        cameraMessage.setVisibility(View.VISIBLE);
        overlay.clear();
    }

    /** Turns detection on, asking for the camera first if the app does not have it yet. */
    private void enableDetection(boolean on) {
        RobotService service = getRobotService();
        if (service == null) return;
        if (!on) {
            service.setDetectionEnabled(false);
            return;
        }
        ensureNotificationPermission();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            service.setDetectionEnabled(true);
            service.attachPreview(previewView.getSurfaceProvider());
        } else if (!permissionAsked) {
            permissionAsked = true;
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            // the system stops showing the dialog after repeated denials: open app settings
            setChecked(enableSwitch, false);
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    // ---- settings --------------------------------------------------------------------------

    private void setupSettings() {
        LinearLayout toggles = findViewById(R.id.toggle_list);
        enableSwitch = addToggle(toggles, R.drawable.ic_scan, R.string.enable_detection,
                this::enableDetection);
        addToggle(toggles, R.drawable.ic_crosshair, R.string.show_boxes, overlay::setShowBoxes);
        addToggle(toggles, R.drawable.ic_note, R.string.show_labels, overlay::setShowLabels);
        maskSwitch = addToggle(toggles, R.drawable.ic_layers, R.string.show_masks, on -> {
            overlay.setShowMasks(on);
            DetectionAnalyzer analyzer = analyzer();
            if (analyzer != null) analyzer.setMasksEnabled(on);
        });
        trackSwitch = addToggle(toggles, R.drawable.ic_follow, R.string.track_objects, on -> {
            overlay.setShowTrails(on);
            DetectionAnalyzer analyzer = analyzer();
            if (analyzer != null) analyzer.setTrackingEnabled(on);
        });

        final Spinner threshold = bindSpinner(R.id.spinner_threshold, R.array.confidence_thresholds, 2);
        threshold.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                float value = Float.parseFloat(parent.getItemAtPosition(position).toString());
                DetectionAnalyzer analyzer = analyzer();
                if (analyzer != null) analyzer.setConfidence(value);
                RobotSession.get().send("DETECTION THRESHOLD " + value);
            }
        });
    }

    /** Pushes the switches onto the analyzer the service is using. */
    private void applySettings(RobotService service) {
        DetectionAnalyzer analyzer = service.getDetectionAnalyzer();
        if (analyzer == null) return;
        analyzer.setMasksEnabled(maskSwitch.isChecked());
        analyzer.setTrackingEnabled(trackSwitch.isChecked());
        Spinner threshold = findViewById(R.id.spinner_threshold);
        if (threshold.getSelectedItem() != null) {
            analyzer.setConfidence(Float.parseFloat(threshold.getSelectedItem().toString()));
        }
    }

    private DetectionAnalyzer analyzer() {
        RobotService service = getRobotService();
        return service == null ? null : service.getDetectionAnalyzer();
    }

    private void setChecked(Switch toggle, boolean checked) {
        binding = true;
        toggle.setChecked(checked);
        binding = false;
    }

    private interface OnToggle {
        void onToggle(boolean on);
    }

    private Switch addToggle(LinearLayout parent, int icon, int title, final OnToggle onToggle) {
        // reuses the Voice Control switch row (subtitle hidden)
        View row = LayoutInflater.from(this).inflate(R.layout.item_voice_toggle_row, parent, false);
        ImageView iconView = row.findViewById(R.id.toggle_icon);
        iconView.setImageResource(icon);
        iconView.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        ((TextView) row.findViewById(R.id.toggle_title)).setText(title);
        row.findViewById(R.id.toggle_subtitle).setVisibility(View.GONE);
        Switch toggle = row.findViewById(R.id.toggle_switch);
        toggle.setChecked(true);
        final String name = getString(title);
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (binding) return; // the switch is only reflecting the service
            onToggle.onToggle(on);
            RobotSession.get().send("DETECTION " + name.toUpperCase(Locale.US).replace(' ', '_') + (on ? " ON" : " OFF"));
        });
        parent.addView(row);
        return toggle;
    }

    private Spinner bindSpinner(int id, int entries, int selection) {
        Spinner spinner = findViewById(id);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entries, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        return spinner;
    }

    private abstract static class SimpleItemListener implements AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    // ---- results ---------------------------------------------------------------------------

    private void render(List<ObjectTracker.Snapshot> objects) {
        renderResults(objects);
        renderCounts(objects);
    }

    /** Detection Result: one row per tracked object, most confident first. */
    private void renderResults(List<ObjectTracker.Snapshot> objects) {
        resultList.removeAllViews();
        if (objects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(emptyMessage());
            empty.setTextColor(color(R.color.text_secondary));
            empty.setTextSize(14);
            empty.setPadding(dp(8), dp(12), 0, 0);
            resultList.addView(empty);
            return;
        }
        for (ObjectTracker.Snapshot object : objects) {
            String label = object.id > 0
                    ? getString(R.string.track_label, object.id, object.label) : object.label;
            addRow(resultList, CocoLabels.color(object.classId), label,
                    getString(R.string.confidence_value, object.score));
        }
    }

    private int emptyMessage() {
        DetectionAnalyzer analyzer = analyzer();
        if (analyzer == null || !analyzer.canDetect()) return R.string.no_detector;
        RobotService service = getRobotService();
        if (service != null && !service.isDetectionEnabled()) return R.string.detection_disabled;
        return R.string.no_detections;
    }

    /** Detected Objects: how many are tracked right now, and how many of each class. */
    private void renderCounts(List<ObjectTracker.Snapshot> objects) {
        int total = objects.size();
        totalCount.setText(String.valueOf(total));
        if (lastTotal >= 0 && total != lastTotal) {
            boolean up = total > lastTotal;
            totalTrend.setText(up ? "▲" : "▼");
            totalTrend.setTextColor(color(up ? R.color.teal : R.color.red));
        } else if (lastTotal < 0) {
            totalTrend.setText("▲");
            totalTrend.setTextColor(color(R.color.teal));
        }
        lastTotal = total;

        // classes in view, most common first; insertion order keeps the list from jumping around
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (ObjectTracker.Snapshot object : objects) {
            Integer count = counts.get(object.classId);
            counts.put(object.classId, count == null ? 1 : count + 1);
        }
        countList.removeAllViews();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            int classId = entry.getKey();
            addRow(countList, CocoLabels.color(classId), CocoLabels.name(classId),
                    String.valueOf(entry.getValue()));
        }
        if (counts.isEmpty()) {
            DetectionAnalyzer analyzer = analyzer();
            addRow(countList, color(R.color.text_muted), getString(R.string.total_seen),
                    String.valueOf(analyzer == null ? 0 : analyzer.getTotalSeen()));
        }
    }

    private void addRow(LinearLayout list, int markerColor, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);
        row.setMinimumHeight(dp(30));

        View marker = new View(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(markerColor);
        shape.setCornerRadius(dp(3));
        marker.setBackground(shape);
        row.addView(marker, new LinearLayout.LayoutParams(dp(14), dp(14)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(color(R.color.white));
        name.setTextSize(15);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(dp(18));
        row.addView(name, nameLp);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(color(R.color.white));
        valueView.setTextSize(15);
        row.addView(valueView);

        // fixed row height: the panel keeps its size and the list scrolls inside it
        list.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
