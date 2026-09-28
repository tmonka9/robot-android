package com.falcon.robot.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Bluetooth Low Energy link to the robot: finds it, connects to it and carries the commands.
 *
 * <p>The robot is expected to advertise the Nordic UART Service, which is what a serial link over
 * BLE looks like nearly everywhere: one characteristic written to, one notified from. The UUIDs
 * are the constants below — change them here if the robot's firmware uses its own.
 *
 * <p>Everything a page sees happens on the main thread: the Android callbacks arrive on a binder
 * thread and are posted across. Writes are queued, because BLE allows one at a time, and split to
 * whatever the negotiated MTU holds.
 */
public final class BleLink {

    private static final String TAG = "BleLink";

    /** Nordic UART Service: the service, the characteristic written to, the one notified from. */
    public static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** How long a scan runs before it gives up; a scan left on is a drain on the battery. */
    public static final long SCAN_MS = 12000;
    private static final int MTU = 185;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public enum State {
        IDLE,
        SCANNING,
        CONNECTING,
        CONNECTED,
    }

    /** A device the scan has seen. */
    public static final class Found {
        public final String address;
        public final String name;
        public int rssi;

        Found(String address, String name, int rssi) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
        }
    }

    public interface Listener {
        void onState(State state, String device);

        void onDevices(List<Found> devices);

        /** A line the robot sent back over the notify characteristic. */
        void onMessage(String text);

        /** The device connected to does not offer the robot's service, so nothing can be sent. */
        void onServiceMissing();
    }

    private static final BleLink INSTANCE = new BleLink();

    public static BleLink get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Found> found = new LinkedHashMap<>();
    private final Deque<byte[]> outbox = new ArrayDeque<>();

    private State state = State.IDLE;
    private String deviceName = "";
    private String deviceAddress;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeTo;
    private boolean writing;
    private int payload = 20; // until the MTU is negotiated
    private BluetoothLeScanner scanner;
    private final Runnable scanTimeout = this::stopScan;

    private BleLink() {
    }

    // ---- what the pages use -----------------------------------------------------------------

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.onState(state, deviceName);
        listener.onDevices(devices());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        return state;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** The devices seen so far, strongest signal first. */
    public List<Found> devices() {
        List<Found> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                return b.rssi - a.rssi;
            }
        });
        return list;
    }

    /** The permissions this device needs before it may scan, or an empty array when it has them. */
    public static String[] missingPermissions(Context context) {
        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted.add(Manifest.permission.BLUETOOTH_SCAN);
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // before Android 12 a BLE scan could be used to work out where someone is, so it was
            // the location permission that guarded it
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String permission : wanted) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    /** Whether this device has Bluetooth LE at all and the adapter is on. */
    public boolean isReady(Context context) {
        BluetoothAdapter adapter = adapter(context);
        return adapter != null && adapter.isEnabled()
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    /**
     * Looks for robots. Devices with no name are left out: they are the phones, watches and
     * beacons in the room, and none of them is what is being looked for.
     */
    public void startScan(Context context) {
        if (!isReady(context) || missingPermissions(context).length > 0) return;
        if (state == State.SCANNING) return;
        BluetoothAdapter adapter = adapter(context);
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        found.clear();
        publishDevices();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "scan refused: " + e);
            return;
        }
        setState(State.SCANNING, deviceName);
        main.removeCallbacks(scanTimeout);
        main.postDelayed(scanTimeout, SCAN_MS);
    }

    public void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                Log.w(TAG, "stopScan refused: " + e);
            }
        }
        if (state == State.SCANNING) setState(State.IDLE, deviceName);
    }

    /** Connects to a device the scan found, dropping whatever was connected before. */
    public void connect(Context context, String address, String name) {
        if (!isReady(context)) return;
        stopScan();
        disconnect();
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null) return;
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "not an address: " + address);
            return;
        }
        deviceAddress = address;
        deviceName = name == null || name.isEmpty() ? address : name;
        setState(State.CONNECTING, deviceName);
        try {
            gatt = device.connectGatt(context.getApplicationContext(), false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            Log.w(TAG, "connect refused: " + e);
            setState(State.IDLE, "");
        }
    }

    public void disconnect() {
        BluetoothGatt open = gatt;
        gatt = null;
        writeTo = null;
        writing = false;
        outbox.clear();
        if (open != null) {
            try {
                open.disconnect();
                open.close();
            } catch (SecurityException e) {
                Log.w(TAG, "disconnect refused: " + e);
            }
        }
        if (state == State.CONNECTED || state == State.CONNECTING) setState(State.IDLE, "");
    }

    /**
     * Sends one command, newline-terminated the way a serial link expects. False when there is
     * nothing connected to send it to.
     */
    public boolean send(String command) {
        if (state != State.CONNECTED || writeTo == null || gatt == null) return false;
        byte[] bytes = (command + "\n").getBytes(UTF8);
        for (int at = 0; at < bytes.length; at += payload) {
            int end = Math.min(bytes.length, at + payload);
            byte[] chunk = new byte[end - at];
            System.arraycopy(bytes, at, chunk, 0, chunk.length);
            outbox.add(chunk);
        }
        writeNext();
        return true;
    }

    // ---- the Android side -------------------------------------------------------------------

    private void writeNext() {
        if (writing || outbox.isEmpty() || gatt == null || writeTo == null) return;
        byte[] chunk = outbox.poll();
        writing = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(writeTo, chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writeTo.setValue(chunk);
                writeTo.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                gatt.writeCharacteristic(writeTo);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "write refused: " + e);
            writing = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name;
            try {
                name = device.getName();
            } catch (SecurityException e) {
                return;
            }
            if (name == null || name.trim().isEmpty()) return; // nameless: not what is wanted
            final String address = device.getAddress();
            final String label = name;
            final int rssi = result.getRssi();
            main.post(() -> {
                Found seen = found.get(address);
                if (seen == null) {
                    found.put(address, new Found(address, label, rssi));
                } else {
                    seen.rssi = rssi;
                }
                publishDevices();
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed: " + errorCode);
            main.post(() -> setState(State.IDLE, deviceName));
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                try {
                    gatt.requestMtu(MTU); // the commands are longer than the default 20 bytes
                } catch (SecurityException e) {
                    Log.w(TAG, "requestMtu refused: " + e);
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                main.post(() -> {
                    if (BleLink.this.gatt != gatt) return; // an older link letting go
                    disconnect();
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            payload = Math.max(20, mtu - 3); // three bytes of ATT header
            try {
                gatt.discoverServices();
            } catch (SecurityException e) {
                Log.w(TAG, "discoverServices refused: " + e);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE);
            final BluetoothGattCharacteristic write =
                    service == null ? null : service.getCharacteristic(WRITE);
            final BluetoothGattCharacteristic notify =
                    service == null ? null : service.getCharacteristic(NOTIFY);
            if (write == null) {
                main.post(() -> {
                    for (Listener listener : new ArrayList<>(listeners)) listener.onServiceMissing();
                    disconnect();
                });
                return;
            }
            if (notify != null) {
                try {
                    gatt.setCharacteristicNotification(notify, true);
                    BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd,
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        } else {
                            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(cccd);
                        }
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "notifications refused: " + e);
                }
            }
            main.post(() -> {
                writeTo = write;
                setState(State.CONNECTED, deviceName);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c,
                                          int status) {
            main.post(() -> {
                writing = false;
                writeNext();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
            deliver(c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c,
                                            byte[] value) {
            deliver(value);
        }
    };

    private void deliver(byte[] value) {
        if (value == null || value.length == 0) return;
        final String text = new String(value, UTF8).trim();
        if (text.isEmpty()) return;
        main.post(() -> {
            for (Listener listener : new ArrayList<>(listeners)) listener.onMessage(text);
        });
    }

    private void setState(State next, String device) {
        if (state == next && deviceName.equals(device)) return;
        state = next;
        deviceName = device == null ? "" : device;
        if (next != State.CONNECTED && next != State.CONNECTING) deviceAddress = null;
        for (Listener listener : new ArrayList<>(listeners)) listener.onState(state, deviceName);
    }

    private void publishDevices() {
        List<Found> devices = devices();
        for (Listener listener : new ArrayList<>(listeners)) listener.onDevices(devices);
    }
}
