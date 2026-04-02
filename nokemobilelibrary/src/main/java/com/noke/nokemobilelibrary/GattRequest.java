package com.noke.nokemobilelibrary;

import com.noke.nokemobilelibrary.enums.SigningDeviceCharacteristicType;
import com.noke.nokemobilelibrary.enums.SigningWriteCharacteristicType;
import com.noke.nokemobilelibrary.helpers.NokeErrorMapper;
import com.noke.nokemobilelibrary.interfaces.NokeErrorCode;

import android.Manifest;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.UUID;

/**
 * Unified GATT request that supports WRITE and READ against a single characteristic.
 * - Executes on the main thread (matches your previous behavior).
 * - Enforces BLUETOOTH_CONNECT permission on API 31+.
 * - Reports errors via ErrorReporter.
 */
public final class GattRequest {

    public interface BytesProvider {
        /** For WRITE requests: provide payload bytes (throw to report setup errors). */
        byte[] get() throws Exception;
    }

    public interface ReadCallback {
        /** For READ requests: receives the raw bytes (may be empty, never null). */
        void onRead(@NonNull NokeDevice noke, @NonNull byte[] value);
    }

    public interface ErrorReporter {
        void onError(@NonNull NokeDevice noke, @NokeErrorCode int code, @NonNull String message);
    }

    /** Which operation to perform. */
    public enum Op { READ, WRITE }

    private final Context appContext;
    private final NokeDevice noke;

    private final Op op;
    private final UUID serviceUuid;
    private final UUID characteristicUuid;
    private final String tag;

    // WRITE-specific
    @Nullable private final BytesProvider bytesProvider;

    // READ-specific
    @Nullable private final ReadCallback readCallback;

    private final ErrorReporter errorReporter;
    
    // BLE operation queue for serialization
    @Nullable private final BleOperationQueue bleQueue;

    private GattRequest(
            @NonNull Context context,
            @NonNull NokeDevice noke,
            @NonNull Op op,
            @NonNull UUID serviceUuid,
            @NonNull UUID characteristicUuid,
            @NonNull String tag,
            @Nullable BytesProvider bytesProvider,
            @Nullable ReadCallback readCallback,
            @NonNull ErrorReporter errorReporter,
            @Nullable BleOperationQueue bleQueue
    ) {
        this.appContext = context.getApplicationContext();
        this.noke = noke;
        this.op = op;
        this.serviceUuid = serviceUuid;
        this.characteristicUuid = characteristicUuid;
        this.tag = tag;
        this.bytesProvider = bytesProvider;
        this.readCallback = readCallback;
        this.errorReporter = errorReporter;
        this.bleQueue = bleQueue;
    }

    /** Factory: WRITE request (reuses your SigningWriteCharacteristicType). */
    public static GattRequest write(
            @NonNull Context context,
            @NonNull NokeDevice noke,
            @NonNull SigningWriteCharacteristicType type,
            @NonNull BytesProvider bytesProvider,
            @NonNull ErrorReporter errorReporter,
            @Nullable BleOperationQueue bleQueue
    ) {
        return new GattRequest(
                context,
                noke,
                Op.WRITE,
                SigningDeviceCharacteristicType.getServiceUuid(),
                type.getCharacteristicUuid(),
                type.getTag(),
                bytesProvider,
                null,
                errorReporter,
                bleQueue
        );
    }

    /**
     * Factory: READ request.
     * If you already have a specific enum for read characteristics, you can swap the (tag, uuid) source.
     * For now, we accept a UUID + tag, which works for any characteristic.
     */
    public static GattRequest read(
            @NonNull Context context,
            @NonNull NokeDevice noke,
            @NonNull UUID characteristicUuid,
            @NonNull String tag,
            @NonNull ReadCallback readCallback,
            @NonNull ErrorReporter errorReporter,
            @Nullable BleOperationQueue bleQueue
    ) {
        return new GattRequest(
                context,
                noke,
                Op.READ,
                SigningDeviceCharacteristicType.getServiceUuid(),
                characteristicUuid,
                tag,
                null,
                readCallback,
                errorReporter,
                bleQueue
        );
    }

    /** True if we have permission to call GATT APIs on API 31+. */
    private boolean hasBtConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Execute the request on the main thread. */
    public void execute() {
        try {
            if (noke == null || noke.gatt == null) return;

            // Use queue if provided for proper serialization
            if (bleQueue != null) {
                bleQueue.enqueue(this::executeInternal);
            } else {
                // Fallback to old behavior if queue not provided
                if (Looper.getMainLooper().isCurrentThread()) {
                    executeInternal();
                } else {
                    new Handler(Looper.getMainLooper()).post(this::executeInternal);
                }
            }

        } catch (Exception e) { // outer guard (NPE, etc.)
            Log.e(tag, "execute -> Exception (outer)", e);
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "Invalid noke device"
            );
        }
    }

    private void executeInternal() {
        final BluetoothGatt gatt = noke.gatt;
        if (gatt == null) return;

        final BluetoothGattService service = gatt.getService(serviceUuid);
        if (service == null) {
            Log.e(tag, "execute -> BAD DEVICE (service null)");
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "Invalid noke device (service not found)"
            );
            return;
        }

        final BluetoothGattCharacteristic ch = service.getCharacteristic(characteristicUuid);
        if (ch == null) {
            Log.e(tag, "execute -> BAD DEVICE (characteristic null)");
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "Invalid noke device (characteristic not found)"
            );
            return;
        }

        if (!hasBtConnectPermission()) {
            errorReporter.onError(
                    noke,
                    NokeErrorCode.ERROR_BLUETOOTH_SCAN_PERMISSION,
                    "BLUETOOTH_CONNECT permission is required on Android 12+"
            );
            return;
        }

        switch (op) {
            case WRITE:
                doWrite(gatt, ch);
                break;
            case READ:
                doRead(gatt, ch);
                break;
        }
    }

    private void doWrite(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic ch) {
        try {
            if (bytesProvider == null) {
                errorReporter.onError(noke,
                        NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                        "No BytesProvider for write");
                return;
            }
            byte[] bytes = bytesProvider.get();
            if (bytes == null) throw new NullPointerException("BytesProvider returned null");

            ch.setValue(bytes);

            // noinspection MissingPermission (guarded by hasBtConnectPermission)
            boolean started = gatt.writeCharacteristic(ch);
            Log.d(tag, "write(" + characteristicUuid + ") - started=" + started);
            if (!started) {
                // CRITICAL: Notify queue even on failure, or queue will hang forever
                if (bleQueue != null) {
                    bleQueue.notifyOperationComplete();
                }
                errorReporter.onError(
                        noke,
                        NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                        "writeCharacteristic() returned false"
                );
            }
        } catch (Exception e) {
            Log.e(tag, "doWrite -> Exception", e);
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "Exception during write: " + e.getMessage()
            );
        }
    }

    private void doRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic ch) {
        try {
            // Validate READ property
            final int props = ch.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                errorReporter.onError(
                        noke,
                        NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                        "Characteristic is not readable"
                );
                return;
            }

            // noinspection MissingPermission (guarded by hasBtConnectPermission)
            boolean started = gatt.readCharacteristic(ch);
            Log.d(tag, "read(" + characteristicUuid + ") - started=" + started);
            if (!started) {
                // CRITICAL: Notify queue even on failure, or queue will hang forever
                if (bleQueue != null) {
                    bleQueue.notifyOperationComplete();
                }
                errorReporter.onError(
                        noke,
                        NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                        "readCharacteristic() returned false"
                );
            }
        } catch (Exception e) {
            Log.e(tag, "doRead -> Exception", e);
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "Exception during read: " + e.getMessage()
            );
        }
    }

    // ===== Helpers to dispatch read results from your BluetoothGattCallback =====
    // Call this from your central BluetoothGattCallback.onCharacteristicRead(...)
    public void dispatchOnCharacteristicRead(@NonNull BluetoothGattCharacteristic characteristic, int status) {
        if (op != Op.READ) return;
        if (!characteristicUuid.equals(characteristic.getUuid())) return;

        if (status == BluetoothGatt.GATT_SUCCESS) {
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            if (readCallback != null) {
                try {
                    readCallback.onRead(noke, value);
                } catch (Exception ignored) {
                    // keep failures local to callback
                }
            }
        } else {
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "onCharacteristicRead failed, status=" + status
            );
        }
    }

    // Optional: for symmetry, a dispatcher for writes (not strictly necessary here)
    public void dispatchOnCharacteristicWrite(@NonNull BluetoothGattCharacteristic characteristic, int status) {
        if (op != Op.WRITE) return;
        if (!characteristicUuid.equals(characteristic.getUuid())) return;

        if (status != BluetoothGatt.GATT_SUCCESS) {
            errorReporter.onError(
                    noke,
                    NokeErrorMapper.fromNokeMobileError(NokeMobileError.ERROR_INVALID_NOKE_DEVICE),
                    "onCharacteristicWrite failed, status=" + status
            );
        }
    }
}
