package com.falcon.robot;

import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.falcon.robot.widget.CompassView;
import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.JoystickView;
import com.falcon.robot.widget.LidarMapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Remote Control page (design/remote.png): robot camera, robot status with estimated position,
 * map, joystick / button movement with control modes, arm control and preset poses, quick
 * actions, camera recording and an operation log.
 *
 * <p>Telemetry, odometry and the camera stream are simulated; commands go through
 * {@link RobotSession}.
 */
public class RemoteControlActivity extends BaseActivity {

    private static final long ODOMETRY_TICK_MS = 100;
    private static final float MAX_SPEED_MPS = 1.2f;
    private static final float STEP_M = 0.25f;
    private static final int MAX_LOG_LINES = 100;

    private final RobotSession session = RobotSession.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> captures = new ArrayList<>();
    private final SimpleDateFormat fileTime = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private TextView cameraTitle;
    private TextView[] cameraTabs;
    private int camera;
    private boolean fullscreen;
    private TextView statusOnline;
    private TextView valueMode;
    private TextView valueSpeed;
    private TextView valueBattery;
    private ProgressBar barBattery;
    private TextView valueTemperature;
    private TextView valueConnection;
    private TextView posX;
    private TextView posY;
    private TextView posZ;
    private CompassView compass;
    private ImageView mapImage;
    private LidarMapView mapLive;
    private TextView map2dTab;
    private TextView map3dTab;
    private float mapZoom = 1f;
    private SeekBar speedSeek;
    private TextView[] modes;
    private TextView[] armParts;
    private TextView[] poses;
    private TextView patrol;
    private TextView follow;
    private TextView video;
    private TextView audio;
    private LinearLayout logList;
    private ScrollView logScroll;

    // simulated odometry
    private float x = 1.23f;
    private float y = 0.56f;
    private float heading;
    private JoystickView joystick;
    private float joyX;
    private float joyY;
    private int lastJoyX;
    private int lastJoyY;
    private boolean wasConnected;

    private final Runnable odometry = new Runnable() {
        @Override
        public void run() {
            if (session.isConnected() && (joyX != 0 || joyY != 0)) {
                float v = MAX_SPEED_MPS * speedFraction() * ODOMETRY_TICK_MS / 1000f;
                x += joyX * v;
                y += joyY * v;
                heading = normalize((float) Math.toDegrees(Math.atan2(joyX, joyY)));
                refreshPosition();
            }
            handler.postDelayed(this, ODOMETRY_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_remote_control, R.id.nav_remote, 0);
        setupRichHeader(R.drawable.ic_gamepad, R.string.nav_remote, R.string.remote_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        logList = findViewById(R.id.log_list);
        logScroll = findViewById(R.id.log_scroll);
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> logList.removeAllViews());
        setIcon(findViewById(R.id.log_title), R.drawable.ic_note, 20, color(R.color.blue_light), Gravity.START);

        setupCamera();
        setupStatus();
        setupMap();
        setupMovement();
        setupArm();
        setupQuickActions();
        setupRecording();

        wasConnected = session.isConnected();
        if (wasConnected) log(LOG_OK, getString(R.string.log_robot_connected));
        log(LOG_OK, getString(R.string.log_camera_started, cameraTabs[camera].getText()));
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        onConnectionChanged();
        handler.removeCallbacks(odometry);
        handler.post(odometry);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(odometry);
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
        boolean connected = session.isConnected();
        if (connected != wasConnected) {
            log(connected ? LOG_OK : LOG_WARN,
                    getString(connected ? R.string.log_robot_connected : R.string.log_robot_disconnected));
            wasConnected = connected;
        }
        refreshStatus();
    }

    private void log(int color, String message) {
        appendLog(logList, logScroll, color, message, MAX_LOG_LINES);
    }

    private static float normalize(float degrees) {
        float d = degrees % 360f;
        return d < 0 ? d + 360f : d;
    }

    // ---- camera ----------------------------------------------------------------------------

    private void setupCamera() {
        cameraTitle = findViewById(R.id.camera_title);
        setIcon(cameraTitle, R.drawable.ic_camera, 20, color(R.color.cyan), Gravity.START);
        CoverImageView feed = findViewById(R.id.camera_feed);
        feed.setFocus(0.25f, 0.1f, 0.75f, 1f); // keep the robot in frame
        findViewById(R.id.feed_frame).setClipToOutline(true);
        ((TextView) findViewById(R.id.camera_info)).setText(
                getResources().getStringArray(R.array.camera_resolution_sizes)[0]);

        int white = color(R.color.text_primary);
        cameraTabs = new TextView[] {
                findViewById(R.id.cam_front), findViewById(R.id.cam_rear),
                findViewById(R.id.cam_left), findViewById(R.id.cam_right),
        };
        for (int i = 0; i < cameraTabs.length; i++) {
            final int index = i;
            setIcon(cameraTabs[i], R.drawable.ic_camera, 14, white, Gravity.START);
            cameraTabs[i].setCompoundDrawablePadding(Math.round(4 * getResources().getDisplayMetrics().density));
            cameraTabs[i].setOnClickListener(v -> selectCamera(index, true));
        }
        selectCamera(0, false);

        for (int id : new int[] {R.id.tool_photo, R.id.tool_switch, R.id.tool_fullscreen}) {
            ((ImageView) findViewById(id)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        }
        findViewById(R.id.tool_photo).setOnClickListener(v -> takePhoto());
        findViewById(R.id.tool_switch).setOnClickListener(v -> selectCamera((camera + 1) % cameraTabs.length, true));
        findViewById(R.id.tool_fullscreen).setOnClickListener(v -> {
            fullscreen = !fullscreen;
            int visibility = fullscreen ? View.GONE : View.VISIBLE;
            for (int i = 1; i < ((LinearLayout) findViewById(R.id.columns)).getChildCount(); i++) {
                ((LinearLayout) findViewById(R.id.columns)).getChildAt(i).setVisibility(visibility);
            }
            findViewById(R.id.columns_bottom).setVisibility(visibility);
            ((ImageView) findViewById(R.id.tool_fullscreen)).setImageResource(
                    fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        });
    }

    private void selectCamera(int index, boolean announce) {
        camera = index;
        for (int i = 0; i < cameraTabs.length; i++) cameraTabs[i].setSelected(i == index);
        CharSequence name = cameraTabs[index].getText();
        cameraTitle.setText(getString(R.string.camera_view_named, name));
        if (announce) {
            session.send("CAMERA SELECT " + name.toString().toUpperCase(Locale.US));
            log(LOG_OK, getString(R.string.log_camera_started, name));
        }
    }

    // ---- status ----------------------------------------------------------------------------

    private void setupStatus() {
        int cyan = color(R.color.cyan);
        TextView title = findViewById(R.id.status_title);
        title.setOnClickListener(v -> showConnectionDialog());
        setIcon(findViewById(R.id.label_mode), R.drawable.ic_gamepad, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_speed), R.drawable.ic_bolt, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_battery), R.drawable.ic_battery_h, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_temperature), R.drawable.ic_thermometer, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_connection), R.drawable.ic_wifi, 18, cyan, Gravity.START);

        statusOnline = findViewById(R.id.status_online);
        statusOnline.setOnClickListener(v -> showConnectionDialog());
        valueMode = findViewById(R.id.value_mode);
        valueSpeed = findViewById(R.id.value_speed);
        valueBattery = findViewById(R.id.value_battery);
        barBattery = findViewById(R.id.bar_battery);
        valueTemperature = findViewById(R.id.value_temperature);
        valueConnection = findViewById(R.id.value_connection);
        posX = findViewById(R.id.pos_x);
        posY = findViewById(R.id.pos_y);
        posZ = findViewById(R.id.pos_z);
        compass = findViewById(R.id.compass);
        refreshPosition();
    }

    private void refreshStatus() {
        if (valueMode == null || modes == null) return;
        boolean connected = session.isConnected();
        TextView title = findViewById(R.id.status_title);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(connected ? R.drawable.dot_teal : R.drawable.dot_gray, 0, 0, 0);
        setStatus(statusOnline, connected ? R.string.online : R.string.status_offline_short,
                connected ? R.drawable.dot_teal : R.drawable.dot_gray);
        statusOnline.setTextColor(color(connected ? R.color.teal : R.color.text_secondary));

        String mode = getString(R.string.mode_remote);
        for (TextView m : modes) if (m.isActivated() && m.getId() != R.id.mode_manual) mode = m.getText().toString();
        valueMode.setText(connected ? mode : getString(R.string.value_offline));
        int p = speedSeek.getProgress();
        valueSpeed.setText(p < 34 ? R.string.speed_slow : p < 67 ? R.string.speed_normal : R.string.speed_fast);
        valueBattery.setText(connected ? R.string.demo_battery : R.string.placeholder_value);
        barBattery.setProgress(connected ? 78 : 0);
        valueTemperature.setText(connected ? R.string.demo_temperature : R.string.placeholder_value);
        valueConnection.setText(connected ? session.getHost() : getString(R.string.placeholder_value));
    }

    private void refreshPosition() {
        posX.setText(getString(R.string.pose_meters, x));
        posY.setText(getString(R.string.pose_meters, y));
        posZ.setText(getString(R.string.pose_meters, 0f));
        compass.setHeading(heading);
    }

    // ---- map -------------------------------------------------------------------------------

    private void setupMap() {
        setIcon(findViewById(R.id.map_title), R.drawable.ic_lidar, 22, color(R.color.blue_light), Gravity.START);
        setIcon(findViewById(R.id.legend_robot), R.drawable.ic_navigation, 16, color(R.color.blue), Gravity.START);
        findViewById(R.id.map_frame).setClipToOutline(true);
        mapImage = findViewById(R.id.map_image);
        mapLive = findViewById(R.id.map_live);
        map2dTab = findViewById(R.id.map_2d_tab);
        map3dTab = findViewById(R.id.map_3d_tab);
        map2dTab.setOnClickListener(v -> selectMap(false));
        map3dTab.setOnClickListener(v -> selectMap(true));
        selectMap(false);

        int white = color(R.color.text_primary);
        for (int id : new int[] {R.id.map_zoom_in, R.id.map_zoom_out, R.id.map_locate}) {
            ((ImageView) findViewById(id)).setColorFilter(white, PorterDuff.Mode.SRC_IN);
        }
        findViewById(R.id.map_zoom_in).setOnClickListener(v -> setMapZoom(mapZoom + 0.25f));
        findViewById(R.id.map_zoom_out).setOnClickListener(v -> setMapZoom(mapZoom - 0.25f));
        findViewById(R.id.map_locate).setOnClickListener(v -> setMapZoom(1f));
    }

    private void selectMap(boolean threeD) {
        map2dTab.setSelected(!threeD);
        map3dTab.setSelected(threeD);
        mapImage.setVisibility(threeD ? View.GONE : View.VISIBLE);
        mapLive.setVisibility(threeD ? View.VISIBLE : View.GONE);
    }

    private void setMapZoom(float zoom) {
        mapZoom = Math.max(1f, Math.min(3f, zoom));
        mapImage.setScaleX(mapZoom);
        mapImage.setScaleY(mapZoom);
    }

    // ---- movement --------------------------------------------------------------------------

    private void setupMovement() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.movement_title), R.drawable.ic_crosshair, 22, 0, Gravity.START);
        setIcon(findViewById(R.id.label_speed_slider), R.drawable.ic_run, 18, 0, Gravity.START);

        bindMove(R.id.move_forward, R.drawable.ic_arrow_up, "MOVE FORWARD", 0, 1);
        bindMove(R.id.move_backward, R.drawable.ic_arrow_down, "MOVE BACKWARD", 0, -1);
        bindMove(R.id.move_left, R.drawable.ic_arrow_left, "TURN LEFT", -1, 0);
        bindMove(R.id.move_right, R.drawable.ic_arrow_right, "TURN RIGHT", 1, 0);

        final TextView stop = findViewById(R.id.move_stop);
        setIcon(stop, R.drawable.ic_square, 20, white, Gravity.TOP);
        stop.setOnClickListener(v -> {
            session.send("STOP"); // stop is never blocked by the connect prompt
            joyX = 0;
            joyY = 0;
            log(LOG_WARN, getString(R.string.log_movement, stop.getText()));
        });
        final TextView reset = findViewById(R.id.move_reset);
        setIcon(reset, R.drawable.ic_refresh, 20, white, Gravity.TOP);
        reset.setOnClickListener(v -> {
            session.send("ODOMETRY RESET");
            x = 0;
            y = 0;
            heading = 0;
            refreshPosition();
            log(LOG_INFO, getString(R.string.position_reset));
        });

        speedSeek = findViewById(R.id.speed_seek);
        final TextView speedValue = findViewById(R.id.speed_value);
        speedValue.setText(getString(R.string.percent, speedSeek.getProgress()));
        speedSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(getString(R.string.percent, progress));
                refreshStatus();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                session.send("SPEED " + seekBar.getProgress());
                log(LOG_WARN, getString(R.string.log_speed, seekBar.getProgress()));
            }
        });

        joystick = findViewById(R.id.joystick);
        joystick.setOnMoveListener(this::onJoystick);

        modes = new TextView[] {
                findViewById(R.id.mode_manual), findViewById(R.id.mode_autonomous),
                findViewById(R.id.mode_follow), findViewById(R.id.mode_path),
        };
        int[] modeIcons = {R.drawable.ic_person, R.drawable.ic_robot, R.drawable.ic_follow, R.drawable.ic_navigation};
        final String[] modeCommands = {"MANUAL", "AUTONOMOUS", "FOLLOW", "PATH"};
        for (int i = 0; i < modes.length; i++) {
            final int index = i;
            setIcon(modes[i], modeIcons[i], 16, white, Gravity.START);
            modes[i].setOnClickListener(v -> {
                if (!sendCommand("MODE " + modeCommands[index])) return;
                selectOnly(modes, modes[index]);
                log(LOG_INFO, getString(R.string.log_mode, modes[index].getText()));
                refreshStatus();
            });
        }
        modes[0].setActivated(true);
        modes[0].setLayoutParams(noStartMargin(modes[0]));
    }

    private void bindMove(int id, int icon, final String command, final int dx, final int dy) {
        final TextView button = findViewById(id);
        setIcon(button, icon, 20, color(R.color.text_primary), Gravity.TOP);
        button.setOnClickListener(v -> {
            if (!sendCommand(command)) return;
            float step = STEP_M * speedFraction();
            x += dx * step;
            y += dy * step;
            heading = normalize((float) Math.toDegrees(Math.atan2(dx, dy)));
            refreshPosition();
            log(LOG_OK, getString(R.string.log_movement, button.getText()));
        });
    }

    /** The gamepad stick drives the same control as the on-screen one. */
    @Override
    protected void onGamepadDirection(float x, float y, float turn) {
        joystick.setDirection(x, y);
        onJoystick(x, y);
    }

    private void onJoystick(float jx, float jy) {
        int qx = Math.round(jx * 10);
        int qy = Math.round(jy * 10);
        if (qx == lastJoyX && qy == lastJoyY) return;
        lastJoyX = qx;
        lastJoyY = qy;
        if (qx == 0 && qy == 0) {
            joyX = 0;
            joyY = 0;
            session.send("MOVE STOP");
            return;
        }
        if (!sendCommand(String.format(Locale.US, "MOVE %.1f %.1f", qx / 10f, qy / 10f))) {
            joyX = 0;
            joyY = 0;
            return;
        }
        joyX = qx / 10f;
        joyY = qy / 10f;
    }

    private float speedFraction() {
        return speedSeek == null ? 0.5f : speedSeek.getProgress() / 100f;
    }

    private static void selectOnly(TextView[] group, TextView selected) {
        for (TextView item : group) item.setActivated(item == selected);
    }

    private static LinearLayout.LayoutParams noStartMargin(View view) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        lp.setMarginStart(0);
        return lp;
    }

    // ---- arm -------------------------------------------------------------------------------

    private void setupArm() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.arm_title), R.drawable.ic_arm, 22, 0, Gravity.START);

        armParts = new TextView[] {
                findViewById(R.id.arm_left), findViewById(R.id.arm_right),
                findViewById(R.id.arm_head), findViewById(R.id.arm_waist),
        };
        final String[] partCommands = {"LEFT_ARM", "RIGHT_ARM", "HEAD", "WAIST"};
        for (int i = 0; i < armParts.length; i++) {
            final int index = i;
            setIcon(armParts[i], R.drawable.ic_play_white, 16, white, Gravity.START);
            armParts[i].setCompoundDrawablesRelative(armParts[i].getCompoundDrawablesRelative()[0], null,
                    tinted(R.drawable.ic_chevron_right, 16), null);
            armParts[i].setOnClickListener(v -> {
                if (!sendCommand("ARM SELECT " + partCommands[index])) return;
                selectOnly(armParts, armParts[index]);
                log(LOG_INFO, getString(R.string.log_arm, armParts[index].getText()));
            });
        }

        poses = new TextView[] {
                findViewById(R.id.pose_stand), findViewById(R.id.pose_sit), findViewById(R.id.pose_wave),
                findViewById(R.id.pose_tpose), findViewById(R.id.pose_pick), findViewById(R.id.pose_point),
                findViewById(R.id.pose_crouch), findViewById(R.id.pose_custom),
        };
        int[] poseIcons = {
                R.drawable.ic_person, R.drawable.ic_pose_sit, R.drawable.ic_pose_wave, R.drawable.ic_tpose,
                R.drawable.ic_arm, R.drawable.ic_touch, R.drawable.ic_follow, R.drawable.ic_settings,
        };
        final String[] poseCommands = {"STAND", "SIT", "WAVE", "T_POSE", "PICK", "POINT", "CROUCH", "CUSTOM"};
        for (int i = 0; i < poses.length; i++) {
            final int index = i;
            setIcon(poses[i], poseIcons[i], 16, white, Gravity.START);
            if (i % 4 == 0) poses[i].setLayoutParams(noStartMargin(poses[i]));
            poses[i].setOnClickListener(v -> {
                if (!sendCommand("POSE " + poseCommands[index])) return;
                selectOnly(poses, poses[index]);
                log(LOG_WARN, getString(R.string.log_pose, poses[index].getText()));
            });
        }
        poses[0].setActivated(true);
    }

    private android.graphics.drawable.Drawable tinted(int drawable, int sizeDp) {
        android.graphics.drawable.Drawable d = getResources().getDrawable(drawable).mutate();
        int size = Math.round(sizeDp * getResources().getDisplayMetrics().density);
        d.setBounds(0, 0, size, size);
        d.setColorFilter(color(R.color.text_secondary), PorterDuff.Mode.SRC_IN);
        return d;
    }

    // ---- quick actions ---------------------------------------------------------------------

    private void setupQuickActions() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.quick_title), R.drawable.ic_bolt, 22, color(R.color.cyan), Gravity.START);

        final TextView home = findViewById(R.id.qa_home);
        setIcon(home, R.drawable.ic_home, 22, white, Gravity.START);
        home.setOnClickListener(v -> {
            if (sendCommand("GO_HOME")) log(LOG_INFO, getString(R.string.log_action, home.getText()));
        });

        patrol = findViewById(R.id.qa_patrol);
        setIcon(patrol, R.drawable.ic_shield, 22, white, Gravity.START);
        patrol.setOnClickListener(v -> {
            boolean start = !patrol.isActivated();
            if (!sendCommand(start ? "PATROL START" : "PATROL STOP")) return;
            patrol.setActivated(start);
            patrol.setText(start ? R.string.qa_stop_patrol : R.string.qa_start_patrol);
            log(LOG_INFO, getString(R.string.log_action, getString(start ? R.string.qa_start_patrol : R.string.qa_stop_patrol)));
        });

        follow = findViewById(R.id.qa_follow);
        setIcon(follow, R.drawable.ic_follow, 22, white, Gravity.START);
        follow.setOnClickListener(v -> {
            boolean start = !follow.isActivated();
            if (!sendCommand(start ? "FOLLOW START" : "FOLLOW STOP")) return;
            follow.setActivated(start);
            follow.setText(start ? R.string.qa_stop_follow : R.string.qa_follow_me);
            log(LOG_INFO, getString(R.string.log_action, getString(start ? R.string.qa_follow_me : R.string.qa_stop_follow)));
        });

        final TextView shutdown = findViewById(R.id.qa_shutdown);
        setIcon(shutdown, R.drawable.ic_power, 22, white, Gravity.START);
        shutdown.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.qa_shutdown)
                .setMessage(R.string.shutdown_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.qa_shutdown, (d, w) -> {
                    if (sendCommand("SHUTDOWN")) log(LOG_ERROR, getString(R.string.log_action, shutdown.getText()));
                })
                .show());
    }

    // ---- camera & recording ----------------------------------------------------------------

    private void setupRecording() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.recording_title), R.drawable.ic_camera, 22, color(R.color.cyan), Gravity.START);

        TextView photo = findViewById(R.id.rec_photo);
        setIcon(photo, R.drawable.ic_camera, 16, white, Gravity.START);
        photo.setOnClickListener(v -> takePhoto());

        video = findViewById(R.id.rec_video);
        setIcon(video, R.drawable.ic_videocam, 16, white, Gravity.START);
        video.setOnClickListener(v -> {
            boolean start = !video.isActivated();
            session.send(start ? "CAMERA VIDEO START" : "CAMERA VIDEO STOP");
            video.setActivated(start);
            video.setText(start ? R.string.stop_video : R.string.start_video);
            if (start) {
                log(LOG_INFO, getString(R.string.log_video_started));
            } else {
                String file = "VID_" + fileTime.format(new Date()) + ".mp4";
                captures.add(file);
                log(LOG_OK, getString(R.string.log_video_saved, file));
            }
        });

        audio = findViewById(R.id.rec_audio);
        audio.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_red, 0, 0, 0);
        audio.setOnClickListener(v -> {
            boolean start = !audio.isActivated();
            session.send(start ? "AUDIO RECORD START" : "AUDIO RECORD STOP");
            audio.setActivated(start);
            audio.setText(start ? R.string.stop_record : R.string.record);
            if (start) {
                log(LOG_INFO, getString(R.string.log_audio_started));
            } else {
                String file = "REC_" + fileTime.format(new Date()) + ".wav";
                captures.add(file);
                log(LOG_OK, getString(R.string.log_audio_saved, file));
            }
        });

        TextView gallery = findViewById(R.id.rec_gallery);
        setIcon(gallery, R.drawable.ic_photo, 16, white, Gravity.START);
        gallery.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                    .setTitle(R.string.gallery)
                    .setPositiveButton(R.string.close, null);
            if (captures.isEmpty()) {
                builder.setMessage(R.string.gallery_empty);
            } else {
                List<String> newestFirst = new ArrayList<>(captures);
                java.util.Collections.reverse(newestFirst);
                builder.setItems(newestFirst.toArray(new CharSequence[0]), null);
            }
            builder.show();
        });
    }

    private void takePhoto() {
        session.send("CAMERA PHOTO");
        String file = "IMG_" + fileTime.format(new Date()) + ".jpg";
        captures.add(file);
        toast(R.string.snapshot_saved);
        log(LOG_OK, getString(R.string.log_photo, file));
    }
}
