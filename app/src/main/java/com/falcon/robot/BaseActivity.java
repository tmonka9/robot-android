package com.falcon.robot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Base for every screen: full-screen mode, the sidebar shell, the robot connection dialog and
 * small UI helpers shared by the pages.
 */
public abstract class BaseActivity extends Activity {

    private static final int[] NAV_IDS = {
            R.id.nav_home, R.id.nav_robot, R.id.nav_remote, R.id.nav_face,
            R.id.nav_voice, R.id.nav_object, R.id.nav_settings,
    };

    /** Simulated connection delay; replace with the real handshake. */
    private static final long CONNECT_DELAY_MS = 900;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int currentNav;
    private AlertDialog connectionDialog;

    @Override
    protected void onResume() {
        super.onResume();
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
        if (navId == R.id.nav_settings) return SettingsActivity.class;
        return MainActivity.class;
    }

    /** Updates the in-app Wi-Fi / battery indicators (the system status bar is hidden). */
    @SuppressWarnings("deprecation")
    private void refreshSystemStatus() {
        TextView battery = findViewById(R.id.status_battery);
        if (battery != null) {
            Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status != null) {
                int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    battery.setText(getString(R.string.percent, Math.round(level * 100f / scale)));
                }
            }
        }
        View wifi = findViewById(R.id.status_wifi);
        if (wifi != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            boolean onWifi = info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
            wifi.setAlpha(onWifi ? 1f : 0.3f);
        }
    }

    // ---- robot connection --------------------------------------------------------------

    /** Called after the robot connects or disconnects. */
    protected void onConnectionChanged() {
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
        final TextView linkWifi = view.findViewById(R.id.link_wifi);
        final TextView linkBluetooth = view.findViewById(R.id.link_bluetooth);
        final View wifiFields = view.findViewById(R.id.wifi_fields);
        final EditText inputIp = view.findViewById(R.id.input_ip);
        final EditText inputPort = view.findViewById(R.id.input_port);
        TextView status = view.findViewById(R.id.connection_status);

        inputIp.setText(session.getHost());
        inputPort.setText(String.valueOf(session.getPort()));
        final boolean connected = session.isConnected();
        setStatus(status, connected ? connectionLabel() : getString(R.string.status_disconnected),
                connected ? R.drawable.dot_teal : R.drawable.dot_gray);

        final RobotSession.Transport[] transport = {session.getTransport()};
        View.OnClickListener select = v -> {
            if (connected) return; // disconnect before switching transport
            transport[0] = v == linkWifi ? RobotSession.Transport.WIFI : RobotSession.Transport.BLUETOOTH;
            linkWifi.setSelected(transport[0] == RobotSession.Transport.WIFI);
            linkBluetooth.setSelected(transport[0] == RobotSession.Transport.BLUETOOTH);
            wifiFields.setVisibility(transport[0] == RobotSession.Transport.WIFI ? View.VISIBLE : View.GONE);
        };
        linkWifi.setOnClickListener(select);
        linkBluetooth.setOnClickListener(select);
        linkWifi.setSelected(transport[0] == RobotSession.Transport.WIFI);
        linkBluetooth.setSelected(transport[0] == RobotSession.Transport.BLUETOOTH);
        wifiFields.setVisibility(transport[0] == RobotSession.Transport.WIFI ? View.VISIBLE : View.GONE);
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
            if (transport[0] == RobotSession.Transport.WIFI) {
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
            }
            session.setTransport(transport[0]);
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
        return getString(R.string.connected_via, getString(
                RobotSession.get().getTransport() == RobotSession.Transport.WIFI
                        ? R.string.wifi : R.string.bluetooth));
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
     * Arranges the children of {@code R.id.columns} side by side on tablets
     * ({@code R.bool.two_columns}, landscape tablets) or stacked on phones. A child's {@code android:tag}
     * may hold its column weight (default 1). Fixed heights are kept.
     */
    protected void setupColumns() {
        LinearLayout columns = findViewById(R.id.columns);
        boolean side = getResources().getBoolean(R.bool.two_columns);
        int gap = getResources().getDimensionPixelSize(R.dimen.gap);
        columns.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < columns.getChildCount(); i++) {
            View child = columns.getChildAt(i);
            ViewGroup.LayoutParams old = child.getLayoutParams();
            int fixedHeight = old != null && old.height > 0 ? old.height : 0;
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
                lp = new LinearLayout.LayoutParams(0,
                        fixedHeight > 0 ? fixedHeight : ViewGroup.LayoutParams.MATCH_PARENT, weight);
                if (i > 0) lp.setMarginStart(gap);
            } else {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        fixedHeight > 0 ? fixedHeight : ViewGroup.LayoutParams.WRAP_CONTENT);
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
