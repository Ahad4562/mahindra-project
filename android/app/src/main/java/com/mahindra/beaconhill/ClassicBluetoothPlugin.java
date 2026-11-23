package com.mahindra.beaconhill;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(
    name = "ClassicBluetooth",
    permissions = {
        @Permission(
            alias = "bluetooth",
            strings = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }
        ),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public final class ClassicBluetoothPlugin extends Plugin {
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long SCAN_DURATION_MS = 12_000;
    private static final long CONNECT_TIMEOUT_MS = 12_000;
    private static final long COMMAND_TIMEOUT_MS = 2_000;
    private static final int MAX_RESPONSE_BYTES = 8_192;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object connectionLock = new Object();
    private final List<JSObject> scannedDevices = new ArrayList<>();
    private volatile BluetoothSocket socket;
    private volatile PluginCall scanCall;
    private volatile BroadcastReceiver scanReceiver;
    private volatile BroadcastReceiver disconnectReceiver;
    private final Runnable finishScanRunnable = this::finishScan;
    private final AtomicBoolean commandPending = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatPending = new AtomicBoolean(false);
    private final AtomicInteger commandSequence = new AtomicInteger(0);

    private BluetoothAdapter adapter() {
        BluetoothManager manager =
            (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean hasRuntimePermission(String alias) {
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    @PluginMethod
    public void scanDevices(PluginCall call) {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "bluetooth" : "location";
        if (!hasRuntimePermission(permission)) {
            requestPermissionForAlias(permission, call, "scanDevicesPermissionCallback");
            return;
        }
        startScan(call);
    }

    @PermissionCallback
    private void scanDevicesPermissionCallback(PluginCall call) {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "bluetooth" : "location";
        if (!hasRuntimePermission(permission)) {
            call.reject("Bluetooth scanning permission was not granted.");
            return;
        }
        startScan(call);
    }

    private synchronized void startScan(PluginCall call) {
        BluetoothAdapter bluetooth = adapter();
        if (bluetooth == null) {
            call.reject("This Android device does not support Bluetooth Classic.");
            return;
        }
        if (!bluetooth.isEnabled()) {
            call.reject("Turn on Bluetooth in Android settings, then scan again.");
            return;
        }
        if (scanCall != null) {
            finishScan();
        }

        scannedDevices.clear();
        try {
            Set<BluetoothDevice> paired = bluetooth.getBondedDevices();
            if (paired != null) {
                for (BluetoothDevice device : paired) {
                    addScannedDevice(device);
                }
            }
        } catch (SecurityException exception) {
            call.reject("Android denied access to paired Bluetooth devices.", exception);
            return;
        }

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice.class
                    );
                } else {
                    device = (BluetoothDevice) intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE
                    );
                }
                if (device != null) {
                    JSObject discovered = addScannedDevice(device);
                    if (discovered != null) {
                        notifyListeners("deviceFound", discovered);
                    }
                }
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(scanReceiver, filter);
            }
            scanCall = call;
            call.setKeepAlive(true);
            if (!bluetooth.startDiscovery()) {
                finishScan();
                return;
            }
            mainHandler.postDelayed(finishScanRunnable, SCAN_DURATION_MS);
        } catch (SecurityException exception) {
            unregisterScanReceiver();
            scanCall = null;
            call.reject("Android could not start a Bluetooth device scan.", exception);
        } catch (RuntimeException exception) {
            unregisterScanReceiver();
            scanCall = null;
            call.reject("Bluetooth scanning failed: " + exception.getMessage(), exception);
        }
    }

    private JSObject addScannedDevice(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            if (address == null) {
                return null;
            }
            synchronized (scannedDevices) {
                for (JSObject existing : scannedDevices) {
                    if (address.equals(existing.getString("address"))) {
                        return null;
                    }
                }
                JSObject result = new JSObject();
                result.put("id", address);
                result.put("address", address);
                result.put("name", device.getName() == null ? "Unnamed Bluetooth device" : device.getName());
                result.put("paired", device.getBondState() == BluetoothDevice.BOND_BONDED);
                scannedDevices.add(result);
                return result;
            }
        } catch (SecurityException exception) {
            throw exception;
        }
    }

    private synchronized void finishScan() {
        mainHandler.removeCallbacks(finishScanRunnable);
        BluetoothAdapter bluetooth = adapter();
        try {
            if (bluetooth != null && bluetooth.isDiscovering()) {
                bluetooth.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the scan was running.
        }
        unregisterScanReceiver();
        PluginCall activeScan = scanCall;
        scanCall = null;
        if (activeScan != null) {
            JSArray devices = new JSArray();
            synchronized (scannedDevices) {
                for (JSObject device : scannedDevices) {
                    devices.put(device);
                }
            }
            JSObject result = new JSObject();
            result.put("devices", devices);
            activeScan.setKeepAlive(false);
            activeScan.resolve(result);
        }
    }

    private void unregisterScanReceiver() {
        BroadcastReceiver receiver = scanReceiver;
        scanReceiver = null;
        if (receiver != null) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // The scan was already stopped.
            }
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (!hasRuntimePermission("bluetooth")) {
            requestPermissionForAlias("bluetooth", call, "connectPermissionCallback");
            return;
        }
        connectPairedDevice(call);
    }

    @PermissionCallback
    private void connectPermissionCallback(PluginCall call) {
        if (!hasRuntimePermission("bluetooth")) {
            call.reject("Bluetooth connection permission was not granted.");
            return;
        }
        connectPairedDevice(call);
    }

    private void connectPairedDevice(PluginCall call) {
        String address = call.getString("address");
        BluetoothAdapter bluetooth = adapter();
        if (bluetooth == null) {
            call.reject("This Android device does not support Bluetooth Classic.");
            return;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            call.reject("Choose a paired Bluetooth Classic device with a valid address.");
            return;
        }

        call.setKeepAlive(true);
        AtomicBoolean finished = new AtomicBoolean(false);
        BluetoothSocket[] pendingSocket = new BluetoothSocket[1];
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                closeQuietly(pendingSocket[0]);
                call.setKeepAlive(false);
                call.reject("The Bluetooth controller did not connect within 12 seconds.");
            }
        };
        mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS);

        io.execute(() -> {
            try {
                BluetoothDevice device = bluetooth.getRemoteDevice(address);
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    throw new IOException(
                        "Pair this device in Android Bluetooth settings before connecting."
                    );
                }
                BluetoothSocket nextSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                pendingSocket[0] = nextSocket;
                bluetooth.cancelDiscovery();
                nextSocket.connect();
                if (finished.get()) {
                    closeQuietly(nextSocket);
                    return;
                }
                synchronized (connectionLock) {
                    closeConnectionLocked();
                    socket = nextSocket;
                    registerDisconnectReceiver(address);
                }
                JSObject result = new JSObject();
                result.put("name", device.getName() == null ? "Bluetooth Classic controller" : device.getName());
                result.put("address", address);
                result.put("controlReady", true);
                mainHandler.post(() -> {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout);
                        call.setKeepAlive(false);
                        call.resolve(result);
                    }
                });
            } catch (Exception exception) {
                closeQuietly(pendingSocket[0]);
                mainHandler.post(() -> {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout);
                        call.setKeepAlive(false);
                        call.reject(
                            "Could not connect over Bluetooth Classic: " + exception.getMessage(),
                            exception
                        );
                    }
                });
            }
        });
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        synchronized (connectionLock) {
            closeConnectionLocked();
        }
        JSObject result = new JSObject();
        result.put("disconnected", true);
        call.resolve(result);
    }

    @PluginMethod
    public void sendCommand(PluginCall call) {
        String zoneId = call.getString("zoneId");
        String state = call.getString("state");
        if (zoneId == null || zoneId.trim().isEmpty() || !("ON".equals(state) || "OFF".equals(state))) {
            call.reject("The Bluetooth command requires a zone ID and an ON or OFF state.");
            return;
        }
        if ("emergency".equals(zoneId)) {
            call.reject("Life-safety lighting is not controlled by the general-purpose Bluetooth relay system.");
            return;
        }
        JSObject command = new JSObject();
        command.put("zoneId", zoneId);
        command.put("state", state);
        command.put("brightness", call.getInt("brightness", 72));
        command.put("color", call.getString("color", "#eac28a"));
        command.put("effect", call.getString("effect", "steady"));
        executeCommand(call, command);
    }

    @PluginMethod
    public void sendMasterCommand(PluginCall call) {
        String state = call.getString("state");
        JSArray zoneIds = call.getArray("zoneIds");
        if (!("ON".equals(state) || "OFF".equals(state)) || zoneIds == null || zoneIds.length() == 0) {
            call.reject("The master command requires one or more zone IDs and an ON or OFF state.");
            return;
        }

        JSObject command = new JSObject();
        command.put("command", "master");
        command.put("state", state);
        command.put("zoneIds", zoneIds);
        command.put("maintenanceAuthorized", false);
        for (int index = 0; index < zoneIds.length(); index++) {
            try {
                if ("emergency".equals(zoneIds.getString(index))) {
                    call.reject("Life-safety lighting is excluded from master relay commands.");
                    return;
                }
            } catch (Exception exception) {
                call.reject("The master command contains an invalid zone ID.", exception);
                return;
            }
        }
        executeCommand(call, command);
    }

    @PluginMethod
    public void probeWatchdog(PluginCall call) {
        JSObject command = new JSObject();
        command.put("command", "watchdogProbe");
        executeCommand(call, command);
    }

    @PluginMethod
    public void sendHeartbeat(PluginCall call) {
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null || !activeSocket.isConnected()) {
            call.reject("The Bluetooth Classic controller is not connected.");
            return;
        }
        if (!heartbeatPending.compareAndSet(false, true)) {
            JSObject result = new JSObject();
            result.put("sent", false);
            call.resolve(result);
            return;
        }

        call.setKeepAlive(true);
        io.execute(() -> {
            try {
                if (socket != activeSocket || !activeSocket.isConnected()) {
                    throw new IOException("The Bluetooth connection is no longer active.");
                }
                OutputStream output = activeSocket.getOutputStream();
                output.write("{\"command\":\"heartbeat\"}\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                mainHandler.post(() -> {
                    heartbeatPending.set(false);
                    call.setKeepAlive(false);
                    JSObject result = new JSObject();
                    result.put("sent", true);
                    call.resolve(result);
                });
            } catch (Exception exception) {
                if (exception instanceof IOException && socket == activeSocket) {
                    reportConnectionLost("Bluetooth heartbeat failed: " + exception.getMessage());
                }
                mainHandler.post(() -> {
                    heartbeatPending.set(false);
                    call.setKeepAlive(false);
                    call.reject("Could not send the relay heartbeat: " + exception.getMessage(), exception);
                });
            }
        });
    }

    private void executeCommand(PluginCall call, JSObject command) {
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null || !activeSocket.isConnected()) {
            reportConnectionLost(
                "The Bluetooth connection is no longer active. Reconnect to the HC-05 before sending commands."
            );
            call.reject("The Bluetooth Classic controller is not connected.");
            return;
        }
        if (!commandPending.compareAndSet(false, true)) {
            call.reject("Another Bluetooth lighting command is awaiting acknowledgement.");
            return;
        }

        int commandId = commandSequence.updateAndGet(current -> current == Integer.MAX_VALUE ? 1 : current + 1);
        command.put("commandId", commandId);
        call.setKeepAlive(true);
        AtomicBoolean finished = new AtomicBoolean(false);
        io.execute(() -> {
            try {
                OutputStream output = activeSocket.getOutputStream();
                output.write((command.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                JSObject acknowledgement = readMatchingResponse(activeSocket, commandId, command);
                finishCommandCall(call, finished, acknowledgement, null);
            } catch (CommandTimeoutException exception) {
                finishCommandCall(call, finished, null, exception.getMessage());
            } catch (Exception exception) {
                if (exception instanceof IOException && socket == activeSocket) {
                    reportConnectionLost("Bluetooth transport failed: " + exception.getMessage());
                }
                finishCommandCall(call, finished, null, exception.getMessage());
            }
        });
    }

    private JSObject readMatchingResponse(
        BluetoothSocket activeSocket,
        int commandId,
        JSObject command
    )
        throws IOException, InterruptedException {
        InputStream input = activeSocket.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean overflow = false;
        long deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (socket != activeSocket || !activeSocket.isConnected()) {
                throw new IOException("The HC-05 socket closed while waiting for the controller.");
            }
            if (input.available() == 0) {
                Thread.sleep(5);
                continue;
            }
            int next = input.read();
            if (next < 0) {
                throw new IOException("The controller closed the Bluetooth connection.");
            }
            if (next == '\n') {
                if (!overflow && bytes.size() > 0) {
                    try {
                        JSObject response = new JSObject(
                            bytes.toString(StandardCharsets.UTF_8.name()).trim()
                        );
                        if (response.has("commandId")) {
                            if (response.getInt("commandId") == commandId) {
                                return response;
                            }
                        } else if (commandMatchesLegacyReply(command, response)) {
                            return response;
                        }
                    } catch (Exception ignored) {
                        // Ignore malformed or stale replies and wait for this command's ID.
                    }
                }
                bytes.reset();
                overflow = false;
            } else if (next != '\r') {
                if (bytes.size() < MAX_RESPONSE_BYTES) {
                    bytes.write(next);
                } else {
                    overflow = true;
                }
            }
        }
        throw new CommandTimeoutException(
            "The controller did not respond within two seconds; Bluetooth remains connected."
        );
    }

    private static boolean commandMatchesLegacyReply(JSObject command, JSObject response) {
        try {
            String state = command.getString("state");
            if (state == null || !state.equals(response.getString("state"))) {
                return false;
            }
            if ("master".equals(command.getString("command"))) {
                return "invalid_command".equals(response.getString("error"))
                    && "".equals(response.getString("zoneId"));
            }
            String zoneId = command.getString("zoneId");
            return zoneId != null && zoneId.equals(response.getString("zoneId"));
        } catch (Exception exception) {
            return false;
        }
    }

    private void finishCommandCall(
        PluginCall call,
        AtomicBoolean finished,
        JSObject response,
        String error
    ) {
        mainHandler.post(() -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            commandPending.set(false);
            call.setKeepAlive(false);
            if (error == null) {
                call.resolve(response);
            } else {
                call.reject(error);
            }
        });
    }

    private static final class CommandTimeoutException extends IOException {
        CommandTimeoutException(String message) {
            super(message);
        }
    }

    private void registerDisconnectReceiver(String address) {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        disconnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice.class
                    );
                } else {
                    device = (BluetoothDevice) intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE
                    );
                }
                if (device != null && address.equals(device.getAddress())) {
                    reportConnectionLost("The HC-05 Bluetooth link was disconnected.");
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(disconnectReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(disconnectReceiver, filter);
        }
    }

    private void closeConnection() {
        synchronized (connectionLock) {
            closeConnectionLocked();
        }
    }

    private void reportConnectionLost(String reason) {
        closeConnection();
        JSObject event = new JSObject();
        event.put("reason", reason);
        mainHandler.post(() -> notifyListeners("connectionLost", event));
    }

    private void closeConnectionLocked() {
        BroadcastReceiver receiver = disconnectReceiver;
        disconnectReceiver = null;
        if (receiver != null) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // The connection was already closed.
            }
        }
        closeQuietly(socket);
        socket = null;
    }

    private static void closeQuietly(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException ignored) {
                // Closing an already disconnected socket is harmless.
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        finishScan();
        closeConnection();
        io.shutdownNow();
        super.handleOnDestroy();
    }
}
