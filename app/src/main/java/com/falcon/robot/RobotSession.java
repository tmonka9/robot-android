package com.falcon.robot;

import android.util.Log;

/**
 * In-memory robot link state shared by all screens.
 *
 * <p>The transport is simulated: {@link #send} only logs. Replace it with a real
 * Wi-Fi (socket/HTTP) or Bluetooth client when the robot protocol is available.
 */
public final class RobotSession {

    private static final String TAG = "RobotSession";
    private static final RobotSession INSTANCE = new RobotSession();

    private boolean connected;
    private String host = "192.168.11.1";
    private int port = 8080;
    private boolean lightsOn;

    private RobotSession() {
    }

    public static RobotSession get() {
        return INSTANCE;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
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
        if (!connected) return false;
        Log.d(TAG, "send: " + command);
        return true;
    }
}
