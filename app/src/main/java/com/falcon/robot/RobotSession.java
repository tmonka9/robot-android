package com.falcon.robot;

import android.util.Log;

import com.falcon.robot.ble.BleLink;

/**
 * The robot link every screen shares.
 *
 * <p>Commands go out over Bluetooth Low Energy once the Remote Control page has connected a
 * device ({@link BleLink}). The address and port are the Wi-Fi link, which is still simulated:
 * with no BLE device connected {@link #send} only logs.
 */
public final class RobotSession {

    private static final String TAG = "RobotSession";
    private static final RobotSession INSTANCE = new RobotSession();

    private boolean connected;
    /** Whether the link is the BLE one rather than the address below. */
    private boolean overBle;
    private String host = "192.168.11.1";
    private int port = 8080;
    private boolean lightsOn;

    private RobotSession() {
    }

    public static RobotSession get() {
        return INSTANCE;
    }

    /** A BLE link that has dropped is not a connection any more, wherever the operator is. */
    public boolean isConnected() {
        return connected && (!overBle || BleLink.get().isConnected());
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) {
            overBle = false;
            // switching the link off here means the BLE one too: it is what "connected" was
            if (BleLink.get().isConnected()) BleLink.get().disconnect();
        }
    }

    /** The Remote Control page has the robot on BLE; from now on the commands go out over it. */
    public void setBleConnected() {
        connected = true;
        overBle = true;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void setAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public boolean isLightsOn() {
        return lightsOn;
    }

    public void setLightsOn(boolean lightsOn) {
        this.lightsOn = lightsOn;
    }

    /** Sends a command to the robot. Returns false when not connected. */
    public boolean send(String command) {
        if (!isConnected()) return false;
        if (overBle) return BleLink.get().send(command);
        Log.d(TAG, "send: " + command); // the Wi-Fi transport is still to be written
        return true;
    }
}
