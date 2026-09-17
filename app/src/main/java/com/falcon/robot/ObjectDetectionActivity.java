package com.falcon.robot;

import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import com.falcon.robot.widget.DetectionView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Object Detection page (design 06_Object_Detection): camera frame with bounding boxes,
 * detection results, detection settings and per-class counts.
 *
 * <p>The frame is a still lobby photo with simulated detections placed on the objects in it;
 * replace them with the camera stream and model output.
 */
public class ObjectDetectionActivity extends BaseActivity {

    private static final int COLOR_ROBOT = 0xFFFF4D5E;
    private static final int COLOR_PLANT = 0xFF22D3EE;
    private static final int COLOR_DESK = 0xFFFFC53D;
    private static final int COLOR_DOOR = 0xFFB45CFF;

    /** Classes shown in Detected Objects, in display order. */
    private static final int[] CLASS_NAMES = {R.string.cls_robot, R.string.cls_plant, R.string.cls_desk, R.string.cls_door};
    private static final int[] CLASS_COLORS = {COLOR_ROBOT, COLOR_PLANT, COLOR_DESK, COLOR_DOOR};

    private final Random random = new Random();

    private DetectionView detectionView;
    private LinearLayout resultList;
    private LinearLayout countList;
    private TextView totalCount;
    private TextView totalTrend;
    private TextView feedInfo;
    private Spinner model;
    private Switch enableSwitch;
    private int lastTotal = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_object_detection, R.id.nav_object, 0);
        setupRichHeader(R.drawable.ic_cube, R.string.nav_object, R.string.object_subtitle);
        ((ImageView) findViewById(R.id.header_icon)).setColorFilter(color(R.color.orange), PorterDuff.Mode.SRC_IN);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        resultList = findViewById(R.id.result_list);
        countList = findViewById(R.id.count_list);
        totalCount = findViewById(R.id.total_count);
        totalTrend = findViewById(R.id.total_trend);
        feedInfo = findViewById(R.id.feed_info);
        TextView live = findViewById(R.id.feed_live);
        live.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_red, 0, 0, 0);
        setIcon(findViewById(R.id.settings_title), R.drawable.ic_crosshair, 22, 0, Gravity.START);

        detectionView = findViewById(R.id.detection_view);
        detectionView.setImage(R.drawable.remote_camera_front);
        detectionView.setDetections(sampleDetections());

        setupSettings();
        detectionView.setOnDetectionsChangedListener(this::render);
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

    /** Detections placed on the objects in remote_camera_front.png (651 x 350 px). */
    private List<DetectionView.Detection> sampleDetections() {
        List<DetectionView.Detection> list = new ArrayList<>();
        list.add(box(R.string.cls_robot, COLOR_ROBOT, 0.94f, 242, 138, 362, 350));
        list.add(box(R.string.cls_plant, COLOR_PLANT, 0.88f, 0, 70, 122, 345));
        list.add(box(R.string.cls_plant, COLOR_PLANT, 0.81f, 588, 195, 651, 345));
        list.add(box(R.string.cls_plant, COLOR_PLANT, 0.74f, 400, 105, 465, 185));
        list.add(box(R.string.cls_desk, COLOR_DESK, 0.69f, 452, 172, 512, 235));
        list.add(box(R.string.cls_plant, COLOR_PLANT, 0.63f, 500, 140, 552, 262));
        list.add(box(R.string.cls_door, COLOR_DOOR, 0.58f, 560, 70, 596, 290));
        list.add(box(R.string.cls_plant, COLOR_PLANT, 0.52f, 205, 118, 252, 183));
        return list;
    }

    private DetectionView.Detection box(int label, int color, float confidence, float l, float t, float r, float b) {
        float w = 651f;
        float h = 350f;
        return new DetectionView.Detection(getString(label), color, confidence, l / w, t / h, r / w, b / h);
    }

    // ---- settings --------------------------------------------------------------------------

    private void setupSettings() {
        LinearLayout toggles = findViewById(R.id.toggle_list);
        enableSwitch = addToggle(toggles, R.drawable.ic_scan, R.string.enable_detection, on -> {
            detectionView.setDetecting(on);
            findViewById(R.id.feed_live).setAlpha(on ? 1f : 0.4f);
        });
        addToggle(toggles, R.drawable.ic_crosshair, R.string.show_boxes, detectionView::setShowBoxes);
        addToggle(toggles, R.drawable.ic_note, R.string.show_labels, detectionView::setShowLabels);
        addToggle(toggles, R.drawable.ic_bolt, R.string.realtime_inference, detectionView::setLive);

        model = bindSpinner(R.id.spinner_model, R.array.object_models, 0);
        model.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RobotSession.get().send("DETECTION MODEL " + parent.getItemAtPosition(position));
                render(detectionView.getVisibleDetections());
            }
        });

        final Spinner threshold = bindSpinner(R.id.spinner_threshold, R.array.confidence_thresholds, 2);
        threshold.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                float value = Float.parseFloat(parent.getItemAtPosition(position).toString());
                detectionView.setThreshold(value);
                RobotSession.get().send("DETECTION THRESHOLD " + value);
            }
        });
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

    private void render(List<DetectionView.Detection> visible) {
        renderResults(visible);
        renderCounts(visible);
        if (model != null) {
            feedInfo.setText(getString(R.string.inference_info, model.getSelectedItem(), 18 + random.nextInt(9)));
        }
    }

    /** Detection Result: every visible detection, highest confidence first. */
    private void renderResults(List<DetectionView.Detection> visible) {
        resultList.removeAllViews();
        if (visible.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(enableSwitch == null || enableSwitch.isChecked() ? R.string.no_detections : R.string.detection_disabled);
            empty.setTextColor(color(R.color.text_secondary));
            empty.setTextSize(14);
            empty.setPadding(dp(8), dp(12), 0, 0);
            resultList.addView(empty);
            return;
        }
        List<DetectionView.Detection> sorted = new ArrayList<>(visible);
        Collections.sort(sorted, (a, b) -> Float.compare(b.confidence, a.confidence));
        for (DetectionView.Detection d : sorted) {
            addRow(resultList, d.color, d.label, getString(R.string.confidence_value, d.confidence));
        }
    }

    /** Detected Objects: total with trend, and a count for every class. */
    private void renderCounts(List<DetectionView.Detection> visible) {
        int total = visible.size();
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

        countList.removeAllViews();
        for (int i = 0; i < CLASS_NAMES.length; i++) {
            String name = getString(CLASS_NAMES[i]);
            int count = 0;
            for (DetectionView.Detection d : visible) if (d.label.equals(name)) count++;
            addRow(countList, CLASS_COLORS[i], name, String.valueOf(count));
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

        // rows share the panel height, but never shrink below 30dp (the list scrolls with the page)
        list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
