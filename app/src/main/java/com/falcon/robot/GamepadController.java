package com.falcon.robot;

import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * USB or Bluetooth gamepad input.
 *
 * <p>Android reports a gamepad as an ordinary input device: sticks and triggers arrive as generic
 * motion events, buttons and the D-pad as key events. A dongle needs no driver and no permission —
 * the events simply start arriving once it is plugged in, which is why this only has to translate
 * them.
 *
 * <p>Sticks are reported as a direction in [-1, 1] with y positive forward, matching
 * {@link com.falcon.robot.widget.JoystickView}. Values inside the device's own dead zone are
 * reported as zero, and a direction is only passed on when it actually changes, so a stick held
 * still does not flood the robot link.
 */
public final class GamepadController {

    /** Below this, a stick counts as centred (unless the device reports a larger dead zone). */
    private static final float DEAD_ZONE = 0.16f;
    /** Directions are quantized to this step before being compared, as the on-screen stick is. */
    private static final float STEP = 0.1f;

    public interface Listener {
        /**
         * Stick or D-pad direction, each in [-1, 1]: {@code x} right, {@code y} forward,
         * {@code turn} clockwise from the right stick. All zero means "centred".
         */
        void onGamepadMove(float x, float y, float turn);

        /** A gamepad button went down; {@code keyCode} is one of {@code KeyEvent.KEYCODE_BUTTON_*}. */
        void onGamepadButton(int keyCode);

        /** First event from a pad, so the page can say which one is in use. */
        void onGamepadConnected(String deviceName);
    }

    private final Listener listener;
    private String connectedDevice;
    private float lastX;
    private float lastY;
    private float lastTurn;

    public GamepadController(Listener listener) {
        this.listener = listener;
    }

    /** True for events from a gamepad, joystick or D-pad, whatever else the device also is. */
    public static boolean isGamepad(InputEvent event) {
        int source = event.getSource();
        return has(source, InputDevice.SOURCE_GAMEPAD)
                || has(source, InputDevice.SOURCE_JOYSTICK)
                || has(source, InputDevice.SOURCE_DPAD);
    }

    private static boolean has(int source, int type) {
        return (source & type) == type;
    }

    /** Handles a stick or trigger movement; returns true when the event was a gamepad's. */
    public boolean onMotionEvent(MotionEvent event) {
        if (!isGamepad(event) || event.getAction() != MotionEvent.ACTION_MOVE) return false;
        report(event.getDevice());

        InputDevice device = event.getDevice();
        // left stick, plus the hat switch that most pads use for their D-pad
        float x = pick(axis(event, device, MotionEvent.AXIS_X),
                axis(event, device, MotionEvent.AXIS_HAT_X));
        float y = pick(axis(event, device, MotionEvent.AXIS_Y),
                axis(event, device, MotionEvent.AXIS_HAT_Y));
        // right stick: AXIS_Z on most pads, AXIS_RX on some
        float turn = pick(axis(event, device, MotionEvent.AXIS_Z),
                axis(event, device, MotionEvent.AXIS_RX));

        // screen coordinates point down, the robot's forward is up
        deliver(x, -y, turn);
        return true;
    }

    /** Handles a button or D-pad press; returns true when the event was a gamepad's. */
    public boolean onKeyEvent(KeyEvent event) {
        if (!isGamepad(event)) return false;
        int code = event.getKeyCode();
        if (!isGamepadKey(code)) return false;
        report(event.getDevice());

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            listener.onGamepadButton(code);
        }
        return true;
    }

    private static boolean isGamepadKey(int keyCode) {
        // KeyEvent knows which codes a gamepad produces; the D-pad is separate from that list
        return KeyEvent.isGamepadButton(keyCode)
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    /** Forgets the last direction, so the next event is reported even if it repeats one. */
    public void reset() {
        lastX = 0;
        lastY = 0;
        lastTurn = 0;
    }

    private void deliver(float x, float y, float turn) {
        float qx = quantize(x);
        float qy = quantize(y);
        float qTurn = quantize(turn);
        if (qx == lastX && qy == lastY && qTurn == lastTurn) return;
        lastX = qx;
        lastY = qy;
        lastTurn = qTurn;
        listener.onGamepadMove(qx, qy, qTurn);
    }

    private static float quantize(float value) {
        return Math.round(value / STEP) * STEP;
    }

    /** The axis value, or 0 inside the dead zone the device reports for it. */
    private static float axis(MotionEvent event, InputDevice device, int axis) {
        float value = event.getAxisValue(axis);
        float dead = DEAD_ZONE;
        if (device != null) {
            InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
            if (range != null) dead = Math.max(dead, range.getFlat());
        }
        if (Math.abs(value) <= dead) return 0f;
        // rescale so the travel outside the dead zone still covers the full range
        float sign = value < 0 ? -1f : 1f;
        return sign * Math.min(1f, (Math.abs(value) - dead) / (1f - dead));
    }

    private static float pick(float primary, float fallback) {
        return primary != 0f ? primary : fallback;
    }

    private void report(InputDevice device) {
        if (device == null || connectedDevice != null) return;
        connectedDevice = device.getName();
        listener.onGamepadConnected(connectedDevice);
    }
}
