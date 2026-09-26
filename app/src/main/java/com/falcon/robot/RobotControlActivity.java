package com.falcon.robot;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.Robot3DView;
import com.falcon.robot.widget.JoystickView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Robot Control page (design/robot.png): robot status, camera view with image settings,
 * joystick and button movement, arm control with preset poses, quick and custom actions.
 * Robot telemetry and the camera stream are simulated until the real robot protocol exists.
 */
public class RobotControlActivity extends BaseActivity {

    /** An OBJ export of the real robot, if one is added to the assets. */
    private static final String ROBOT_MODEL_ASSET = "xiaoao.obj";

    private static final long ODOMETRY_TICK_MS = 100;
    /** Metres per second at 100% speed (simulated odometry). */
    private static final float MAX_SPEED_MPS = 1.2f;
    /** Distance of one tap on a direction button at 100% speed. */
    private static final float STEP_M = 0.25f;

    /** The robot overlay on the camera feed, in dp: width and height, then the same full screen. */
    private static final int[] OVERLAY_DP = {150, 118, 255, 200};

    /**
     * The bar along the bottom in full screen: an icon, and the button on the page it works. The
     * panels those buttons live in are hidden while a panel is full screen, so the bar stands in
     * for them; the custom actions are added to it as they are built.
     */
    private static final int[][] FULLSCREEN_BAR = {
            {R.drawable.ic_arrow_up, R.id.move_forward},
            {R.drawable.ic_arrow_down, R.id.move_backward},
            {R.drawable.ic_arrow_left, R.id.move_left},
            {R.drawable.ic_arrow_right, R.id.move_right},
            {R.drawable.ic_square, R.id.move_stop},
            {R.drawable.ic_robot, R.id.pose_stand},
            {R.drawable.ic_pose_sit, R.id.pose_sit},
            {R.drawable.ic_pose_wave, R.id.pose_wave},
            {R.drawable.ic_tpose, R.id.pose_tpose},
            {R.drawable.ic_home, R.id.qa_home},
            {R.drawable.ic_shield, R.id.qa_patrol},
            {R.drawable.ic_follow, R.id.qa_follow},
            {R.drawable.ic_power, R.id.qa_shutdown},
    };

    private final RobotSession session = RobotSession.get();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView valueMode;
    private TextView valueSpeed;
    private TextView valueBattery;
    private TextView valueTemperature;
    private TextView positionX;
    private TextView positionY;
    private TextView positionZ;
    private TextView tip;
    private SeekBar speedSeek;
    private CoverImageView cameraFeed;
    private Robot3DView robot3D;
    private TextView cameraInfo;
    private SeekBar brightness;
    private SeekBar contrast;
    private SeekBar saturation;
    private Spinner resolution;
    private Spinner fps;
    private TextView[] armParts;
    private TextView[] poses;
    private TextView patrol;
    private TextView follow;

    /** The panel filling the page, or 0 when the page is laid out normally. */
    private int fullscreenPanel;
    /** Whether Robot Status is folded down to its title bar, and the height to put back. */
    private boolean statusCollapsed;
    private int panelMinHeight;

    /** The robot laid over the camera feed, doing whatever the full figure does. */
    private Robot3DView feedOverlay;
    private PreviewView previewView;
    private boolean permissionAsked;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) attachPreview();
            });
    private TextView[] customActions;
    private final List<TextView> barButtons = new ArrayList<>();
    private final List<TextView> barSources = new ArrayList<>();

    // simulated odometry
    private float posX;
    private float posY;
    private JoystickView joystick;
    private float joyX;
    private float joyY;
    private int lastJoyX;
    private int lastJoyY;

    private final Runnable odometry = new Runnable() {
        @Override
        public void run() {
            if (session.isConnected() && (joyX != 0 || joyY != 0)) {
                float dt = ODOMETRY_TICK_MS / 1000f;
                float v = MAX_SPEED_MPS * speedFraction();
                posX += joyX * v * dt;
                posY += joyY * v * dt;
                refreshPosition();
            }
            handler.postDelayed(this, ODOMETRY_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_robot_control, R.id.nav_robot, 0);
        setupRichHeader(R.drawable.ic_robot, R.string.nav_robot, R.string.robot_subtitle);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        setupStatusPanel();
        setupCamera();
        setupMovement();
        setupArm();
        setupActions();
        buildFullscreenBar(); // mirrors buttons the three setups above have just made
        bindRobotService(null); // for the camera only: this page has no state to listen for
    }

    @Override
    protected void onRobotServiceReady(RobotService service) {
        attachPreview();
    }

    /**
     * Puts the tablet's camera in the feed panel, which is what the robot overlay is laid over.
     * The camera belongs to {@link RobotService}, so face recognition and object detection go on
     * sharing it; this page only asks for the picture.
     */
    private void attachPreview() {
        RobotService service = getRobotService();
        if (service == null || previewView == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            service.attachPreview(previewView.getSurfaceProvider());
        } else if (!permissionAsked) {
            // without it the design artwork stays in the panel, which is still a usable page
            permissionAsked = true;
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (robot3D != null) robot3D.onResume();
        if (feedOverlay != null) feedOverlay.onResume();
        attachPreview();
        onConnectionChanged();
        handler.removeCallbacks(odometry);
        handler.post(odometry);
    }

    @Override
    protected void onPause() {
        if (robot3D != null) robot3D.onPause();
        if (feedOverlay != null) feedOverlay.onPause();
        RobotService service = getRobotService();
        if (service != null && previewView != null) {
            service.detachPreview(previewView.getSurfaceProvider());
        }
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
        refreshStatus();
    }

    /**
     * Every command goes past here, so the 3D robot acts out whatever was asked for: it walks on
     * a move, waves on a greeting, raises the arm that was selected. It shows the command rather
     * than the robot's own state, the way the odometry readout does, so it still demonstrates the
     * action while nothing is connected; the status panel is what says whether it is.
     */
    @Override
    protected boolean sendCommand(String command) {
        showOnModel(command);
        return super.sendCommand(command);
    }

    /** Both views act out the same command at the same moment, so the head stays with the body. */
    private void showOnModel(String command) {
        if (robot3D != null) robot3D.perform(command);
        if (feedOverlay != null) feedOverlay.perform(command);
    }

    // ---- Robot Status ----------------------------------------------------------------------

    private void setupStatusPanel() {
        int cyan = color(R.color.cyan);
        TextView title = findViewById(R.id.robot_status_title);
        title.setOnClickListener(v -> showConnectionDialog());
        setIcon(findViewById(R.id.label_mode), R.drawable.ic_gamepad, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_speed), R.drawable.ic_bolt, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_battery), R.drawable.ic_battery_h, 18, cyan, Gravity.START);
        setIcon(findViewById(R.id.label_temperature), R.drawable.ic_thermometer, 18, cyan, Gravity.START);

        valueMode = findViewById(R.id.value_mode);
        valueSpeed = findViewById(R.id.value_speed);
        valueBattery = findViewById(R.id.value_battery);
        valueTemperature = findViewById(R.id.value_temperature);
        positionX = findViewById(R.id.position_x);
        positionY = findViewById(R.id.position_y);
        positionZ = findViewById(R.id.position_z);

        // keep the robot figure (right half of the artwork) in view beside the status table
        robot3D = findViewById(R.id.robot_3d);
        robot3D.setModelAsset(ROBOT_MODEL_ASSET); // used when the file is there, ignored otherwise

        View panel = findViewById(R.id.robot_status_panel);
        panel.setClipToOutline(true);
        panelMinHeight = panel.getMinimumHeight(); // the height to put back when it is expanded

        int white = color(R.color.text_primary);
        ImageView full = findViewById(R.id.robot_fullscreen);
        full.setColorFilter(white, PorterDuff.Mode.SRC_IN);
        full.setOnClickListener(v -> toggleFullscreen(R.id.robot_status_panel));
        ImageView collapse = findViewById(R.id.robot_collapse);
        collapse.setColorFilter(white, PorterDuff.Mode.SRC_IN);
        collapse.setOnClickListener(v -> toggleStatusPanel());

        refreshPosition();
    }

    /**
     * Gives the whole page to one panel — the robot or the camera — by hiding the panels beside
     * and below it, the way the camera pages do. The actions live in the panels that go, so they
     * come back along the bottom for as long as full screen lasts.
     */
    private void toggleFullscreen(int panelId) {
        fullscreenPanel = fullscreenPanel == panelId ? 0 : panelId;
        boolean full = fullscreenPanel != 0;

        LinearLayout columns = findViewById(R.id.columns);
        for (int i = 0; i < columns.getChildCount(); i++) {
            View child = columns.getChildAt(i);
            child.setVisibility(!full || child.getId() == fullscreenPanel ? View.VISIBLE : View.GONE);
        }
        findViewById(R.id.columns_bottom).setVisibility(full ? View.GONE : View.VISIBLE);
        findViewById(R.id.fullscreen_actions).setVisibility(full ? View.VISIBLE : View.GONE);
        if (full) refreshFullscreenBar();

        boolean camera = fullscreenPanel == R.id.camera_panel;
        // the colour and resolution controls are not what a full-screen feed is for
        findViewById(R.id.camera_settings_column).setVisibility(camera ? View.GONE : View.VISIBLE);
        // the overlay is a surface drawn over the window, and a hidden ancestor does not reach it:
        // without this it would go on drawing the robot over whatever took the panel's place
        if (feedOverlay != null) {
            feedOverlay.setVisibility(fullscreenPanel == R.id.robot_status_panel
                    ? View.GONE : View.VISIBLE);
        }
        sizeFeedOverlay(camera);

        setFullscreenIcon(R.id.robot_fullscreen, R.id.robot_status_panel);
        setFullscreenIcon(R.id.camera_fullscreen, R.id.camera_panel);
    }

    private void setFullscreenIcon(int buttonId, int panelId) {
        boolean on = fullscreenPanel == panelId;
        ImageView button = findViewById(buttonId);
        button.setImageResource(on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        button.setContentDescription(getString(on ? R.string.exit_full_screen : R.string.full_screen));
    }

    /**
     * Builds the bottom bar out of the buttons it mirrors, so each action keeps one implementation
     * and the bar cannot drift from the panels.
     */
    private void buildFullscreenBar() {
        LinearLayout row = findViewById(R.id.fullscreen_action_row);
        for (int[] entry : FULLSCREEN_BAR) {
            addFullscreenButton(row, entry[0], findViewById(entry[1]));
        }
        for (TextView custom : customActions) {
            addFullscreenButton(row, R.drawable.ic_settings, custom);
        }
    }

    private void addFullscreenButton(LinearLayout row, int icon, final TextView source) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(6 * density);

        TextView button = new TextView(this);
        button.setText(source.getText());
        button.setTextColor(color(R.color.text_primary));
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setBackgroundResource(R.drawable.bg_control_button);
        button.setPadding(pad, pad, pad, pad);
        setIcon(button, icon, 20, color(R.color.text_primary), Gravity.TOP);
        button.setOnClickListener(v -> {
            source.performClick();
            refreshFullscreenBar(); // Patrol and Follow rename themselves when they are toggled
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(80 * density), Math.round(62 * density));
        if (row.getChildCount() > 0) lp.setMarginStart(pad);
        row.addView(button, lp);
        barButtons.add(button);
        barSources.add(source);
    }

    private void refreshFullscreenBar() {
        for (int i = 0; i < barButtons.size(); i++) {
            barButtons.get(i).setText(barSources.get(i).getText());
            barButtons.get(i).setActivated(barSources.get(i).isActivated());
        }
    }

    /** Folds the panel down to its title bar, and back to the size it was. */
    private void toggleStatusPanel() {
        statusCollapsed = !statusCollapsed;
        if (statusCollapsed && fullscreenPanel == R.id.robot_status_panel) {
            toggleFullscreen(R.id.robot_status_panel); // nothing left to be full screen with
        }

        int visibility = statusCollapsed ? View.GONE : View.VISIBLE;
        robot3D.setVisibility(visibility);
        findViewById(R.id.robot_status_table).setVisibility(visibility);
        findViewById(R.id.robot_position).setVisibility(visibility);
        findViewById(R.id.robot_fullscreen).setVisibility(visibility);

        View panel = findViewById(R.id.robot_status_panel);
        panel.setMinimumHeight(statusCollapsed ? 0 : panelMinHeight);
        if (statusCollapsed) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panel.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; // shrink to the title chip
            // stacked, the weight is its share of the height, so drop it and let the panel below
            // have the room; side by side it is the share of the width, which has to stay
            if (!getResources().getBoolean(R.bool.two_columns)) lp.weight = 0f;
            panel.setLayoutParams(lp);
        } else {
            setupColumns(R.id.columns); // puts the row's own sizes back, wide or stacked
        }

        ImageView button = findViewById(R.id.robot_collapse);
        button.setImageResource(statusCollapsed ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_up);
        button.setContentDescription(getString(statusCollapsed ? R.string.expand : R.string.collapse));
    }

    /** Back leaves full screen before it leaves the page. */
    @Override
    public void onBackPressed() {
        if (fullscreenPanel != 0) {
            toggleFullscreen(fullscreenPanel);
            return;
        }
        super.onBackPressed();
    }

    private void refreshStatus() {
        boolean connected = session.isConnected();
        TextView title = findViewById(R.id.robot_status_title);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                connected ? R.drawable.dot_teal : R.drawable.dot_gray, 0, 0, 0);
        valueMode.setText(connected ? getString(R.string.mode_remote) : getString(R.string.value_offline));
        valueBattery.setText(connected ? R.string.demo_battery : R.string.placeholder_value);
        valueTemperature.setText(connected ? R.string.demo_temperature : R.string.placeholder_value);
        refreshSpeedLabel();
    }

    private void refreshSpeedLabel() {
        if (valueSpeed == null || speedSeek == null) return;
        int p = speedSeek.getProgress();
        valueSpeed.setText(p < 34 ? R.string.speed_slow : p < 67 ? R.string.speed_normal : R.string.speed_fast);
    }

    private void refreshPosition() {
        positionX.setText(getString(R.string.position_x, posX));
        positionY.setText(getString(R.string.position_y, posY));
        positionZ.setText(getString(R.string.position_z, 0f));
    }

    // ---- Camera ----------------------------------------------------------------------------

    private void setupCamera() {
        setIcon(findViewById(R.id.camera_title), R.drawable.ic_camera, 20, color(R.color.cyan), Gravity.START);
        cameraFeed = findViewById(R.id.camera_feed);
        cameraFeed.setFocus(0.25f, 0.2f, 0.75f, 1f); // keep the robot in frame
        findViewById(R.id.camera_feed_frame).setClipToOutline(true);

        ImageView cameraFull = findViewById(R.id.camera_fullscreen);
        cameraFull.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        cameraFull.setOnClickListener(v -> toggleFullscreen(R.id.camera_panel));

        previewView = findViewById(R.id.camera_preview);
        // a view in the hierarchy rather than a surface of its own: the artwork behind shows
        // through until frames arrive, and the robot overlay draws on top of it
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        addFeedOverlay();
        cameraInfo = findViewById(R.id.camera_info);

        brightness = bindCameraSlider(R.id.label_brightness, R.drawable.ic_brightness, R.id.seek_brightness, R.id.value_brightness);
        contrast = bindCameraSlider(R.id.label_contrast, R.drawable.ic_contrast, R.id.seek_contrast, R.id.value_contrast);
        saturation = bindCameraSlider(R.id.label_saturation, R.drawable.ic_saturation, R.id.seek_saturation, R.id.value_saturation);

        resolution = bindDropdown(R.id.camera_resolution, R.array.camera_resolutions);
        fps = bindDropdown(R.id.camera_fps, R.array.camera_fps);
        refreshCameraInfo();
        applyImageAdjustments();
    }

    /**
     * Lays the robot over the feed, seen from behind and sitting on the bottom edge, so the panel
     * reads as the robot's own point of view: the live camera is what is in front of it, and the
     * head and shoulders in the foreground turn with whatever it is doing.
     */
    private void addFeedOverlay() {
        float density = getResources().getDisplayMetrics().density;
        feedOverlay = Robot3DView.cameraOverlay(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(OVERLAY_DP[0] * density), Math.round(OVERLAY_DP[1] * density),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ((FrameLayout) findViewById(R.id.camera_feed_frame)).addView(feedOverlay, lp);
    }

    /** The robot grows with the feed, so it is not lost in a full-screen camera. */
    private void sizeFeedOverlay(boolean full) {
        if (feedOverlay == null) return;
        float density = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) feedOverlay.getLayoutParams();
        lp.width = Math.round(OVERLAY_DP[full ? 2 : 0] * density);
        lp.height = Math.round(OVERLAY_DP[full ? 3 : 1] * density);
        feedOverlay.setLayoutParams(lp);
    }

    private SeekBar bindCameraSlider(int labelId, int icon, int seekId, int valueId) {
        setIcon(findViewById(labelId), icon, 16, color(R.color.blue_light), Gravity.START);
        final TextView value = findViewById(valueId);
        SeekBar seek = findViewById(seekId);
        value.setText(getString(R.string.percent, seek.getProgress()));
        seek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(getString(R.string.percent, progress));
                applyImageAdjustments();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                session.send(String.format(Locale.US, "CAMERA B%d C%d S%d",
                        brightness.getProgress(), contrast.getProgress(), saturation.getProgress()));
            }
        });
        return seek;
    }

    /** Applies brightness / contrast / saturation to the feed preview (50% = unchanged). */
    private void applyImageAdjustments() {
        if (brightness == null || contrast == null || saturation == null) return;
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation.getProgress() / 50f);

        float c = contrast.getProgress() / 50f;
        float t = (1f - c) * 127.5f;
        matrix.postConcat(new ColorMatrix(new float[] {
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0,
        }));

        float b = (brightness.getProgress() - 50) * 2.55f;
        matrix.postConcat(new ColorMatrix(new float[] {
                1, 0, 0, 0, b,
                0, 1, 0, 0, b,
                0, 0, 1, 0, b,
                0, 0, 0, 1, 0,
        }));
        cameraFeed.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    private Spinner bindDropdown(int spinnerId, int entries) {
        Spinner spinner = findViewById(spinnerId);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entries, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initialized;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshCameraInfo();
                if (initialized) {
                    session.send("CAMERA " + parent.getItemAtPosition(position));
                }
                initialized = true;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return spinner;
    }

    private void refreshCameraInfo() {
        if (resolution == null || fps == null) return;
        String size = getResources().getStringArray(R.array.camera_resolution_sizes)[resolution.getSelectedItemPosition()];
        cameraInfo.setText(getString(R.string.feed_info, size, fps.getSelectedItem()));
    }

    // ---- Movement --------------------------------------------------------------------------

    private void setupMovement() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.movement_title), R.drawable.ic_crosshair, 22, 0, Gravity.START);
        setIcon(findViewById(R.id.label_speed_slider), R.drawable.ic_run, 18, 0, Gravity.START);

        bindMoveButton(R.id.move_forward, R.drawable.ic_arrow_up, "MOVE FORWARD", 0, 1);
        bindMoveButton(R.id.move_backward, R.drawable.ic_arrow_down, "MOVE BACKWARD", 0, -1);
        bindMoveButton(R.id.move_left, R.drawable.ic_arrow_left, "TURN LEFT", -1, 0);
        bindMoveButton(R.id.move_right, R.drawable.ic_arrow_right, "TURN RIGHT", 1, 0);

        TextView stop = findViewById(R.id.move_stop);
        setIcon(stop, R.drawable.ic_square, 20, white, Gravity.TOP);
        stop.setOnClickListener(v -> {
            // stop is always attempted and never blocked by the connect prompt
            showOnModel("STOP");
            session.send("STOP");
            setTip(getString(R.string.sent_command, stop.getText()));
        });

        TextView reset = findViewById(R.id.move_reset);
        setIcon(reset, R.drawable.ic_refresh, 20, white, Gravity.TOP);
        reset.setOnClickListener(v -> {
            session.send("ODOMETRY RESET");
            posX = 0;
            posY = 0;
            refreshPosition();
            setTip(getString(R.string.position_reset));
        });

        speedSeek = findViewById(R.id.speed_seek);
        final TextView speedValue = findViewById(R.id.speed_value);
        speedValue.setText(getString(R.string.percent, speedSeek.getProgress()));
        speedSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(getString(R.string.percent, progress));
                refreshSpeedLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                session.send("SPEED " + seekBar.getProgress());
            }
        });
        refreshSpeedLabel();

        joystick = findViewById(R.id.joystick);
        joystick.setOnMoveListener(this::onJoystick);
    }

    private void bindMoveButton(int id, int icon, final String command, final int dx, final int dy) {
        final TextView button = findViewById(id);
        setIcon(button, icon, 20, color(R.color.text_primary), Gravity.TOP);
        button.setOnClickListener(v -> {
            if (!sendCommand(command)) return;
            float step = STEP_M * speedFraction();
            posX += dx * step;
            posY += dy * step;
            refreshPosition();
            setTip(getString(R.string.sent_command, button.getText()));
        });
    }

    /** The gamepad stick drives the same control as the on-screen one. */
    @Override
    protected void onGamepadDirection(float x, float y, float turn) {
        joystick.setDirection(x, y);
        onJoystick(x, y);
    }

    private void onJoystick(float x, float y) {
        int qx = Math.round(x * 10);
        int qy = Math.round(y * 10);
        if (qx == lastJoyX && qy == lastJoyY) return;
        lastJoyX = qx;
        lastJoyY = qy;

        if (qx == 0 && qy == 0) {
            joyX = 0;
            joyY = 0;
            showOnModel("MOVE STOP");
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

    // ---- Arm -------------------------------------------------------------------------------

    private void setupArm() {
        setIcon(findViewById(R.id.arm_title), R.drawable.ic_arm, 22, 0, Gravity.START);
        int white = color(R.color.text_primary);

        armParts = new TextView[] {
                findViewById(R.id.arm_left), findViewById(R.id.arm_right),
                findViewById(R.id.arm_head), findViewById(R.id.arm_waist),
        };
        final String[] partCommands = {"LEFT_ARM", "RIGHT_ARM", "HEAD", "WAIST"};
        for (int i = 0; i < armParts.length; i++) {
            final int index = i;
            setIcon(armParts[i], R.drawable.ic_play_white, 16, white, Gravity.START);
            armParts[i].setOnClickListener(v -> {
                if (!sendCommand("ARM SELECT " + partCommands[index])) return;
                selectOnly(armParts, armParts[index]);
                setTip(getString(R.string.sent_command, armParts[index].getText()));
            });
        }

        poses = new TextView[] {
                findViewById(R.id.pose_stand), findViewById(R.id.pose_sit),
                findViewById(R.id.pose_wave), findViewById(R.id.pose_tpose),
        };
        final String[] poseCommands = {"STAND", "SIT", "WAVE", "T_POSE"};
        for (int i = 0; i < poses.length; i++) {
            final int index = i;
            poses[i].setOnClickListener(v -> {
                if (!sendCommand("POSE " + poseCommands[index])) return;
                selectOnly(poses, poses[index]);
                setTip(getString(R.string.sent_command, poses[index].getText()));
            });
        }
        poses[0].setActivated(true); // design default: Stand
    }

    private static void selectOnly(TextView[] group, TextView selected) {
        for (TextView item : group) item.setActivated(item == selected);
    }

    // ---- Quick & custom actions ----------------------------------------------------------

    private void setupActions() {
        setIcon(findViewById(R.id.quick_title), R.drawable.ic_bolt, 22, color(R.color.cyan), Gravity.START);
        setIcon(findViewById(R.id.custom_title), R.drawable.ic_star, 22, 0, Gravity.START);
        setIcon(findViewById(R.id.robot_tip), R.drawable.ic_info, 20, 0, Gravity.START);
        tip = findViewById(R.id.robot_tip);
        int white = color(R.color.text_primary);

        TextView home = findViewById(R.id.qa_home);
        setIcon(home, R.drawable.ic_home, 22, white, Gravity.START);
        home.setOnClickListener(v -> {
            if (sendCommand("GO_HOME")) setTip(getString(R.string.sent_command, home.getText()));
        });

        patrol = findViewById(R.id.qa_patrol);
        setIcon(patrol, R.drawable.ic_shield, 22, white, Gravity.START);
        patrol.setOnClickListener(v -> {
            boolean start = !patrol.isActivated();
            if (!sendCommand(start ? "PATROL START" : "PATROL STOP")) return;
            patrol.setActivated(start);
            patrol.setText(start ? R.string.qa_stop_patrol : R.string.qa_start_patrol);
            setTip(getString(R.string.sent_command, getString(start ? R.string.qa_start_patrol : R.string.qa_stop_patrol)));
        });

        follow = findViewById(R.id.qa_follow);
        setIcon(follow, R.drawable.ic_follow, 22, white, Gravity.START);
        follow.setOnClickListener(v -> {
            boolean start = !follow.isActivated();
            if (!sendCommand(start ? "FOLLOW START" : "FOLLOW STOP")) return;
            follow.setActivated(start);
            follow.setText(start ? R.string.qa_stop_follow : R.string.qa_follow_me);
            setTip(getString(R.string.sent_command, getString(start ? R.string.qa_follow_me : R.string.qa_stop_follow)));
        });

        TextView shutdown = findViewById(R.id.qa_shutdown);
        setIcon(shutdown, R.drawable.ic_power, 22, white, Gravity.START);
        shutdown.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.qa_shutdown)
                .setMessage(R.string.shutdown_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.qa_shutdown, (d, w) -> {
                    if (sendCommand("SHUTDOWN")) setTip(getString(R.string.sent_command, shutdown.getText()));
                })
                .show());

        buildCustomActions();
    }

    private void buildCustomActions() {
        LinearLayout row = findViewById(R.id.custom_actions);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        customActions = new TextView[4];
        for (int i = 1; i <= 4; i++) {
            final String label = getString(R.string.custom_action, i);
            final int number = i;
            TextView button = new TextView(this);
            button.setText(label);
            button.setTextColor(color(R.color.text_primary));
            button.setTextSize(12);
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setBackgroundResource(R.drawable.bg_control_button);
            button.setCompoundDrawablePadding(gap / 2);
            setIcon(button, R.drawable.ic_settings, 16, color(R.color.text_primary), Gravity.START);
            button.setPadding(gap, 0, gap, 0);
            button.setOnClickListener(v -> {
                if (sendCommand("CUSTOM_ACTION " + number)) setTip(getString(R.string.sent_command, label));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 1) lp.setMarginStart(gap);
            row.addView(button, lp);
            customActions[i - 1] = button; // the full-screen bar mirrors these too
        }
    }

    private void setTip(CharSequence text) {
        tip.setText(text);
    }
}
