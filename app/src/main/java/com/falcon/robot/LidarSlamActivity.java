package com.falcon.robot;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.falcon.robot.widget.LidarMapView;

import java.util.Locale;
import java.util.Random;

/** LiDAR SLAM page: mapping / localization / navigation. Scan data is simulated. */
public class LidarSlamActivity extends BaseActivity {

    private static final long TICK_MS = 500;

    private enum Mode { MAPPING, LOCALIZATION, NAVIGATION }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private LidarMapView map;
    private TextView status;
    private TextView points;
    private TextView area;
    private TextView pose;
    private TextView scanToggle;
    private View goalsPanel;
    private TextView[] modeTabs;

    private Mode mode = Mode.MAPPING;
    private boolean scanning = true;
    private float poseX = 7.2f;
    private float poseY = 8.6f;
    private int poseTheta = 45;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (scanning) {
                poseX += (random.nextFloat() - 0.5f) * 0.04f;
                poseY += (random.nextFloat() - 0.5f) * 0.04f;
                poseTheta = (poseTheta + random.nextInt(5) - 2 + 360) % 360;
                refreshStats();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_lidar_slam, R.id.nav_lidar, R.string.nav_lidar);
        setupColumns();

        map = findViewById(R.id.lidar_map);
        status = findViewById(R.id.lidar_status);
        points = findViewById(R.id.stat_points);
        area = findViewById(R.id.stat_area);
        pose = findViewById(R.id.stat_pose);
        scanToggle = findViewById(R.id.btn_scan_toggle);
        goalsPanel = findViewById(R.id.goals_panel);
        ((TextView) findViewById(R.id.stat_rate)).setText(R.string.scan_rate_value);

        modeTabs = new TextView[] {
                findViewById(R.id.mode_mapping),
                findViewById(R.id.mode_localization),
                findViewById(R.id.mode_navigation),
        };
        for (int i = 0; i < modeTabs.length; i++) {
            final Mode target = Mode.values()[i];
            modeTabs[i].setOnClickListener(v -> selectMode(target));
        }

        scanToggle.setOnClickListener(v -> setScanning(!scanning));
        findViewById(R.id.btn_save_map).setOnClickListener(v -> {
            if (sendCommand("SLAM SAVE_MAP")) toast(R.string.map_saved);
        });
        findViewById(R.id.btn_clear_map).setOnClickListener(v -> {
            RobotSession.get().send("SLAM CLEAR_MAP");
            map.setMapVisible(false);
            refreshStats();
            toast(R.string.map_cleared);
        });

        View.OnClickListener goal = v -> {
            String name = ((TextView) v).getText().toString();
            if (!sendCommand("NAV GOTO " + name.toUpperCase(Locale.US))) return;
            setStatus(status, getString(R.string.navigating_to, name), R.drawable.dot_teal);
        };
        for (int id : new int[] {R.id.goal_dock, R.id.goal_entrance, R.id.goal_kitchen, R.id.goal_office}) {
            findViewById(id).setOnClickListener(goal);
        }

        selectMode(Mode.MAPPING);
        setScanning(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void selectMode(Mode mode) {
        this.mode = mode;
        for (int i = 0; i < modeTabs.length; i++) {
            modeTabs[i].setSelected(i == mode.ordinal());
        }
        goalsPanel.setVisibility(mode == Mode.NAVIGATION ? View.VISIBLE : View.GONE);
        map.setShowRoute(mode == Mode.NAVIGATION);
        RobotSession.get().send("SLAM MODE " + mode);
        refreshStatus();
    }

    private void setScanning(boolean scanning) {
        this.scanning = scanning;
        if (scanning) map.setMapVisible(true); // a new scan rebuilds the map
        map.setScanning(scanning);
        RobotSession.get().send("SLAM SCAN " + (scanning ? "START" : "STOP"));
        scanToggle.setText(scanning ? R.string.stop_scan : R.string.start_scan);
        scanToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                scanning ? R.drawable.ic_stop : R.drawable.ic_play, 0, 0, 0);
        refreshStatus();
        refreshStats();
    }

    private void refreshStatus() {
        String modeName = modeTabs[mode.ordinal()].getText().toString();
        if (scanning) {
            setStatus(status, getString(R.string.lidar_scanning, modeName), R.drawable.dot_teal);
        } else {
            setStatus(status, getString(R.string.lidar_paused, modeName), R.drawable.dot_amber);
        }
    }

    private void refreshStats() {
        points.setText(String.format(Locale.US, "%,d", map.getPointCount() * 40));
        area.setText(getString(R.string.map_area_value, map.getMapArea()));
        pose.setText(getString(R.string.pose_value, poseX, poseY, poseTheta));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
