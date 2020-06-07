package org.c19x.old.beacon.ble3;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import org.c19x.C19XApplication;
import org.c19x.old.beacon.BeaconListener;
import org.c19x.old.beacon.BeaconReceiver;
import org.c19x.util.FlipFlopTimer;
import org.c19x.util.Logger;
import org.c19x.util.messaging.Broadcaster;
import org.c19x.util.messaging.DefaultBroadcaster;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Bluetooth low energy scanner for collecting the identifier of all other discoverable devices.
 */
public class BLEReceiver extends DefaultBroadcaster<BeaconListener> implements BeaconReceiver {
    private final static String tag = BLEReceiver.class.getName();
    private final static long gattConnectionTimeout = 20000;
    private final static int manufacturerIdForApple = 76;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bluetoothLeScanner;
    private final AtomicReference<ScanCallback> scanCallback = new AtomicReference<>(null);
    private final ConcurrentLinkedQueue<ScanResult> scanResults = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final FlipFlopTimer flipFlopTimer;

    private static enum ScanResultType {APPLE_FOREGROUND, APPLE_BACKGROUND, ANDROID}

    private final static class ScanResultForProcessing {
        public final ScanResultType scanResultType;
        public final ScanResult scanResult;

        public ScanResultForProcessing(ScanResultType scanResultType, ScanResult scanResult) {
            this.scanResultType = scanResultType;
            this.scanResult = scanResult;
        }
    }


    public BLEReceiver() {
        this.bluetoothManager = (BluetoothManager) C19XApplication.getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
        this.bluetoothLeScanner = (bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeScanner() : null);
        if (bluetoothAdapter == null) {
            Logger.warn(tag, "Bluetooth unsupported");
        }
        if (bluetoothLeScanner == null) {
            Logger.warn(tag, "Bluetooth LE scanner unsupported");
        }
        this.flipFlopTimer = new FlipFlopTimer(
                C19XApplication.getGlobalStatusLog().getBeaconReceiverOnDuration(),
                C19XApplication.getGlobalStatusLog().getBeaconReceiverOffDuration(),
                () -> startScan(), () -> stopScan());
    }

    private final static ScanCallback startScan(final BluetoothLeScanner bluetoothLeScanner, final Collection<ScanResult> scanResults, final AtomicBoolean started, final Broadcaster<BeaconListener> broadcaster) {
        final List<ScanFilter> filter = new ArrayList<>(2);
        filter.add(new ScanFilter.Builder().setManufacturerData(
                manufacturerIdForApple, new byte[0], new byte[0]).build());
        filter.add(new ScanFilter.Builder().setServiceUuid(
                new ParcelUuid(new UUID(BLETransmitter.bleServiceId, 0)),
                new ParcelUuid(new UUID(0xFFFFFFFFFFFFFFFFl, 0)))
                .build());

        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build();

        final ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                Logger.debug(tag, "Scan result (result={})", result);
                scanResults.add(result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                Logger.warn(tag, "Scan failed (error={})", onScanFailedErrorCodeToString(errorCode));
                if (errorCode != ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                    started.set(false);
                }
                broadcaster.broadcast(l -> l.error(errorCode));
            }
        };

        bluetoothLeScanner.startScan(filter, settings, callback);
        Logger.debug(tag, "Scan started (filter={},settings={})", filter, settings);
        return callback;
    }

    private final static void stopScan(final BluetoothLeScanner bluetoothLeScanner, final ScanCallback scanCallback) {
        bluetoothLeScanner.stopScan(scanCallback);
        Logger.debug(tag, "Scan stopped");
    }

    private final static void processScanResults(final ConcurrentLinkedQueue<ScanResult> scanResults, final long serviceId, final long beaconCode, final Broadcaster<BeaconListener> broadcaster, final long timeout) {
        // Separate results and sort by RSSI
        final List<ScanResult> scanResultsApple = scanResults.stream()
                .filter(r -> r.getScanRecord() != null && r.getScanRecord().getManufacturerSpecificData(manufacturerIdForApple) != null)
                .collect(Collectors.toList());
        final List<ScanResultForProcessing> scanResultsAppleBackground = scanResultsApple.stream()
                .filter(r -> r.getScanRecord().getServiceUuids() == null || r.getScanRecord().getServiceUuids().size() == 0)
                .sorted((a, b) -> Integer.compare(b.getRssi(), a.getRssi()))
                .map(r -> new ScanResultForProcessing(ScanResultType.APPLE_BACKGROUND, r))
                .collect(Collectors.toList());
        final List<ScanResultForProcessing> scanResultsAppleForeground = scanResultsApple.stream()
                .filter(r -> r.getScanRecord().getServiceUuids() != null &&
                        r.getScanRecord().getServiceUuids().stream().filter(u -> u.getUuid().getMostSignificantBits() == serviceId).count() > 0)
                .sorted((a, b) -> Integer.compare(b.getRssi(), a.getRssi()))
                .map(r -> new ScanResultForProcessing(ScanResultType.APPLE_FOREGROUND, r))
                .collect(Collectors.toList());
        final List<ScanResultForProcessing> scanResultsAndroid = scanResults.stream()
                .filter(r -> !scanResultsApple.contains(r) && r.getScanRecord() != null && r.getScanRecord().getServiceUuids().stream().filter(u -> u.getUuid().getMostSignificantBits() == serviceId).count() > 0)
                .sorted((a, b) -> Integer.compare(b.getRssi(), a.getRssi()))
                .map(r -> new ScanResultForProcessing(ScanResultType.ANDROID, r))
                .collect(Collectors.toList());


        final Set<BluetoothDevice> devices = new HashSet<>();
        for (ScanResultForProcessing scanResultForProcessing : scanResultsAndroid) {
            if (!devices.contains(scanResultForProcessing.scanResult.getDevice())) {
                processScanResult(scanResultForProcessing, serviceId, beaconCode, broadcaster, timeout);
                devices.add(scanResultForProcessing.scanResult.getDevice());
            }
        }
        devices.clear();
        for (ScanResultForProcessing scanResultForProcessing : scanResultsAppleForeground) {
            if (!devices.contains(scanResultForProcessing.scanResult.getDevice())) {
                processScanResult(scanResultForProcessing, serviceId, beaconCode, broadcaster, timeout);
                devices.add(scanResultForProcessing.scanResult.getDevice());
            }
        }
        for (ScanResultForProcessing scanResultForProcessing : scanResultsAppleBackground) {
            if (!devices.contains(scanResultForProcessing.scanResult.getDevice())) {
                processScanResult(scanResultForProcessing, serviceId, beaconCode, broadcaster, timeout);
                devices.add(scanResultForProcessing.scanResult.getDevice());
            }
        }

        scanResults.clear();
    }

    private final static void processScanResult(final ScanResultForProcessing scanResultForProcessing, final long serviceId, final long beaconCode, final Broadcaster<BeaconListener> broadcaster, final long timeout) {
        Logger.debug(tag, "Processing scan result (type={},scanResult={})", scanResultForProcessing.scanResultType, scanResultForProcessing.scanResult);

        final int rssi = scanResultForProcessing.scanResult.getRssi();
        final CompletableFuture<Long> future = new CompletableFuture<>();
        final AtomicBoolean gattOpen = new AtomicBoolean(true);
        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            private Long transmitterBeaconCode = null;

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Logger.debug(tag, "GATT client connection state change (device={},status={},newState={})",
                        gatt.getDevice(), status, onConnectionStatusChangeStateToString(newState));
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (gattOpen.compareAndSet(true, false)) {
                        gatt.close();
                        Logger.debug(tag, "GATT client closed");
                    }
                    future.complete(transmitterBeaconCode);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                Logger.debug(tag, "GATT client discovered services (device={},status={})", gatt.getDevice(), status);
                for (BluetoothGattService service : gatt.getServices()) {
                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        if (characteristic.getUuid().getMostSignificantBits() == serviceId) {
                            transmitterBeaconCode = characteristic.getUuid().getLeastSignificantBits();
                            Logger.debug(tag, "GATT client discovered C19X service (device={},transmitterBeaconCode={})", gatt.getDevice(), transmitterBeaconCode);

                            if (!C19XApplication.getBeaconTransmitter().isSupported()) {
                                Logger.debug(tag, "GATT client not associated with transmitter, sending data instead");
                                final ByteBuffer byteBuffer = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                                byteBuffer.putLong(0, beaconCode);
                                byteBuffer.putInt(Long.BYTES, rssi);
                                characteristic.setValue(byteBuffer.array());
                                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                                final boolean writeInitSuccess = gatt.writeCharacteristic(characteristic);
                                Logger.debug(tag, "GATT client initiated write (device={},receiverBeaconCode={},rssi={},success={})", gatt.getDevice(), beaconCode, rssi, writeInitSuccess);
                                // GATT close on write complete
                                return;
                            } else {
                                gatt.disconnect();
                                future.complete(transmitterBeaconCode);
                            }
                        }
                    }
                }
                gatt.disconnect();
                future.complete(transmitterBeaconCode);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                Logger.debug(tag, "GATT client wrote characteristic (device={},success={},status={})", gatt.getDevice(), (status == BluetoothGatt.GATT_SUCCESS), onCharacteristicWriteStatusToString(status));
                gatt.disconnect();
                future.complete(transmitterBeaconCode);
            }
        };
        final BluetoothGatt gatt = scanResultForProcessing.scanResult.getDevice().connectGatt(C19XApplication.getContext(), false, callback);
        try {
            final Long transmitterBeaconCode = future.get(timeout, TimeUnit.MILLISECONDS);
            if (transmitterBeaconCode != null) {
                broadcaster.broadcast(l -> l.detect(C19XApplication.getTimestamp().getTime(), transmitterBeaconCode, rssi));
            }
        } catch (Throwable e) {
            Logger.debug(tag, "GATT client timeout");
        }
        if (gattOpen.compareAndSet(true, false)) {
            try {
                gatt.disconnect();
            } catch (Throwable e) {
            }
            gatt.close();
            Logger.debug(tag, "GATT client closed");
        }
    }

    private final static String onCharacteristicWriteStatusToString(final int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
                return "GATT_SUCCESS";
            case BluetoothGatt.GATT_CONNECTION_CONGESTED:
                return "GATT_CONNECTION_CONGESTED";
            case BluetoothGatt.GATT_FAILURE:
                return "GATT_FAILURE";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                return "GATT_INSUFFICIENT_AUTHENTICATION";
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                return "GATT_INSUFFICIENT_ENCRYPTION";
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
                return "GATT_INVALID_ATTRIBUTE_LENGTH";
            case BluetoothGatt.GATT_INVALID_OFFSET:
                return "GATT_INVALID_OFFSET";
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                return "GATT_READ_NOT_PERMITTED";
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                return "GATT_REQUEST_NOT_SUPPORTED";
            case BluetoothGatt.GATT_SERVER:
                return "GATT_SERVER";
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                return "GATT_WRITE_NOT_PERMITTED";
            default:
                return "UNKNOWN_STATUS_" + status;
        }
    }

    private final static String onScanFailedErrorCodeToString(final int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "SCAN_FAILED_ALREADY_STARTED";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "SCAN_FAILED_INTERNAL_ERROR";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "SCAN_FAILED_FEATURE_UNSUPPORTED";
            default:
                return "UNKNOWN_ERROR_CODE_" + errorCode;
        }
    }

    private final static String onConnectionStatusChangeStateToString(final int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "STATE_CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTED:
                return "STATE_DISCONNECTED";
            default:
                return "UNKNOWN_STATE_" + state;
        }
    }

    private final void startScan() {
        if (bluetoothLeScanner != null && bluetoothAdapter.isEnabled()) {
            scanCallback.set(startScan(bluetoothLeScanner, scanResults, started, this));
        }
    }

    private final void stopScan() {
        if (scanCallback.get() != null) {
            try {
                stopScan(bluetoothLeScanner, scanCallback.get());
            } catch (Throwable e) {
                if (bluetoothAdapter.isEnabled()) {
                    Logger.warn(tag, "Failed to stop BLE scanner even though bluetooth adapter is ON", e);
                } else {
                    Logger.info(tag, "Failed to stop BLE scanner because bluetooth adapter is already OFF", e);
                }
            }
            try {
                bluetoothAdapter.cancelDiscovery();
                processScanResults(scanResults, BLETransmitter.bleServiceId, C19XApplication.getBeaconTransmitter().getId(), this, gattConnectionTimeout);
            } catch (Throwable e) {
                Logger.warn(tag, "Failed to process scan results", e);
            }
        }
    }

    @Override
    public void start() {
        if (!started.get()) {
            if (bluetoothAdapter != null && bluetoothLeScanner != null && bluetoothAdapter.isEnabled()) {
                flipFlopTimer.start();
                started.set(true);
                broadcast(l -> l.start());
                Logger.debug(tag, "Beacon receiver started");
            } else {
                Logger.warn(tag, "Beacon receiver start failed (adapter={},enabled={})", bluetoothAdapter, (bluetoothAdapter != null && bluetoothAdapter.isEnabled()));
            }
        } else {
            Logger.warn(tag, "Beacon receiver already started");
        }
    }

    @Override
    public void stop() {
        if (started.get()) {
            started.set(false);
            if (flipFlopTimer.isStarted()) {
                flipFlopTimer.stop();
            }
            broadcast(l -> l.stop());
            Logger.debug(tag, "Beacon receiver stopped");
        } else {
            Logger.warn(tag, "Beacon receiver already stopped");
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public boolean isSupported() {
        return bluetoothLeScanner != null;
    }

    @Override
    public void setDutyCycle(int onDuration, int offDuration) {
        Logger.debug(tag, "Set receiver duty cycle (on={},off={})", onDuration, offDuration);
        flipFlopTimer.setOnDuration(onDuration);
        flipFlopTimer.setOffDuration(offDuration);
    }
}