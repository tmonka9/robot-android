package com.falcon.robot;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.falcon.robot.detect.CocoLabels;
import com.falcon.robot.detect.ObjectTracker;
import com.falcon.robot.face.FaceAnalyzer;
import com.falcon.robot.voice.VoiceCommands;
import com.falcon.robot.widget.CoverImageView;
import com.falcon.robot.widget.FaceOverlayView;
import com.falcon.robot.widget.LidarMapView;
import com.falcon.robot.widget.Robot3DView;
import com.falcon.robot.widget.TrackingOverlayView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Robot Control page (design/robot.png): robot status, the camera feed with its image settings,
 * the LiDAR map, and a log of what the models recognised. Robot telemetry and the camera stream
 * are simulated until the real robot protocol exists.
 *
 * <p>The sidebar down the right-hand side holds what the operator asks for: the three recognition
 * switches, the four directions, the arm, the preset poses and the quick actions. Faces and objects are drawn over the feed, a spoken command is carried out exactly as the
 * button for it would be, and the status bar along the foot says what happened last. The sidebar
 * and the status bar sit outside the scrolling page, so they are in reach wherever it is scrolled.
 */
public class RobotControlActivity extends BaseActivity {

    /** An OBJ export of the real robot, if one is added to the assets. */
    private static final String ROBOT_MODEL_ASSET = "xiaoao.obj";

    /** The robot overlay on the camera feed, in dp: width and height, then the same full screen. */
    private static final int[] OVERLAY_DP = {150, 118, 255, 200};

    /** The Follow me switch, which the button and the spoken order share. */
    private static final String FOLLOW = VoiceCommands.FOLLOW;

    /** The recognition features, as {@link #toggleFeature} counts them. */
    private static final int NO_FEATURE = -1;
    private static final int FACE = 0;
    private static final int OBJECTS = 1;
    private static final int VOICE = 2;

    /** How many lines the Log panel keeps; it scrolls, and older than this is of no use. */
    private static final int MAX_LOG = 80;

    /**
     * How often the direction that is latched on is sent again. A move used to last only as long
     * as the robot's own timer allowed; repeating it keeps the robot going until it is stopped.
     */
    private static final long KEEP_ALIVE_MS = 1000;

    /** The four sidebar directions, in button order. */
    private static final String[] MOVE_COMMANDS =
            {"MOVE FORWARD", "MOVE BACKWARD", "TURN LEFT", "TURN RIGHT"};

    /** The arm buttons and the pose buttons, in button order. */
    private static final String[] ARM_COMMANDS = {"ARM SELECT LEFT_ARM", "ARM SELECT RIGHT_ARM"};
    private static final String[] POSE_COMMANDS =
            {"POSE WAVE", "GREET", "POSE T_POSE", "POSE DANCE"};

    private final RobotSession session = RobotSession.get();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private TextView valueMode;
    private TextView valueSpeed;
    private TextView valueBattery;
    private TextView valueTemperature;
    private TextView positionX;
    private TextView positionY;
    private TextView positionZ;
    private TextView tip;
    private CoverImageView cameraFeed;
    private Robot3DView robot3D;
    private TextView cameraInfo;
    private TextView cameraSource;
    private ImageView recordButton;
    private SeekBar brightness;
    private SeekBar contrast;
    private SeekBar saturation;
    private Spinner resolution;
    private Spinner fps;
    private TextView[] moveButtons;
    /** The direction latched on, and the command it repeats; null when the robot is not moving. */
    private TextView heldButton;
    private String heldCommand;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView[] armParts;
    private TextView[] poses;
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
    private LidarMapView lidarMap;
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
        setupLidar();
        setupLog();
        setupMovement();
        setupArm();
        setupActions();
        bindRobotService(recognitionListener);
    }

    @Override
    protected void onRobotServiceReady(RobotService service) {
        attachPreview();
        renderRecognition();
        renderRecording();
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
        } else if (!cameraPermissionAsked()) {
            // asked once, and never again on the way back to this page: without the camera the
            // design artwork stays in the panel, which is still a usable page
            noteCameraPermissionAsked();
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (robot3D != null) robot3D.onResume();
        if (feedOverlay != null) feedOverlay.onResume();
        if (lidarMap != null) lidarMap.setScanning(true);
        attachPreview();
        renderRecognition(); // the features can have been switched on elsewhere
        onConnectionChanged();
    }

    @Override
    protected void onPause() {
        if (heldCommand != null) stopMoving(); // nothing repeats the command once this page stops
        if (lidarMap != null) lidarMap.setScanning(false); // no sweep while the page is away
        if (robot3D != null) robot3D.onPause();
        if (feedOverlay != null) feedOverlay.onPause();
        RobotService service = getRobotService();
        if (service != null && previewView != null) {
            service.detachPreview(previewView.getSurfaceProvider());
        }
        super.onPause();
    }

    @Override
    protected void onConnectionChanged() {
        refreshStatus();
    }

    /**
     * Every command goes past here, so the 3D robot acts out whatever was asked for: it walks on
     * a move, waves on a greeting, raises the arm that was selected. It shows the command rather
     * than the robot's own state, so it still demonstrates the action while nothing is connected
     * — which is why {@link #report} says, for every command, whether it reached the robot, the
     * demo link, or nothing at all.
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

        showOnlyFullscreen(R.id.columns);
        showOnlyFullscreen(R.id.columns_bottom);

        boolean camera = fullscreenPanel == R.id.camera_panel;
        // the overlay is a surface drawn over the window, and a hidden ancestor does not reach it:
        // without this it would go on drawing the robot over whatever took the panel's place
        if (feedOverlay != null) {
            feedOverlay.setVisibility(!full || camera ? View.VISIBLE : View.GONE);
        }
        sizeFeedOverlay(camera);
        // the LiDAR panel is pinned to the robot's width; full screen it takes the row instead
        if (fullscreenPanel == R.id.lidar_panel) {
            View lidar = findViewById(R.id.lidar_panel);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) lidar.getLayoutParams();
            lp.width = 0;
            lp.weight = 1f;
            lidar.setLayoutParams(lp);
        } else {
            matchLidarWidth();
        }

        setFullscreenIcon(R.id.robot_fullscreen, R.id.robot_status_panel);
        setFullscreenIcon(R.id.camera_fullscreen, R.id.camera_panel);
        setFullscreenIcon(R.id.lidar_fullscreen, R.id.lidar_panel);
    }

    /** Leaves one panel in a row and hides the rest — and the row too, when it holds none. */
    private void showOnlyFullscreen(int rowId) {
        boolean full = fullscreenPanel != 0;
        LinearLayout row = findViewById(rowId);
        boolean holdsIt = false;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            boolean keep = !full || child.getId() == fullscreenPanel;
            child.setVisibility(keep ? View.VISIBLE : View.GONE);
            holdsIt |= full && keep;
        }
        row.setVisibility(!full || holdsIt ? View.VISIBLE : View.GONE);
    }

    private void setFullscreenIcon(int buttonId, int panelId) {
        boolean on = fullscreenPanel == panelId;
        ImageView button = findViewById(buttonId);
        button.setImageResource(on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        button.setContentDescription(getString(on ? R.string.exit_full_screen : R.string.full_screen));
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
        // the speed the robot reports, now that this page has no slider of its own to set it
        valueSpeed.setText(connected ? R.string.speed_normal : R.string.placeholder_value);
    }

    /**
     * Where the robot is. Driving happens on the Remote Control page, so nothing here moves the
     * figures; they wait for the real protocol to report a position.
     */
    private void refreshPosition() {
        positionX.setText(getString(R.string.position_x, 0f));
        positionY.setText(getString(R.string.position_y, 0f));
        positionZ.setText(getString(R.string.position_z, 0f));
    }

    // ---- Camera ----------------------------------------------------------------------------

    private void setupCamera() {
        setIcon(findViewById(R.id.camera_title), R.drawable.ic_camera, 16, color(R.color.cyan), Gravity.START);
        setIcon(findViewById(R.id.camera_settings_title), R.drawable.ic_tune, 16,
                color(R.color.blue_light), Gravity.START);
        cameraFeed = findViewById(R.id.camera_feed);
        cameraFeed.setFocus(0.25f, 0.2f, 0.75f, 1f); // keep the robot in frame
        findViewById(R.id.camera_feed_frame).setClipToOutline(true);

        ImageView cameraFull = findViewById(R.id.camera_fullscreen);
        cameraFull.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        cameraFull.setOnClickListener(v -> toggleFullscreen(R.id.camera_panel));

        cameraSource = findViewById(R.id.camera_source);
        cameraSource.setOnClickListener(this::showCameraMenu);

        recordButton = findViewById(R.id.camera_record);
        recordButton.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.camera_open).setOnClickListener(v -> showRecordings());
        renderRecording();

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
        updateCameraSource();
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

    /** Which camera the feed comes from; the lens belongs to the service, and pages share it. */
    /**
     * Records what the camera shows. It is the service that does it, so the recording goes on
     * while the operator is on another page, and the button says so when they come back.
     */
    private void toggleRecording() {
        RobotService service = getRobotService();
        if (service == null) return;
        boolean start = !service.isRecording();
        if (start && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            attachPreview(); // asks for the camera, once, exactly as the picture does
            toast(R.string.camera_permission_needed);
            return;
        }
        service.setRecording(start);
        // what actually happened: starting can fail, and the service says why in a message
        boolean now = service.isRecording();
        setTip(getString(now ? R.string.recording_started : R.string.recording_stopped));
        renderRecording();
    }

    private void renderRecording() {
        if (recordButton == null) return;
        RobotService service = getRobotService();
        boolean recording = service != null && service.isRecording();
        recordButton.setImageResource(recording
                ? R.drawable.ic_record_stop : R.drawable.ic_record_dot);
        recordButton.setContentDescription(getString(recording
                ? R.string.stop_recording : R.string.record_video));
    }

    /** The recordings, newest first; picking one hands it to whatever plays video. */
    private void showRecordings() {
        RobotService service = getRobotService();
        final List<File> files = service == null ? new ArrayList<File>() : service.recordings();
        if (files.isEmpty()) {
            toast(R.string.no_recordings);
            return;
        }
        CharSequence[] names = new CharSequence[files.size()];
        for (int i = 0; i < files.size(); i++) names[i] = files.get(i).getName();
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.open_recordings)
                .setItems(names, (d, which) -> play(files.get(which)))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void play(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // no video player, or a file outside what the provider shares: it is still on the
            // tablet either way, and the name says where to look for it
            toast(getString(R.string.no_video_player, file.getName()));
        }
    }

    private void showCameraMenu(View anchor) {
        final RobotService service = getRobotService();
        if (service == null) return;
        final String[] sources = getResources().getStringArray(R.array.camera_sources);
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < sources.length; i++) menu.getMenu().add(0, i, i, sources[i]);
        menu.setOnMenuItemClickListener(item -> {
            service.setLensFacing(item.getItemId() == 0 ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK);
            updateCameraSource();
            return true;
        });
        menu.show();
    }

    private void updateCameraSource() {
        if (cameraSource == null) return;
        int index = frontCamera() ? 0 : 1;
        cameraSource.setText(getResources().getStringArray(R.array.camera_sources)[index]);
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

        aiStatus = findViewById(R.id.ai_status);
        setIcon(findViewById(R.id.ai_title), R.drawable.ic_chip, 14, color(R.color.cyan), Gravity.START);
        faceButton = bindFeature(R.id.toggle_face, R.drawable.ic_face_id, FACE);
        objectButton = bindFeature(R.id.toggle_object, R.drawable.ic_cube, OBJECTS);
        voiceButton = bindFeature(R.id.toggle_voice, R.drawable.ic_mic, VOICE);
        renderRecognition();
    }

    private TextView bindFeature(int id, int icon, final int feature) {
        TextView button = findViewById(id);
        setIcon(button, icon, 14, color(R.color.text_primary), Gravity.START);
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
            if (feature != VOICE) noteCameraPermissionAsked();
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
        updateCameraSource(); // the lens can have been switched from another page

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
            if (text.length() > 0) text.append("  \u00b7  ");
            text.append(service.getDetectorModel() != null
                    ? getString(R.string.inference_info, service.getDetectorModel(),
                            (int) service.getLastInferenceMs())
                    : getString(R.string.no_detector));
        }
        if (service != null && service.isVoiceEnabled()) {
            if (text.length() > 0) text.append("  \u00b7  ");
            text.append(getString(service.isTranscribing()
                    ? R.string.status_processing : R.string.status_listening));
        }
        aiStatus.setText(text.length() == 0 ? getString(R.string.status_paused) : text);
    }

    // ---- LiDAR -------------------------------------------------------------------------------

    /**
     * The LiDAR map, in the bottom row under the robot it belongs to. The map is the same one the
     * LiDAR page draws, and the title opens that page for the settings and the readouts.
     */
    private void setupLidar() {
        TextView title = findViewById(R.id.lidar_title);
        setIcon(title, R.drawable.ic_lidar, 16, color(R.color.cyan), Gravity.START);
        title.setOnClickListener(v -> navigate(R.id.nav_lidar));
        lidarMap = findViewById(R.id.lidar_map);

        ImageView loadMap = findViewById(R.id.lidar_load_map);
        loadMap.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        loadMap.setOnClickListener(v -> showMaps());

        ImageView full = findViewById(R.id.lidar_fullscreen);
        full.setColorFilter(color(R.color.text_primary), PorterDuff.Mode.SRC_IN);
        full.setOnClickListener(v -> toggleFullscreen(R.id.lidar_panel));

        // the panel is asked to be exactly as wide as the robot above it, which only the layout
        // knows: the two rows have a different number of gaps, so equal weights are not equal widths
        findViewById(R.id.robot_status_panel).addOnLayoutChangeListener(
                (v, l, top, r, b, ol, ot, or_, ob) -> matchLidarWidth());
        matchLidarWidth();
    }

    /** The saved maps, as the LiDAR page lists them; picking one asks the robot to load it. */
    private void showMaps() {
        final String[] maps = getResources().getStringArray(R.array.saved_maps);
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.load_map)
                .setItems(maps, (d, which) -> {
                    if (!sendCommand("SLAM LOAD_MAP " + maps[which])) return;
                    setTip(getString(R.string.log_map_loaded, maps[which]));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void matchLidarWidth() {
        View robot = findViewById(R.id.robot_status_panel);
        View lidar = findViewById(R.id.lidar_panel);
        if (robot == null || lidar == null) return;
        if (fullscreenPanel == R.id.lidar_panel) return; // it has the row to itself just now
        if (!getResources().getBoolean(R.bool.two_columns)) return; // stacked: both are full width
        int width = robot.getWidth();
        if (width <= 0) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) lidar.getLayoutParams();
        if (lp.width == width && lp.weight == 0f) return; // already right: do not ask for another pass
        lp.width = width;
        lp.weight = 0f;
        lidar.setLayoutParams(lp);
    }

    // ---- Log ---------------------------------------------------------------------------------

    /**
     * The Log panel: switched on with the button at its head, and then every face, object and
     * voice result is written down with the time it arrived. It is off to begin with, because a
     * log nobody asked for is only noise on a driving screen.
     */
    private void setupLog() {
        setIcon(findViewById(R.id.log_title), R.drawable.ic_history, 16,
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
            renderRecording();
        }

        @Override
        public void onMessage(String text) {
            toast(text);
        }
    };

    /**
     * A spoken command arrives here after the service has already dealt with it, so what is
     * left is to show it: the figure acts the order out, the button for that order lights up as
     * though it had been pressed, and the line underneath says where the command went.
     *
     * <p>That line matters: the figure moves for every order that was understood, whether or not
     * anything reached the robot. Only the report says whether it did.
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
        showOnModel(action.command);
        followVoice(action, result);
        if (action.command == null) { // the tablet's own work: face, objects, the recorder
            recognised(getString(R.string.done_here, getString(action.labelRes)));
            return;
        }
        report(action.command, result == R.string.result_executed);
    }

    // ---- Move ------------------------------------------------------------------------------

    /**
     * Move Control: the four directions, one button each. A press latches the button on and the
     * command is repeated every second, so the robot keeps going; pressing the lit button again,
     * or leaving the page, stops it. There is no Stop button because the lit button is the stop.
     */
    private void setupMovement() {
        setIcon(findViewById(R.id.move_title), R.drawable.ic_crosshair, 14, 0, Gravity.START);
        int white = color(R.color.text_primary);

        moveButtons = new TextView[] {
                findViewById(R.id.move_forward), findViewById(R.id.move_backward),
                findViewById(R.id.move_left), findViewById(R.id.move_right),
        };
        int[] icons = {R.drawable.ic_arrow_up, R.drawable.ic_arrow_down,
                R.drawable.ic_arrow_left, R.drawable.ic_arrow_right};
        for (int i = 0; i < moveButtons.length; i++) {
            final int index = i;
            setIcon(moveButtons[i], icons[i], 14, white, Gravity.START);
            moveButtons[i].setOnClickListener(v -> hold(index, MOVE_COMMANDS[index], true));
        }

        TextView stop = findViewById(R.id.move_stop);
        setIcon(stop, R.drawable.ic_square, 14, white, Gravity.START);
        stop.setOnClickListener(v -> stopMoving());
    }

    /**
     * Latches a direction on and repeats {@code command} until something stops it. {@code send}
     * is false when the command has already gone out: a spoken order is sent by the service, and
     * the page only takes the repeating over — with the wording the robot was first told, prefix
     * and all, so what it hears every second is what it heard to begin with.
     */
    private void hold(int index, String command, boolean send) {
        TextView button = moveButtons[index];
        if (heldButton == button && send) { // pressing the lit button again is how it is stopped
            stopMoving();
            return;
        }
        if (send && !sendCommand(command)) return;
        heldButton = button;
        heldCommand = command;
        selectOnly(moveButtons, button);
        if (send) setTip(getString(R.string.sent_command, button.getText()));
        handler.removeCallbacks(keepAlive);
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS);
    }

    /** Ends the movement: the robot is told to stop and the button goes out. */
    private void stopMoving() {
        releaseHold();
        showOnModel("STOP");
        // stop is never held up by the connect prompt, but it is still reported like the rest
        report("STOP", session.send("STOP"));
    }

    /** Lets go of the latch without ordering anything, for when something else has taken over. */
    private void releaseHold() {
        handler.removeCallbacks(keepAlive);
        heldButton = null;
        heldCommand = null;
        if (moveButtons != null) selectOnly(moveButtons, null);
    }

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (heldCommand == null) return;
            session.send(heldCommand); // silently: the prompt was answered when it was pressed
            handler.postDelayed(this, KEEP_ALIVE_MS);
        }
    };

    /**
     * Shows a spoken order on the buttons it belongs to: the direction latches on exactly as the
     * button does, so "Forward" keeps going and "Stop" ends it; the arm, the pose and Follow me
     * light up the same way. Nothing is sent from here — the service has already done that.
     */
    private void followVoice(VoiceCommands.Action action, int result) {
        if (action == null || action.command == null) return;
        if ("STOP".equals(action.command) || "CANCEL".equals(action.command)) {
            releaseHold();
            return;
        }
        if (result != R.string.result_executed) return; // it never left, so show nothing as on
        if (moveButtons != null) {
            for (int i = 0; i < MOVE_COMMANDS.length; i++) {
                if (MOVE_COMMANDS[i].equals(action.command)) {
                    hold(i, action.command, false);
                    return;
                }
            }
        }
        if (armParts != null) {
            for (int i = 0; i < ARM_COMMANDS.length; i++) {
                if (ARM_COMMANDS[i].equals(action.command)) {
                    selectOnly(armParts, armParts[i]);
                    return;
                }
            }
        }
        if (poses != null) {
            for (int i = 0; i < POSE_COMMANDS.length; i++) {
                if (POSE_COMMANDS[i].equals(action.command)) {
                    selectOnly(poses, poses[i]);
                    return;
                }
            }
        }
        // Follow me: the service has flipped the switch, so the button follows it
        if (FOLLOW.equals(action.name) && follow != null) setFollowing(VoiceCommands.isSwitchedOn(FOLLOW), false);
    }

    // ---- Arm -------------------------------------------------------------------------------

    private void setupArm() {
        setIcon(findViewById(R.id.arm_title), R.drawable.ic_arm, 14, 0, Gravity.START);
        int white = color(R.color.text_primary);

        armParts = new TextView[] {
                findViewById(R.id.arm_left), findViewById(R.id.arm_right),
        };
        for (int i = 0; i < armParts.length; i++) {
            final int index = i;
            setIcon(armParts[i], R.drawable.ic_arm, 14, white, Gravity.START);
            armParts[i].setOnClickListener(v -> {
                if (!sendAndReport(ARM_COMMANDS[index])) return;
                selectOnly(armParts, armParts[index]);
            });
        }

        poses = new TextView[] {
                findViewById(R.id.pose_wave), findViewById(R.id.pose_hello),
                findViewById(R.id.pose_tpose), findViewById(R.id.pose_dance),
        };
        int[] poseIcons = {R.drawable.ic_pose_wave, R.drawable.ic_person,
                R.drawable.ic_tpose, R.drawable.ic_robot};
        for (int i = 0; i < poses.length; i++) {
            final int index = i;
            setIcon(poses[i], poseIcons[i], 14, white, Gravity.START);
            poses[i].setOnClickListener(v -> {
                if (!sendAndReport(POSE_COMMANDS[index])) return;
                selectOnly(poses, poses[index]);
            });
        }
    }

    private static void selectOnly(TextView[] group, TextView selected) {
        for (TextView item : group) item.setActivated(item == selected);
    }

    // ---- Quick actions -----------------------------------------------------------------------

    private void setupActions() {
        setIcon(findViewById(R.id.quick_title), R.drawable.ic_bolt, 14, color(R.color.cyan), Gravity.START);
        setIcon(findViewById(R.id.robot_tip), R.drawable.ic_info, 16, 0, Gravity.START);
        tip = findViewById(R.id.robot_tip);
        int white = color(R.color.text_primary);

        follow = findViewById(R.id.qa_follow);
        setIcon(follow, R.drawable.ic_follow, 14, white, Gravity.START);
        follow.setOnClickListener(v -> setFollowing(!VoiceCommands.isSwitchedOn(FOLLOW), true));
        setFollowing(VoiceCommands.isSwitchedOn(FOLLOW), false);

        TextView recording = findViewById(R.id.qa_recording);
        setIcon(recording, R.drawable.ic_videocam, 14, white, Gravity.START);
        recording.setOnClickListener(v -> toggleRecording());

        TextView no = findViewById(R.id.qa_no);
        setIcon(no, R.drawable.ic_square, 14, white, Gravity.START);
        no.setOnClickListener(v -> cancel());

        TextView home = findViewById(R.id.qa_home);
        setIcon(home, R.drawable.ic_home, 14, white, Gravity.START);
        home.setOnClickListener(v -> sendAndReport("GO_HOME"));

        TextView shutdown = findViewById(R.id.qa_shutdown);
        setIcon(shutdown, R.drawable.ic_power, 14, white, Gravity.START);
        shutdown.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.qa_shutdown)
                .setMessage(R.string.shutdown_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.qa_shutdown, (d, w) -> sendAndReport("SHUTDOWN"))
                .show());
    }

    /**
     * Follow me is a switch, and the button and the spoken order share which way it is set
     * ({@link VoiceCommands#isSwitchedOn}), so saying it and tapping it cannot disagree.
     */
    private void setFollowing(boolean on, boolean send) {
        if (send && !sendAndReport(on ? "FOLLOW START" : "FOLLOW STOP")) return;
        VoiceCommands.setSwitchedOn(FOLLOW, on);
        follow.setActivated(on);
        follow.setText(on ? R.string.qa_stop_follow : R.string.qa_follow_me);
    }

    /** No: the robot is told to drop what it was asked for, and the page lets go of its latch. */
    private void cancel() {
        releaseHold();
        sendAndReport("CANCEL");
    }

    private void setTip(CharSequence text) {
        tip.setText(getString(R.string.status_line, clock.format(new Date()), text));
    }

    /**
     * Sends a command and says what became of it: which link carried it, that the link is the
     * demo one and nothing left the tablet, or that there is no robot to send to. The 3D figure
     * acts every order out whether or not it was delivered, so this is the part to read.
     */
    private boolean sendAndReport(String command) {
        boolean sent = sendCommand(command); // offers the connection dialog when there is none
        report(command, sent);
        return sent;
    }

    private void report(String command, boolean sent) {
        int line = !sent ? R.string.not_sent_no_robot
                : session.isLive() ? R.string.sent_via_ble : R.string.sent_demo;
        recognised(getString(line, command)); // the status bar, and the log while it is running
    }
}
