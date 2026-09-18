package com.falcon.robot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.falcon.robot.detect.CocoLabels;
import com.falcon.robot.detect.DetectionAnalyzer;
import com.falcon.robot.detect.ObjectTracker;
import com.falcon.robot.detect.YoloSegmenter;
import com.falcon.robot.widget.TrackingOverlayView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Object Detection page: live camera (CameraX) segmented by YOLOv8-seg
 * ({@link YoloSegmenter}) and followed across frames by {@link ObjectTracker}, so every object
 * keeps an id while it is in view.
 *
 * <p>Without a detector model the camera still runs; the panels simply stay empty and the page
 * says which file is missing.
 */
public class ObjectDetectionActivity extends BaseActivity {

    /** Analysis resolution: the model letterboxes this down to its own input size. */
    private static final Size ANALYSIS_SIZE = new Size(640, 480);

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private DetectionAnalyzer analyzer;
    private ProcessCameraProvider cameraProvider;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean permissionAsked;
    private boolean loadingModel;
    private String modelName;    // the model in use, null when none loaded
    private String pendingModel; // the model being loaded right now
    private int lastTotal = -1;

    private PreviewView previewView;
    private TrackingOverlayView overlay;
    private TextView cameraMessage;
    private LinearLayout resultList;
    private LinearLayout countList;
    private TextView totalCount;
    private TextView totalTrend;
    private TextView feedInfo;
    private Spinner modelSpinner;
    private Switch enableSwitch;
    private Switch boxSwitch;
    private Switch labelSwitch;
    private Switch maskSwitch;
    private Switch trackSwitch;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else showCameraMessage(getString(R.string.camera_permission_needed));
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

        setupSettings();
        render(new ArrayList<>());
        loadModelThenStartCamera(selectedModel());
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
    protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        final DetectionAnalyzer current = analyzer;
        if (current != null) cameraExecutor.execute(current::close);
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    // ---- model + camera --------------------------------------------------------------------

    /** Loads the detector off the UI thread, then opens the camera. */
    private void loadModelThenStartCamera(final String model) {
        pendingModel = model;
        if (model == null) {
            analyzer = new DetectionAnalyzer(null, analyzerListener);
            applySettingsToAnalyzer();
            requestCameraOrStart();
            return;
        }
        loadingModel = true;
        feedInfo.setText(getString(R.string.loading_model, model));
        final DetectionAnalyzer previous = analyzer;
        cameraExecutor.execute(() -> {
            if (previous != null) previous.close();
            YoloSegmenter segmenter = null;
            String error = null;
            try {
                segmenter = new YoloSegmenter(this, model, 4);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final YoloSegmenter loaded = segmenter;
            final String message = error;
            runOnUiThread(() -> {
                loadingModel = false;
                if (isDestroyed()) {
                    if (loaded != null) cameraExecutor.execute(loaded::close);
                    return;
                }
                modelName = loaded != null ? model : null;
                analyzer = new DetectionAnalyzer(loaded, analyzerListener);
                applySettingsToAnalyzer();
                if (message != null) toast(getString(R.string.detector_failed, model, message));
                if (cameraProvider != null) bindCamera();
                else requestCameraOrStart();
            });
        });
    }

    private void requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (!permissionAsked) {
            permissionAsked = true;
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            // the system stops showing the dialog after repeated denials: open app settings
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                showCameraMessage(getString(R.string.camera_unavailable));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null || analyzer == null || isDestroyed()) return;
        cameraProvider.unbindAll();
        overlay.clear();

        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        try {
            if (!cameraProvider.hasCamera(selector)) {
                // fall back to the other lens (e.g. tablets without a back camera)
                lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                        ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
                selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                if (!cameraProvider.hasCamera(selector)) {
                    showCameraMessage(getString(R.string.camera_unavailable));
                    return;
                }
            }
        } catch (Exception e) {
            showCameraMessage(getString(R.string.camera_unavailable));
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(ANALYSIS_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, analyzer);

        try {
            cameraProvider.bindToLifecycle(this, selector, preview, analysis);
            if (analyzer.canDetect()) cameraMessage.setVisibility(View.GONE);
        } catch (Exception e) {
            showCameraMessage(getString(R.string.camera_unavailable));
        }
    }

    private void showCameraMessage(String message) {
        cameraMessage.setText(message);
        cameraMessage.setVisibility(View.VISIBLE);
        overlay.clear();
    }

    private final DetectionAnalyzer.Listener analyzerListener = (objects, width, height, inferenceMs) -> {
        overlay.setObjects(objects, width, height, lensFacing == CameraSelector.LENS_FACING_FRONT);
        render(objects);
        if (!loadingModel) {
            feedInfo.setText(modelName == null ? getString(R.string.no_detector)
                    : getString(R.string.inference_info, modelName, (int) inferenceMs));
        }
    };

    // ---- settings --------------------------------------------------------------------------

    private void setupSettings() {
        LinearLayout toggles = findViewById(R.id.toggle_list);
        enableSwitch = addToggle(toggles, R.drawable.ic_scan, R.string.enable_detection, on -> {
            if (analyzer != null) analyzer.setDetectionEnabled(on);
            findViewById(R.id.feed_live).setAlpha(on ? 1f : 0.4f);
        });
        boxSwitch = addToggle(toggles, R.drawable.ic_crosshair, R.string.show_boxes, overlay::setShowBoxes);
        labelSwitch = addToggle(toggles, R.drawable.ic_note, R.string.show_labels, overlay::setShowLabels);
        maskSwitch = addToggle(toggles, R.drawable.ic_layers, R.string.show_masks, on -> {
            overlay.setShowMasks(on);
            if (analyzer != null) analyzer.setMasksEnabled(on);
        });
        trackSwitch = addToggle(toggles, R.drawable.ic_follow, R.string.track_objects, on -> {
            overlay.setShowTrails(on);
            if (analyzer != null) analyzer.setTrackingEnabled(on);
        });

        List<CharSequence> models = new ArrayList<>(YoloSegmenter.listModels(this));
        if (models.isEmpty()) models.add(getString(R.string.none));
        modelSpinner = findViewById(R.id.spinner_model);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this, R.layout.item_spinner, models);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        modelSpinner.setAdapter(adapter);
        modelSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // fires once on the initial selection too, which the model already being
                // loaded takes care of
                String selected = selectedModel();
                if (selected == null || selected.equals(pendingModel)) return;
                RobotSession.get().send("DETECTION MODEL " + selected);
                loadModelThenStartCamera(selected);
            }
        });

        final Spinner threshold = bindSpinner(R.id.spinner_threshold, R.array.confidence_thresholds, 2);
        threshold.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                float value = Float.parseFloat(parent.getItemAtPosition(position).toString());
                if (analyzer != null) analyzer.setConfidence(value);
                RobotSession.get().send("DETECTION THRESHOLD " + value);
            }
        });
    }

    /** Pushes the switch and spinner state onto a freshly created analyzer. */
    private void applySettingsToAnalyzer() {
        analyzer.setDetectionEnabled(enableSwitch.isChecked());
        analyzer.setMasksEnabled(maskSwitch.isChecked());
        analyzer.setTrackingEnabled(trackSwitch.isChecked());
        Spinner threshold = findViewById(R.id.spinner_threshold);
        if (threshold.getSelectedItem() != null) {
            analyzer.setConfidence(Float.parseFloat(threshold.getSelectedItem().toString()));
        }
        if (!analyzer.canDetect()) {
            showCameraMessage(getString(R.string.detector_missing, YoloSegmenter.DEFAULT_MODEL));
        } else {
            cameraMessage.setVisibility(View.GONE);
            maskSwitch.setEnabled(analyzer.hasMasks()); // a plain detector has no masks to show
        }
    }

    /** Selected file name, or null when there is no model to choose. */
    private String selectedModel() {
        Object selected = modelSpinner == null ? null : modelSpinner.getSelectedItem();
        if (selected == null) {
            List<String> models = YoloSegmenter.listModels(this);
            return models.isEmpty() ? null : models.get(0);
        }
        String name = selected.toString();
        return name.endsWith(".tflite") ? name : null;
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
        for (int i = 0; i < objects.size(); i++) {
            ObjectTracker.Snapshot object = objects.get(i);
            String label = object.id > 0
                    ? getString(R.string.track_label, object.id, object.label) : object.label;
            addRow(resultList, CocoLabels.color(object.classId), label,
                    getString(R.string.confidence_value, object.score));
        }
    }

    private int emptyMessage() {
        if (analyzer == null || !analyzer.canDetect()) return R.string.no_detector;
        if (enableSwitch != null && !enableSwitch.isChecked()) return R.string.detection_disabled;
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
