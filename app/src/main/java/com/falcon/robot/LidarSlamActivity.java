package com.falcon.robot;

import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.falcon.robot.widget.CompassView;
import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.DPadView;
import com.falcon.robot.widget.LidarMapView;
import com.falcon.robot.widget.RadarSweepView;

import java.util.Locale;
import java.util.Random;

/**
 * LiDAR & SLAM page (design/slam.png): 3D / top / live map views, 2D map with legend, LiDAR
 * settings, SLAM status, robot pose, LiDAR scan, mapping and navigation controls, and a log.
 *
 * <p>SLAM data is simulated (points grow while mapping, the pose drives toward navigation
 * targets); commands go through {@link RobotSession}. Replace {@link #tick} with real data.
 */
public class LidarSlamActivity extends BaseActivity {

    private static final long TICK_MS = 500;
    private static final int MAX_LOG_LINES = 100;

    /** Artwork coordinates (slam_3d_view.png pixels) of the view tabs and tool buttons. */
    private static final RectF[] TAB_RECTS = {
            new RectF(8, 10, 107, 45), new RectF(117, 10, 226, 45), new RectF(236, 10, 350, 45),
    };
    private static final RectF[] TOOL_RECTS = {
            new RectF(732, 17, 774, 59), new RectF(732, 66, 774, 108),
            new RectF(732, 115, 774, 157), new RectF(732, 164, 774, 206),
    };

    /** Navigation goals: names and matching {x, y} positions in metres. */
    private static final int[] GOAL_NAMES = {
            R.string.goal_dock, R.string.goal_entrance, R.string.goal_kitchen, R.string.goal_office,
    };
    private static final float[][] GOAL_POSITIONS = {
            {-3.20f, 1.40f}, {4.10f, -2.60f}, {2.34f, -1.27f}, {-1.80f, 3.90f},
    };

    private final RobotSession session = RobotSession.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private CoverImageView view3d;
    private View viewAlt;
    private ImageView viewTop;
    private LidarMapView viewLive;
    private TextView[] tabs;
    private ImageView[] tools;
    private ImageView map2d;
    private LinearLayout logList;
    private ScrollView logScroll;
    private TextView startMapping;
    private TextView poseX;
    private TextView poseY;
    private TextView poseYaw;
    private CompassView compass;
    private RadarSweepView radarSweep;
    private TextView lidarPoints;
    private TextView lidarRange;
    private SeekBar rangeSeek;
    private Spinner resolution;
    private Spinner mappingMode;
    private Spinner updateRate;

    private boolean mapping = true;
    private boolean navigating;
    private boolean fullscreen;
    private boolean showPath = true;
    private float mapZoom = 1f;
    private int points = 68432;
    private float x = 2.34f;
    private float y = -1.27f;
    private float yaw = 132.6f;
    private float targetX;
    private float targetY;
    private int tickCount;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_lidar_slam, R.id.nav_lidar, 0);
        setupRichHeader(R.drawable.ic_lidar, R.string.lidar_title, R.string.lidar_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_left_bottom);
        setupColumns(R.id.columns_right_top);

        logList = findViewById(R.id.log_list);
        logScroll = findViewById(R.id.log_scroll);

        setupViews();
        setupMapPanel();
        setupSettings();
        setupStatusPanels();
        setupControls();
        seedLog();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRichHeader();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onConnectionChanged() {
        refreshRichHeader();
    }

    // ---- simulation ------------------------------------------------------------------------

    private void tick() {
        tickCount++;
        if (mapping) {
            points += 40 + random.nextInt(120);
            if (tickCount % 20 == 0) addLog(LOG_OK, getString(R.string.log_map_updated, points));
        }
        if (navigating) {
            float dx = targetX - x;
            float dy = targetY - y;
            float dist = (float) Math.hypot(dx, dy);
            float step = 0.12f;
            if (dist <= step) {
                x = targetX;
                y = targetY;
                navigating = false;
                addLog(LOG_OK, getString(R.string.log_reached));
            } else {
                x += dx / dist * step;
                y += dy / dist * step;
                yaw = normalize((float) Math.toDegrees(Math.atan2(dx, dy)));
                if (random.nextInt(40) == 0) {
                    addLog(LOG_ERROR, getString(R.string.log_obstacle, 10 + random.nextInt(20)));
                    addLog(LOG_WARN, getString(R.string.log_replanning));
                }
            }
        }
        refreshAll();
    }

    private static float normalize(float degrees) {
        float d = degrees % 360f;
        return d < 0 ? d + 360f : d;
    }

    // ---- 3D / top / live views -------------------------------------------------------------

    private void setupViews() {
        view3d = findViewById(R.id.view_3d);
        view3d.setFocus(0f, 0f, 1f, 1f); // keep tabs, tools and compass of the artwork visible
        findViewById(R.id.view_frame).setClipToOutline(true);
        viewAlt = findViewById(R.id.view_alt);
        viewTop = findViewById(R.id.view_top);
        viewLive = findViewById(R.id.view_live);

        int white = color(R.color.text_primary);
        tabs = new TextView[] {findViewById(R.id.tab_3d), findViewById(R.id.tab_top), findViewById(R.id.tab_map)};
        int[] tabIcons = {R.drawable.ic_cube, R.drawable.ic_top_view, R.drawable.ic_map};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            setIcon(tabs[i], tabIcons[i], 16, white, Gravity.START);
            tabs[i].setOnClickListener(v -> selectView(index));
        }
        tools = new ImageView[] {
                findViewById(R.id.tool_layers), findViewById(R.id.tool_center),
                findViewById(R.id.tool_snapshot), findViewById(R.id.tool_fullscreen),
        };
        for (ImageView tool : tools) tool.setColorFilter(white, PorterDuff.Mode.SRC_IN);

        tools[0].setOnClickListener(v -> {
            showPath = !showPath;
            viewLive.setShowRoute(showPath);
            addLog(LOG_INFO, getString(R.string.log_path_layer, getString(showPath ? R.string.on : R.string.off)));
        });
        tools[1].setOnClickListener(v -> {
            setMapZoom(1f);
            addLog(LOG_INFO, getString(R.string.log_centered));
        });
        tools[2].setOnClickListener(v -> {
            session.send("SLAM SNAPSHOT");
            toast(R.string.log_snapshot);
            addLog(LOG_INFO, getString(R.string.log_snapshot));
        });
        tools[3].setOnClickListener(v -> toggleFullscreen());

        // keep the native tabs/tools exactly over the artwork's own whenever the view resizes
        view3d.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeViewOverlays));
        selectView(0);
    }

    private void placeViewOverlays() {
        RectF rect = new RectF();
        for (int i = 0; i < tabs.length; i++) {
            if (!view3d.mapImageRect(TAB_RECTS[i], rect)) return;
            place(tabs[i], rect);
            tabs[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, rect.height() * 0.38f);
            tabs[i].setPadding(Math.round(rect.height() * 0.3f), 0, 0, 0);
        }
        for (int i = 0; i < tools.length; i++) {
            if (!view3d.mapImageRect(TOOL_RECTS[i], rect)) return;
            place(tools[i], rect);
            int pad = Math.round(rect.width() * 0.24f);
            tools[i].setPadding(pad, pad, pad, pad);
        }
    }

    private static void place(View view, RectF rect) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = Math.round(rect.width());
        lp.height = Math.round(rect.height());
        lp.setMarginStart(0);
        view.setLayoutParams(lp);
        view.setX(rect.left);
        view.setY(rect.top);
    }

    /** 0 = 3D artwork, 1 = top-down map, 2 = live animated map. */
    private void selectView(int index) {
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == index);
        viewAlt.setVisibility(index == 0 ? View.GONE : View.VISIBLE);
        viewTop.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        viewLive.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        int visibility = fullscreen ? View.GONE : View.VISIBLE;
        findViewById(R.id.column_right).setVisibility(visibility);
        findViewById(R.id.columns_left_bottom).setVisibility(visibility);
        tools[3].setImageResource(fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    // ---- map view + legend -----------------------------------------------------------------

    private void setupMapPanel() {
        setIcon(findViewById(R.id.map_title), R.drawable.ic_map, 22, color(R.color.cyan), Gravity.START);
        findViewById(R.id.map_frame).setClipToOutline(true);
        map2d = findViewById(R.id.map_2d);
        int white = color(R.color.text_primary);
        for (int id : new int[] {R.id.map_zoom_in, R.id.map_zoom_out, R.id.map_fit}) {
            ((ImageView) findViewById(id)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        }
        findViewById(R.id.map_zoom_in).setOnClickListener(v -> setMapZoom(mapZoom + 0.25f));
        findViewById(R.id.map_zoom_out).setOnClickListener(v -> setMapZoom(mapZoom - 0.25f));
        findViewById(R.id.map_fit).setOnClickListener(v -> setMapZoom(1f));
        buildLegend();
    }

    private void setMapZoom(float zoom) {
        mapZoom = Math.max(1f, Math.min(3f, zoom));
        map2d.setScaleX(mapZoom);
        map2d.setScaleY(mapZoom);
        viewTop.setScaleX(mapZoom);
        viewTop.setScaleY(mapZoom);
    }

    private void buildLegend() {
        LinearLayout legend = findViewById(R.id.map_legend);
        float density = getResources().getDisplayMetrics().density;
        int[][] items = {
                {R.string.legend_robot, 0xFF3FA0FF, 0},
                {R.string.legend_path, 0xFF22D3EE, 1},
                {R.string.legend_obstacle, 0xFFFF3B4E, 2},
                {R.string.legend_wall, 0xFF4ADE80, 2},
                {R.string.legend_explored, 0xFF3FA0FF, 2},
                {R.string.legend_unexplored, 0xFF4A5468, 2},
        };
        for (int[] item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            View marker;
            int w;
            int h;
            if (item[2] == 0) { // robot arrow
                ImageView arrow = new ImageView(this);
                arrow.setImageResource(R.drawable.ic_navigation);
                arrow.setRotation(90);
                arrow.setColorFilter(item[1], PorterDuff.Mode.SRC_IN);
                marker = arrow;
                w = h = Math.round(18 * density);
            } else {
                marker = new View(this);
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(item[1]);
                shape.setCornerRadius(2 * density);
                marker.setBackground(shape);
                w = Math.round(18 * density);
                h = Math.round((item[2] == 1 ? 3 : 14) * density);
            }
            FrameLayout box = new FrameLayout(this);
            box.addView(marker, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
            row.addView(box, new LinearLayout.LayoutParams(Math.round(22 * density), Math.round(22 * density)));

            TextView label = new TextView(this);
            label.setText(item[0]);
            label.setTextColor(color(R.color.text_primary));
            label.setTextSize(13);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.setMarginStart(Math.round(10 * density));
            row.addView(label, labelLp);

            legend.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    // ---- settings --------------------------------------------------------------------------

    private void setupSettings() {
        setIcon(findViewById(R.id.settings_title), R.drawable.ic_settings, 22, color(R.color.text_primary), Gravity.START);
        rangeSeek = findViewById(R.id.seek_range);
        final TextView rangeValue = findViewById(R.id.value_range);
        rangeValue.setText(getString(R.string.meters, rangeMeters()));
        rangeSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rangeValue.setText(getString(R.string.meters, rangeMeters()));
            }
        });
        resolution = bindSpinner(R.id.spinner_resolution, R.array.lidar_resolutions);
        mappingMode = bindSpinner(R.id.spinner_mapping_mode, R.array.mapping_modes);
        updateRate = bindSpinner(R.id.spinner_update_rate, R.array.update_rates);

        findViewById(R.id.btn_apply).setOnClickListener(v -> {
            String command = String.format(Locale.US, "LIDAR CONFIG range=%d res=%s mode=%s rate=%s",
                    rangeMeters(), resolution.getSelectedItem(), mappingMode.getSelectedItem(), updateRate.getSelectedItem());
            if (!sendCommand(command)) return;
            lidarRange.setText(getString(R.string.lidar_range_label, rangeMeters()));
            addLog(LOG_OK, getString(R.string.log_settings_applied, rangeMeters(),
                    resolution.getSelectedItem(), mappingMode.getSelectedItem(), updateRate.getSelectedItem()));
        });
    }

    private int rangeMeters() {
        return rangeSeek.getProgress() + 1;
    }

    private Spinner bindSpinner(int id, int entries) {
        Spinner spinner = findViewById(id);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entries, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        return spinner;
    }

    // ---- status, pose, LiDAR data ----------------------------------------------------------

    private void setupStatusPanels() {
        TextView slamTitle = findViewById(R.id.slam_title);
        slamTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_teal, 0, 0, 0);
        bindStatusRow(R.id.status_mapping, R.string.lidar_mapping, R.drawable.ic_map);
        bindStatusRow(R.id.status_localization, R.string.lidar_localization, R.drawable.ic_crosshair);
        bindStatusRow(R.id.status_navigation, R.string.lidar_navigation, R.drawable.ic_navigation);
        bindStatusRow(R.id.status_quality, R.string.map_quality, R.drawable.ic_star);

        setIcon(findViewById(R.id.pose_title), R.drawable.ic_robot, 24, color(R.color.blue_light), Gravity.START);
        poseX = findViewById(R.id.pose_x);
        poseY = findViewById(R.id.pose_y);
        poseYaw = findViewById(R.id.pose_yaw);
        compass = findViewById(R.id.pose_compass);

        setIcon(findViewById(R.id.lidar_title), R.drawable.ic_lidar, 22, 0, Gravity.START);
        lidarRange = findViewById(R.id.lidar_range);
        lidarRange.setText(getString(R.string.lidar_range_label, rangeMeters()));
        lidarPoints = findViewById(R.id.lidar_points);
        radarSweep = findViewById(R.id.radar_sweep);
    }

    private void bindStatusRow(int rowId, int label, int icon) {
        TextView labelView = findViewById(rowId).findViewById(R.id.row_label);
        labelView.setText(label);
        setIcon(labelView, icon, 18, color(R.color.blue_light), Gravity.START);
    }

    private void setStatusValue(int rowId, int text, int colorRes) {
        TextView value = findViewById(rowId).findViewById(R.id.row_value);
        value.setText(text);
        value.setTextColor(color(colorRes));
    }

    private void refreshAll() {
        setStatusValue(R.id.status_mapping, mapping ? R.string.state_running : R.string.state_paused,
                mapping ? R.color.teal : R.color.amber);
        setStatusValue(R.id.status_localization, R.string.state_stable, R.color.teal);
        setStatusValue(R.id.status_navigation, navigating ? R.string.state_active : R.string.state_ready,
                navigating ? R.color.cyan : R.color.teal);
        setStatusValue(R.id.status_quality, R.string.state_good, R.color.teal);

        poseX.setText(getString(R.string.pose_meters, x));
        poseY.setText(getString(R.string.pose_meters, y));
        poseYaw.setText(getString(R.string.pose_degrees, yaw));
        compass.setHeading(yaw);
        lidarPoints.setText(String.format(Locale.US, "%,d", points));
        radarSweep.setSweeping(mapping || navigating);
        viewLive.setScanning(mapping || navigating);
    }

    // ---- controls --------------------------------------------------------------------------

    private void setupControls() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.control_title), R.drawable.ic_crosshair, 22, 0, Gravity.START);
        setIcon(findViewById(R.id.log_title), R.drawable.ic_note, 20, white, Gravity.START);
        setIcon(findViewById(R.id.target_title), R.drawable.ic_place, 18, 0, Gravity.START);

        startMapping = findViewById(R.id.btn_start_mapping);
        startMapping.setOnClickListener(v -> {
            boolean start = !mapping;
            if (!sendCommand(start ? "SLAM MAPPING START" : "SLAM MAPPING PAUSE")) return;
            mapping = start;
            addLog(LOG_INFO, getString(start ? R.string.log_mapping_started : R.string.log_mapping_paused));
            refreshMappingButton();
            refreshAll();
        });
        refreshMappingButton();

        TextView stop = findViewById(R.id.btn_stop);
        setIcon(stop, R.drawable.ic_square, 18, white, Gravity.START);
        stop.setOnClickListener(v -> {
            session.send("SLAM STOP"); // stop is never blocked by the connect prompt
            mapping = false;
            navigating = false;
            addLog(LOG_WARN, getString(R.string.log_stopped));
            refreshMappingButton();
            refreshAll();
        });

        TextView save = findViewById(R.id.btn_save_map);
        setIcon(save, R.drawable.ic_save, 18, white, Gravity.START);
        save.setOnClickListener(v -> {
            if (sendCommand("SLAM SAVE_MAP")) addLog(LOG_OK, getString(R.string.log_map_saved, points));
        });

        TextView load = findViewById(R.id.btn_load_map);
        setIcon(load, R.drawable.ic_folder_open, 18, white, Gravity.START);
        load.setOnClickListener(v -> {
            final String[] maps = getResources().getStringArray(R.array.saved_maps);
            new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                    .setTitle(R.string.load_map)
                    .setItems(maps, (d, which) -> {
                        if (sendCommand("SLAM LOAD_MAP " + maps[which])) {
                            addLog(LOG_OK, getString(R.string.log_map_loaded, maps[which]));
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        Switch autoNav = findViewById(R.id.sw_auto_nav);
        autoNav.setOnCheckedChangeListener((b, on) -> {
            session.send("SLAM AUTO_NAV " + (on ? "ON" : "OFF"));
            addLog(LOG_INFO, getString(R.string.log_setting, getString(R.string.auto_navigation),
                    getString(on ? R.string.on : R.string.off)));
        });
        Switch follow = findViewById(R.id.sw_follow);
        follow.setOnCheckedChangeListener((b, on) -> {
            session.send("SLAM FOLLOW " + (on ? "ON" : "OFF"));
            addLog(LOG_INFO, getString(R.string.log_setting, getString(R.string.follow_mode),
                    getString(on ? R.string.on : R.string.off)));
        });

        TextView setTarget = findViewById(R.id.btn_set_target);
        setIcon(setTarget, R.drawable.ic_crosshair, 16, white, Gravity.START);
        setTarget.setOnClickListener(v -> showTargetDialog());

        DPadView dpad = findViewById(R.id.slam_dpad);
        dpad.setOnDirectionListener(this::onDpad);
        bindDrive(R.id.drive_forward, R.drawable.ic_arrow_up, "MOVE FORWARD", 0.25f, 0f);
        bindDrive(R.id.drive_backward, R.drawable.ic_arrow_down, "MOVE BACKWARD", -0.25f, 0f);
        bindDrive(R.id.drive_rotate_left, R.drawable.ic_rotate_left, "ROTATE LEFT", 0f, -15f);
        bindDrive(R.id.drive_rotate_right, R.drawable.ic_rotate_right, "ROTATE RIGHT", 0f, 15f);

        findViewById(R.id.btn_clear_log).setOnClickListener(v -> logList.removeAllViews());
    }

    private void refreshMappingButton() {
        startMapping.setText(mapping ? R.string.pause_mapping : R.string.start_mapping);
        setIcon(startMapping, mapping ? R.drawable.ic_square : R.drawable.ic_play_white, 18,
                color(R.color.white), Gravity.START);
    }

    private void showTargetDialog() {
        CharSequence[] names = new CharSequence[GOAL_NAMES.length];
        for (int i = 0; i < GOAL_NAMES.length; i++) names[i] = getString(GOAL_NAMES[i]);
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.navigation_target)
                .setItems(names, (d, which) -> {
                    float[] goal = GOAL_POSITIONS[which];
                    String name = names[which].toString();
                    if (!sendCommand(String.format(Locale.US, "NAV GOTO %.2f %.2f", goal[0], goal[1]))) return;
                    targetX = goal[0];
                    targetY = goal[1];
                    navigating = true;
                    addLog(LOG_INFO, getString(R.string.log_target_set, name, goal[0], goal[1]));
                    addLog(LOG_INFO, getString(R.string.log_robot_moving));
                    refreshAll();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void bindDrive(int id, int icon, final String command, final float forward, final float turn) {
        final TextView button = findViewById(id);
        setIcon(button, icon, 16, color(R.color.text_primary), Gravity.START);
        button.setOnClickListener(v -> drive(command, forward, turn));
    }

    private void onDpad(DPadView.Direction direction) {
        switch (direction) {
            case UP: drive("MOVE FORWARD", 0.25f, 0f); break;
            case DOWN: drive("MOVE BACKWARD", -0.25f, 0f); break;
            case LEFT: drive("ROTATE LEFT", 0f, -15f); break;
            case RIGHT: drive("ROTATE RIGHT", 0f, 15f); break;
            case CENTER:
                session.send("STOP");
                navigating = false;
                refreshAll();
                break;
            default:
                break;
        }
    }

    /** Manual drive step (cancels navigation): move {@code forward} metres, turn {@code turn} degrees. */
    private void drive(String command, float forward, float turn) {
        if (!sendCommand(command)) return;
        navigating = false;
        yaw = normalize(yaw + turn);
        double rad = Math.toRadians(yaw);
        x += (float) Math.sin(rad) * forward;
        y += (float) Math.cos(rad) * forward;
        addLog(LOG_INFO, getString(R.string.log_command, command));
        refreshAll();
    }

    // ---- log -------------------------------------------------------------------------------

    private void seedLog() {
        addLog(LOG_OK, getString(R.string.log_slam_initialized));
        addLog(LOG_OK, getString(R.string.log_map_updated, points));
        addLog(LOG_OK, getString(R.string.log_localization_stable));
    }

    private void addLog(int dotColor, String message) {
        appendLog(logList, logScroll, dotColor, message, MAX_LOG_LINES);
    }
}
