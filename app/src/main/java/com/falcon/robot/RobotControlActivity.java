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
import androidx.camera.core.CameraSelector;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.falcon.robot.detect.CocoLabels;
import com.falcon.robot.detect.ObjectTracker;
import com.falcon.robot.face.FaceAnalyzer;
import com.falcon.robot.voice.VoiceCommands;
import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.FaceOverlayView;
import com.falcon.robot.widget.Robot3DView;
import com.falcon.robot.widget.JoystickView;
import com.falcon.robot.widget.TrackingOverlayView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Robot Control page (design/robot.png): robot status, camera view with image settings,
 * joystick and button movement, arm control with preset poses, quick and custom actions.
 * Robot telemetry and the camera stream are simulated until the real robot protocol exists.
 *
 * <p>The three recognition features are switched on from here as well, since this is the page the
 * robot is driven from: faces and objects are drawn over the feed, a spoken command is carried out
 * exactly as the button for it would be, and the Log panel keeps what each model reported. The
 * status bar along the foot says what happened last, whichever panel it happened in.
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

    /** The recognition features, as {@link #toggleFeature} counts them. */
    private static final int NO_FEATURE = -1;
    private static final int FACE = 0;
    private static final int OBJECTS = 1;
    private static final int VOICE = 2;

    /** How many lines the Log panel keeps; it scrolls, and older than this is of no use. */
    private static final int MAX_LOG = 80;

    private final RobotSession session = RobotSession.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

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

    // recognition
    private TextView faceButton;
    private TextView objectButton;
    private TextView voiceButton;
    private FaceOverlayView faceOverlay;
    private TrackingOverlayView objectOverlay;
    /** The feature waiting for a permission answer, or {@link #NO_FEATURE}. */
    private int pendingFeature = NO_FEATURE;
    /** A detector that will not load is said once, not on every frame. */
    private boolean detectorReported;
    private TextView aiStatus;

    // log
    private TextView logButton;
    private LinearLayout logList;
    private final List<String> logLines = new ArrayList<>();
    private boolean logging;
    /** The objects last written down, so a steady scene is logged once and not every frame. */
    private String lastObjects;

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
                if (!granted) {
                    pendingFeature = NO_FEATURE;
                    return;
                }
                attachPreview();
                startPendingFeature();
            });

    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startPendingFeature();
                } else {
                    pendingFeature = NO_FEATURE;
                    toast(R.string.mic_permission_needed);
                }
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
        // no page header on this page: the screen goes to the panels instead
        setPage(R.layout.activity_robot_control, R.id.nav_robot, 0);
        setupColumns(R.id.columns);
        setupColumns(R.id.columns_bottom);

        setupStatusPanel();
        setupCamera();
        setupRecognition();
        setupLog();
        setupMovement();
        setupArm();
        setupActions();
        buildFullscreenBar(); // mirrors buttons the three setups above have just made
        bindRobotService(recognitionListener);
    }

    @Override
    protected void onRobotServiceReady(RobotService service) {
        attachPreview();
        renderRecognition();
        // whatever the models found while the page was elsewhere, so the feed is not blank
        faceOverlay.setFaces(service.getLastFaces(), service.getFrameWidth(),
                service.getFrameHeight(), frontCamera());
        objectOverlay.setObjects(service.getLastObjects(), service.getFrameWidth(),
                service.getFrameHeight(), frontCamera());
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
        renderRecognition(); // the features can have been switched on elsewhere
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
        setIcon(findViewById(R.id.camera_settings_title), R.drawable.ic_tune, 20,
                color(R.color.blue_light), Gravity.START);
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

    // ---- Recognition -----------------------------------------------------------------------

    /**
     * The face, object and voice switches. They are {@link RobotService}'s own switches, so a
     * feature turned on here is still on from the Face, Object or Voice page — and keeps running
     * when this page is left.
     */
    private void setupRecognition() {
        CocoLabels.init(this); // class names in the app language

        faceOverlay = findViewById(R.id.face_overlay);
        faceOverlay.setLabels(getString(R.string.unknown_person), getString(R.string.face_label_face));
        objectOverlay = findViewById(R.id.tracking_overlay);

        setIcon(findViewById(R.id.ai_title), R.drawable.ic_chip, 20, color(R.color.cyan), Gravity.START);
        aiStatus = findViewById(R.id.ai_status);
        faceButton = bindFeature(R.id.toggle_face, R.drawable.ic_face_id, FACE);
        objectButton = bindFeature(R.id.toggle_object, R.drawable.ic_cube, OBJECTS);
        voiceButton = bindFeature(R.id.toggle_voice, R.drawable.ic_mic, VOICE);
        renderRecognition();
    }

    private TextView bindFeature(int id, int icon, final int feature) {
        TextView button = findViewById(id);
        setIcon(button, icon, 20, color(R.color.text_primary), Gravity.START);
        button.setOnClickListener(v -> toggleFeature(feature));
        return button;
    }

    /** Switches a feature, asking for the camera or the microphone the first time. */
    private void toggleFeature(int feature) {
        if (getRobotService() == null) return;
        if (isFeatureEnabled(feature)) {
            setFeatureEnabled(feature, false);
            return;
        }
        ensureNotificationPermission(); // the service needs it to keep running in the background
        String permission = feature == VOICE
                ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA;
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            pendingFeature = feature;
            (feature == VOICE ? microphonePermission : cameraPermission).launch(permission);
            return;
        }
        setFeatureEnabled(feature, true);
    }

    private void startPendingFeature() {
        int feature = pendingFeature;
        pendingFeature = NO_FEATURE;
        if (feature != NO_FEATURE) setFeatureEnabled(feature, true);
    }

    private boolean isFeatureEnabled(int feature) {
        RobotService service = getRobotService();
        if (service == null) return false;
        switch (feature) {
            case FACE:
                return service.isFaceEnabled();
            case OBJECTS:
                return service.isDetectionEnabled();
            default:
                return service.isVoiceEnabled();
        }
    }

    private void setFeatureEnabled(int feature, boolean on) {
        RobotService service = getRobotService();
        if (service == null) return;
        switch (feature) {
            case FACE:
                service.setFaceEnabled(on);
                break;
            case OBJECTS:
                detectorReported = false;
                lastObjects = null; // so the scene is written down again when it comes back
                service.setDetectionEnabled(on);
                break;
            default:
                // listening from this page is for driving the robot, so the commands are switched
                // through whatever the Voice page was last left set to
                if (on) service.setCommandControl(true);
                service.setVoiceEnabled(on);
                session.send(on ? "VOICE LISTEN ON" : "VOICE LISTEN OFF");
                break;
        }
        renderRecognition();
    }

    /** Puts the switches and the overlays in step with what the service is actually doing. */
    private void renderRecognition() {
        RobotService service = getRobotService();
        boolean face = service != null && service.isFaceEnabled();
        boolean objects = service != null && service.isDetectionEnabled();
        boolean voice = service != null && service.isVoiceEnabled();

        faceButton.setActivated(face);
        objectButton.setActivated(objects);
        voiceButton.setActivated(voice);

        faceOverlay.setVisibility(face ? View.VISIBLE : View.GONE);
        objectOverlay.setVisibility(objects ? View.VISIBLE : View.GONE);
        if (!face) faceOverlay.clear();
        if (!objects) objectOverlay.clear();
        renderAiStatus();

        // a detector that will not load draws nothing at all, which looks like the switch failing
        if (objects && !detectorReported && service.getDetectorError() != null) {
            detectorReported = true;
            toast(getString(R.string.detector_failed, String.valueOf(service.getDetectorModel()),
                    service.getDetectorError()));
        }
    }

    /** What the AI panel says under its switches: what is running, and how it is doing. */
    private void renderAiStatus() {
        if (aiStatus == null) return;
        RobotService service = getRobotService();
        StringBuilder text = new StringBuilder();
        if (service != null && service.isFaceEnabled()) text.append(getString(R.string.nav_face));
        if (service != null && service.isDetectionEnabled()) {
            if (text.length() > 0) text.append('\n');
            text.append(service.getDetectorModel() != null
                    ? getString(R.string.inference_info, service.getDetectorModel(),
                            (int) service.getLastInferenceMs())
                    : getString(R.string.no_detector));
        }
        if (service != null && service.isVoiceEnabled()) {
            if (text.length() > 0) text.append('\n');
            text.append(getString(service.isTranscribing()
                    ? R.string.status_processing : R.string.status_listening));
        }
        aiStatus.setText(text.length() == 0 ? getString(R.string.status_paused) : text);
    }

    // ---- Log ---------------------------------------------------------------------------------

    /**
     * The Log panel: switched on with the button at its head, and then every face, object and
     * voice result is written down with the time it arrived. It is off to begin with, because a
     * log nobody asked for is only noise on a driving screen.
     */
    private void setupLog() {
        setIcon(findViewById(R.id.log_title), R.drawable.ic_history, 20,
                color(R.color.text_primary), Gravity.START);
        logList = findViewById(R.id.log_list);
        logButton = findViewById(R.id.btn_log);
        setIcon(logButton, R.drawable.ic_play_white, 14, color(R.color.text_primary), Gravity.START);
        logButton.setOnClickListener(v -> setLogging(!logging));
        renderLog();
    }

    private void setLogging(boolean on) {
        logging = on;
        logButton.setActivated(on);
        logButton.setText(on ? R.string.log_stop : R.string.log_start);
        lastObjects = null;
        renderLog(); // whatever was collected stays in view after the log is stopped
    }

    /**
     * One recognition result: the status bar always says it, and the log keeps it while it is
     * running. Button presses go to the status bar only — the log is for what the models report.
     */
    private void recognised(String line) {
        setTip(line);
        if (!logging) return;
        logLines.add(getString(R.string.status_line, clock.format(new Date()), line));
        while (logLines.size() > MAX_LOG) logLines.remove(0);
        renderLog();
    }

    private void renderLog() {
        if (logList == null) return;
        logList.removeAllViews();
        if (logLines.isEmpty()) {
            logList.addView(logLine(getString(logging ? R.string.log_waiting : R.string.log_off),
                    color(R.color.text_muted)));
            return;
        }
        for (int i = logLines.size() - 1; i >= 0; i--) { // newest at the top
            logList.addView(logLine(logLines.get(i), color(R.color.text_primary)));
        }
    }

    private TextView logLine(String text, int textColor) {
        int pad = Math.round(3 * getResources().getDisplayMetrics().density);
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextColor(textColor);
        line.setTextSize(11);
        line.setPadding(0, pad, 0, pad);
        return line;
    }

    /** "person x2, chair" — what is in frame, so a scene that has not changed is logged once. */
    private static String objectSummary(List<ObjectTracker.Snapshot> objects) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ObjectTracker.Snapshot object : objects) {
            Integer seen = counts.get(object.label);
            counts.put(object.label, seen == null ? 1 : seen + 1);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.getKey());
            if (entry.getValue() > 1) text.append(" x").append(entry.getValue());
        }
        return text.toString();
    }

    /** The front lens mirrors the picture, so the overlays have to be mirrored with it. */
    private boolean frontCamera() {
        RobotService service = getRobotService();
        return service == null || service.getLensFacing() == CameraSelector.LENS_FACING_FRONT;
    }

    private final RobotService.Listener recognitionListener = new RobotService.Adapter() {
        @Override
        public void onFaceFrame(List<FaceAnalyzer.FrameFace> faces, int width, int height,
                                long inferenceMs) {
            faceOverlay.setFaces(faces, width, height, frontCamera());
        }

        @Override
        public void onFaceEvent(FaceAnalyzer.FaceEvent event) {
            String who = event.record != null ? event.record.name
                    : getString(R.string.unknown_person);
            recognised(getString(R.string.log_face, who + "  "
                    + getString(R.string.similarity_value, event.similarity)));
        }

        @Override
        public void onObjects(List<ObjectTracker.Snapshot> objects, int width, int height,
                              long inferenceMs) {
            objectOverlay.setObjects(objects, width, height, frontCamera());
            renderAiStatus(); // the inference time is part of what the panel reports
            String summary = objectSummary(objects);
            if (!summary.isEmpty() && !summary.equals(lastObjects)) {
                lastObjects = summary;
                recognised(getString(R.string.log_objects, summary));
            }
        }

        @Override
        public void onTranscript(String text, float confidence, VoiceCommands.Action action,
                                 int result) {
            showVoiceCommand(action, result);
        }

        @Override
        public void onServiceState() {
            renderRecognition();
        }

        @Override
        public void onMessage(String text) {
            toast(text);
        }
    };

    /**
     * A spoken command arrives here after the service has already sent it to the robot, so all
     * that is left is to show it: the figure acts the order out, as it does for the button of the
     * same name, and the tip says what was obeyed.
     *
     * <p>Anything that was not one of the commands is reported as one thing — Unknown Command —
     * and not as whatever the model guessed at, which is noise rather than an order.
     */
    private void showVoiceCommand(VoiceCommands.Action action, int result) {
        boolean heard = action != null && result != R.string.command_unsure;
        recognised(getString(R.string.log_voice, heard ? getString(action.labelRes)
                : result == R.string.no_speech ? getString(R.string.no_speech)
                : getString(R.string.result_no_match)));
        if (!heard) return; // nothing was ordered, so there is nothing for the robot to show
        if (action.command == null) { // the robot answers instead of moving, such as the time
            setTip(getString(R.string.result_answered));
            return;
        }
        showOnModel(action.command);
        setTip(result == R.string.result_executed
                ? getString(R.string.sent_command, getString(action.labelRes))
                : getString(result));
    }

    // ---- Movement --------------------------------------------------------------------------

    private void setupMovement() {
        int white = color(R.color.text_primary);
        setIcon(findViewById(R.id.control_panel_title), R.drawable.ic_gamepad, 20,
                color(R.color.cyan), Gravity.START);
        setIcon(findViewById(R.id.movement_title), R.drawable.ic_crosshair, 16, 0, Gravity.START);
        setIcon(findViewById(R.id.label_speed_slider), R.drawable.ic_run, 16, 0, Gravity.START);

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
        setIcon(button, icon, 18, color(R.color.text_primary), Gravity.TOP);
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
        setIcon(findViewById(R.id.arm_title), R.drawable.ic_arm, 16, 0, Gravity.START);
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
        setIcon(findViewById(R.id.quick_title), R.drawable.ic_bolt, 16, color(R.color.cyan), Gravity.START);
        setIcon(findViewById(R.id.custom_title), R.drawable.ic_star, 16, 0, Gravity.START);
        setIcon(findViewById(R.id.robot_tip), R.drawable.ic_info, 16, 0, Gravity.START);
        tip = findViewById(R.id.robot_tip);
        int white = color(R.color.text_primary);

        TextView home = findViewById(R.id.qa_home);
        setIcon(home, R.drawable.ic_home, 16, white, Gravity.START);
        home.setOnClickListener(v -> {
            if (sendCommand("GO_HOME")) setTip(getString(R.string.sent_command, home.getText()));
        });

        patrol = findViewById(R.id.qa_patrol);
        setIcon(patrol, R.drawable.ic_shield, 16, white, Gravity.START);
        patrol.setOnClickListener(v -> {
            boolean start = !patrol.isActivated();
            if (!sendCommand(start ? "PATROL START" : "PATROL STOP")) return;
            patrol.setActivated(start);
            patrol.setText(start ? R.string.qa_stop_patrol : R.string.qa_start_patrol);
            setTip(getString(R.string.sent_command, getString(start ? R.string.qa_start_patrol : R.string.qa_stop_patrol)));
        });

        follow = findViewById(R.id.qa_follow);
        setIcon(follow, R.drawable.ic_follow, 16, white, Gravity.START);
        follow.setOnClickListener(v -> {
            boolean start = !follow.isActivated();
            if (!sendCommand(start ? "FOLLOW START" : "FOLLOW STOP")) return;
            follow.setActivated(start);
            follow.setText(start ? R.string.qa_stop_follow : R.string.qa_follow_me);
            setTip(getString(R.string.sent_command, getString(start ? R.string.qa_follow_me : R.string.qa_stop_follow)));
        });

        TextView shutdown = findViewById(R.id.qa_shutdown);
        setIcon(shutdown, R.drawable.ic_power, 16, white, Gravity.START);
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

    /** The status bar: the time, and the last thing that happened. */
    private void setTip(CharSequence text) {
        tip.setText(getString(R.string.status_line, clock.format(new Date()), text));
    }
}
