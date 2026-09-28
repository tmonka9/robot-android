package com.falcon.robot;

import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.falcon.robot.ble.BleLink;
import com.falcon.robot.widget.CompassView;
import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.LidarMapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Remote Control page (design/remote.png): robot camera, robot status with estimated position,
 * map, the BLE link to the robot, camera recording and an operation log.
 *
 * <p>The robot is driven over Bluetooth Low Energy, so this page is where the link is made: it
 * finds the robot, connects to it and hands the connection to {@link RobotSession}, which every
 * other page sends through. What the robot is asked to do is on the Robot Control page.
 *
 * <p>Telemetry and the camera stream are still simulated.
 */
public class RemoteControlActivity extends BaseActivity {

    private static final int MAX_LOG_LINES = 100;

    private final RobotSession session = RobotSession.get();
    private final BleLink ble = BleLink.get();
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
    private TextView video;
    private TextView audio;
    private LinearLayout logList;
    private ScrollView logScroll;

    // the position the robot reports; nothing here moves it
    private float x = 1.23f;
    private float y = 0.56f;
    private float heading;
    private boolean wasConnected;

    // the BLE panel
    private TextView bleStatus;
    private TextView bleScan;
    private TextView bleDisconnect;
    private LinearLayout bleList;
    /** Whether the link the session is holding is this page's BLE one. */
    private boolean bleSession;

    private final ActivityResultLauncher<String[]> blePermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                for (Boolean given : granted.values()) {
                    if (!Boolean.TRUE.equals(given)) {
                        log(LOG_WARN, getString(R.string.ble_permission_needed));
                        return;
                    }
                }
                ble.startScan(this);
            });

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
        setIcon(findViewById(R.id.log_title), R.drawable.ic_note, 16, color(R.color.blue_light), Gravity.START);

        setupCamera();
        setupStatus();
        setupMap();

        // what the log starts with, before the BLE panel reports the link it already has
        wasConnected = session.isConnected();
        if (wasConnected) log(LOG_OK, getString(R.string.log_robot_connected));
        log(LOG_OK, getString(R.string.log_camera_started, cameraTabs[camera].getText()));

        setupBle();
        setupRecording();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        onConnectionChanged();
        renderBle(); // Bluetooth can have been switched on while the page was away
    }

    @Override
    protected void onPause() {
        ble.stopScan(); // a scan nobody is watching is only a drain on the battery
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ble.removeListener(bleListener); // the link itself stays up, and other pages send over it
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

    // ---- camera ----------------------------------------------------------------------------

    private void setupCamera() {
        cameraTitle = findViewById(R.id.camera_title);
        setIcon(cameraTitle, R.drawable.ic_camera, 16, color(R.color.cyan), Gravity.START);
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
        if (valueMode == null || bleStatus == null) return;
        boolean connected = session.isConnected();
        TextView title = findViewById(R.id.status_title);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(connected ? R.drawable.dot_teal : R.drawable.dot_gray, 0, 0, 0);
        setStatus(statusOnline, connected ? R.string.online : R.string.status_offline_short,
                connected ? R.drawable.dot_teal : R.drawable.dot_gray);
        statusOnline.setTextColor(color(connected ? R.color.teal : R.color.text_secondary));

        valueMode.setText(connected ? getString(R.string.mode_remote) : getString(R.string.value_offline));
        // the speed the robot reports: this page no longer sets it
        valueSpeed.setText(connected ? R.string.speed_normal : R.string.placeholder_value);
        valueBattery.setText(connected ? R.string.demo_battery : R.string.placeholder_value);
        barBattery.setProgress(connected ? 78 : 0);
        valueTemperature.setText(connected ? R.string.demo_temperature : R.string.placeholder_value);
        valueConnection.setText(!connected ? getString(R.string.placeholder_value)
                : ble.isConnected() ? ble.getDeviceName() : session.getHost());
        renderBle();
    }

    private void refreshPosition() {
        posX.setText(getString(R.string.pose_meters, x));
        posY.setText(getString(R.string.pose_meters, y));
        posZ.setText(getString(R.string.pose_meters, 0f));
        compass.setHeading(heading);
    }

    // ---- map -------------------------------------------------------------------------------

    private void setupMap() {
        setIcon(findViewById(R.id.map_title), R.drawable.ic_lidar, 16, color(R.color.blue_light), Gravity.START);
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

    // ---- the BLE link ----------------------------------------------------------------------

    /**
     * The link to the robot: Scan looks for it, a row in the list connects to it, and from then
     * on every page's commands go out over that connection ({@link RobotSession#send}).
     */
    private void setupBle() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.ble_title), R.drawable.ic_bluetooth, 16, color(R.color.blue_light),
                Gravity.START);
        bleStatus = findViewById(R.id.ble_status);
        bleList = findViewById(R.id.ble_list);

        bleScan = findViewById(R.id.ble_scan);
        setIcon(bleScan, R.drawable.ic_search, 18, white, Gravity.START);
        bleScan.setOnClickListener(v -> toggleScan());

        bleDisconnect = findViewById(R.id.ble_disconnect);
        setIcon(bleDisconnect, R.drawable.ic_power, 18, white, Gravity.START);
        bleDisconnect.setOnClickListener(v -> ble.disconnect());

        ble.addListener(bleListener); // says the state and the devices straight back
    }

    private void toggleScan() {
        if (ble.getState() == BleLink.State.SCANNING) {
            ble.stopScan();
            return;
        }
        if (!ble.isReady(this)) {
            toast(R.string.ble_adapter_off);
            return;
        }
        String[] missing = BleLink.missingPermissions(this);
        if (missing.length > 0) {
            blePermissions.launch(missing);
            return;
        }
        ble.startScan(this);
        log(LOG_INFO, getString(R.string.ble_log_scanning));
    }

    private void connectTo(BleLink.Found device) {
        String[] missing = BleLink.missingPermissions(this);
        if (missing.length > 0) {
            blePermissions.launch(missing);
            return;
        }
        log(LOG_INFO, getString(R.string.ble_connecting, device.name));
        ble.connect(this, device.address, device.name);
    }

    private final BleLink.Listener bleListener = new BleLink.Listener() {
        @Override
        public void onState(BleLink.State state, String device) {
            boolean connected = state == BleLink.State.CONNECTED;
            // the session is what every page sends through, so the BLE link hands it over — and
            // takes back only what it gave: a link made in the connection dialog is left alone
            if (connected && !bleSession) {
                bleSession = true;
                session.setBleConnected();
                log(LOG_OK, getString(R.string.ble_log_connected, device));
            } else if (!connected && bleSession) {
                bleSession = false;
                session.setConnected(false);
                log(LOG_WARN, getString(R.string.ble_log_disconnected));
            }
            renderBle();
            onConnectionChanged();
        }

        @Override
        public void onDevices(List<BleLink.Found> devices) {
            renderDevices(devices);
        }

        @Override
        public void onMessage(String text) {
            log(LOG_INFO, getString(R.string.ble_log_message, text));
        }

        @Override
        public void onServiceMissing() {
            log(LOG_ERROR, getString(R.string.ble_service_missing,
                    BleLink.SERVICE.toString()));
        }
    };

    private void renderBle() {
        if (bleStatus == null) return;
        BleLink.State state = ble.getState();
        boolean ready = ble.isReady(this);
        boolean connected = state == BleLink.State.CONNECTED;
        CharSequence text;
        if (!ready) {
            text = getString(R.string.ble_adapter_off);
        } else if (connected) {
            text = getString(R.string.ble_connected, ble.getDeviceName());
        } else if (state == BleLink.State.CONNECTING) {
            text = getString(R.string.ble_connecting, ble.getDeviceName());
        } else if (state == BleLink.State.SCANNING) {
            text = getString(R.string.ble_scanning);
        } else {
            text = getString(R.string.ble_disconnected);
        }
        setStatus(bleStatus, text, connected ? R.drawable.dot_teal
                : state == BleLink.State.IDLE || !ready ? R.drawable.dot_gray : R.drawable.dot_amber);
        bleStatus.setTextColor(color(connected ? R.color.teal : R.color.text_secondary));

        bleScan.setText(state == BleLink.State.SCANNING ? R.string.ble_stop_scan : R.string.ble_scan);
        bleScan.setActivated(state == BleLink.State.SCANNING);
        boolean linked = connected || state == BleLink.State.CONNECTING;
        bleDisconnect.setEnabled(linked);
        bleDisconnect.setAlpha(linked ? 1f : 0.45f);
    }

    /** One row per device the scan has seen, strongest signal first; a tap connects to it. */
    private void renderDevices(List<BleLink.Found> devices) {
        if (bleList == null) return;
        bleList.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        if (devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(ble.getState() == BleLink.State.SCANNING
                    ? R.string.ble_searching : R.string.ble_no_devices);
            empty.setTextColor(color(R.color.text_muted));
            empty.setTextSize(12);
            bleList.addView(empty);
            return;
        }
        int pad = Math.round(10 * density);
        for (final BleLink.Found device : devices) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_control_button);
            row.setPadding(pad, pad, pad, pad);
            row.setActivated(device.address.equals(ble.getDeviceAddress()));
            row.setOnClickListener(v -> connectTo(device));

            TextView name = new TextView(this);
            name.setText(device.name);
            name.setTextColor(color(R.color.text_primary));
            name.setTextSize(13);
            name.setSingleLine(true);
            row.addView(name);

            TextView detail = new TextView(this);
            detail.setText(getString(R.string.ble_device_detail, device.address, device.rssi));
            detail.setTextColor(color(R.color.text_secondary));
            detail.setTextSize(11);
            row.addView(detail);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (bleList.getChildCount() > 0) lp.topMargin = Math.round(6 * density);
            bleList.addView(row, lp);
        }
    }

    // ---- camera & recording ----------------------------------------------------------------

    private void setupRecording() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.recording_title), R.drawable.ic_camera, 16, color(R.color.cyan), Gravity.START);

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
