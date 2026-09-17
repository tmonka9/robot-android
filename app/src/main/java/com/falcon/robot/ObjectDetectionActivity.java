package com.falcon.robot;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.falcon.robot.widget.DetectionView;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Object detection page. Detections are simulated until a camera/model is wired up. */
public class ObjectDetectionActivity extends BaseActivity {

    private final Map<String, TextView> countViews = new HashMap<>();
    private DetectionView detectionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_object_detection, R.id.nav_object, R.string.nav_object);
        setupColumns();

        detectionView = findViewById(R.id.detection_view);

        Switch showBoxes = findViewById(R.id.show_boxes);
        showBoxes.setOnCheckedChangeListener((button, checked) -> detectionView.setShowBoxes(checked));

        final TextView thresholdValue = findViewById(R.id.threshold_value);
        SeekBar threshold = findViewById(R.id.threshold_seek);
        SimpleSeekListener thresholdListener = new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 100f;
                thresholdValue.setText(String.format(Locale.US, "%.2f", value));
                detectionView.setThreshold(value);
            }
        };
        threshold.setOnSeekBarChangeListener(thresholdListener);
        thresholdListener.onProgressChanged(threshold, threshold.getProgress(), false);

        Spinner mode = findViewById(R.id.detection_mode);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.detection_modes, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        mode.setAdapter(adapter);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                detectionView.setLive(position == 0); // 0 = Real-time, 1 = Paused
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        buildObjectList();
        detectionView.setOnDetectionsChangedListener(this::updateCounts);
    }

    /** One row per category: colored dot, name, visible count. */
    private void buildObjectList() {
        LinearLayout list = findViewById(R.id.detected_list);
        float density = getResources().getDisplayMetrics().density;
        int rowHeight = Math.round(40 * density);
        int dotSize = Math.round(10 * density);

        for (DetectionView.Detection category : detectionView.getCategories()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(category.color);
            dot.setBackground(circle);
            row.addView(dot, new LinearLayout.LayoutParams(dotSize, dotSize));

            TextView name = new TextView(this);
            name.setText(category.label);
            name.setTextColor(color(R.color.text_primary));
            name.setTextSize(14);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.setMarginStart(Math.round(12 * density));
            row.addView(name, nameLp);

            TextView count = new TextView(this);
            count.setTextColor(color(R.color.text_primary));
            count.setTextSize(14);
            row.addView(count);
            countViews.put(category.label, count);

            list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight));
        }
    }

    private void updateCounts(List<DetectionView.Detection> visible) {
        Map<String, Integer> counts = new HashMap<>();
        for (DetectionView.Detection d : visible) {
            Integer c = counts.get(d.label);
            counts.put(d.label, c == null ? 1 : c + 1);
        }
        for (Map.Entry<String, TextView> entry : countViews.entrySet()) {
            Integer c = counts.get(entry.getKey());
            entry.getValue().setText(String.valueOf(c == null ? 0 : c));
        }
    }
}
