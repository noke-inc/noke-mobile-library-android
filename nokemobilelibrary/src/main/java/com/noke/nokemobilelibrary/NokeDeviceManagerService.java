package com.noke.nokemobilelibrary;

//import static androidx.core.app.ActivityCompat.requestPermissions;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

//import androidx.core.app.ActivityCompat;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.gson.Gson;
import com.noke.nokemobilelibrary.enums.NokeDeviceSigningError;
import com.noke.nokemobilelibrary.enums.NokeDeviceSigningStatus;
import com.noke.nokemobilelibrary.enums.NokeEncryptionType;
import com.noke.nokemobilelibrary.enums.SigningReadCharacteristicType;
import com.noke.nokemobilelibrary.enums.SigningWriteCharacteristicType;
import com.noke.nokemobilelibrary.interfaces.FailureHandler;
import com.noke.nokemobilelibrary.interfaces.ResultCallback;
import com.noke.nokemobilelibrary.interfaces.SuccessHandler;
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager;

import com.noke.smartentrycore.helpers.PermissionHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.noke.nokemobilelibrary.NokeDefines.bytesToHex;
import static com.noke.nokemobilelibrary.NokeMobileError.*;
import static com.noke.smartentrycore.helpers.SharedPreferencesHelperKt.PREF_USER_UUID;

import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyAcl;
import com.noke.nokemobilelibrary.phonekey.internal.SecurityService;
import com.noke.nokemobilelibrary.phonekey.internal.SecurityService.ProvisionCallback;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/************************************************************************************************************************************************
 * Copyright © 2018 Nokē Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Created by Spencer on 1/17/18.
 * Service for handling all bluetooth communication with the lock
 */

@SuppressWarnings("unused")
@SuppressLint("MissingPermission")
public class NokeDeviceManagerService extends Service {

    /**
     * The amount of time the app will wait for scan results before restarting scanning
     * */
    private static final long SCAN_TIMEOUT = 10_000;
    /**
     * The amount of time the app will wait before restarting scanning
     * */
    private static final long RESTART_SCAN_DELAY = 5_000;
    /**
     * The amount of time the app will scan before restarting scanning
     * */
    private static final long SCANNER_TIME_LIMIT = 5 * 60 * 1000; // 5 minutes

    private static final long BAN_DURATION_LIMIT = 2 * 60 * 1000; // 2 minutes

    private Handler mScanRestartHandler = new Handler();
    private Runnable mScanRestartRunnable = new Runnable() {
        @Override
        public void run() {
            stopLeScanning();
            mScanRestartHandler.postDelayed(mRestartScanRunnable, RESTART_SCAN_DELAY);
        }
    };

    private Handler mScanTimeoutHandler = new Handler();
    
    /**
     * BLE operation queue to serialize all Bluetooth operations and prevent Connection 104 errors.
     * Similar to iOS Core Bluetooth's automatic serialization.
     */
    private final BleOperationQueue bleOperationQueue = new BleOperationQueue();
    private Runnable mScanTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // If this runnable executes, it means we haven't received any scan results
            // within the timeout period since starting the scan.
            // This could indicate that the scan failed to start effectively or is being suppressed.
            Log.w(TAG, "Scan timeout reached. No scan results received within the timeout period.");
            // Implement a delay before attempting to restart scanning
            // This delay can help mitigate potential throttling issues by giving the system a break.
            stopLeScanning();
            mScanTimeoutHandler.postDelayed(mRestartScanRunnable, RESTART_SCAN_DELAY);
        }
    };

    private Runnable mRestartScanRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Attempting to restart scan after delay.");
            startLeScanning();
        }
    };

    private PhoneKeyManager phoneKeyManager;

    /**
     * Get PhoneKeyManager instance - lazily initialized with current userId
     * Detects user changes and retrieves singleton instance for user switching
     * Thread-safe for concurrent access
     * Uses singleton factory to ensure same instance across all code paths
     * @return PhoneKeyManager instance or null if no user is logged in
     */
    private synchronized PhoneKeyManager getPhoneKeyManager() {
        SharedPreferences pref = getSharedPreferences(NokeDefines.DEF_NAME, MODE_PRIVATE);
        String userId = pref.getString(PREF_USER_UUID, "");
        
        // No user logged in - clear manager
        if (userId.isEmpty()) {
            phoneKeyManager = null;
            return null;
        }
        
        // User changed - clear old manager and get singleton for new user
        if (phoneKeyManager != null && !phoneKeyManager.getUserId().equals(userId)) {
            Log.d("ION-2", "User changed from " + phoneKeyManager.getUserId() + " to " + userId + " - getting singleton for new user");
            phoneKeyManager = null;
        }
        
        // Get singleton instance for current user (ensures same instance as PhoneKeyFacade)
        if (phoneKeyManager == null) {
            phoneKeyManager = PhoneKeyManager.getInstance(getApplicationContext(), userId);
            Log.d("ION-2", "Retrieved PhoneKeyManager singleton for user " + userId);
        }
        
        return phoneKeyManager;
    }

    private final static String TAG = NokeDeviceManagerService.class.getSimpleName();

    /**
     * High level manager used to obtain an instance of BluetoothAdapter and to conduct overall
     * Bluetooth Managment
     */
    private BluetoothManager mBluetoothManager;
    /**
     * Represents the local device Bluetooth adapter.  Used for performing fundamental Bluetooth tasks, such as
     * device discovery, connection, and sending/receiving data
     */
    private BluetoothAdapter mBluetoothAdapter;
    /**
     * Provides methods to perform scan releated operations for BLE devices.
     */
    private BluetoothLeScanner mBluetoothScanner;
    /**
     * Bluetooth LE scan callbacks. Scan results are reported using these callbacks
     * Note: This newer callback introduced in Android 5.0 has been dismissed in favor of the LE callback in Android 4.4
     */
    private ScanCallback mNewBluetoothScanCallback;
    /**
     * Bluetooth LE scan callbacks.  Testing has shown that the older Android 4.4 bluetooth scanning is faster and more
     * reliable.
     */
    private BluetoothAdapter.LeScanCallback mOldBluetoothScanCallback;
    /**
     * A boolean indicating if the bluetooth broadcast receiver has been registered
     */
    private boolean mReceiverRegistered;
    /**
     * A boolean indicating if the service is currently scanning for Noke devices
     */
    private boolean mScanning;
    /**
     * Array containing responses from the lock bundled with the session, mac address, and upload time.
     * These responses are uploaded directly to the Noke API via the Noke Go library
     */
    ArrayList<JSONObject> globalUploadQueue;
    /**
     * A boolean that allows the device manager to discover devices that are not in the array
     */
    private boolean mAllowAllDevices;
    /**
     * Value of how many seconds device should wait before auto unlocking again
     */
    private long autoUnlockSeconds;
    /**
     * property used to detect if a connection fails when a device that is not available tries to create a connection
     */
    public Handler connectionTimer;
    /**
     * This propeerty is filled when the connection starts
     */
    private NokeDevice currentNoke;
    /**
     * number of seconds the connection process will wait until return an error connection
     */
    public long numberOfSecondsToDetectTheConnectionError = 6;
    /** Handler used to post the immediate-close sequence on the main thread in disconnectNoke(). */
    private final Handler disconnectHandler = new Handler(Looper.getMainLooper());
    /**
     * Maximum number of ACL signing retry attempts allowed
     * iOS equivalent: MAX_SIGNING_RETRIES = 1 (total 2 attempts: initial + 1 retry)
     */
    private static final int MAX_SIGNING_RETRIES = 1;

    /**
     * Listener for Noke device events.  Triggered on various events including:
     * <ul>
     * <li>Noke device discovery</li>
     * <li>Noke device begin connection</li>
     * <li>Noke device connected</li>
     * <li>Noke device syncing</li>
     * <li>Noke device unlocked</li>
     * <li>Noke device disconnected</li>
     * </ul>
     * <p>
     * Also used for error handling
     */
    private NokeServiceListener mGlobalNokeListener;
    /**
     * To conserve battery and be compliant with the Android SDK scanning is done by toggling on and off
     * this variable is the delay between turning off and on
     */
    private int bluetoothDelayDefault;
    /**
     * Bluetooth scanning can be adjusted while in the background.
     */
    private int bluetoothDelayBackgroundDefault;
    /**
     * Duration that bluetooth scans before shutting off and restarting
     */
    private int bluetoothScanDuration;
    /**
     * A LinkedHashMap that stores a list of NokeDevices linked my MAC address.
     * Only devices that are in this array will be discovered when scanning
     */
    public LinkedHashMap<String, NokeDevice> nokeDevices;

    public Boolean firmwareScanning = false;

    private Boolean callJammedLockingDelegate = true;
    private Boolean callJammedUnlockingDelegate = true;

    private ResultCallback<byte[]> readCommandIdCompletion;

    private SuccessHandler handleSuccess;

    private FailureHandler handleFailure;

    /**
     * Class for binding service to activity
     */
    public class LocalBinder extends Binder {
        /**
         * Returns reference to the NokeDeviceManagerService
         *
         * @param mode must be set upon initialization. Determines the upload url used for uploading
         *             responses from the lock to the Core API.  Mode types can be found in NokeDefines
         *             file:
         *             - Sandbox (NOKE_LIBRARY_SANDBOX)
         *             - Production (NOKE_LIBRARY_PRODUCTION)
         *             - Develop (NOKE_LIBRARY_DEVELOP)
         */
        public NokeDeviceManagerService getService(int mode) {
            switch (mode) {
                case NokeDefines.NOKE_LIBRARY_SANDBOX:
                    setUploadUrl(NokeDefines.sandboxUploadURL);
                    break;
                case NokeDefines.NOKE_LIBRARY_PRODUCTION:
                    setUploadUrl(NokeDefines.productionUploadURL);
                    break;
                case NokeDefines.NOKE_LIBRARY_DEVELOP:
                    setUploadUrl(NokeDefines.developUploadURL);
                    break;
                case NokeDefines.NOKE_LIBRARY_OPEN:
                    setUploadUrl("");
                    break;
                default:
                    Log.e(TAG, "getService -> Unknown Mode Type. Setting URL to Sandbox");
                    setUploadUrl(NokeDefines.sandboxUploadURL);
                    break;
            }

            return NokeDeviceManagerService.this;
        }
    }

    /**
     * Read more here: <a href="https://developer.android.com/reference/android/os/IBinder.html">https://developer.android.com/reference/android/os/IBinder.html</a>
     */
    private final IBinder mBinder = new LocalBinder();

    private int signingRetries = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter btFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothBroadcastReceiver, btFilter);
        mReceiverRegistered = true;
        mAllowAllDevices = false;
        if (nokeDevices == null) {
            nokeDevices = new LinkedHashMap<>();
        }
        Context appContext = getApplicationContext();
        // PhoneKeyManager will be lazily initialized when needed with userId
        androidId = Settings.Secure.getString( appContext.getContentResolver(), Settings.Secure.ANDROID_ID );
        setBluetoothDelayDefault(NokeDefines.BLUETOOTH_DEFAULT_SCAN_TIME);
        setBluetoothDelayBackgroundDefault(NokeDefines.BLUETOOTH_DEFAULT_SCAN_TIME_BACKGROUND);
        setBluetoothScanDuration(NokeDefines.BLUETOOTH_DEFAULT_SCAN_DURATION);


        //
    }

    /**
     * Sets the global listener for the service
     *
     * @param listener the listener implemented in the activity the registered the service
     */
    public void registerNokeListener(NokeServiceListener listener) {
        this.mGlobalNokeListener = listener;
        Log.d(TAG, "registerNokeListener -> CHECK BLUETOOTH SOMETHING NULL: " + mBluetoothAdapter + " " + mGlobalNokeListener);
        if (mBluetoothAdapter != null && mGlobalNokeListener != null) {
            Log.d(TAG, "registerNokeListener -> CHECK BLUETOOTH STATUS!!!!!!!");
            mGlobalNokeListener.onBluetoothStatusChanged(mBluetoothAdapter.getState());
        }
    }

    /**
     * Used for getting the Global Noke Listener
     *
     * @return the global listener
     */
    NokeServiceListener getNokeListener() {
        return mGlobalNokeListener;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    public void clearUploadQueue() {
        Log.d(TAG, "clearUploadQueue");
        globalUploadQueue.clear();
    }

    /**
     * Adds noke device to the device array.  These devices can be discovered and connected to by the service
     *
     * @param noke The noke device to add
     */
    public void addNokeDevice(NokeDevice noke) {
        if (nokeDevices == null) {
            nokeDevices = new LinkedHashMap<>();
        }

        NokeDevice newNoke = nokeDevices.get(noke.getMac());
        if (newNoke == null) {
            noke.isAdded = true;
            noke.mService = this;
            nokeDevices.put(noke.getMac(), noke);
        }
    }

    public void addNokeDevice(NokeDevice noke, Boolean forceAdd) {
        if (nokeDevices == null) {
            nokeDevices = new LinkedHashMap<>();
        }

        NokeDevice newNoke = nokeDevices.get(noke.getMac());
        if (newNoke == null || forceAdd) {
            noke.isAdded = true;
            noke.mService = this;
            nokeDevices.put(noke.getMac(), noke);
        }
    }

    public void addHandleSuccessClosure(@NonNull SuccessHandler closure) {
        this.handleSuccess = closure;
    }

    public void addHandleFailureClosure(@NonNull FailureHandler closure) {
        this.handleFailure = closure;
    }

    /**
     * Removes noke device from the device array.
     *
     * @param noke The noke device to remove
     */
    public void removeNokeDevice(NokeDevice noke) {
        if (nokeDevices != null) {
            nokeDevices.remove(noke.getMac());
        }
    }

    /**
     * Removes noke device from the device array.  These devices can be discovered and connected to by the service
     *
     * @param mac The mac address of noke device to remove
     */
    public void removeNokeDevice(String mac) {
        if (nokeDevices != null) {
            nokeDevices.remove(mac);
        }
    }

    /**
     * Removes all devices from the noke device array
     */
    public void removeAllNoke() {
        if (nokeDevices != null) {
            nokeDevices.clear();
        }
    }

    /**
     * Removes all banned mac address used in the bluetooth filter
     */
    public void removeAllBanned() {
        banned.clear();
    }

    /**
     * Returns a count of noke devices that have been added to the device manager
     *
     * @return a count of devices in the device manager
     */
    public int getNokeCount() {
        if (nokeDevices != null) {
            return nokeDevices.size();
        } else {
            return 0;
        }
    }

    /**
     * Returns an array of current noke devices that have been added to the device manager
     *
     * @return an array of noke devices
     */
    public ArrayList<NokeDevice> getAllNoke() {
        if (nokeDevices != null) {
            return new ArrayList<>(nokeDevices.values());
        } else {
            return new ArrayList<>();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mReceiverRegistered) {
            unregisterReceiver(bluetoothBroadcastReceiver);
            mReceiverRegistered = false;
        }
        //TODO Handle restarting service
    }

    /**
     * Initializes the bluetooth manager and adapter used for interacting with Noke devices
     *
     * @return boolean after initialization
     */
    public boolean initialize() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                return false;
            }
        }
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter != null && mGlobalNokeListener != null) {
            mGlobalNokeListener.onBluetoothStatusChanged(mBluetoothAdapter.getState());
        }
        return mBluetoothAdapter != null;
    }

    /**
     * Begins scanning for Noke devices that have been added to the device array
     */
    public void startScanningForNokeDevices() {
        try {
            Log.d(TAG, "start scanning");
            LocationManager lm = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            boolean gps_enabled = false;
            boolean network_enabled = false;
            try {
                if (lm != null) {
                    gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
                }
            } catch (Exception e) {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_GPS_ENABLED, "GPS is not enabled");
            }
            try {
                if (lm != null) {
                    network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception e) {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_NETWORK_ENABLED, "Network is not enabled");
            }
            int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION);
            boolean checkBluetooth = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissionCheck == PackageManager.PERMISSION_GRANTED) {
                checkBluetooth = true;
                permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_SCAN);
                if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                    permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_CONNECT);
                }
            }
            if (!gps_enabled && !network_enabled) {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_LOCATION_SERVICES_DISABLED, "Location services are disabled");
            } else if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkBluetooth) {
                    mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_SCAN_PERMISSION, "Bluetooth scan permission needed");
                } else {
                    mGlobalNokeListener.onError(null, NokeMobileError.ERROR_LOCATION_SERVICES_DISABLED, "Location services are disabled");
                }
            } else if (mBluetoothAdapter != null) {
                if (!mBluetoothAdapter.isEnabled()) {
                    mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_DISABLED, "Bluetooth is disabled");
                } else {
                    initiateBackgroundBLEScan();
                }
            } else {
                mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                if (mBluetoothManager != null) {
                    mBluetoothAdapter = mBluetoothManager.getAdapter();
                }
                if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
                    mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_DISABLED, "Bluetooth is disabled");
                } else {
                    initiateBackgroundBLEScan();
                }
            }
        } catch (NullPointerException e) {
            FirebaseCrashlytics.getInstance().log(e.getLocalizedMessage());
            mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_SCANNING, "Bluetooth scanning is not supported");
        }
    }


    boolean scanLoopOn = false;
    boolean scanLoopOff = false;
    boolean backgroundScanning = false;

    /**
     * Initiates BLE scan
     */
    private void initiateBackgroundBLEScan() {
        if (!backgroundScanning) {
            backgroundScanning = true;
            turnOnBLEScan();
        }
    }

    /**
     * bluetoothDelay in milliseconds
     */
    int bluetoothDelay;

    /**
     * Starts background scanning. Will not stop until cancelled
     */
    private void turnOnBLEScan() {
        startLeScanning();
//        final Handler refreshScan = new Handler(Looper.getMainLooper());
//        refreshScan.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                turnOffBLEScan();
//                if (isServiceRunningInForeground()) {
//                    bluetoothDelay = bluetoothDelayDefault;
//                } else {
//                    bluetoothDelay = bluetoothDelayBackgroundDefault;
//                }
//
//            }
//        }, bluetoothScanDuration);
    }

    /**
     * Sets the default delay of scanning in the foreground.  Currently the default is 10 milliseconds
     *
     * @param delay time in milliseconds
     */
    public void setBluetoothDelayDefault(int delay) {
        bluetoothDelayDefault = delay;
    }

    /**
     * Sets the default delay of scanning in the background.  Currently the default is 10 milliseconds
     *
     * @param delay time in milliseconds
     */
    public void setBluetoothDelayBackgroundDefault(int delay) {
        bluetoothDelayBackgroundDefault = delay;
    }

    /**
     * Sets the duration of scanning in the background.  Currently the default is 8000 milliseconds
     *
     * @param duration time in milliseconds
     */
    public void setBluetoothScanDuration(int duration) {
        bluetoothScanDuration = duration;
    }

    /**
     * Stops background BLE Scan
     */
    private void turnOffBLEScan() {
        stopLeScanning();
        if (backgroundScanning) {
            final Handler refreshScan = new Handler();
            refreshScan.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scanLoopOn = true;
                    scanLoopOff = false;
                    turnOnBLEScan();
                }
            }, bluetoothDelay);
        }
    }

    /**
     * Stops scanning for Noke devices
     */
    public void stopScanning() {
        stopLeScanning();
        backgroundScanning = false;
    }

    /**
     * Starts BLE scanning using the Bluetooth Adapter.
     */
    @TargetApi(Build.VERSION_CODES.M)
    ScanSettings scanSettings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build();
    @SuppressWarnings("deprecation")
    private void startLeScanning() {
        if (!mScanning) {
            if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                // Start a timeout for scanning, if no scans results are detected within the SCAN_TIMEOUT period attempt to restart scanning.
                mScanTimeoutHandler.removeCallbacks(mScanTimeoutRunnable);
                mScanRestartHandler.removeCallbacks(mScanRestartRunnable);

                mScanTimeoutHandler.postDelayed(mScanTimeoutRunnable, SCAN_TIMEOUT);
                mScanRestartHandler.postDelayed(mScanRestartRunnable, SCANNER_TIME_LIMIT);
                mScanning = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    //SCANNING WITH THE OLD APIS IS MORE RELIABLE. HENCE THE 100
//                    mScanTimeoutHandler.removeCallbacks(mScanTimeoutRunnable);
                    if (mNewBluetoothScanCallback == null) {
                        initNewBluetoothCallback();
                    };
                    mBluetoothScanner = mBluetoothAdapter.getBluetoothLeScanner();
                     /*Commented permission as unlock was failing for Android 11 and below*/
                    if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    Log.w(TAG, "REGISTERED NEW CALLBACK");
                    mBluetoothScanner.startScan(scanFilters(), scanSettings, mNewBluetoothScanCallback);
                } else {
                    initOldBluetoothCallback();
                    try {
                        mBluetoothAdapter.startLeScan(mOldBluetoothScanCallback);
                    } catch (Exception e) {
                        Log.d(TAG, "StartLeScanning exception in starting scan -" +e.getMessage());
                    }
                }
            } else {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_SCANNING, "Bluetooth scanning is not supported");
            }
        }
    }

    /**
     * Start a timeout for scanning, if no scans results are detected within the SCAN_TIMEOUT period attempt to restart scanning.
     */
    private void startScanTimeoutRunnable() {
        mScanTimeoutHandler.postDelayed(mScanTimeoutRunnable, SCAN_TIMEOUT);
    }

    /**
     * Stops BLE scanning using the bluetooth adapter.
     */
    @SuppressWarnings("deprecation")
    private void stopLeScanning() {
        Log.w(TAG, "stopLeScanning");
        mScanning = false;
        if (mBluetoothAdapter != null) {
            if (mBluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (mNewBluetoothScanCallback != null) {
                        mBluetoothScanner.stopScan(mNewBluetoothScanCallback);
                    }
                } else {
                    //DEPRECATED. INCLUDING FOR 4.0 SUPPORT
                    if (mOldBluetoothScanCallback != null) {
                        mBluetoothAdapter.stopLeScan(mOldBluetoothScanCallback);
                    }
                }
            }
        }
        mScanTimeoutHandler.removeCallbacks(mScanTimeoutRunnable);
        mScanTimeoutHandler.removeCallbacks(mRestartScanRunnable);
        mScanRestartHandler.removeCallbacks(mScanRestartRunnable);
    }

    public int getBluetoothState() {
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.getState();
        } else {
            return 0;
        }
    }

    /**
     * Initializes Bluetooth Scanning Callback for Lollipop and higher OS
     */
    private final Map<String, Long> banned = new ConcurrentHashMap<>();
    private final Map<String, String> deviceNameCache = new HashMap<>();
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void initNewBluetoothCallback() {
        mNewBluetoothScanCallback = new ScanCallback() {

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                Log.w(TAG, "onBatchScanResults: " + results.size() + " results");
                super.onBatchScanResults(results);
            }

            @Override
            public void onScanResult(int callbackType, ScanResult result) {
//                mScanTimeoutHandler.removeCallbacks(mScanTimeoutRunnable);
//                Log.d("SCAN_RAW", "MAC=" + result.getDevice().getAddress()
//                        + " NAME=" + result.getDevice().getName()
//                        + " RECORD=" + Arrays.toString(result.getScanRecord().getBytes()));

                try {
                    BluetoothDevice device = result.getDevice();
                    String deviceAddress = device.getAddress();
                    String btDeviceName = device.getName();

                    if (btDeviceName == null) {
                        //If device name is not in advertisement data, use raw scan record bytes to get name
                        btDeviceName = extractDeviceNameFromScanRecord(result.getScanRecord() != null ? result.getScanRecord().getBytes() : null);
                    }

                    Log.d(TAG, "onScanResult: device found - Parsed Name: " + btDeviceName + ", Address: " + deviceAddress);

                    if (btDeviceName == null) {
                        banned.put(deviceAddress, System.currentTimeMillis());
                        return;
                    }

                    if (btDeviceName.contains("1R")) {
                        deviceAddress = reverseMac(deviceAddress);
                    }

                    boolean isMatchingName = btDeviceName.contains(NokeDefines.NOKE_DEVICE_IDENTIFER_STRING) ||
                            (btDeviceName.toLowerCase().contains(NokeDefines.NOKE_FIRMWARE_DEVICE_IDENTIFIER_STRING) && firmwareScanning);

                    if (isMatchingName) {
                        Log.d(TAG, "onScanResult: Matching device name found: " + btDeviceName + deviceAddress);
                        NokeDevice noke = nokeDevices.get(deviceAddress);
                        deviceNameCache.put(deviceAddress, btDeviceName);

                        if (noke != null || mAllowAllDevices) {
                            if (noke == null) {
                                Log.d(TAG, "onScanResult: Creating new NokeDevice for " + btDeviceName + deviceAddress);
                                noke = new NokeDevice(btDeviceName, deviceAddress);
                                noke.isAdded = false;
                            }
                            if (isIonLock(noke)) {
                                //Log.d("ION-2", "5E is matching " + noke.getName() + " " + noke.getMac() + " " + noke.getHardwareVersion());
                            }

                            noke.bluetoothDevice = device;
                            noke.setLastSeen(new Date().getTime());
                            noke.rssi = result.getRssi();
                            noke.addRSSIArray(result.getRssi());

                            byte[] broadcastData;
                            String nameVersion;

                            if (btDeviceName.contains("FOB") && !btDeviceName.contains("NFOB")) {
                                nameVersion = btDeviceName.substring(3, 5);
                            } else {
                                nameVersion = btDeviceName.substring(4, 6);
                            }

                            Log.d(TAG, "name: " + btDeviceName + " nameVersion: " + nameVersion);
                            Log.d(TAG, "onScanResult: Device name version: " + nameVersion);

                            if (!nameVersion.equals("06") && !nameVersion.equals("04")) {
                                byte[] getdata = getManufacturerData(result.getScanRecord().getBytes());

                                if (getdata.length < 5) {
                                    Log.w(TAG, "onScanResult: Manufacturer data too short: " + Arrays.toString(getdata));
                                    return;
                                }

                                broadcastData = new byte[]{getdata[2], getdata[3], getdata[4]};

                                String version = noke.getVersion(broadcastData, btDeviceName);
                                noke.setVersion(version);

                                int lockState = NokeDefines.NOKE_LOCK_STATE_LOCKED;

                                //TODO remove this after checking broadcast is fully implemented
                                //noke.canAutoUnlock = false;

                                if (noke.getHardwareVersion().contains(NokeDefines.NOKE_HW_TYPE_HD_LOCK)) {
                                    int lockStateBroadcast = (broadcastData[0] >> 5) & 0x01;
                                    int lockStateBroadcast2 = (broadcastData[0] >> 6) & 0x01;
                                    lockState = lockStateBroadcast + lockStateBroadcast2;
                                } else if (isDoorController(noke.getHardwareVersion())) {
                                    lockState = broadcastData[0] >> 5;
//                                    Log.d(TAG, "onScanResult: Detected Door Controller with lockState: " + lockState + " mac :"+noke.getMac());
                                    if (nameVersion.equals("4E")) {
                                        if (callJammedLockingDelegate && noke.lockState == NokeDefines.NOKE_LOCK_STATE_JAMMED_LOCKING) {
                                            Log.d(TAG, "onScanResult: Jammed Locking Detected");
                                            callJammedLockingDelegate = false;
                                            mGlobalNokeListener.onNokeJammedLocking(noke);
                                        }

                                        if (callJammedUnlockingDelegate && noke.lockState == NokeDefines.NOKE_LOCK_STATE_JAMMED_UNLOCKING) {
                                            Log.d(TAG, "onScanResult: Jammed Unlocking Detected");
                                            callJammedUnlockingDelegate = false;
                                            mGlobalNokeListener.onNokeJammedUnlocking(noke);
                                        }
                                    }

                                    if (getdata.length >= 7) {
                                        noke.canAutoUnlock = canAutoUnlock(new byte[]{getdata[5], getdata[6]});
                                    } else {
                                        noke.canAutoUnlock = false;
                                        Log.d(TAG, "onScanResult: Cannot determine auto-unlock capability");
                                    }

                                } else if (noke.getHardwareVersion().equals(NokeDefines.NOKE_HW_TYPE_ULOCK)) {
                                    int lockStateBroadcast = (broadcastData[0] >> 5) & 0x01;
                                    int lockStateBroadcast2 = (broadcastData[0] >> 6) & 0x01;
                                    int addlockState = lockStateBroadcast + lockStateBroadcast2;

                                    if (addlockState == 1) {
                                        lockState = NokeDefines.NOKE_LOCK_STATE_LOCKED;
                                    } else if (addlockState == 0) {
                                        lockState = NokeDefines.NOKE_LOCK_STATE_UNLOCKED;
                                    } else {
                                        lockState = NokeDefines.NOKE_LOCK_STATE_UNKNOWN;
                                    }
                                }

                                noke.bluetoothDevice = device;
                                noke.lockState = lockState;

                                if (nokeDevices.get(noke.getMac()) == null) {
                                    Log.d(TAG, "onScanResult: Adding new NokeDevice to map: " + noke.getMac());
                                    nokeDevices.put(noke.getMac(), noke);
                                }

                                if (isInPostUnlockCooldown(noke.getMac())) {
                                    Log.d(TAG, "onScanResult: Suppressing re-discovery for " + noke.getMac() + " (post-unlock cooldown active)");
                                } else if (noke.connectionState == NokeDefines.NOKE_STATE_DISCONNECTED
                                        || noke.connectionState == NokeDefines.NOKE_STATE_DISCOVERED) {
                                    noke.connectionState = NokeDefines.NOKE_STATE_DISCOVERED;
                                    Log.i(TAG, "onScanResult: NokeDevice discovered: " + noke.getMac() + " with lockState: " + lockState);
                                    mGlobalNokeListener.onNokeDiscovered(noke);
                                } else {
                                    Log.d(TAG, "onScanResult: Skipping onNokeDiscovered for " + noke.getMac() + " — already in state " + noke.connectionState);
                                }
                            } else {
                                banned.put(deviceAddress, System.currentTimeMillis());
                                Log.d(TAG, "onScanResult: Skipping device with unsupported name version: " + nameVersion + ", " + deviceAddress);
                            }
                        } else {
                            Log.d(TAG, "onScanResult: Device not allowed (mAllowAllDevices is false and not found in list): " + btDeviceName + ", " + deviceAddress);
                        }
                    } else {
                        banned.put(deviceAddress, System.currentTimeMillis());
                        Log.d(TAG, "onScanResult: Device name does not match expected identifiers: " + btDeviceName + ", " + deviceAddress);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "onScanResult: Exception occurred", e);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "onScanFailed: errorCode = " + errorCode);
                super.onScanFailed(errorCode);
            }
        };
    }

    public static String reverseMac(String mac) {
        String[] parts = mac.split(":");
        List<String> list = Arrays.asList(parts);
        Collections.reverse(list);
        return String.join(":", list);
    }

    private boolean isIonLock(NokeDevice noke) {
        return noke.getHardwareVersion() != null && (noke.getHardwareVersion().toLowerCase().contains("E5") || noke.getHardwareVersion().toLowerCase().contains("5E"));
    }

    private String extractDeviceNameFromScanRecord(byte[] scanRecord) {
        if (scanRecord == null) return null;

        int index = 0;
        while (index < scanRecord.length) {
            int length = scanRecord[index++] & 0xFF;
            if (length == 0 || index + length > scanRecord.length) break;

            int type = scanRecord[index++] & 0xFF;

            if (type == 0x09 || type == 0x08) {
                byte[] nameBytes = Arrays.copyOfRange(scanRecord, index, index + length - 1);
                return new String(nameBytes, StandardCharsets.UTF_8);
            }

            index += (length - 1);
        }

        return null;
    }

    /**
     * Sets mAllowDevices boolean
     */
    @SuppressWarnings("SameParameterValue")
    public void setAllowAllDevices(boolean allow) {
        mAllowAllDevices = allow;
    }

    public void setAutoUnlockSeconds(long autoUnlockSeconds) {
        this.autoUnlockSeconds = autoUnlockSeconds;
    }

    /**
     * Initializes Bluetooth Scanning Callback for KitKat OS
     */
    private void initOldBluetoothCallback() {
        mOldBluetoothScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(final BluetoothDevice bluetoothDevice, final int rssi, byte[] scanRecord) {
               /* Log.w(TAG, "Removing mScanTimeoutRunnable callback. "+ bluetoothDevice.getName()); */
//                mScanTimeoutHandler.removeCallbacks(mScanTimeoutRunnable);

                try {
                    String btDeviceName = bluetoothDevice.getName();
                    if (btDeviceName != null && btDeviceName.contains(NokeDefines.NOKE_DEVICE_IDENTIFER_STRING) || (btDeviceName != null && btDeviceName.toLowerCase().contains(NokeDefines.NOKE_FIRMWARE_DEVICE_IDENTIFIER_STRING) && firmwareScanning)) {
                        NokeDevice noke = nokeDevices.get(bluetoothDevice.getAddress());
                        deviceNameCache.put(bluetoothDevice.getAddress(), btDeviceName);
                        if (noke != null || mAllowAllDevices) {
                            if (noke == null) {
                                noke = new NokeDevice(btDeviceName, bluetoothDevice.getAddress());
                                noke.isAdded = false;
                            }
                            noke.bluetoothDevice = bluetoothDevice;
                            noke.setLastSeen(new Date().getTime());
                            noke.rssi = rssi;
                            noke.addRSSIArray(rssi);
                            byte[] broadcastData;
                            String nameVersion;

                            if (btDeviceName.contains("FOB") && !btDeviceName.contains("NFOB")) {
                                nameVersion = btDeviceName.substring(3, 5);
                            } else {
                                nameVersion = btDeviceName.substring(4, 6);
                            }

                            if (!nameVersion.equals("06") && !nameVersion.equals("04")) {
                                byte[] getdata = getManufacturerData(scanRecord);

                                broadcastData = new byte[]{getdata[2], getdata[3], getdata[4]};

                                String version = noke.getVersion(broadcastData, btDeviceName);
                                noke.setVersion(version);

                                int lockState = NokeDefines.NOKE_LOCK_STATE_LOCKED;

                                //TODO remove this after checking broadcast is fully implemented
                                //noke.canAutoUnlock = false;

                                if (noke.getHardwareVersion().contains(NokeDefines.NOKE_HW_TYPE_HD_LOCK)) {
                                    int lockStateBroadcast = (broadcastData[0] >> 5) & 0x01;
                                    int lockStateBroadcast2 = (broadcastData[0] >> 6) & 0x01;
                                    lockState = lockStateBroadcast + lockStateBroadcast2;

                                } else if (isDoorController(noke.getHardwareVersion())) {
                                    lockState = broadcastData[0] >> 5;
                                    if (nameVersion.equals("4E")) {
                                        //Log.d(TAG, "initOldBluetoothCallback -> onLeScan -> lockState = " + lockState);
                                        if (callJammedLockingDelegate && noke.lockState == NokeDefines.NOKE_LOCK_STATE_JAMMED_LOCKING) {
                                            Log.d(TAG, "initOldBluetoothCallback -> onLeScan -> onNokeJammedLocking");
                                            callJammedLockingDelegate = false;
                                            mGlobalNokeListener.onNokeJammedLocking(noke);
                                        }

                                        if (callJammedUnlockingDelegate && noke.lockState == NokeDefines.NOKE_LOCK_STATE_JAMMED_UNLOCKING) {
                                            Log.d(TAG, "initOldBluetoothCallback -> onLeScan -> onNokeJammedUnlocking");
                                            callJammedUnlockingDelegate = false;
                                            mGlobalNokeListener.onNokeJammedUnlocking(noke);
                                        }
                                    }
                                    if (getdata.length >= 7) {
                                        noke.canAutoUnlock = canAutoUnlock(new byte[]{getdata[5], getdata[6]});
                                    } else {
                                        noke.canAutoUnlock = false;
                                    }
                                } else if (noke.getHardwareVersion().equals(NokeDefines.NOKE_HW_TYPE_ULOCK)) {
                                    int lockStateBroadcast = (broadcastData[0] >> 5) & 0x01;
                                    int lockStateBroadcast2 = (broadcastData[0] >> 6) & 0x01;
                                    int addlockState = lockStateBroadcast + lockStateBroadcast2;
                                    if (addlockState == 1) {
                                        lockState = NokeDefines.NOKE_LOCK_STATE_LOCKED;
                                    } else if (addlockState == 0) {
                                        lockState = NokeDefines.NOKE_LOCK_STATE_UNLOCKED;
                                    } else {
                                        lockState = NokeDefines.NOKE_LOCK_STATE_UNKNOWN;

                                    }
                                }

                                noke.bluetoothDevice = bluetoothDevice;
                                noke.connectionState = NokeDefines.NOKE_STATE_DISCOVERED;

                                if (nokeDevices.get(noke.getMac()) == null) {
                                    nokeDevices.put(noke.getMac(), noke);
                                }
                                noke.lockState = lockState;
                                mGlobalNokeListener.onNokeDiscovered(noke);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "initOldBluetoothCallback -> onLeScan -> CAUGHT DEAD OBJECT " + e.getMessage());
                }
            }
        };
    }

    private boolean isDoorController(String hwVersion) {
        return hwVersion.contains(NokeDefines.NOKE_HW_TYPE_DOOR_CONTROLLER) ||
                hwVersion.contains(NokeDefines.NOKE_HW_TYPE_THUNDERGUN) ||
                hwVersion.contains(NokeDefines.NOKE_HW_TYPE_KEYPAD);
    }


    private Boolean canAutoUnlock(byte[] timeBytes) {
        //Log.d("BROADCAST", "BROADCAST: " + NokeDefines.bytesToHex(timeBytes));
        String timeHexString = bytesToHex(timeBytes);
        if (timeHexString.equals("0000")) {
            return false;
        }

        if (timeHexString.equals("0001")) {
            return true;
        }
        long value = Long.parseLong(timeHexString, 16);
        long unixTime = System.currentTimeMillis() / 1000L;
        byte[] currentBytes = longToBytes(unixTime);
        byte[] twoBytes = new byte[]{currentBytes[2], currentBytes[3]};
        long currentValue = Long.parseLong(bytesToHex(twoBytes), 16);
//        Log.d("AUTOUNLOCK", "CURRENT VALUE: " + currentValue);
//        Log.d("AUTOUNLOCK", "VALUE: " + value);
        if (currentValue > value) {
            long diff = currentValue - value;
//            Log.d("AUTOUNLOCK", "DIFF: " + diff);
            if (diff > autoUnlockSeconds) {
                return true;
            }
        }
        return false;
    }

    public static byte[] longToBytes(long l) {
        byte[] result = new byte[4];
        for (int i = 3; i >= 0; i--) {
            result[i] = (byte) (l & 0xFF);
            l >>= 8;
        }
        return result;
    }

    /**
     * Parses through the manufacturer data
     *
     * @param scanRecord - broadcast data from the lock
     * @return - returns formatted manufacturer data
     */
    private byte[] getManufacturerData(byte[] scanRecord) {
        int i = 0;
        do {
            try {
                int length = scanRecord[i];
                i++;
                byte type = scanRecord[i];
                if (type == (byte) 0xFF) {
                    i++;
                    byte[] manufacturerdata = new byte[length];
                    for (int j = 0; j < length; j++) {
                        manufacturerdata[j] = scanRecord[i];
                        i++;
                    }
                    return manufacturerdata;
                } else {
                    i = i + length;
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                return new byte[]{0, 0, 0, 0, 0};
            }
        } while (i < scanRecord.length);
        return new byte[]{0, 0, 0, 0, 0};
    }

    /**
     * Starts connection to Noke device
     *
     * @param noke - The device to which to connect
     */
    public void connectToNoke(NokeDevice noke) {
//        Log.d(TAG, "CONNECT TO DEVICE ND1");
//        invalidateConnectionTimer();
//        initializeConnectionTimer();
        currentNoke = noke;
        connectToDevice(noke.bluetoothDevice, noke.rssi);
    }

    /**
     * Start initial provisioning flow - should be called after user login
     * This is a ONE-TIME operation per user
     * @deprecated This method should be called from application logic after login, not automatically
     */
    @Deprecated
    private void mockProv(NokeDevice noke) {
        startInitialProvisioning();
    }

    /**
     * Start initial phone key provisioning
     * Call this after successful user login if not already provisioned
     */
    public void startInitialProvisioning() {
        try {
            Log.d("ION-2", "startInitialProvisioning() called");
            
            PhoneKeyManager manager = getPhoneKeyManager();
            if (manager == null) {
                Log.e("ION-2", "Cannot provision - no userId available");
                return;
            }
            
            // Check if already provisioned
            if (manager.isProvisioned()) {
                Log.d("ION-2", "Already provisioned - skipping");
                return;
            }

            Log.d("ION-2", "Not provisioned yet, proceeding with provisioning");
            
            // Ensure phone has keys
            manager.ensureKeys();

            // Get phone public key
            String publicKey = manager.getPublicKeyBase64();
            Log.d("ION-2", "Got public key: " + (publicKey != null && !publicKey.isEmpty() ? "[present]" : "[missing]"));

            // Get userId
            SharedPreferences pref = getSharedPreferences(NokeDefines.DEF_NAME, MODE_PRIVATE);
            String userId = pref.getString(PREF_USER_UUID, "");
            Log.d("ION-2", "Retrieved userId from SharedPreferences: " + (userId.isEmpty() ? "[empty]" : "[present]"));

            if (userId.isEmpty()) {
                Log.e("ION-2", "Cannot provision - no user ID in SharedPreferences");
                return;
            }

            manager.provisionPhoneCompletion(
                    androidId,
                    publicKey,
                    userId,
              keyId -> {
                  // Null-safe handling: keyId is Integer (nullable)
                  // null = provisioning failure, non-null = success
                  if (keyId != null) {
                      Log.d("ION-2", "✅ PROVISIONED SUCCESSFULLY: keyId=" + keyId);
                      // Provisioning state is automatically saved in provisionPhoneCompletion
                      
                      // NOTE: ACL fetching moved to device sync completion (in ApiClient)
                      // to ensure devices are loaded in database before fetching ACLs
                      Log.d("ION-2", "⏳ ACLs will be fetched after device sync completes");
                  } else {
                      Log.e("ION-2", "❌ Provisioning failed - received null keyId from backend");
                  }
                  
                  // Note: Provisioning flag in Application will be reset on next call
                  // based on isProvisionedForUser() check - no need for explicit reset
                  
                  return Unit.INSTANCE;
              }
            );

        } catch (Exception e) {
            Log.e("ION-2", "Provisioning setup failed: " + e.getMessage());
        }
    }


    /**
     * Unlock an ION-2 device using ACL-based authentication from PhoneKeyManager.
     * This method retrieves the ACL from the cached PhoneKeyManager and triggers
     * the ACL-based unlock flow by writing ACL and signature to the lock.
     *
     * @param noke The ION-2 device to unlock
     */
    /**
     * Unlock an ION-2 device using ACL-based authentication.
     * This is the entry point for new user-initiated unlocks.
     * Resets the retry counter before attempting unlock.
     */
    public void unlockSigningDevice(NokeDevice noke) {
        try {
            // Reset retry counter for new user-initiated unlock attempt
            noke.resetSigningRetry();
            
            // Set currentNoke so that handleAclStatus can process the status response
            // This is required because onCharacteristicRead needs currentNoke to be non-null
            currentNoke = noke;
            
            PhoneKeyManager manager = getPhoneKeyManager();
            if (manager == null) {
                Log.e("ION-2", "Cannot unlock - no userId available");
                return;
            }

            if (!manager.isProvisioned()) {
                Log.e("ION-2", "Cannot unlock - phone not provisioned");
                return;
            }

            Log.d("ION-2", "Attempting to unlock ION-2 lock with ACL from PhoneKeyManager");

            String aclBinary = Objects.requireNonNull(manager.getAclEnvelope(noke.getMac())).getAclBinary();
            String aclSignature = Objects.requireNonNull(manager.getAclEnvelope(noke.getMac())).getAclSignature();

            noke.unlockForSigning(aclBinary, aclSignature);

        } catch (Exception e) {
            Log.e("ION-2", "Unlocking failed: " + e.getMessage());
        }
    }

    /**
     * Retry unlock for ION-2 device after ACL refresh.
     * This method is called during retry flows and does NOT reset the retry counter.
     * Used after refetchAcl() completes to continue the retry without resetting progress.
     *
     * @param noke The ION-2 device to unlock
     */
    public void retryUnlockAfterAclFetch(NokeDevice noke) {
        try {
            // Do NOT reset retry counter - this is a retry continuation
            currentNoke = noke;
            
            PhoneKeyManager manager = getPhoneKeyManager();
            if (manager == null) {
                Log.e("ION-2", "Cannot unlock - no userId available");
                return;
            }

            if (!manager.isProvisioned()) {
                Log.e("ION-2", "Cannot unlock - phone not provisioned");
                return;
            }

            Log.d("ION-2", "Retrying unlock after ACL refresh (attempt " + (noke.signingRetryCount + 1) + ")");

            String aclBinary = Objects.requireNonNull(manager.getAclEnvelope(noke.getMac())).getAclBinary();
            String aclSignature = Objects.requireNonNull(manager.getAclEnvelope(noke.getMac())).getAclSignature();

            noke.unlockForSigning(aclBinary, aclSignature);

        } catch (Exception e) {
            Log.e("ION-2", "Retry unlock failed: " + e.getMessage());
        }
    }


    /**
     * Attempts to match MAC address to device in nokeDevices list.  If device is found, stop scanning and
     * call connectToGatt to start service discovery and connect to device.
     *
     * @param device Bluetooth device that was obtained from the scanner callback
     * @param rssi   RSSI value obtained from the scanner.  Can be used for adjusting or checking connecting range.
     */
    private void connectToDevice(BluetoothDevice device, int rssi) {
        Log.d(TAG, "NokeDeviceManagerService -> connectToDevice");
        if (device != null) {
            NokeDevice noke = nokeDevices.get(device.getAddress());
            if (noke != null) {
                noke.mService = this;
                noke.connectionAttempts = 0;
                noke.rssi = rssi;
                if (noke.bluetoothDevice == null) {
                    noke.bluetoothDevice = device;
                }

                if (noke.connectionState == NokeDefines.NOKE_STATE_DISCONNECTED || noke.connectionState == NokeDefines.NOKE_STATE_DISCOVERED) {
                    mBluetoothAdapter.cancelDiscovery();
                    noke.connectionState = NokeDefines.NOKE_STATE_CONNECTING;

                    // ION-2 - Removed mockProv() call - provisioning should happen after login, not on device connection
                    // if (isIonLock(noke)) {
                    //     mockProv(noke);
                    // }

                    Log.d("ION-2", "isIonLock: " + isIonLock(noke));

                    Handler handler = new Handler(Looper.getMainLooper());
                    final NokeDevice finalNoke = noke;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finalNoke.gatt == null) {
                                stopLeScanning();
                                Log.d("ION-2", "connectToDevice -> Initializing gatt connection: " + connectToGatt(finalNoke));
                            } else {
                                /*Reusing GATT objects causes issues.  If the gatt object is not null when first
                                 * connecting to lock. Disconnect/null object and try reconnecting
                                 */
                                finalNoke.gatt.disconnect();
                                finalNoke.gatt.close();
                                finalNoke.gatt = null;
                                stopLeScanning();
                                Log.d("ION-2", "connectToDevice -> Initializing gatt connection: " + connectToGatt(finalNoke));
                            }
                        }
                    });
                }
            } else if (device.getName() != null) {
                if (device.getName().contains(NokeDefines.NOKE_DEVICE_IDENTIFER_STRING)) {
                    stopLeScanning();
                    noke = new NokeDevice(device.getName(), device.getAddress());
                    if (noke.bluetoothDevice == null) {
                        noke.bluetoothDevice = device;
                    }
                    if (noke.connectionState == NokeDefines.NOKE_STATE_DISCONNECTED || noke.connectionState == NokeDefines.NOKE_STATE_DISCOVERED) {
                        mBluetoothAdapter.cancelDiscovery();
                        noke.connectionState = NokeDefines.NOKE_STATE_CONNECTING;
                        if (noke.gatt == null) {
                            stopLeScanning();
                            Log.d(TAG, "connectToDevice -> Initializing gatt connection: " + connectToGatt(noke));
                        } else {
                            /*
                             * Reusing GATT objects causes issues.  If the gatt object is not null when first
                             * connecting to lock. Disconnect/null object and try reconnecting
                             */
                            noke.gatt.disconnect();
                            noke.gatt.close();
                            noke.gatt = null;
                            stopLeScanning();
                            Log.d(TAG, "connectToDevice -> Initializing gatt connection: " + connectToGatt(noke));
                        }
                    }
                }
            }
        }
    }

    /**
     * Connects to the GATT server hosted on the Noke device.
     *
     * @param noke The destination noke device
     * @return Return true if the connection is initiated successfully.
     * The connection result is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    private boolean connectToGatt(final NokeDevice noke) {
        Log.d(TAG, "connectToGatt -> CONNECT TO GATT ND3");
        if (mBluetoothAdapter == null || noke == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_BLUETOOTH_DISABLED, "Bluetooth is disabled");
            return false;
        }

        if (noke.bluetoothDevice == null) {
            Log.e(TAG, "connectToGatt -> BAD DEVICE");
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return false;
        }

        // Clear any stale service-discovery guards from the previous session so the
        // new connection can call discoverServices() and handle onServicesDiscovered cleanly.
        discoveringMacs.remove(noke.getMac());
        servicesHandledMacs.remove(noke.getMac());


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            noke.gatt = noke.bluetoothDevice.connectGatt(NokeDeviceManagerService.this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            noke.gatt = noke.bluetoothDevice.connectGatt(NokeDeviceManagerService.this, false, mGattCallback);
        }

        if (noke.gatt == null) {
            Log.w(TAG, "connectToGatt: GATT returned null");
            return false;
        }

        noke.connectionAttempts++;
        noke.connectionState = NokeDefines.NOKE_STATE_CONNECTING;
        /*
        This is a watchdog. Added in the event that the connection hasn't been established within 5 seconds.
        If the state hasn't changed and remains in connecting, then we manually; disconnect the device, update
        the connection state and restart scanning.
         */
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (noke.connectionState == NokeDefines.NOKE_STATE_CONNECTING) {
                Log.w(TAG, "connectToGatt: Connection timeout. Lock may have powered off: " + noke.getMac());

                if (noke.isAttemptingLockConnection()) {
                    startLeScanning();
                } else {
                    try {
                        if (noke.gatt != null) {
                            noke.gatt.disconnect();
                            noke.gatt.close();
                            noke.gatt = null;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "connectToGatt: Exception during timeout cleanup", e);
                    }

                    noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                    mGlobalNokeListener.onNokeDisconnected(noke);
                    startLeScanning();
                }
            } else if (noke.isAttemptingLockConnection()) {
                connectToGatt(noke);
            }
        }, 8000);

        return true;
    }




    private String androidId;
    private BluetoothGattCharacteristic aclChar; //ION-2 - 12
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic statusChar;

    private BluetoothGattCharacteristic aclSigChar;

    private BluetoothGattCharacteristic commandIdChar;

    private BluetoothGattCharacteristic commandWriteChar;

    private BluetoothGattCharacteristic commandSigChar;

    // ACL upload state
    private byte[] aclBytes;

    private int ACL_CHUNK_SIZE = 128;
    private int currentChunk = 0;
    private int totalChunks = 0;

    private UUID ACL_CHAR_UUID = UUID.fromString("ae82ffb3-6ac4-4f9d-a917-308d52492513");
    private UUID LOCK_SERVICE_UUID = UUID.fromString("AE82FFB0-6AC4-4F9D-A917-308D52492513");
    private UUID STATUS_CHAR_UUID = UUID.fromString("ae82ffb4-6ac4-4f9d-a917-308d52492513");


    /**
     * Implementation of the the BluetoothGatt callbacks.
     * Read more here: <a href="https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback.html">https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback.html</a>
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            final NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            //invalidateConnectionTimer();
            if (noke == null) {
                Log.w("ION-2", "onConnectionStateChange: No NokeDevice found for GATT address: " + gatt.getDevice().getAddress());
                return;
            }

            // Only reassign gatt for non-disconnect states to prevent reattaching
            // a stale/closed gatt reference after force-close timeout fires
            if (newState != BluetoothProfile.STATE_DISCONNECTED) {
                if (noke.gatt == null || noke.gatt != gatt) {
                    noke.gatt = gatt;
                    Log.i("ION-2", "Assigned gatt in onConnectionStateChange");
                }
            }

            try {
                Log.d(TAG, "onConnectionStateChange -> status: " + status + " -> state: " + newState);
                if (status == NokeDefines.NOKE_GATT_ERROR) {


                    if (noke.connectionAttempts > 5) {
                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (noke.gatt != null) {
                                    noke.gatt.disconnect();
                                    noke.gatt.close();
                                    noke.gatt = null;
                                }
                                bleOperationQueue.clear(); // Clear pending operations on disconnect
                                startLeScanning();
                                noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                                mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_BLUETOOTH_GATT, "Bluetooth Gatt Error: 133");
                            }
                        });
                    } else {
                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                noke.connectionAttempts++;
                                refreshDeviceCache(noke.gatt, true);

                                try {
                                    if (noke.gatt != null) {
                                        noke.gatt.disconnect();
                                        noke.gatt.close();
                                        noke.gatt = null;
                                    }
                                    bleOperationQueue.clear(); // Clear pending operations on disconnect

                                    Thread.sleep(2600);
                                } catch (Exception e) {
                                    Log.e(TAG, "onConnectionStateChange -> Exception1 ->", e);
                                }
                                Log.d(TAG, "onConnectionStateChange -> Initializing gatt connection refresh: " + connectToGatt(noke));
                            }
                        });
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Don't restart scanning while connected - we're about to perform operations
                    noke.connectionAttempts = 0;
                    noke.connectionState = NokeDefines.NOKE_STATE_CONNECTING;
                    noke.isRestoring = false;
                    noke.clearCommands();
                    mGlobalNokeListener.onNokeConnecting(noke);

                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {

                                boolean requested = false;

                                if (noke.gatt == null) {
                                    noke.gatt = gatt;
                                    Log.i(TAG, "Assigned new GATT from connection");
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    try {
                                        requested = noke.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                                        Log.i(TAG, "Connection priority HIGH requested: " + requested);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in requestConnectionPriority", e);
                                    }
                                }

                                // Request larger MTU for better BLE stability and throughput
                                // Default MTU is 23 bytes, requesting 512 (will negotiate down if needed)
                                // This helps reduce Connection 104 errors on some devices
                                // Note: Service discovery will be triggered in onMtuChanged callback
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    try {
                                        boolean mtuRequested = noke.gatt.requestMtu(512);
                                        Log.i(TAG, "MTU 512 requested: " + mtuRequested + " (will wait for onMtuChanged before service discovery)");
                                        
                                        if (!mtuRequested) {
                                            // If MTU request failed, proceed with service discovery immediately
                                            Log.w(TAG, "MTU request failed, proceeding with service discovery");
                                            startServiceDiscovery(noke);
                                        } else {
                                            // Set timeout in case onMtuChanged never arrives (device doesn't support MTU negotiation)
                                            Handler handler = new Handler(Looper.getMainLooper());
                                            handler.postDelayed(() -> {
                                                if (noke.connectionState == NokeDefines.NOKE_STATE_CONNECTING) {
                                                    Log.w(TAG, "MTU change timeout, proceeding with service discovery anyway");
                                                    startServiceDiscovery(noke);
                                                }
                                            }, 500); // 500ms timeout for MTU negotiation
                                        }
                                    } catch (Exception e) {
                                        Log.e(TAG, "Exception in requestMtu", e);
                                        startServiceDiscovery(noke);
                                    }
                                } else {
                                    // Android < 5.0 doesn't support MTU negotiation
                                    startServiceDiscovery(noke);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "onConnectionStateChange -> Exception2", e);
                            }
                        }
                    });
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Restart scanning after disconnect to find other devices
                    startLeScanning();
                    if (noke.connectionState == 2) {
                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (noke.gatt != null) {
                                        noke.gatt.disconnect();
                                    }
                                    if (noke.gatt != null) {
                                        noke.gatt.close();
                                        noke.gatt = null;
                                    }
                                    bleOperationQueue.clear(); // Clear pending operations on disconnect
                                    Log.d(TAG, "onConnectionStateChange -> Initializing gatt connection: " + connectToGatt(noke));
                                } catch (Exception e) {
                                    Log.e(TAG, "onConnectionStateChange -> Exception3", e);
                                }
                            }
                        });
                    } else {
                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "[DISCONNECT] STATE_DISCONNECTED else-branch for " + noke.getMac() + ": connectionAttempts=" + noke.connectionAttempts + ", connectionState=" + noke.connectionState);
                                
                                // Refresh device cache before closing (clears Android service cache for clean reconnect)
                                refreshDeviceCache(gatt, NokeDefines.SHOULD_FORCE_GATT_REFRESH);
                                
                                // Close gatt properly using the callback parameter (noke.gatt may already be null
                                // because disconnectNoke() closes immediately now)
                                try {
                                    if (gatt != null) {
                                        gatt.close();
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "[DISCONNECT] Exception closing gatt in STATE_DISCONNECTED: " + e.getMessage());
                                }
                                // Guard against corrupting a new connection that was established while
                                // this old STATE_DISCONNECTED callback was queued on the main thread.
                                final boolean newConnectionActive = (noke.gatt != null);
                                if (!newConnectionActive) {
                                    noke.gatt = null; // already null, explicit for clarity
                                    discoveringMacs.remove(noke.getMac());
                                    servicesHandledMacs.remove(noke.getMac());
                                } else {
                                    Log.d(TAG, "[DISCONNECT] STATE_DISCONNECTED: new GATT active for " + noke.getMac() + " — skipping gatt null-out and guard-set clear to prevent servicesHandledMacs race");
                                }
                                bleOperationQueue.clear();
                                
                                if (noke.connectionAttempts == 0) {
                                    // Guard against double notification (force-close timeout may have already fired)
                                    if (noke.connectionState != NokeDefines.NOKE_STATE_DISCONNECTED) {
                                        Log.d(TAG, "[DISCONNECT] STATE_DISCONNECTED: setting DISCONNECTED state and firing onNokeDisconnected for " + noke.getMac());
                                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                                        mGlobalNokeListener.onNokeDisconnected(noke);
                                    }
                                    uploadData();
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "onConnectionStateChange -> Exception4 -> " + e.getMessage());
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            if (noke == null) return;
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU changed successfully to " + mtu + " bytes for " + noke.getMac());
            } else {
                Log.w(TAG, "MTU change failed with status " + status + " for " + noke.getMac() + ", using default MTU");
            }
            
            // MTU negotiation complete (success or failure), now proceed with service discovery
            if (noke.connectionState == NokeDefines.NOKE_STATE_CONNECTING) {
                startServiceDiscovery(noke);
            }
        }
        
        /**
         * Helper method to start service discovery.
         * Should be called after MTU negotiation completes (or times out).
         * Uses discoveringMacs to ensure only one concurrent discoverServices() call per device
         * — the MTU 500ms timeout and onMtuChanged can otherwise both fire with
         * connectionState == CONNECTING, each calling discoverServices() and producing
         * two onServicesDiscovered callbacks that both slip past the CONNECTED guard.
         */
        private void startServiceDiscovery(NokeDevice noke) {
            if (noke == null || noke.gatt == null) return;

            // Atomic guard: if this MAC is already being discovered, skip.
            // Collections.synchronizedSet ensures thread-safety between the BLE callback
            // thread (onMtuChanged) and the main thread (MTU timeout).
            if (!discoveringMacs.add(noke.getMac())) {
                Log.w(TAG, "startServiceDiscovery: discovery already started for " + noke.getMac() + ", skipping duplicate call");
                return;
            }

            try {
                boolean discoveryStarted = noke.gatt.discoverServices();
                Log.i(TAG, "Starting service discovery: " + discoveryStarted);
                if (!discoveryStarted) {
                    // Discovery couldn't start — allow retry
                    discoveringMacs.remove(noke.getMac());
                }
                // Service discovery will trigger onServicesDiscovered callback
                // which handles provisioning/ACL and calls onNokeConnected
            } catch (Exception e) {
                Log.e(TAG, "Exception in discoverServices", e);
                discoveringMacs.remove(noke.getMac());
            }
        }

        void refreshDeviceCache(final BluetoothGatt gatt, final boolean force) {
            /*
             * If the device is bonded this is up to the Service Changed characteristic to notify Android that the services has changed.
             * There is no need for this trick in that case.
             * If not bonded, the Android should not keep the services cached when the Service Changed characteristic is present in the target device database.
             * However, due to the Android bug (still exists in Android 5.0.1), it is keeping them anyway and the only way to clear services is by using this hidden refresh method.
             */

            try {
                if (gatt != null && (force || gatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE)) {
                    /*
                     * There is a refresh() method in BluetoothGatt class but for now it's hidden. We will call it using reflections.
                     */

                    @SuppressWarnings("JavaReflectionMemberAccess") final Method refresh = gatt.getClass().getMethod("refresh");
                    if (refresh != null) {
                        final boolean success = (Boolean) refresh.invoke(gatt);
                        Log.d(TAG, "refreshDeviceCache -> Refreshing Result: " + success);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "refreshDeviceCache -> Exception -> " + e.getMessage());
            }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) { //ION-2 - 0
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            if (noke == null) return;

            if (status != BluetoothGatt.GATT_SUCCESS) return;

            if (noke.gatt != null && noke.gatt != gatt) {
                Log.w(TAG, "[onServicesDiscovered] Duplicate service discovery on stale GATT for " + noke.getMac() + " - closing stale gatt");
                try { gatt.close(); } catch (Exception ignored) {}
                return;
            }

            if (!servicesHandledMacs.add(noke.getMac())) {
                Log.w(TAG, "[onServicesDiscovered] Duplicate callback suppressed for " + noke.getMac() + " (servicesHandledMacs guard) - connectionState=" + noke.connectionState);
                return;
            }

            if (noke.connectionState == NokeDefines.NOKE_STATE_CONNECTED
                    || noke.connectionState == NokeDefines.NOKE_STATE_UNLOCKED) {
                Log.w(TAG, "[onServicesDiscovered] Skipping duplicate service discovery for " + noke.getMac() + " (connectionState=" + noke.connectionState + ")");
                return;
            }

            // Claim CONNECTED state before any async processing.
            noke.connectionState = NokeDefines.NOKE_STATE_CONNECTED;

            // Cache device name if missing
            String cachedName = deviceNameCache.get(noke.getMac());
            if (cachedName == null) deviceNameCache.put(noke.getMac(), noke.getName());

            Log.e("Hardware version: " + noke.getHardwareVersion(), "ION-2");
            if (!(noke.getVersion().contains("5E") || noke.getVersion().contains("E5"))) {
                readStateCharacteristic(noke); // Old flow for non-Ion locks
                return;
            }

            BluetoothGattService service = gatt.getService(LOCK_SERVICE_UUID);

            // Noke Ion 2 - Write Characteristics
            aclChar = service.getCharacteristic(SigningWriteCharacteristicType.ACL.getCharacteristicUuid());
            aclSigChar = service.getCharacteristic(SigningWriteCharacteristicType.ACL_SIGNATURE.getCharacteristicUuid());
            commandWriteChar = service.getCharacteristic(SigningWriteCharacteristicType.COMMAND.getCharacteristicUuid());
            commandSigChar = service.getCharacteristic(SigningWriteCharacteristicType.COMMAND_SIGNATURE.getCharacteristicUuid());

            // Noke Ion 2 - Read Characteristics
            statusChar = service.getCharacteristic(SigningReadCharacteristicType.STATUS.getCharacteristicUuid());
            commandIdChar = service.getCharacteristic(SigningReadCharacteristicType.COMMAND_ID.getCharacteristicUuid());

            // Ion lock flow - Check if already provisioned
            PhoneKeyManager manager = getPhoneKeyManager();
            if (manager == null || !manager.isProvisioned()) {
                Log.w("ION-2", "Phone not provisioned yet. Provisioning should happen after login, not on device connection.");
                noke.cryptoState = NokeDevice.CryptoState.NEEDS_PROVISIONING;
                // Optionally: trigger a warning or request user to complete provisioning
                return;
            }

            // Check for valid cached ACL
            PhoneKeyAcl acl = manager.getAcl(noke.getMac());
            if (acl != null && manager.isAclValid(acl)) {
                Log.d("ION-2", "Valid ACL found for lock " + noke.getMac());
                noke.cryptoState = NokeDevice.CryptoState.READY;
                // connectionState already set to NOKE_STATE_CONNECTED above
                
                // BLE connection is fully stable after successful service discovery
                // No additional delay needed - BleOperationQueue serializes actual BLE operations
                Log.d("ION-2", "Service discovery complete, ready for operations");
                mGlobalNokeListener.onNokeConnected(noke); // ACL available - ready for unlock
            } else {
                // ACL missing or expired - need to fetch from backend
                Log.d("ION-2", "No valid ACL for lock " + noke.getMac() + " - fetching from backend");
                // connectionState already set to NOKE_STATE_CONNECTED above
                
                // BLE connection is fully stable - fetch ACL immediately (backend call, not BLE operation)
                Log.d("ION-2", "Service discovery complete, requesting ACL from backend");
                requestAclFromBackend(noke);
            }
        }

    /**
     * Request ACL from backend for a specific lock
     * Called when ACL is missing or expired
     * 
     * This method first checks if a valid cached ACL exists (from bulk fetch).
     * If found, uses cached ACL. Otherwise, fetches individual ACL from backend.
     */
    private void requestAclFromBackend(NokeDevice noke) {
        try {
            PhoneKeyManager manager = getPhoneKeyManager();
            if (manager == null) {
                Log.e("ION-2", "PhoneKeyManager not available - cannot request ACL");
                noke.cryptoState = NokeDevice.CryptoState.FAILED;
                return;
            }
            
            // Check if valid cached ACL exists (from bulk fetch)
            if (manager.hasCachedAcl(noke.getMac())) {
                Log.d("ION-2", "Valid cached ACL found for lock " + noke.getMac() + " - using cached version");
                noke.cryptoState = NokeDevice.CryptoState.READY;
                mGlobalNokeListener.onNokeConnected(noke);
                return;
            }
            
            Log.d("ION-2", "No valid cached ACL found for lock " + noke.getMac() + " - fetching from backend");
            
            String phoneKeyId = manager.getPhoneKeyId();
            if (phoneKeyId == null) {
                Log.e("ION-2", "No phone key ID found - cannot request ACL");
                noke.cryptoState = NokeDevice.CryptoState.FAILED;
                return;
            }

            // Get userId from SharedPreferences
            SharedPreferences pref = getSharedPreferences(NokeDefines.DEF_NAME, MODE_PRIVATE);
            String userId = pref.getString(PREF_USER_UUID, "");
            
            if (userId.isEmpty()) {
                Log.e("ION-2", "No user ID found - cannot request ACL");
                noke.cryptoState = NokeDevice.CryptoState.FAILED;
                return;
            }

            int convertedUserId = Integer.parseInt(userId);
            int convertedKeyId = Integer.parseInt(phoneKeyId);

            manager.getAcl(
                    convertedUserId,
                    noke.getMac(),
                    convertedKeyId,
                    new Function1<Boolean, Unit>() {
                        @Override
                        public Unit invoke(Boolean success) {
                            if (success != null && success) {
                                Log.d("ION-2", "ACL received for lock " + noke.getMac());
                                noke.cryptoState = NokeDevice.CryptoState.READY;
                                // Connection state already set to CONNECTED in onServicesDiscovered
                                mGlobalNokeListener.onNokeConnected(noke);
                            } else {
                                Log.e("ION-2", "Failed to get ACL for lock " + noke.getMac());
                                noke.cryptoState = NokeDevice.CryptoState.FAILED;
                            }
                            return Unit.INSTANCE;
                        }
                    }
            );
        } catch (Exception e) {
            Log.e("ION-2", "Error requesting ACL: " + e.getMessage());
            noke.cryptoState = NokeDevice.CryptoState.FAILED;
        }
    }

    /**
     * Refetch ACL from backend and retry unlock operation
     * Matches iOS: refetchAcl() method (eligibility must be checked by caller)
     * This method is called when ACL expires or signature verification fails during unlock
     *
     * @param noke The device requiring ACL refetch
     * @param errorOnExhaustion The error to report if fetch fails
     */
    private void refetchAcl(NokeDevice noke, NokeDeviceSigningError errorOnExhaustion) {
        if (noke == null) {
            Log.e("ION-2", "refetchAcl called with null noke device");
            return;
        }
        
        // Use final variable for inner class access
        final NokeDeviceSigningError finalError = (errorOnExhaustion != null) 
            ? errorOnExhaustion 
            : NokeDeviceSigningError.ACL_REJECTED;

        Log.d("ION-2", "Refetching ACL from backend for " + noke.getMac() + " (retry attempt " + (noke.signingRetryCount + 1) + ")");

        try {
            PhoneKeyManager manager = getPhoneKeyManager();
            if (manager == null) {
                Log.e("ION-2", "PhoneKeyManager not available - cannot refetch ACL");
                if (handleFailure != null) {
                    handleFailure.onFailure(finalError.asException());
                }
                return;
            }

            String phoneKeyId = manager.getPhoneKeyId();
            if (phoneKeyId == null) {
                Log.e("ION-2", "No phone key ID found - cannot refetch ACL");
                if (handleFailure != null) {
                    handleFailure.onFailure(finalError.asException());
                }
                return;
            }

            // Get userId from SharedPreferences
            SharedPreferences pref = getSharedPreferences(NokeDefines.DEF_NAME, MODE_PRIVATE);
            String userId = pref.getString(PREF_USER_UUID, "");

            if (userId.isEmpty()) {
                Log.e("ION-2", "No user ID found - cannot refetch ACL");
                if (handleFailure != null) {
                    handleFailure.onFailure(finalError.asException());
                }
                return;
            }

            int convertedUserId;
            int convertedKeyId;
            
            try {
                convertedUserId = Integer.parseInt(userId);
                convertedKeyId = Integer.parseInt(phoneKeyId);
            } catch (NumberFormatException e) {
                Log.e("ION-2", "Invalid userId or phoneKeyId format: userId=" + userId + ", phoneKeyId=" + phoneKeyId, e);
                if (handleFailure != null) {
                    handleFailure.onFailure(finalError.asException());
                }
                return;
            }

            // Fetch fresh ACL from backend (not cache)
            manager.getAcl(
                    convertedUserId,
                    noke.getMac(),
                    convertedKeyId,
                    new Function1<Boolean, Unit>() {
                        @Override
                        public Unit invoke(Boolean success) {
                            if (success != null && success) {
                                Log.d("ION-2", "ACL refetch successful - retrying unlock (counter=" + noke.signingRetryCount + ")");
                                // Retry unlock with new ACL - DON'T reset counter (already incremented)
                                retryUnlockAfterAclFetch(noke);
                            } else {
                                Log.e("ION-2", "ACL refetch failed - notifying failure: " + finalError.getDescription());
                                if (handleFailure != null) {
                                    handleFailure.onFailure(finalError.asException());
                                }
                            }
                            return Unit.INSTANCE;
                        }
                    }
            );
        } catch (Exception e) {
            Log.e("ION-2", "Error refetching ACL: " + e.getMessage());
            if (handleFailure != null) {
                handleFailure.onFailure(finalError.asException());
            }
        }
    }

//        private void setupProvisioning(NokeDevice noke) { //ION-2 - 1
//            try {
//                // Ensure phone has keys
//                phoneKeyManager.ensureKeys();
//
//                // Get phone public key to send to backend
//                String publicKey = phoneKeyManager.getPublicKeyBase64();
//
//                //TODO: provision public key -> get ACL -> ACL to bytes -> call start upload
//                SharedPreferences pref = getSharedPreferences(NokeDefines.DEF_NAME, MODE_PRIVATE);
//                String userId = pref.getString(PREF_USER_UUID, "");
//
//                phoneKeyManager.provisionPhone(
//                        androidId,
//                        publicKey,
//                        userId,
//                        new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
//                            @Override
//                            public kotlin.Unit invoke(String keyId) {
//                                if (!keyId.contains("Error")) {
//                                    Log.d("ION-2", "PROVISIONED");
//                                    int convertedKeyId = Integer.parseInt(keyId);
//                                    int convertedUserId = Integer.parseInt(userId);
//                                    phoneKeyManager.getAcl(
//                                            convertedUserId,
//                                            noke.getMac(),
//                                            convertedKeyId,
//                                            new kotlin.jvm.functions.Function1<Boolean, kotlin.Unit>() {
//                                                @Override
//                                                public kotlin.Unit invoke(Boolean acl) {
//                                                    if (acl != null) {
//                                                        Log.d("ION-2", "ACL RECEIVED");
//                                                        sendACLToLock(noke, noke.gatt);
//                                                    } else {
//                                                        failProvisioning(noke, "Get ACL failed");
//                                                    }
//                                                    return kotlin.Unit.INSTANCE;
//                                                }
//                                            }
//                                    );
//                                } else {
//                                    failProvisioning(noke, "Provisioning has failed");
//                                }
//                                return kotlin.Unit.INSTANCE;
//                            }
//                        }
//                );
//            } catch (Exception e) {
//                failProvisioning(noke, "Provisioning setup failed: " + e.getMessage());
//            }
//        }


        public void handleAclStatus(NokeDevice noke, @NonNull NokeDeviceSigningStatus status, BluetoothGatt bluetoothGatt) {
            Log.d("ION-2", "Handling ACL status: " + status);
            switch (status) {
                case TIME_SYNCED:
                    Log.d("ION-2", "Lock time synced, now writing ACL");
                    if (noke != null && noke.gatt != null) {
                        byte[] aclData = noke.getCurrentAclData();
                        if (aclData != null && aclData.length > 0) {
                            writeAclCharacteristic(noke, aclData);
                        } else {
                            Log.e("ION-2", "TIME_SYNCED: ACL data is null or empty, cannot proceed");
                            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_SIGNING, "ACL data not available after time sync");
                        }
                    }
                    return;
                case ACL_RECEIVED:
                case ACL_VALIDATED:  // Some ION-2 locks return ACL_VALIDATED instead of ACL_RECEIVED
                    Log.d("ION-2", "ACL packet validated by lock (" + status + "), now writing signature");
                    if (noke != null && noke.gatt != null) {
                        byte[] aclSigData = noke.getCurrentAclSignatureData();
                        if (aclSigData != null && aclSigData.length > 0) {
                            writeAclSignatureCharacteristic(noke, aclSigData);
                            // Queue status read to execute after signature write completes
                            readStatusCharacteristic(noke);
                        } else {
                            Log.e("ION-2", "ACL_RECEIVED/VALIDATED: ACL signature data is null or empty, cannot proceed");
                            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_SIGNING, "ACL signature data not available");
                        }
                    }
                    return;

                case ACLSIG_VERIFIED:
                    Log.d("ION-2", "Signature received by lock, now reading command id");
                    readCommandId(bluetoothGatt, new ResultCallback<byte[]>() {
                        @Override
                        public void onSuccess(byte[] commandBytes) {
                            try {
                                // Create command: 0x00 + nonce (9 bytes total, matching iOS)
                                byte[] fullCommand = new byte[commandBytes.length + 1];
                                fullCommand[0] = 0x00; // Unlock command prefix
                                System.arraycopy(commandBytes, 0, fullCommand, 1, commandBytes.length);

                                Log.d("ION-2", "Command bytes (0x00 + nonce): " + bytesToHex(fullCommand));

                                // Sign the full command using PhoneKeyManager
                                PhoneKeyManager manager = getPhoneKeyManager();
                                if (manager == null) {
                                    Log.e("ION-2", "Cannot sign command - no PhoneKeyManager available");
                                    if (handleFailure != null) {
                                        handleFailure.onFailure(NokeDeviceSigningError.UNKNOWN.asException());
                                    }
                                    return;
                                }

                                byte[] commandSignature = manager.sign(fullCommand);
                                noke.setCurrentCommandSignatureData(commandSignature);
                                Log.d("ION-2", "Generated command signature: " + bytesToHex(commandSignature));

                                // Write the full command (0x00 + nonce)
                                writeCommandCharacteristic(noke, fullCommand);
                                // Queue status read to execute after command write completes
                                readStatusCharacteristic(noke);
                                Log.d("ION-2", "Successfully wrote command with signature");
                            } catch (Exception e) {
                                Log.e("ION-2", "Error writing command characteristic", e);
                                if (handleFailure != null) {
                                    handleFailure.onFailure(NokeDeviceSigningError.UNKNOWN.asException());
                                }
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            Log.e("ION-2", "readCommandId failed", t);
                            if (handleFailure != null) {
                                handleFailure.onFailure(NokeDeviceSigningError.INVALID_COMMAND_ID.asException());
                            }
                        }
                    });
                    return;

                case CMD_RECEIVED:
                    if (noke != null && noke.gatt != null) {
                        Log.d("ION-2", "Writing command signature characteristic");
                        writeCommandSignatureCharacteristic(noke, noke.getCurrentCommandSignatureData());
                        // Queue status read to execute after signature write completes
                        readStatusCharacteristic(noke);
                    }
                    return;

                case UNLOCK_EXECUTED:
                    Log.d("ION-2", "🎉 Unlock executed successfully");
                    // Record unlock time before disconnecting to suppress immediate re-discovery
                    recordUnlockTime(noke.getMac());
                    // Update connection state and notify listener (same as legacy unlock flow)
                    noke.connectionState = NokeDefines.NOKE_STATE_UNLOCKED;
                    mGlobalNokeListener.onNokeUnlocked(noke);
                    if (handleSuccess != null) {
                        handleSuccess.onSuccess();
                    }
                    Log.d("ION-2", "Disconnecting after successful unlock");
                    disconnectNoke(noke);
                    return;
                case ACL_REJECTED,
                     ACL_SETUP_ERR:
                    Log.d("ION-2", "ACL rejected by lock with status: " + status);
                    if (noke.isEligibleForSigningRetry()) {
                        // Increment counter BEFORE async refetch to prevent infinite loop
                        noke.incrementSigningRetry();
                        Log.d("ION-2", "Attempting ACL refetch (retry " + noke.signingRetryCount + "/" + (MAX_SIGNING_RETRIES + 1) + ")");
                        refetchAcl(noke, NokeDeviceSigningError.ACL_REJECTED);
                    } else {
                        Log.e("ION-2", "ACL retry limit exhausted for " + noke.getMac());
                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                        mGlobalNokeListener.onNokeDisconnected(noke);
                        if (handleFailure != null) {
                            handleFailure.onFailure(NokeDeviceSigningError.ACL_REJECTED.asException());
                        }
                    }
                    return;
                case ACL_TIME_EXPIRED:
                    // iOS pattern: Check eligibility first, increment counter BEFORE async refetch, then refetch (one retry only)
                    Log.d("ION-2", "ACL TIME EXPIRED on lock " + noke.getMac());
                    if (noke.isEligibleForSigningRetry()) {
                        // Increment counter BEFORE async refetch to prevent infinite loop
                        noke.incrementSigningRetry();
                        Log.d("ION-2", "Attempting ACL refetch (retry " + noke.signingRetryCount + "/" + (MAX_SIGNING_RETRIES + 1) + ")");
                        refetchAcl(noke, NokeDeviceSigningError.ACL_REJECTED);
                    } else {
                        Log.e("ION-2", "ACL retry limit exhausted for " + noke.getMac());
                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                        mGlobalNokeListener.onNokeDisconnected(noke);
                        if (handleFailure != null) {
                            handleFailure.onFailure(NokeDeviceSigningError.ACL_REJECTED.asException());
                        }
                    }
                    return;
                case ACLSIG_VERIFY_FAIL:
                    // iOS pattern: Check eligibility first, increment counter BEFORE async refetch, then refetch
                    Log.d("ION-2", "ACL SIGNATURE VERIFY FAIL on lock " + noke.getMac());
                    if (signingRetries < MAX_SIGNING_RETRIES) {
                        signingRetries += 1;
                        // Increment counter BEFORE async refetch to prevent infinite loop
//                        noke.incrementSigningRetry();
                        Log.d("ION-2", "Attempting ACL refetch (retry " + noke.signingRetryCount + "/" + (MAX_SIGNING_RETRIES + 1) + ")");
                        refetchAcl(noke, NokeDeviceSigningError.INVALID_ACL_SIGNATURE);
                    } else {
                        Log.e("ION-2", "ACL signature retry limit exhausted for " + noke.getMac());
                        if (handleFailure != null) {
                            handleFailure.onFailure(NokeDeviceSigningError.INVALID_ACL_SIGNATURE.asException());
                            disconnectNoke(noke);
                            noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                            mGlobalNokeListener.onNokeDisconnected(noke);
                        }
                        signingRetries = 0;
                    }
                    return;
                case ACL_SCHEDULE_BLOCKED:
                    Log.d("ION-2", "ACL rejected due to schedule restrictions (status: " + status + ")");
                    signingRetries += 1;
                    if (signingRetries < MAX_SIGNING_RETRIES) {
                        // Increment counter BEFORE async refetch to prevent infinite loop
//                        noke.incrementSigningRetry();
                        Log.d("ION-2", "Attempting ACL refetch (retry " + noke.signingRetryCount + "/" + (MAX_SIGNING_RETRIES + 1) + ")");
                        refetchAcl(noke, NokeDeviceSigningError.INVALID_ACL_SIGNATURE);
                    } else {
                        Log.e("ION-2", "ACL retry limit exhausted for " + noke.getMac());
                        if (handleFailure != null) {
                            handleFailure.onFailure(NokeDeviceSigningError.OUT_OF_SCHEDULE.asException());
                            disconnectNoke(noke);
                            noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                            mGlobalNokeListener.onNokeDisconnected(noke);
                        }
                        signingRetries = 0;
                    }
                    return;
                case UNLOCK_DENIED:
                    Log.w("ION-2", "Unlock command denied by lock for MAC: " + (noke != null ? noke.getMac() : "unknown"));
                    if (handleFailure != null) {
                        handleFailure.onFailure(NokeDeviceSigningError.UNLOCK_DENIED.asException());
                        disconnectNoke(noke);
                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                        mGlobalNokeListener.onNokeDisconnected(noke);
                    }
                    return;
                case UNLOCK_OVERLOCKED:
                    Log.w("ION-2", "Unlock command denied by lock for MAC due to overlock: " + (noke != null ? noke.getMac() : "unknown"));
                    if (handleFailure != null) {
                        handleFailure.onFailure(NokeDeviceSigningError.UNLOCK_OVERLOCKED.asException());
                        disconnectNoke(noke);
                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                        mGlobalNokeListener.onNokeDisconnected(noke);
                    }
                    return;
                
                case CID_READ:
                    // The lock is informing us it has read the command-ID characteristic.
                    // This is an intermediate status before CMD_RECEIVED; re-read status
                    // so the ACL state machine continues instead of stalling.
                    Log.d("ION-2", "CID_READ status received — re-reading status to get CMD_RECEIVED");
                    readStatusCharacteristic(noke);
                    return;
                case LOCK_ALREADY_UNLOCKED:
                    Log.d("ION-2", "Lock is already unlocked - treating as successful unlock");
                    // Record unlock time to suppress immediate re-discovery after disconnect
                    recordUnlockTime(noke.getMac());
                    // Update connection state and notify listener (same as UNLOCK_EXECUTED flow)
                    noke.connectionState = NokeDefines.NOKE_STATE_UNLOCKED;
                    mGlobalNokeListener.onNokeUnlocked(noke);
                    if (handleSuccess != null) {
                        handleSuccess.onSuccess();
                    }
                    Log.d("ION-2", "Disconnecting after LOCK_ALREADY_UNLOCKED");
                    disconnectNoke(noke);
                    return;
                case LOCK_LOCKED:
                    Log.w("ION-2", "Lock command rejected because lock is already locked for MAC: " + (noke != null ? noke.getMac() : "unknown"));
                    if (handleFailure != null) {
                        handleFailure.onFailure(NokeDeviceSigningError.LOCK_LOCKED.asException());
                        disconnectNoke(noke);
                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                        mGlobalNokeListener.onNokeDisconnected(noke);
                    }
                    return;
                case CMDSIG_VERIFY_FAIL:
                    // iOS pattern: Fail immediately, no retry (matches iOS Ion2SigningCoordinator)
                    Log.e("ION-2", "Command signature verification failed on lock " + noke.getMac());
                    if (handleFailure != null) {
                        handleFailure.onFailure(NokeDeviceSigningError.CMDSIG_VERIFY_FAIL.asException());
                    }
                    disconnectNoke(noke);
                    noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                    mGlobalNokeListener.onNokeDisconnected(noke);
                    return;
                default:
                    Log.w("ION-2", "⚠️ Unknown ACL status: " + status);
            }
        }


        public void readCommandId(BluetoothGatt bluetoothGatt, @NonNull ResultCallback<byte[]> completion) {
            if (aclChar == null) {
                // Swift: handleFailure?(NokeDeviceSigningError.missingCommandIdCharacteritic)
                if (handleFailure != null) {
                    handleFailure.onFailure(NokeDeviceSigningError.MISSING_COMMAND_ID_CHARACTERITIC.asException());
                }
                return;
            }
            if (bluetoothGatt == null) {
                // No GATT available; fail fast through completion
                completion.onFailure(new IllegalStateException("BluetoothGatt is null"));
                return;
            }

            // Store completion to be called when the async read completes
            readCommandIdCompletion = completion;

            // Kick off the read (async). Result arrives in onCharacteristicRead
            boolean started = readCommandIdCharacteristic(currentNoke);
            if (!started) {
                // Fail immediately and clear the stored completion
                ResultCallback<byte[]> cb = readCommandIdCompletion;
                readCommandIdCompletion = null;

                if (handleFailure != null) {
                    handleFailure.onFailure(NokeDeviceSigningError.INVALID_COMMAND_ID.asException());
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {

            Log.d(TAG, "onCharacteristicRead -> UUID: " + characteristic.getUuid() + " status: " + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (SigningReadCharacteristicType.STATUS.getCharacteristicUuid().equals(characteristic.getUuid())) {
                    String statusText = new String(characteristic.getValue(), StandardCharsets.US_ASCII);
                    NokeDeviceSigningStatus statusValue = NokeDeviceSigningStatus.fromValue(statusText);
                    Log.d("ION-2", "STATUS = " + statusText);
                    
                    // Get NokeDevice from gatt connection (not currentNoke which can be stale)
                    NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
                    
                    if (noke != null && statusValue != null) {
                        handleAclStatus(noke, statusValue, gatt);
                    } else if (noke == null) {
                        Log.e("ION-2", "NokeDevice not found for address: " + gatt.getDevice().getAddress());
                    } else {
                        Log.e("ION-2", "Unknown status received: " + statusText + " (not in enum)");
                    }
                } else if (SigningReadCharacteristicType.COMMAND_ID.getCharacteristicUuid().equals(characteristic.getUuid())) {
                    if (readCommandIdCompletion != null) {
                        readCommandIdCompletion.onSuccess(characteristic.getValue());
                        readCommandIdCompletion = null;
                    }
                } else {
                    // Legacy flow for non-ION locks
                    NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
                    if (noke != null) {
                        noke.setSession(characteristic.getValue());
                        enableTXNotification(noke);
                    }
                }
            } else {
                Log.w(TAG, "onCharacteristicRead failed with status: " + status);
            }
            
            // Notify queue that this read operation completed
            bleOperationQueue.notifyOperationComplete();
        }


        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            Log.d(TAG, "onCharacteristicChanged -> " + bytesToHex(characteristic.getValue()));
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            byte[] data = characteristic.getValue();
            onReceivedDataFromLock(data, noke);
        }


        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                String address = gatt.getDevice().getAddress();
                String cachedName = deviceNameCache.get(address);
                NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());

                if (cachedName == null) {
                    return;
                }

                try {
                    Log.d(TAG, "onDescriptorWrite -> description -> " + descriptor.toString() + " -> Status -> " + status);
                    if (cachedName.contains("NOKE_FW") || cachedName.contains("NFOB_FW") || cachedName.contains("N3P_FW")) {
                        noke.connectionState = NokeDefines.NOKE_STATE_CONNECTED;
                        mGlobalNokeListener.onNokeConnected(noke);
                    } else {
                        noke.connectionState = NokeDefines.NOKE_STATE_CONNECTED;
                        mGlobalNokeListener.onNokeConnected(noke);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onDescriptorWrite -> Exception -> " + e.getMessage());
                }
                
                // Notify queue that descriptor write completed
                // This handles notification setup operations
                bleOperationQueue.notifyOperationComplete();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            
            Log.d(TAG, "onCharacteristicWrite -> UUID: " + characteristic.getUuid() + " status: " + status);
            
            // Notify queue that this write operation completed
            // Do NOT trigger readStatusCharacteristic here - handleAclStatus controls the flow
            bleOperationQueue.notifyOperationComplete();
        }
    };

    private byte[] lengthAscii(int length) {
        return String.format("%04X", length)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private final Map<String, Long> lastDisconnectTimeMap = new ConcurrentHashMap<>();
    private static final long DISCONNECT_COOLDOWN_MS = 8000; // 8 seconds cooldown

    /**
     * Tracks the timestamp of the last successful unlock per MAC address.
     * Used in onScanResult to suppress re-discovery (and re-unlock) of a device that was
     * just successfully unlocked. After an unlock, the BLE advertisement continues to be
     * received immediately after disconnect, which would trigger a new connect→LOCK_ALREADY_UNLOCKED
     * loop. Suppressing onNokeDiscovered for POST_UNLOCK_SCAN_SUPPRESSION_MS breaks this loop.
     */
    private final Map<String, Long> lastUnlockTimeMap = new ConcurrentHashMap<>();
    private static final long POST_UNLOCK_SCAN_SUPPRESSION_MS = 4000; // 4 seconds post-unlock cooldown

    /**
     * Record the timestamp of a successful unlock for the given MAC.
     * Called from handleAclStatus on UNLOCK_EXECUTED and LOCK_ALREADY_UNLOCKED.
     */
    private void recordUnlockTime(String mac) {
        lastUnlockTimeMap.put(mac, System.currentTimeMillis());
        Log.d(TAG, "[COOLDOWN] Recorded unlock time for " + mac + " (suppressing re-discovery for " + POST_UNLOCK_SCAN_SUPPRESSION_MS + "ms)");
    }

    /**
     * Returns true if the device should be suppressed from re-discovery
     * because it was recently successfully unlocked.
     */
    private boolean isInPostUnlockCooldown(String mac) {
        Long lastUnlock = lastUnlockTimeMap.get(mac);
        if (lastUnlock == null) return false;
        long elapsed = System.currentTimeMillis() - lastUnlock;
        if (elapsed < POST_UNLOCK_SCAN_SUPPRESSION_MS) {
            return true;
        }
        // Cooldown expired — clean up
        lastUnlockTimeMap.remove(mac);
        return false;
    }

    /**
     * Tracks device MACs for which gatt.discoverServices() has been called in the current
     * connection session.  Cleared on connectToGatt() and on disconnect so a fresh connection
     * always starts discovery cleanly.  The synchronized set makes it safe to call from both
     * the BLE callback thread (onMtuChanged) and the main thread (MTU timeout).
     */
    private final Set<String> discoveringMacs = Collections.synchronizedSet(new HashSet<>());

    /**
     * Tracks device MACs whose onServicesDiscovered has already been fully processed in the
     * current connection session.  Defense-in-depth against the known Android BLE bug where
     * the stack fires onServicesDiscovered more than once per discoverServices() call, even
     * from a different BLE callback thread than the first invocation.
     * Set.add() on a synchronizedSet provides the atomic "first-caller-wins" semantic AND a
     * happens-before memory barrier, making it reliable even without volatile on connectionState
     * (though connectionState is also now volatile for belt-and-suspenders correctness).
     */
    private final Set<String> servicesHandledMacs = Collections.synchronizedSet(new HashSet<>());

    public void safeDisconnectNoke(NokeDevice noke) {
        long now = System.currentTimeMillis();
        Long lastTime = lastDisconnectTimeMap.get(noke.getMac());
        if (lastTime == null || (now - lastTime) > DISCONNECT_COOLDOWN_MS) {
            lastDisconnectTimeMap.put(noke.getMac(), now);
            disconnectNoke(noke);
        }
    }

    /**
     * Parses through the data received from the lock after a command has been sent.  There are two different types of data packets:
     * <ul>
     * <li>Server Packets - encrypted packets sent to the server to be parsed</li>
     * <li>App Packets - unencrypted packets that can be used by the app to handle errors</li>
     * </ul>
     *
     * @param data The data from the lock. A 40 character hex string
     * @param noke The noke device that sent the data
     */
    public void onReceivedDataFromLock(byte[] data, NokeDevice noke) {
        Log.e(TAG, "onReceivedDataFromLock");
        byte destination = data[0];
        if (destination == NokeDefines.SERVER_Dest) {
            if (noke.session != null) {
                addDataPacketToQueue(bytesToHex(data), noke.session, noke.getMac());
            }
        } else if (destination == NokeDefines.APP_Dest) {
            byte resulttype = data[1];
            Log.e(TAG, "onReceivedDataFromLock -> resulttype -> " + resulttype);
            switch (resulttype) {
                case NokeDefines.SUCCESS_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> SUCCESS_ResultType");
                    byte datatype = data[4];
                    if (datatype == NokeDefines.DIAGNOSTIC_PacketType) {
                        mGlobalNokeListener.nokeDeviceDidSendDiagnostics(parseDiagnosticPacket(data), noke);
                    } else {
                        int commandid = data[2];
                        if (noke.isRestoring) {
                            noke.commands.clear();
                            globalUploadQueue.clear();
                            noke.isRestoring = false;
                            confirmRestore(noke.getMac(), commandid);
                            disconnectNoke(noke);
                        } else {
                            mGlobalNokeListener.successPacketReceived(noke);
                            moveToNext(noke);
                            if (noke.commands.size() == 0) {
                                if (noke.getHardwareVersion().equals("4E")) {
                                    Log.d(TAG, "onReceivedDataFromLock -> lockState = " + noke.lockState);
                                    resetJammedPopups();
                                }
                                noke.connectionState = NokeDefines.NOKE_STATE_UNLOCKED;
                                mGlobalNokeListener.onNokeUnlocked(noke);
                            }
                        }
                    }
                    break;
                }
                case NokeDefines.INVALIDKEY_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> INVALIDKEY_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_KEY, "Invalid Key Result");

//                    if (noke.commands.size() == 0) {
                    //If library receives an invalid key error, it will attempt to restore the key by working with the API
//                        if(!noke.isRestoring) {
//                            restoreDevice(noke);
//                        }
//                    }
                    break;
                }
                case NokeDefines.INVALIDCMD_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> INVALIDCMD_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_CMD, "Invalid Command Result");
                    break;
                }
                case NokeDefines.INVALIDPERMISSION_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> INVALIDPERMISSION_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_PERMISSION, "Invalid Permission (wrong key) Result");
                    break;
                }
                case NokeDefines.SHUTDOWN_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> SHUTDOWN_ResultType");
                    moveToNext(noke);
                    byte lockstate = data[2];
                    Boolean isLocked = true;
                    if (lockstate == (byte) 0) {
                        noke.lockState = NokeDefines.NOKE_LOCK_STATE_UNLOCKED;
                        isLocked = false;
                    } else {
                        noke.lockState = NokeDefines.NOKE_LOCK_STATE_LOCKED;
                    }

                    byte timeoutstate = data[3];
                    Boolean didTimeout = true;
                    if (timeoutstate == (byte) 1) {
                        didTimeout = false;
                    }

                    mGlobalNokeListener.onNokeShutdown(noke, isLocked, didTimeout);
                    disconnectNoke(noke);
                    break;
                }
                case NokeDefines.INVALIDDATA_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> INVALIDDATA_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_DATA, "Invalid Data Result");
                    break;
                }
                case NokeDefines.FREEEXIT_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> FREEEXIT_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_FREE_EXIT_UNLOCK, "Free Exit Error Result");
                    break;
                }
                case NokeDefines.INVALID_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> INVALID_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_RESULT, "Invalid Result");
                    break;
                }
                case NokeDefines.OUTOFSCHEDULEUNLOCK_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> OUTOFSCHEDULEUNLOCK_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_OUT_OF_SCHEDULE_UNLOCK, "Out Of Schedule Unlock");
                    break;
                }
                case NokeDefines.FAILEDTOLOCK_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> FAILEDTOLOCK_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_FAILED_TO_LOCK, "Device Failed to Lock");
                    break;
                }
                case NokeDefines.FAILEDTOUNLOCK_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> FAILEDTOUNLOCK_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_RESULT, "Device Failed to Unlock");
                    break;
                }
                case NokeDefines.FAILEDTOUNSHACKLE_ResultType: {
                    Log.e(TAG, "onReceivedDataFromLock -> FAILEDTOUNSHACKLE_ResultType");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_RESULT, "Device Failed to Unlock Shackle");
                    break;
                }
                default: {
                    Log.e(TAG, "onReceivedDataFromLock -> default");
                    moveToNext(noke);
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_UNKNOWN, "Invalid packet received");
                    break;
                }
            }

        }
    }

    public void resetJammedPopups() {
        callJammedLockingDelegate = true;
        callJammedUnlockingDelegate = true;
    }

    public JSONObject parseDiagnosticPacket(byte[] data) {
        JSONObject diagnostics = new JSONObject();
        byte lockStateByte = data[5];
        String lockState = "locked";
        switch (lockStateByte) {
            case NokeDefines.LockStateUnlocked:
                Log.d(TAG, "parseDiagnosticPacket -> LockStateUnlocked (unlocked)");
                lockState = "unlocked";
                break;
            case NokeDefines.LockStateLocked:
                Log.d(TAG, "parseDiagnosticPacket -> LockStateLocked (locked)");
                break;
            case NokeDefines.LockStateUnshackled:
                Log.d(TAG, "parseDiagnosticPacket -> LockStateUnshackled (unlocked)");
                lockState = "unlocked";
                break;
            case NokeDefines.LockStateJammedWhileUnlocking:
                Log.d(TAG, "parseDiagnosticPacket -> LockStateJammedWhileUnlocking (unlocked)");
                lockState = "unlocked";
                break;
            case NokeDefines.LockStateJammedWhileLocking:
                Log.d(TAG, "parseDiagnosticPacket -> LockStateJammedWhileLocking (unlocked)");
                lockState = "unlocked";
                break;
            default:
                Log.d(TAG, "parseDiagnosticPacket -> default (unknown)");
                lockState = "unknown";
                break;
        }

        byte ledStateByte = data[6];
        String ledState;
        switch (ledStateByte) {
            case NokeDefines.OffLED:
                ledState = "off";
                break;
            case NokeDefines.RedLED:
                ledState = "red";
                break;
            case NokeDefines.GreenLED:
                ledState = "green";
                break;
            default:
                ledState = "unknown";
        }

        byte touchSensorByte = data[7];
        String touchSensorState;
        switch (touchSensorByte) {
            case NokeDefines.Touched:
                touchSensorState = "touched";
                break;
            case NokeDefines.NotTouched:
                touchSensorState = "notTouched";
                break;
            default:
                touchSensorState = "unknown";
        }

        Integer temperature = Integer.parseInt(bytesToHex(new byte[]{data[8]}), 16);
        Integer batteryVoltage = Integer.parseInt(bytesToHex(new byte[]{data[10], data[9]}), 16);
        Integer wiredVoltage = Integer.parseInt(bytesToHex(new byte[]{data[12], data[11]}), 16);
        Integer interiorMotion = Integer.parseInt(bytesToHex(new byte[]{data[13]}), 16);
        Integer exteriorMotion = Integer.parseInt(bytesToHex(new byte[]{data[14]}), 16);
        Integer batteryChargingControl = Integer.parseInt(bytesToHex(new byte[]{data[15]}), 16);
        Integer batteryChargingStatus = Integer.parseInt(bytesToHex(new byte[]{data[16]}), 16);


        try {
            diagnostics.accumulate("lockState", lockState);
            diagnostics.accumulate("ledState", ledState);
            diagnostics.accumulate("touchSensorState", touchSensorState);
            diagnostics.accumulate("temperature", temperature);
            diagnostics.accumulate("batteryVoltage", batteryVoltage);
            diagnostics.accumulate("wiredVoltage", wiredVoltage);
            diagnostics.accumulate("interiorMotion", interiorMotion);
            diagnostics.accumulate("exteriorMotion", exteriorMotion);
            diagnostics.accumulate("batteryChargingControl", batteryChargingControl);
            diagnostics.accumulate("batteryChargingStatus", batteryChargingStatus);
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage());
        }

        Log.w(TAG, "!!!!!!!!!!GOT DIAGNOSTIC DATA!!!!!!!!!!: " + diagnostics.toString());
        return diagnostics;

    }

    /**
     * Moves through the noke command array to the next command
     *
     * @param noke the noke device that contains the commands
     */
    public void moveToNext(NokeDevice noke) {
        if (noke.commands.size() > 0) {
            noke.commands.remove(0);
            if (noke.commands.size() > 0) {
                writeRXCharacteristic(noke);
            }
        }
    }

    /**
     * Takes Server Packets from the lock and bundles them with the MAC address and session of the lock to be sent to the Noke API for parsing
     *
     * @param response the response from the lock. A 40 char hex string
     * @param session  the session of the lock read upon connecting
     * @param mac      the MAC address of the lock
     */
    public void addDataPacketToQueue(String response, String session, String mac) {
        long unixTime = System.currentTimeMillis() / 1000L;
        if (globalUploadQueue == null) {
            globalUploadQueue = new ArrayList<>();
        }
        for (int i = 0; i < globalUploadQueue.size(); i++) {
            JSONObject dataObject = globalUploadQueue.get(i);
            try {
                String dataSession = dataObject.getString("session");
                if (session.equals(dataSession)) {
                    JSONArray responses = dataObject.getJSONArray("responses");
                    responses.put(response);
                    //TODO: CACHE UPLOAD QUEUE
                    return;
                }
            } catch (JSONException e) {
                Log.e(TAG, "Exception", e);
            }
        }

        try {
            JSONArray responses = new JSONArray();
            responses.put(response);
            JSONObject sessionPacket = new JSONObject();
            sessionPacket.accumulate("session", session);
            sessionPacket.accumulate("responses", responses);
            sessionPacket.accumulate("mac", mac);
            sessionPacket.accumulate("received_time", unixTime);

            globalUploadQueue.add(sessionPacket);

            //TODO: CACHE UPLOAD QUEUE
        } catch (JSONException e) {
            Log.e(TAG, "Exception", e);
        }
    }

    /**
     * Uploads server packets from the Noke device to the server for parsing via the Noke Go Library
     */
    public void uploadData() {
        if (globalUploadQueue != null) {
            if (globalUploadQueue.size() > 0) {
                try {
                    JSONObject jsonObject = new JSONObject();
                    JSONArray data = new JSONArray();
                    for (int i = 0; i < globalUploadQueue.size(); i++) {
                        data.put(globalUploadQueue.get(i));
                    }
                    if (NokeDefines.uploadURL.equals("")) {
                        mGlobalNokeListener.shouldUploadData(data);
                    } else {
                        jsonObject.accumulate("logs", data);
                        try {
                            PackageManager pm = getApplicationContext().getPackageManager();
                            ApplicationInfo ai = pm.getApplicationInfo(getApplicationContext().getPackageName(), PackageManager.GET_META_DATA);
                            Bundle bundle = ai.metaData;
                            String nokeMobileApiKey = bundle.getString(NokeDefines.NOKE_MOBILE_API_KEY);
                            this.uploadDataCallback(NokeMobileApiClient.POST(NokeDefines.uploadURL, jsonObject.toString(), nokeMobileApiKey));
                        } catch (PackageManager.NameNotFoundException |
                                 NullPointerException e) {
                            Log.e(TAG, "Exception", e);
                            mGlobalNokeListener.onError(null, NokeMobileError.ERROR_MISSING_API_KEY, "No API Key found. Have you set it in your Android Manifest?");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception", e);
                }
            }
        }
    }

    /**
     * Caches the upload data from the lock in the case that an internet connection isn't present
     *
     * @param context application context used for getting shared preferences
     */
    @SuppressWarnings("unused")
    void cacheUploadData(Context context) {
        Set<String> data = new HashSet<>();
        for (int i = 0; i < globalUploadQueue.size(); i++) {
            String jsonData = globalUploadQueue.get(i).toString();
            data.add(jsonData);
        }

        context.getSharedPreferences(NokeDefines.PREFS_NAME, MODE_PRIVATE).edit()
                .putStringSet(NokeDefines.PREF_UPLOADDATA, data)
                .apply();
    }

    /**
     * Retrieves cached upload data that can be uploaded to the Noke API
     *
     * @param context application context used for getting shared preferences
     */
    @SuppressWarnings("unused")
    void retrieveUploadData(Context context) {
        SharedPreferences pref = context.getSharedPreferences(NokeDefines.PREFS_NAME, MODE_PRIVATE);
        Set<String> data = pref.getStringSet(NokeDefines.PREF_UPLOADDATA, null);
        if (globalUploadQueue == null) {
            globalUploadQueue = new ArrayList<>();
        }

        if (data != null) {
            for (String entry : data) {
                JSONObject dataEntry = null;
                try {
                    dataEntry = new JSONObject(entry);
                } catch (JSONException e) {
                    Log.e(TAG, "retrieveUploadData -> Exception", e);
                }
                globalUploadQueue.add(dataEntry);
            }
        }
    }

    /**
     * Caches the Noke devices for offline use
     *
     * @param context application context used for getting shared preferences
     */
    @SuppressWarnings("unused")
    void cacheNokeDevices(Context context) {
        Set<String> setNokeDevices = new HashSet<>();
        for (Map.Entry<String, NokeDevice> entry : this.nokeDevices.entrySet()) {
            Gson gson = new Gson();
            String jsonNoke = gson.toJson(entry.getValue());
            setNokeDevices.add(jsonNoke);
        }

        context.getSharedPreferences(NokeDefines.PREFS_NAME, MODE_PRIVATE).edit()
                .putStringSet(NokeDefines.PREF_DEVICES, setNokeDevices)
                .apply();

    }

    /**
     * Retrieves cached Noke devices for offline use
     *
     * @param context application context used for getting shared preferences
     */
    @SuppressWarnings("unused")
    void retrieveNokeDevices(Context context) {
        SharedPreferences pref = context.getSharedPreferences(NokeDefines.PREFS_NAME, MODE_PRIVATE);
        final Set<String> locks = pref.getStringSet(NokeDefines.PREF_DEVICES, null);

        if (locks != null) {
            try {
                for (String entry : locks) {
                    Gson gson = new Gson();
                    NokeDevice noke = gson.fromJson(entry, NokeDevice.class);
                    nokeDevices.put(noke.getMac(), noke);
                }
            } catch (final Exception e) {
                Log.e(TAG, "retrieveNokeDevices -> Exception -> ", e);
            }
        }
    }


    /**
     * Reads the session characteristic on the Noke device.  When read this contains Lock State, Battery State,
     * and Session Key
     *
     * @param noke The device to read the session characteristic from.
     */
    private void readStateCharacteristic(NokeDevice noke) {
        if (mBluetoothAdapter == null || noke.gatt == null) {
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);

        if (noke.gatt == null) {
            Log.e(TAG, "readStateCharacteristic -> BAD DEVICE 2");
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
        }

        if (RxService == null) {
            Log.e(TAG, "readStateCharacteristic -> BAD DEVICE 3 GATT SERVICES ARE: " + noke.gatt.getServices());
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        BluetoothGattCharacteristic StateChar = RxService.getCharacteristic(NokeDefines.STATE_CHAR_UUID);
        if (StateChar == null) {
            Log.e(TAG, "readStateCharacteristic -> BAD DEVICE 4");
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        
        // Queue read operation to prevent concurrent BLE operations
        bleOperationQueue.enqueue(() -> {
            boolean success = noke.gatt.readCharacteristic(StateChar);
            if (!success) {
                Log.e(TAG, "readStateCharacteristic failed");
                bleOperationQueue.notifyOperationComplete();
            }
        });
    }

    /**
     * Enable Notification on TX characteristic
     *
     * @param noke Noke device
     */

    private void enableTXNotification(NokeDevice noke) {

        if (noke.gatt == null) {
            Log.e(TAG, "enableTXNotification -> BAD DEVICE 5");
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);
        if (RxService == null) {
            Log.e(TAG, "enableTXNotification -> BAD DEVICE 6");
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        BluetoothGattCharacteristic TxChar = RxService.getCharacteristic(NokeDefines.TX_CHAR_UUID);
        if (TxChar == null) {
            Log.e(TAG, "enableTXNotification -> BAD DEVICE 7");
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        noke.gatt.setCharacteristicNotification(TxChar, true);

        BluetoothGattDescriptor descriptor = TxChar.getDescriptor(NokeDefines.CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        
        // Queue descriptor write to prevent concurrent BLE operations
        bleOperationQueue.enqueue(() -> {
            boolean success = noke.gatt.writeDescriptor(descriptor);
            if (!success) {
                Log.e(TAG, "writeDescriptor failed for TX notification");
                bleOperationQueue.notifyOperationComplete();
            }
        });
    }


    private void enableFirmwareTXNotification(NokeDevice noke) {
        //TODO Add support for other hardware versions
        if (noke.gatt == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.FIRMWARE_RX_SERVICE_UUID);
        if (RxService == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        BluetoothGattCharacteristic TxChar = RxService.getCharacteristic(NokeDefines.FIRMWARE_TX_CHAR_UUID);
        if (TxChar == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        noke.gatt.setCharacteristicNotification(TxChar, true);
        BluetoothGattDescriptor descriptor = TxChar.getDescriptor(NokeDefines.CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        
        // Queue descriptor write to prevent concurrent BLE operations
        bleOperationQueue.enqueue(() -> {
            boolean success = noke.gatt.writeDescriptor(descriptor);
            if (!success) {
                Log.e(TAG, "writeDescriptor failed for firmware TX notification");
                bleOperationQueue.notifyOperationComplete();
            }
        });
    }


    /**
     * Write RX characteristic on Noke device.
     *
     * @param noke Noke device
     */

    void writeRXCharacteristic(final NokeDevice noke) {

        try {
            if (noke.gatt == null) {
                return;
            }

            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {

                    if (noke.gatt != null) {
                        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);

                        if (RxService == null) {
                            Log.e(TAG, "writeRXCharacteristic -> BAD DEVICE 11");
                            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
                            return;
                        }
                        BluetoothGattCharacteristic RxChar = RxService.getCharacteristic(NokeDefines.RX_CHAR_UUID);
                        if (RxChar == null) {
                            Log.e(TAG, "writeRXCharacteristic -> BAD DEVICE 12");
                            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
                            return;
                        }

                        try {
                            RxChar.setValue(NokeDefines.hexToBytes(noke.commands.get(0)));
                            
                            // Queue write operation to prevent concurrent BLE operations
                            bleOperationQueue.enqueue(() -> {
                                boolean status = noke.gatt.writeCharacteristic(RxChar);
                                Log.d(TAG, "writeRXCharacteristic -> write TXchar - status =" + status);
                                if (!status) {
                                    bleOperationQueue.notifyOperationComplete();
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "writeRXCharacteristic -> Exception1 -> ", e);
                        }
                    }
                }
            });


        } catch (NullPointerException e) {
            Log.e(TAG, "BAD DEVICE 13");
            Log.e(TAG, "writeRXCharacteristic -> Exception2 -> ", e);
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
        }
    }

    void writeSigningCharacteristic(final NokeDevice noke, final SigningWriteCharacteristicType type, byte[] bytes) {
        try {
            // Optional: you can skip this if execute() checks gatt null itself
            if (noke.gatt == null) {
                return;
            }

            GattRequest.ErrorReporter reporter = (device, code, message) ->
                    mGlobalNokeListener.onError(device, code, message);

            GattRequest request = getGattRequestWrite(noke, type, bytes);

            request.execute();
            
            // ⚠️ DO NOT read status here! 
            // The write is asynchronous - status will be read in onCharacteristicWrite callback
            // after the write completes. Reading immediately causes "Connection 104" error
            // due to concurrent BLE operations.

        } catch (NullPointerException e) {
            Log.e(TAG, "BAD DEVICE 13");
            Log.e(TAG, "writeSigningCharacteristic -> Exception2 -> ", e);
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
        }
    }

    void writeTimeCharacteristic(final NokeDevice noke, byte[] bytes) {
        writeSigningCharacteristic(noke, SigningWriteCharacteristicType.TIME, bytes);
        // Queue status read to execute after TIME write completes
        readStatusCharacteristic(noke);
    }

    void writeAclCharacteristic(final NokeDevice noke, byte[] bytes) {
        writeSigningCharacteristic(noke, SigningWriteCharacteristicType.ACL, bytes);
        // Queue status read to execute after ACL write completes
        readStatusCharacteristic(noke);
    }

    void writeAclSignatureCharacteristic(final NokeDevice noke, byte[] bytes) {
        writeSigningCharacteristic(noke, SigningWriteCharacteristicType.ACL_SIGNATURE, bytes);
    }

    void writeCommandCharacteristic(final NokeDevice noke, byte[] bytes) {
        writeSigningCharacteristic(noke, SigningWriteCharacteristicType.COMMAND, bytes);
    }

    void writeCommandSignatureCharacteristic(final NokeDevice noke, byte[] bytes) {
        writeSigningCharacteristic(noke, SigningWriteCharacteristicType.COMMAND_SIGNATURE, bytes);
    }

    void readSigningCharacteristic(final NokeDevice noke, final SigningReadCharacteristicType type) {
        try {
            // Optional: you can skip this if execute() checks gatt null itself
            if (noke.gatt == null) {
                return;
            }

            GattRequest.ErrorReporter reporter = (device, code, message) ->
                    mGlobalNokeListener.onError(device, code, message);

            GattRequest request = getGattRequestRead(noke, type.getCharacteristicUuid(), type.getTag(),
                    (device, value) -> {
                        // handle read bytes (never null; can be empty)
                        // e.g., kick off the next step in your flow
                        Log.d(TAG, "readSigningCharacteristic -> value = " + bytesToHex(value));
                    }
            );

            request.execute();

        } catch (NullPointerException e) {
            Log.e(TAG, "BAD DEVICE 13");
            Log.e(TAG, "readSigningCharacteristic -> Exception2 -> ", e);
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
        }
    }

    void readStatusCharacteristic(final NokeDevice noke) {
        readSigningCharacteristic(noke, SigningReadCharacteristicType.STATUS);
    }

    boolean readCommandIdCharacteristic(final NokeDevice noke) {
        readSigningCharacteristic(noke, SigningReadCharacteristicType.COMMAND_ID);
        return true;
    }

// ...

    /** WRITE: same signature as before, but returns the unified GattRequest */
    @NonNull
    private GattRequest getGattRequestWrite(@NonNull NokeDevice noke,
                                            @NonNull SigningWriteCharacteristicType type,
                                            @NonNull byte[] bytes) {
        GattRequest.ErrorReporter reporter = (device, code, message) ->
                mGlobalNokeListener.onError(device, code, message);

        return GattRequest.write(
                getApplicationContext(),
                noke,
                type,
                () -> bytes,        // BytesProvider
                reporter,
                bleOperationQueue   // Pass the queue for serialization
        );
    }

    /** READ: new helper that targets a specific characteristic UUID */
    @NonNull
    private GattRequest getGattRequestRead(@NonNull NokeDevice noke,
                                           @NonNull UUID characteristicUuid,
                                           @NonNull String tag,
                                           @NonNull GattRequest.ReadCallback onRead) {
        GattRequest.ErrorReporter reporter = (device, code, message) ->
                mGlobalNokeListener.onError(device, code, message);

        return GattRequest.read(
                getApplicationContext(),
                noke,
                characteristicUuid,
                tag,
                onRead,              // ReadCallback
                reporter,
                bleOperationQueue    // Pass the queue for serialization
        );
    }

    /**
     * Disconnect from a Noke device immediately.
     * Calls gatt.disconnect() + gatt.close() synchronously on the main thread, fires
     * onNokeDisconnected immediately, and restarts BLE scanning.
     * The STATE_DISCONNECTED GATT callback may still arrive asynchronously; the
     * connectionState == NOKE_STATE_DISCONNECTED guard in onConnectionStateChange prevents
     * a duplicate onNokeDisconnected notification in that case.
     */
    public void disconnectNoke(final NokeDevice noke) {
        Log.d(TAG, "disconnectNoke");
        
        if (mBluetoothAdapter == null || noke.gatt == null) {
            Log.w(TAG, "[DISCONNECT] disconnectNoke: early exit - adapter=" + (mBluetoothAdapter != null ? "non-null" : "null") + ", gatt=" + (noke.gatt != null ? "non-null" : "null"));
            return;
        }

        // Clear BLE operation queue immediately to prevent further operations
        bleOperationQueue.clear();

        disconnectHandler.post(() -> {
            if (noke.gatt != null) {
                // Mirrors the force-close behaviour observed when Android kills the process:
                // call disconnect() to signal the BLE peer, then close() immediately to release
                // GATT resources rather than waiting for STATE_DISCONNECTED callback.
                // This is more reliable than the two-phase approach for degraded connections.
                BluetoothGatt gattToClose = noke.gatt;
                noke.gatt = null;
                discoveringMacs.remove(noke.getMac());
                servicesHandledMacs.remove(noke.getMac());
                try {
                    gattToClose.disconnect();
                    gattToClose.close();
                    Log.d(TAG, "[DISCONNECT] gatt.disconnect() + gatt.close() called immediately for " + noke.getMac());
                } catch (Exception e) {
                    Log.e(TAG, "[DISCONNECT] Exception during immediate close for " + noke.getMac() + ": " + e.getMessage());
                }
                bleOperationQueue.clear();
                if (noke.connectionState != NokeDefines.NOKE_STATE_DISCONNECTED) {
                    Log.d(TAG, "[DISCONNECT] Firing onNokeDisconnected immediately for " + noke.getMac());
                    noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                    mGlobalNokeListener.onNokeDisconnected(noke);
                }
                startLeScanning();
            }
        });
    }

    /**
     * Checks to see if the service is running in the background
     *
     * @return boolean true if running in foreground, false if running in background
     */
    private boolean isServiceRunningInForeground() {

        ActivityManager.RunningAppProcessInfo myProcess = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(myProcess);
        return myProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

    }

    /**
     * Broadcast receiver for receiving information about state of bluetooth adapter
     */
    private final BroadcastReceiver bluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action != null) {
                if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    switch (state) {
                        case BluetoothAdapter.STATE_OFF:
                            mScanning = false;
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            break;
                        case BluetoothAdapter.STATE_ON:
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            break;
                    }
                    mGlobalNokeListener.onBluetoothStatusChanged(state);
                }
            }
        }
    };

    /**
     * Sets the URL used for uploading data
     *
     * @param uploadUrl string of the url
     */
    @SuppressWarnings({"unused", "SameParameterValue"})
    private void setUploadUrl(String uploadUrl) {
        NokeDefines.uploadURL = uploadUrl;
    }

    private void uploadDataCallback(String s) {
        try {
            JSONObject obj = new JSONObject(s);
            int errorCode = obj.getInt("error_code");
            String message = obj.getString("message");

            if (errorCode == NokeMobileError.SUCCESS) {
                this.globalUploadQueue.clear();
            }
            this.getNokeListener().onDataUploaded(errorCode, message);
        } catch (JSONException e) {
            this.getNokeListener().onDataUploaded(NokeMobileError.ERROR_JSON_UPLOAD, e.toString());
        }
    }

    private void restoreDevice(NokeDevice noke) {
        noke.isRestoring = true;
        restoreKey(noke);
    }

    private void restoreKey(final NokeDevice noke) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.accumulate("session", noke.getSession());
                    jsonObject.accumulate("mac", noke.getMac());
                    String url = NokeDefines.uploadURL.replace("upload/", "restore/");
                    try {
                        PackageManager pm = getApplicationContext().getPackageManager();
                        ApplicationInfo ai = pm.getApplicationInfo(getApplicationContext().getPackageName(), PackageManager.GET_META_DATA);
                        Bundle bundle = ai.metaData;
                        String nokeMobileApiKey = bundle.getString(NokeDefines.NOKE_MOBILE_API_KEY);
                        NokeDeviceManagerService.this.restoreKeyCallback(NokeMobileApiClient.POST(url, jsonObject.toString(), nokeMobileApiKey), noke);
                    } catch (PackageManager.NameNotFoundException | NullPointerException e) {
                        Log.e(TAG, "restoreKey -> Exception", e);
                        mGlobalNokeListener.onError(null, NokeMobileError.ERROR_MISSING_API_KEY, "No API Key found. Have you set it in your Android Manifest?");
                        noke.isRestoring = false;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "restoreKey -> Exception", e);
                    noke.isRestoring = false;
                }
            }
        });

        thread.start();


    }

    private void restoreKeyCallback(String response, NokeDevice noke) {
        try {
            JSONObject obj = new JSONObject(response);
            String result = obj.getString("result");
            if (result.equals("success")) {
                JSONObject data = obj.getJSONObject("data");
                String commandString = data.getString("commands");
                noke.sendCommands(commandString);
            } else {
                noke.isRestoring = false;
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            noke.isRestoring = false;
        }
    }


    private void confirmRestore(final String mac, final int commandid) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.accumulate("mac", mac);
                    jsonObject.accumulate("command_id", commandid);
                    String url = NokeDefines.uploadURL.replace("upload/", "restore/confirm/");
                    try {
                        PackageManager pm = getApplicationContext().getPackageManager();
                        ApplicationInfo ai = pm.getApplicationInfo(getApplicationContext().getPackageName(), PackageManager.GET_META_DATA);
                        Bundle bundle = ai.metaData;
                        String nokeMobileApiKey = bundle.getString(NokeDefines.NOKE_MOBILE_API_KEY);
                        NokeDeviceManagerService.this.confirmRestoreCallback(NokeMobileApiClient.POST(url, jsonObject.toString(), nokeMobileApiKey));
                    } catch (PackageManager.NameNotFoundException | NullPointerException e) {
                        Log.e(TAG, "confirmRestore -> Exception", e);
                        mGlobalNokeListener.onError(null, NokeMobileError.ERROR_MISSING_API_KEY, "No API Key found. Have you set it in your Android Manifest?");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "confirmRestore -> Exception", e);
                }
            }
        });

        thread.start();
    }

    private void confirmRestoreCallback(String s) {
        try {
            JSONObject obj = new JSONObject(s);
            int errorCode = obj.getInt("error_code");
            String message = obj.getString("message");

            if (errorCode == NokeMobileError.SUCCESS) {
                this.getNokeListener().onDataUploaded(errorCode, "Restore Successful: " + message);
            }

        } catch (JSONException e) {
            this.getNokeListener().onDataUploaded(NokeMobileError.ERROR_JSON_UPLOAD, e.toString());
        }
    }

    public boolean areBluetoothPermissionsGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        boolean scanPermissionEnabled = ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        boolean connectPermissionEnabled = ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return scanPermissionEnabled && connectPermissionEnabled;
    }

    public void enableBluetooth(Context activityContext) {
        if (BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            return;
        }
        if (!areBluetoothPermissionsGranted()) {
            mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_SCAN_PERMISSION, "Bluetooth scan permission needed");
            return;
        }
        Intent enableBluetoothIntent = new Intent();
        enableBluetoothIntent.setAction(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        activityContext.startActivity(enableBluetoothIntent);
    }

    private void invalidateConnectionTimer() {
        currentNoke = null;
        if (connectionTimer != null) {
            connectionTimer.removeCallbacksAndMessages(null);
        }
    }

    private void initializeConnectionTimer() {
        connectionTimer = new Handler(Looper.getMainLooper());
        connectionTimer.postDelayed(connectionTimerRunnable(), numberOfSecondsToDetectTheConnectionError * 1000);
    }

    private Runnable connectionTimerRunnable() {
        return new Runnable() {
            @Override
            public void run() {
                mGlobalNokeListener.onError(currentNoke, ERROR_CONNECTION_TIMEOUT, "Connection error");
                disconnectNoke(currentNoke);
            }
        };
    }

    private boolean isBanned(String mac) {
        Long bannedTime = banned.get(mac);
        if (bannedTime == null) {
            return false;
        }

        if (System.currentTimeMillis() - bannedTime > BAN_DURATION_LIMIT) {
            banned.remove(mac);
            return false;
        }

        return true;
    }


    private List<ScanFilter> scanFilters() {
        boolean isClient = new PermissionHelper(getApplicationContext()).isClient();
        List<ScanFilter> filters = new ArrayList<>();

        if (!isClient) return filters;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return filters;
        if (nokeDevices == null || nokeDevices.isEmpty()) return filters;

        for (String macAddress : nokeDevices.keySet()) {
            if (isBanned(macAddress)) {
                continue;
            }
            if (!mAllowAllDevices) {
                if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
                    ScanFilter filter = new ScanFilter.Builder()
                            .setDeviceAddress(macAddress)
                            .build();
                    filters.add(filter);
                }
            } else {
                if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
                    ScanFilter filter = new ScanFilter.Builder()
                            .build();
                    filters.add(filter);
                }
            }
        }

        return filters;
    }


}
