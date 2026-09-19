package com.falcon.robot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.Locale;

/**
 * Base for every screen: full-screen mode, the sidebar shell, the robot connection dialog and
 * small UI helpers shared by the pages.
 */
public abstract class BaseActivity extends ComponentActivity {

    private static final int[] NAV_IDS = {
            R.id.nav_home, R.id.nav_robot, R.id.nav_face, R.id.nav_voice,
            R.id.nav_object, R.id.nav_lidar, R.id.nav_remote, R.id.nav_settings,
    };

    /** Simulated connection delay; replace with the real handshake. */
    private static final long CONNECT_DELAY_MS = 900;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int currentNav;
    private AlertDialog connectionDialog;
    private String appliedLanguage;

    @Override
    protected void attachBaseContext(Context base) {
        appliedLanguage = LocaleHelper.getLanguage(base);
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // a page built before the language was changed still shows the old one
        if (appliedLanguage != null && !appliedLanguage.equals(LocaleHelper.getLanguage(this))) {
            recreate();
            return;
        }
        enterFullScreen();
        refreshSystemStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullScreen();
    }

    @Override
    protected void onDestroy() {
        unbindRobotService();
        uiHandler.removeCallbacksAndMessages(null);
        if (connectionDialog != null) connectionDialog.dismiss();
        super.onDestroy();
    }

    /** Hides the status and navigation bars; a swipe from the edge shows them temporarily. */
    @SuppressWarnings("deprecation")
    private void enterFullScreen() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out);
    }

    // ---- shell & navigation ------------------------------------------------------------

    /** Shows {@code layout} inside the sidebar shell with {@code navId} highlighted. */
    protected void setPage(int layout, int navId, int title) {
        setContentView(R.layout.activity_shell);
        ViewGroup content = findViewById(R.id.page_content);
        LayoutInflater.from(this).inflate(layout, content, true);
        currentNav = navId;

        for (int id : NAV_IDS) {
            TextView item = findViewById(id);
            boolean selected = id == navId;
            item.setSelected(selected);
            item.setTextColor(color(selected ? R.color.white : R.color.nav_label));
            tintTopIcon(item, selected ? R.color.white : R.color.text_primary);
            item.setOnClickListener(v -> navigate(v.getId()));
        }

        View back = findViewById(R.id.header_back);
        if (back != null) back.setOnClickListener(v -> finish());
        TextView titleView = findViewById(R.id.header_title);
        if (titleView != null && title != 0) titleView.setText(title);
    }

    protected void navigate(int navId) {
        if (navId == currentNav) return;
        Class<? extends Activity> target = pageFor(navId);
        if (target == MainActivity.class) {
            finish(); // Home is always at the bottom of the back stack
            return;
        }
        startActivity(new Intent(this, target));
        overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out);
        // keep the stack as Home -> current page
        if (!(this instanceof MainActivity)) finish();
    }

    private static Class<? extends Activity> pageFor(int navId) {
        if (navId == R.id.nav_robot) return RobotControlActivity.class;
        if (navId == R.id.nav_remote) return RemoteControlActivity.class;
        if (navId == R.id.nav_face) return FaceRecognitionActivity.class;
        if (navId == R.id.nav_voice) return VoiceRecognitionActivity.class;
        if (navId == R.id.nav_object) return ObjectDetectionActivity.class;
        if (navId == R.id.nav_lidar) return LidarSlamActivity.class;
        if (navId == R.id.nav_settings) return SettingsActivity.class;
        return MainActivity.class;
    }

    /** Updates the in-app Wi-Fi / battery indicators (the system status bar is hidden). */
    @SuppressWarnings("deprecation")
    private void refreshSystemStatus() {
        TextView battery = findViewById(R.id.status_battery);
        if (battery != null) {
            int percent = readBatteryPercent();
            if (percent >= 0) battery.setText(getString(R.string.percent, percent));
            else battery.setText(R.string.placeholder_value);
        }
        View wifi = findViewById(R.id.status_wifi);
        if (wifi != null) {
            wifi.setAlpha(activeNetworkType() == ConnectivityManager.TYPE_WIFI ? 1f : 0.3f);
        }
    }

    /** Device battery level 0..100, or -1 if unavailable. Never throws (the indicators are cosmetic). */
    protected int readBatteryPercent() {
        try {
            Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) return -1;
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** ConnectivityManager.TYPE_* of the connected network, or -1 when offline/unknown. Never throws. */
    @SuppressWarnings("deprecation")
    protected int activeNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            return info != null && info.isConnected() ? info.getType() : -1;
        } catch (RuntimeException e) {
            return -1; // e.g. SecurityException when ACCESS_NETWORK_STATE is missing
        }
    }

    // ---- robot connection --------------------------------------------------------------

    /** Called after the robot connects or disconnects. */
    protected void onConnectionChanged() {
    }

    // ---- recognition service -------------------------------------------------------------

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                // recognition runs either way; without this the notification is simply hidden
                if (!granted) toast(R.string.notification_permission_needed);
            });

    /**
     * Android 13 and newer ask before the service may show the notification that comes with
     * background camera and microphone use.
     */
    protected void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private RobotService robotService;
    private RobotService.Listener robotListener;
    private boolean serviceBound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            robotService = ((RobotService.LocalBinder) binder).getService();
            if (robotListener != null) robotService.addListener(robotListener);
            onRobotServiceReady(robotService);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            robotService = null;
        }
    };

    /**
     * Connects the page to {@link RobotService}, which keeps face, voice and object recognition
     * running whether or not this page is open. {@link #onRobotServiceReady} follows.
     */
    protected void bindRobotService(RobotService.Listener listener) {
        robotListener = listener;
        serviceBound = bindService(RobotService.intent(this), serviceConnection, BIND_AUTO_CREATE);
    }

    /** The service once it is connected, or null before that. */
    protected RobotService getRobotService() {
        return robotService;
    }

    /** Called on the main thread when the service is ready; the page reads its state here. */
    protected void onRobotServiceReady(RobotService service) {
    }

    private void unbindRobotService() {
        if (!serviceBound) return;
        if (robotService != null && robotListener != null) robotService.removeListener(robotListener);
        unbindService(serviceConnection);
        serviceBound = false;
        robotService = null;
    }

    // ---- gamepad ------------------------------------------------------------------------

    private final GamepadController gamepad = new GamepadController(new GamepadController.Listener() {
        @Override
        public void onGamepadMove(float x, float y, float turn) {
            onGamepadDirection(x, y, turn);
        }

        @Override
        public void onGamepadButton(int keyCode) {
            onGamepadPress(keyCode);
        }

        @Override
        public void onGamepadConnected(String deviceName) {
            toast(getString(R.string.gamepad_connected, deviceName));
        }
    });

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    /**
     * Drives the robot from the pad's sticks, in the same commands the on-screen joystick sends.
     * Pages override this to move their own control as well.
     */
    protected void onGamepadDirection(float x, float y, float turn) {
        if (x == 0 && y == 0 && turn == 0) {
            RobotSession.get().send("MOVE STOP"); // silent: the stick centres constantly
            return;
        }
        if (x == 0 && y == 0) {
            sendCommand(String.format(Locale.US, "TURN %.1f", turn));
            return;
        }
        sendCommand(String.format(Locale.US, "MOVE %.1f %.1f", x, y));
    }

    /** Runs the action a gamepad button stands for. Pages override to add their own. */
    protected void onGamepadPress(int keyCode) {
        String command = commandFor(keyCode);
        if (command == null) return;
        if (sendCommand(command)) toast(getString(R.string.sent_command, command));
    }

    /** The robot command a gamepad button sends, or null for buttons the app ignores. */
    protected String commandFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return "MOVE FORWARD";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "MOVE BACKWARD";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "TURN LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "TURN RIGHT";
            case KeyEvent.KEYCODE_BUTTON_B:
                return "STOP";
            case KeyEvent.KEYCODE_BUTTON_A:
                return "GO_HOME";
            case KeyEvent.KEYCODE_BUTTON_X:
                return "POSE WAVE";
            case KeyEvent.KEYCODE_BUTTON_Y:
                return "POSE DANCE";
            default:
                return null;
        }
    }

    /** Sends a command, or prompts the user to connect first. */
    protected boolean sendCommand(String command) {
        if (RobotSession.get().send(command)) return true;
        if (connectionDialog == null || !connectionDialog.isShowing()) {
            toast(R.string.not_connected);
            showConnectionDialog();
        }
        return false;
    }

    protected void showConnectionDialog() {
        if (connectionDialog != null && connectionDialog.isShowing()) return;
        final RobotSession session = RobotSession.get();
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_connection, null);
        final EditText inputIp = view.findViewById(R.id.input_ip);
        final EditText inputPort = view.findViewById(R.id.input_port);
        TextView status = view.findViewById(R.id.connection_status);

        inputIp.setText(session.getHost());
        inputPort.setText(String.valueOf(session.getPort()));
        final boolean connected = session.isConnected();
        setStatus(status, connected ? connectionLabel() : getString(R.string.status_disconnected),
                connected ? R.drawable.dot_teal : R.drawable.dot_gray);

        inputIp.setEnabled(!connected);
        inputPort.setEnabled(!connected);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.settings_connection)
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(connected ? R.string.disconnect : R.string.connect, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (session.isConnected()) {
                session.setConnected(false);
                dialog.dismiss();
                onConnectionChanged();
                return;
            }
            int port;
            try {
                port = Integer.parseInt(inputPort.getText().toString().trim());
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port <= 0 || port > 65535) {
                inputPort.setError(getString(R.string.invalid_port));
                return;
            }
            session.setAddress(inputIp.getText().toString().trim(), port);
            dialog.dismiss();
            toast(R.string.status_connecting);
            uiHandler.postDelayed(() -> {
                session.setConnected(true);
                toast(connectionLabel());
                onConnectionChanged();
            }, CONNECT_DELAY_MS);
        }));
        connectionDialog = dialog;
        dialog.show();
    }

    /** e.g. "Connected · Wi-Fi". */
    protected String connectionLabel() {
        RobotSession session = RobotSession.get();
        return getString(R.string.connected_via, session.getHost() + ":" + session.getPort());
    }

    /** Binds a status line to the connection state; tapping it opens the connection dialog. */
    protected void bindConnectionStatus(TextView view) {
        boolean connected = RobotSession.get().isConnected();
        setStatus(view, connected ? connectionLabel() : getString(R.string.status_disconnected),
                connected ? R.drawable.dot_teal : R.drawable.dot_gray);
        view.setOnClickListener(v -> showConnectionDialog());
    }

    // ---- layout ------------------------------------------------------------------------

    /**
     * Arranges the children of {@code R.id.columns} side by side on wide screens
     * ({@code R.bool.two_columns}) or stacked on narrow ones. A child's {@code android:tag}
     * may hold its column width weight (default 1).
     *
     * <p>Pages fill the screen height: side by side, every column takes the full height; stacked,
     * the columns share any leftover height. Pages sit in a {@code ScrollView} with
     * {@code fillViewport}, so they only scroll when the screen is too small for the content.
     */
    protected void setupColumns() {
        setupColumns(R.id.columns);
    }

    /** Same as {@link #setupColumns()} for another row container. */
    protected void setupColumns(int containerId) {
        LinearLayout columns = findViewById(containerId);
        boolean side = getResources().getBoolean(R.bool.two_columns);
        int gap = getResources().getDimensionPixelSize(R.dimen.gap);
        columns.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < columns.getChildCount(); i++) {
            View child = columns.getChildAt(i);
            LinearLayout.LayoutParams lp;
            if (side) {
                float weight = 1f;
                if (child.getTag() instanceof String) {
                    try {
                        weight = Float.parseFloat((String) child.getTag());
                    } catch (NumberFormatException ignored) {
                        // keep default weight
                    }
                }
                lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
                if (i > 0) lp.setMarginStart(gap);
            } else {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.topMargin = gap;
            }
            child.setLayoutParams(lp);
        }
    }

    protected void bindSpeed(int seekId, int valueId) {
        final TextView value = findViewById(valueId);
        SeekBar seek = findViewById(seekId);
        value.setText(getString(R.string.percent, seek.getProgress()));
        seek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(getString(R.string.percent, progress));
            }
        });
    }

    /** SeekBar listener with empty start/stop callbacks. */
    protected abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    // ---- small view helpers ------------------------------------------------------------

    protected static void setStatus(TextView view, int text, int dot) {
        view.setText(text);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0);
    }

    protected static void setStatus(TextView view, CharSequence text, int dot) {
        view.setText(text);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0);
    }

    /**
     * Sets a sized, tinted compound drawable on a TextView.
     * {@code gravity} is {@link android.view.Gravity#START} or {@link android.view.Gravity#TOP}.
     */
    protected void setIcon(TextView view, int drawable, int sizeDp, int color, int gravity) {
        Drawable icon = getResources().getDrawable(drawable).mutate();
        int size = Math.round(sizeDp * getResources().getDisplayMetrics().density);
        icon.setBounds(0, 0, size, size);
        if (color != 0) icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (gravity == android.view.Gravity.TOP) {
            view.setCompoundDrawablesRelative(null, icon, null, null);
        } else {
            view.setCompoundDrawablesRelative(icon, null, null, null);
        }
    }

    // ---- log panels ------------------------------------------------------------------------

    public static final int LOG_INFO = 0xFF3FA0FF;
    public static final int LOG_OK = 0xFF1ED9A4;
    public static final int LOG_WARN = 0xFFFFC53D;
    public static final int LOG_ERROR = 0xFFFF4D5E;

    /** Appends "● HH:mm:ss  message" to a log list, trims it to {@code maxLines} and scrolls to the end. */
    protected void appendLog(LinearLayout list, final android.widget.ScrollView scroll, int dotColor,
                             String message, int maxLines) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(4 * density), Math.round(2 * density), 0, Math.round(2 * density));

        View dot = new View(this);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(dotColor);
        dot.setBackground(circle);
        int size = Math.round(7 * density);
        row.addView(dot, new LinearLayout.LayoutParams(size, size));

        TextView time = new TextView(this);
        time.setText(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
        time.setTextColor(color(R.color.text_primary));
        time.setTextSize(11);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        timeLp.setMarginStart(Math.round(8 * density));
        row.addView(time, timeLp);

        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(color(R.color.text_primary));
        text.setTextSize(11);
        text.setSingleLine(true);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(Math.round(14 * density));
        row.addView(text, textLp);

        list.addView(row);
        while (list.getChildCount() > maxLines) list.removeViewAt(0);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---- large page header (view_page_header_rich) --------------------------------------

    protected void setupRichHeader(int icon, int title, int subtitle) {
        ImageView iconView = findViewById(R.id.header_icon);
        iconView.setImageResource(icon);
        iconView.setColorFilter(color(R.color.cyan), PorterDuff.Mode.SRC_IN);
        ((TextView) findViewById(R.id.header_title)).setText(title);
        ((TextView) findViewById(R.id.header_subtitle)).setText(subtitle);
        findViewById(R.id.header_pill).setOnClickListener(v -> showConnectionDialog());
        refreshRichHeader();
    }

    /** Robot connection, address and (simulated) robot battery in the header pill. */
    protected void refreshRichHeader() {
        TextView connection = findViewById(R.id.header_connection);
        if (connection == null) return;
        RobotSession session = RobotSession.get();
        boolean connected = session.isConnected();
        setStatus(connection, connected ? R.string.status_connected_short : R.string.status_offline_short,
                connected ? R.drawable.dot_teal : R.drawable.dot_gray);
        connection.setTextColor(color(connected ? R.color.teal : R.color.text_secondary));
        ((TextView) findViewById(R.id.header_ip)).setText(session.getHost());
        ((TextView) findViewById(R.id.header_battery)).setText(
                connected ? getString(R.string.demo_battery) : getString(R.string.placeholder_value));
        ((ImageView) findViewById(R.id.header_battery_icon)).setColorFilter(
                color(connected ? R.color.teal : R.color.text_muted), PorterDuff.Mode.SRC_IN);
    }

    /** Tints the top compound drawable of a button-like TextView. */
    protected void tintTopIcon(TextView view, int colorRes) {
        Drawable icon = view.getCompoundDrawables()[1];
        if (icon != null) icon.mutate().setColorFilter(color(colorRes), PorterDuff.Mode.SRC_IN);
    }

    @SuppressWarnings("deprecation")
    protected int color(int colorRes) {
        return getResources().getColor(colorRes);
    }

    protected void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    protected void toast(CharSequence text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---- animations --------------------------------------------------------------------

    /** Endless breathing animation for the microphone ring. Caller starts/cancels it. */
    protected static ObjectAnimator createPulse(View ring) {
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.05f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.05f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f));
        pulse.setDuration(1100);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        return pulse;
    }

    protected static void resetPulse(View ring) {
        ring.setScaleX(1f);
        ring.setScaleY(1f);
        ring.setAlpha(0.5f);
    }

    /** Sweeps {@code line} up and down over {@code container}, then runs {@code onDone}. */
    protected static ObjectAnimator startScan(final View line, View container, final Runnable onDone) {
        line.setVisibility(View.VISIBLE);
        float travel = container.getHeight() - line.getHeight();
        ObjectAnimator scan = ObjectAnimator.ofFloat(line, View.TRANSLATION_Y, 0f, travel);
        scan.setDuration(900);
        scan.setRepeatMode(ValueAnimator.REVERSE);
        scan.setRepeatCount(3);
        scan.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                line.setVisibility(View.INVISIBLE);
                if (!cancelled && onDone != null) onDone.run();
            }
        });
        scan.start();
        return scan;
    }
}
