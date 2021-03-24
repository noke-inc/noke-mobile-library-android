package com.noke.nokemobilelibrary;

import android.Manifest;
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
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.noke.nokemobilelibrary.NokeMobileError.DEVICE_SHUTDOWN_RESULT;
import static com.noke.nokemobilelibrary.NokeMobileError.ERROR_CONNECTION_TIMEOUT;


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
public class NokeDeviceManagerService extends Service {

    private final static String TAG = NokeDeviceManagerService.class.getSimpleName();

    /**
     * High level manager used to interact with Location services
     */
    private LocationManager mLocationManager;
    /**
     * Location listener used to detect when the location service is enabled or disabled
     */
    private LocationListener mLocationListener;

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
     *property used to detect if a connection fails when a device that is not available tries to create a connection
     */
    public Handler connectionTimer;
    /**
     *number of seconds the connection process will wait until return an error connection
     */
    public long numberOfSecondsToDetectTheConnectionError = 6;
    /**
     * This propeerty is filled when the connection starts
     */
    private NokeDevice currentNoke;

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
    /**
     * Address for proxy used for making upload requests to the mobile API (optional)
     */
    private String proxyAddress = "";
    /**
     * Port for proxy used for making upload requests to the mobile API
     */
    private int port = 0;

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
                default:
                    Log.e(TAG, "Unknown Mode Type. Setting URL to Sandbox");
                    setUploadUrl(NokeDefines.sandboxUploadURL);
                    break;
            }

            return NokeDeviceManagerService.this;
        }

        /**
         * Returns reference to the NokeDeviceManagerService
         *
         * @param mode must be set upon initialization. Determines the upload url used for uploading
         *             responses from the lock to the Core API.  Mode types can be found in NokeDefines
         *             file:
         *             - Sandbox (NOKE_LIBRARY_SANDBOX)
         *             - Production (NOKE_LIBRARY_PRODUCTION)
         *             - Develop (NOKE_LIBRARY_DEVELOP)
         * @param mobileApiKey Mobile API key. If not set here it must instead be specified in
         *                     the Android manifest.
         */
        public NokeDeviceManagerService getService(int mode, String mobileApiKey) {
            NokeDeviceManagerService service = getService(mode);
            service.setMobileApiKey(mobileApiKey);
            return service;
        }
    }

    /**
     * Read more here: <a href="https://developer.android.com/reference/android/os/IBinder.html">https://developer.android.com/reference/android/os/IBinder.html</a>
     */
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private String mMobileApiKey;

    private String getMobileApiKey() {
        if (mMobileApiKey != null) {
            return mMobileApiKey;
        }

        try {
            PackageManager pm = getApplicationContext().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(getApplicationContext().getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;
            return bundle.getString(NokeDefines.NOKE_MOBILE_API_KEY);
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            e.printStackTrace();
            String message = "No API Key found. Have you set it in your Android Manifest or through setMobileApiKey()?";
            mGlobalNokeListener.onError(null, NokeMobileError.ERROR_MISSING_API_KEY, message);

            throw new IllegalStateException(message, e);
        }
    }

    private void setMobileApiKey(String mobileApiKey) {
        mMobileApiKey = mobileApiKey;
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
        if (mBluetoothAdapter != null && mGlobalNokeListener != null) {
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
            noke.mService = this;
            nokeDevices.put(noke.getMac(), noke);
        }
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
        initializeLocation();
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

    private boolean initializeLocation() {
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
            if (mLocationManager == null) {
                return false;
            }

            // Define a listener that responds to location updates
            mLocationListener = new LocationListener() {
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                public void onLocationChanged(Location location) {
                }

                public void onProviderEnabled(String provider) {
                    Boolean enabled = true;
                    mGlobalNokeListener.onLocationStatusChanged(enabled);
                }

                public void onProviderDisabled(String provider) {
                    Boolean enabled = false;
                    mGlobalNokeListener.onLocationStatusChanged(enabled);
                }
            };

            // Register the listener with the Location Manager to receive location updates
            // minTime:    minimum time interval between location updates (in milliseconds).
            // minDistance:    minimum distance between location updates (in meters).
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5, mLocationListener);
        }
        return true;
    }

    /**
     * Begins scanning for Noke devices that have been added to the device array
     */
    public void startScanningForNokeDevices() {
        try {
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
            if (!gps_enabled && !network_enabled) {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_LOCATION_SERVICES_DISABLED, "Location services are disabled");
            } else if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_LOCATION_PERMISSIONS_NEEDED, "Location services permission needed");
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
            mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_SCANNING, "Bluetooth scanning is not supported");
        }
    }

    public boolean isBluetoothEnabled() {
        boolean bluetoothEnabled = false;
        if (mBluetoothManager != null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        }
        if (mBluetoothManager != null && mBluetoothAdapter == null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
        }
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_DISABLED, "Bluetooth is disabled");
        } else {
            bluetoothEnabled = true;
        }
        return bluetoothEnabled;
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
        final Handler refreshScan = new Handler(Looper.getMainLooper());
        refreshScan.postDelayed(new Runnable() {
            @Override
            public void run() {
                turnOffBLEScan();
                if (isServiceRunningInForeground()) {
                    bluetoothDelay = bluetoothDelayDefault;
                } else {
                    bluetoothDelay = bluetoothDelayBackgroundDefault;
                }

            }
        }, bluetoothScanDuration);
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
    @SuppressWarnings("deprecation")
    private void startLeScanning() {
        if (!mScanning) {
            mScanning = true;
            if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= 100) {
                    //SCANNING WITH THE OLD APIS IS MORE RELIABLE. HENCE THE 100
                    initNewBluetoothCallback();
                    mBluetoothScanner = mBluetoothAdapter.getBluetoothLeScanner();
                    mBluetoothScanner.startScan(mNewBluetoothScanCallback);
                } else {
                    initOldBluetoothCallback();
                    mBluetoothAdapter.startLeScan(mOldBluetoothScanCallback);
                }
            } else {
                mGlobalNokeListener.onError(null, NokeMobileError.ERROR_BLUETOOTH_SCANNING, "Bluetooth scanning is not supported");
            }
        }
    }

    /**
     * Stops BLE scanning using the bluetooth adapter.
     */
    @SuppressWarnings("deprecation")
    private void stopLeScanning() {
        mScanning = false;
        if (mBluetoothAdapter != null) {
            if (mBluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= 100) {
                    mBluetoothScanner.stopScan(mNewBluetoothScanCallback);
                } else {
                    //DEPRECATED. INCLUDING FOR 4.0 SUPPORT
                    if (mOldBluetoothScanCallback != null) {
                        mBluetoothAdapter.stopLeScan(mOldBluetoothScanCallback);
                    }
                }
            }
        }
    }

    /**
     * Initializes Bluetooth Scanning Callback for Lollipop and higher OS
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void initNewBluetoothCallback() {
        mNewBluetoothScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);

//                noke.setLastSeen(new Date().getTime());
                //TODO NEW BLUETOOTH SCAN CALLBACK
            }
        };
    }

    /**
     * Sets mAllowDevices boolean
     */
    @SuppressWarnings("SameParameterValue")
    public void setAllowAllDevices(boolean allow) {
        mAllowAllDevices = allow;
    }


    /**
     * Initializes Bluetooth Scanning Callback for KitKat OS
     */
    private void initOldBluetoothCallback() {
        mOldBluetoothScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(final BluetoothDevice bluetoothDevice, final int rssi, byte[] scanRecord) {
                String btDeviceName = bluetoothDevice.getName();
                if (btDeviceName != null && btDeviceName.contains(NokeDefines.NOKE_DEVICE_IDENTIFER_STRING)) {
                    NokeDevice noke = nokeDevices.get(bluetoothDevice.getAddress());
                    if (noke != null || mAllowAllDevices) {
                        if (noke == null) {
                            noke = new NokeDevice(btDeviceName, bluetoothDevice.getAddress());
                        }
                        noke.bluetoothDevice = bluetoothDevice;
                        noke.setLastSeen(new Date().getTime());
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

                            if (noke.getHardwareVersion().contains(NokeDefines.NOKE_HW_TYPE_HD_LOCK)) {

                                //Log.d(TAG, "HD BROADCAST: " + NokeDefines.bytesToHex(broadcastData));
                                int lockStateBroadcast = (broadcastData[0] >> 5) & 0x01;
                                int lockStateBroadcast2 = (broadcastData[0] >> 6) & 0x01;
                                int lockStateBroadcast3 = (broadcastData[0] >> 7) & 0x01;
                                String lockstateString = "" + lockStateBroadcast3 + lockStateBroadcast2 + lockStateBroadcast;
                                lockState = Integer.parseInt(lockstateString,2);

                            }else if(noke.getHardwareVersion().equals(NokeDefines.NOKE_HW_TYPE_ULOCK)){
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
                            noke.rssi = rssi;
                            mGlobalNokeListener.onNokeDiscovered(noke);
                        }
                    }
                }
            }
        };
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
        invalidateConnectionTimer();
        initializeConnectionTimer();
        currentNoke = noke;
        connectToDevice(noke.bluetoothDevice, noke.rssi);
    }

    /**
     * Attempts to match MAC address to device in nokeDevices list.  If device is found, stop scanning and
     * call connectToGatt to start service discovery and connect to device.
     *
     * @param device Bluetooth device that was obtained from the scanner callback
     * @param rssi   RSSI value obtained from the scanner.  Can be used for adjusting or checking connecting range.
     */
    private void connectToDevice(BluetoothDevice device, int rssi) {
        if (device != null) {
            NokeDevice noke = nokeDevices.get(device.getAddress());
            if (noke != null) {
                noke.mService = this;
                noke.connectionAttempts = 0;
                noke.rssi = rssi;
                stopLeScanning();
                if (noke.bluetoothDevice == null) {
                    noke.bluetoothDevice = device;
                }

                if (noke.connectionState == NokeDefines.NOKE_STATE_DISCONNECTED || noke.connectionState == NokeDefines.NOKE_STATE_DISCOVERED) {
                    mBluetoothAdapter.cancelDiscovery();
                    noke.connectionState = NokeDefines.NOKE_STATE_CONNECTING;

                    Handler handler = new Handler(Looper.getMainLooper());
                    final NokeDevice finalNoke = noke;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finalNoke.gatt == null) {
                                Log.d(TAG, "Initializing gatt connection: " + connectToGatt(finalNoke));
                            } else {
                                /*Reusing GATT objects causes issues.  If the gatt object is not null when first
                                 * connecting to lock. Disconnect/null object and try reconnecting
                                 */
                                finalNoke.gatt.disconnect();
                                finalNoke.gatt.close();
                                finalNoke.gatt = null;
                                Log.d(TAG, "Initializing gatt connection: " + connectToGatt(finalNoke));
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
                            Log.d(TAG, "Initializing gatt connection: " + connectToGatt(noke));
                        } else {
                            /*
                             * Reusing GATT objects causes issues.  If the gatt object is not null when first
                             * connecting to lock. Disconnect/null object and try reconnecting
                             */
                            noke.gatt.disconnect();
                            noke.gatt.close();
                            noke.gatt = null;

                            Log.d(TAG, "Initializing gatt connection: " + connectToGatt(noke));
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
        if (mBluetoothAdapter == null || noke == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_BLUETOOTH_DISABLED, "Bluetooth is disabled");
            return false;
        }

        if (noke.bluetoothDevice == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return false;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    noke.gatt = noke.bluetoothDevice.connectGatt(NokeDeviceManagerService.this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    noke.gatt = noke.bluetoothDevice.connectGatt(NokeDeviceManagerService.this, false, mGattCallback);
                }
            }
        });
        return true;
    }

    /**
     * Implementation of the the BluetoothGatt callbacks.
     * Read more here: <a href="https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback.html">https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback.html</a>
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            final NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            invalidateConnectionTimer();
            if (status == NokeDefines.NOKE_GATT_ERROR) {
                if (noke.connectionAttempts > 4) {
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (noke.gatt != null) {
                                noke.gatt.disconnect();
                                noke.gatt.close();
                                noke.gatt = null;
                            }
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
                            if (noke.gatt != null) {
                                noke.gatt.disconnect();
                                noke.gatt.close();
                                noke.gatt = null;
                            }
                            try {
                                Thread.sleep(2600);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            Log.d(TAG, "Initializing gatt connection: " + connectToGatt(noke));
                        }
                    });
                }
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                noke.connectionAttempts = 0;
                noke.connectionState = NokeDefines.NOKE_STATE_CONNECTING;
                noke.isRestoring = false;
                noke.clearCommands();
                mGlobalNokeListener.onNokeConnecting(noke);

                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {

                        if (noke.gatt != null) {
                            Log.i(TAG, "Gatt not null. Attempting to start service discovery:" +
                                    noke.gatt.discoverServices());
                        } else {
                            noke.gatt = gatt;
                            Log.i(TAG, "Gatt was null. Attempting to start service discovery:" +
                                    noke.gatt.discoverServices());
                        }
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                if (noke.connectionState == 2) {
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (noke.gatt != null) {
                                noke.gatt.disconnect();
                            }
                            if (noke.gatt != null) {
                                noke.gatt.close();
                                noke.gatt = null;
                            }
                            Log.d(TAG, "Initializing gatt connection: " + connectToGatt(noke));
                        }
                    });
                } else {
                    if (noke.connectionAttempts == 0) {
                        refreshDeviceCache(noke.gatt, NokeDefines.SHOULD_FORCE_GATT_REFRESH);
                        noke.connectionState = NokeDefines.NOKE_STATE_DISCONNECTED;
                        mGlobalNokeListener.onNokeDisconnected(noke);
                        uploadData();
                    }
                }
            }
        }

        void refreshDeviceCache(final BluetoothGatt gatt, final boolean force) {
            /*
             * If the device is bonded this is up to the Service Changed characteristic to notify Android that the services has changed.
             * There is no need for this trick in that case.
             * If not bonded, the Android should not keep the services cached when the Service Changed characteristic is present in the target device database.
             * However, due to the Android bug (still exists in Android 5.0.1), it is keeping them anyway and the only way to clear services is by using this hidden refresh method.
             */
            if (force || gatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                /*
                 * There is a refresh() method in BluetoothGatt class but for now it's hidden. We will call it using reflections.
                 */
                try {
                    @SuppressWarnings("JavaReflectionMemberAccess") final Method refresh = gatt.getClass().getMethod("refresh");
                    if (refresh != null) {
                        final boolean success = (Boolean) refresh.invoke(gatt);
                        Log.d(TAG, "Refreshing Result: " + success);
                    }
                } catch (Exception e) {
                    //Log.e(TAG, "REFRESH DEVICE CACHE");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (gatt.getDevice().getName().contains("NOKE_FW") || gatt.getDevice().getName().contains("NFOB_FW") || gatt.getDevice().getName().contains("N3P_FW")) {
                    enableFirmwareTXNotification(noke);
                } else {
                    readStateCharacteristic(noke);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {

            if (status == BluetoothGatt.GATT_SUCCESS) {

                if (NokeDefines.STATE_CHAR_UUID.equals(characteristic.getUuid())) {
                    NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
                    noke.setSession(characteristic.getValue());
                    enableTXNotification(noke);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            Log.d(TAG, "On Characteristic Changed: " + NokeDefines.bytesToHex(characteristic.getValue()));
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            byte[] data = characteristic.getValue();
            onReceivedDataFromLock(data, noke);
        }


        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "On Descriptor Write: " + descriptor.toString() + " Status: " + status);
            if (gatt.getDevice().getName().contains("NOKE_FW") || gatt.getDevice().getName().contains("NFOB_FW") || gatt.getDevice().getName().contains("N3P_FW")) {
                NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
                noke.connectionState = NokeDefines.NOKE_STATE_CONNECTED;
                mGlobalNokeListener.onNokeConnected(noke);
            } else {
                NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
                noke.connectionState = NokeDefines.NOKE_STATE_CONNECTED;
                mGlobalNokeListener.onNokeConnected(noke);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);


        }
    };

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


        byte destination = data[0];
        if (destination == NokeDefines.SERVER_Dest) {
            if (noke.session != null) {
                addDataPacketToQueue(NokeDefines.bytesToHex(data), noke.session, noke.getMac());
            }
        } else if (destination == NokeDefines.APP_Dest) {
            byte resulttype = data[1];
            switch (resulttype) {
                case NokeDefines.SUCCESS_ResultType: {
                    int commandid = data[2];
                    if (noke.isRestoring) {
                        noke.commands.clear();
                        globalUploadQueue.clear();
                        noke.isRestoring = false;
                        confirmRestore(noke.getMac(), commandid);
                        disconnectNoke(noke);
                    } else {
                        moveToNext(noke);
                        if (noke.commands.size() == 0) {
                            noke.connectionState = NokeDefines.NOKE_STATE_UNLOCKED;
                            mGlobalNokeListener.onNokeUnlocked(noke);
                        }
                    }
                    break;
                }
                case NokeDefines.INVALIDKEY_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_KEY, "Invalid Key Result");
                    moveToNext(noke);
//                    if (noke.commands.size() == 0) {
                    //If library receives an invalid key error, it will attempt to restore the key by working with the API
//                        if(!noke.isRestoring) {
//                            restoreDevice(noke);
//                        }
//                    }
                    break;
                }
                case NokeDefines.INVALIDCMD_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_CMD, "Invalid Command Result");
                    moveToNext(noke);
                    break;
                }
                case NokeDefines.INVALIDPERMISSION_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_PERMISSION, "Invalid Permission (wrong key) Result");
                    moveToNext(noke);
                    break;
                }
                case NokeDefines.SHUTDOWN_ResultType: {
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
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_DATA, "Invalid Data Result");
                    moveToNext(noke);
                    break;
                }
                case NokeDefines.INVALID_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_RESULT, "Invalid Result");
                    moveToNext(noke);
                    break;
                }
                case NokeDefines.FAILEDTOLOCK_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_FAILED_TO_LOCK, "Device Failed to Lock");
                    moveToNext(noke);
                    break;
                }
                case NokeDefines.FAILEDTOUNLOCK_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_RESULT, "Device Failed to Unlock");
                    moveToNext(noke);
                    break;
                }
                case NokeDefines.FAILEDTOUNSHACKLE_ResultType: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_INVALID_RESULT, "Device Failed to Unlock Shackle");
                    moveToNext(noke);
                    break;
                }
                default: {
                    mGlobalNokeListener.onError(noke, NokeMobileError.DEVICE_ERROR_UNKNOWN, "Invalid packet received");
                    moveToNext(noke);
                    break;
                }
            }

        }
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
                e.printStackTrace();
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
            e.printStackTrace();
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
                    jsonObject.accumulate("logs", data);
                    String nokeMobileApiKey = getMobileApiKey();
                    this.uploadDataCallback(NokeMobileApiClient.POST(NokeDefines.uploadURL, jsonObject.toString(), nokeMobileApiKey, proxyAddress, port));
                } catch (Exception e) {
                    e.printStackTrace();
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
                    e.printStackTrace();
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
                Log.e(TAG, "Retrieval Error");
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
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
        }

        if (RxService == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        BluetoothGattCharacteristic StateChar = RxService.getCharacteristic(NokeDefines.STATE_CHAR_UUID);
        if (StateChar == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        noke.gatt.readCharacteristic(StateChar);
    }

    /**
     * Enable Notification on TX characteristic
     *
     * @param noke Noke device
     */

    private void enableTXNotification(NokeDevice noke) {

        if (noke.gatt == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);
        if (RxService == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        BluetoothGattCharacteristic TxChar = RxService.getCharacteristic(NokeDefines.TX_CHAR_UUID);
        if (TxChar == null) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
            return;
        }
        noke.gatt.setCharacteristicNotification(TxChar, true);

        BluetoothGattDescriptor descriptor = TxChar.getDescriptor(NokeDefines.CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        noke.gatt.writeDescriptor(descriptor);
    }


    private void enableFirmwareTXNotification(NokeDevice noke) {
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
        noke.gatt.writeDescriptor(descriptor);
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

                    BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);

                    if (RxService == null) {
                        mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
                        return;
                    }
                    BluetoothGattCharacteristic RxChar = RxService.getCharacteristic(NokeDefines.RX_CHAR_UUID);
                    if (RxChar == null) {
                        mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
                        return;
                    }

                    RxChar.setValue(NokeDefines.hexToBytes(noke.commands.get(0)));
                    boolean status = noke.gatt.writeCharacteristic(RxChar);
                    Log.d(TAG, "write TXchar - status =" + status);
                }
            });


        } catch (NullPointerException e) {
            mGlobalNokeListener.onError(noke, NokeMobileError.ERROR_INVALID_NOKE_DEVICE, "Invalid noke device");
        }
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnectNoke(final NokeDevice noke) {
        invalidateConnectionTimer();
        if (mBluetoothAdapter == null || noke.gatt == null) {
            return;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                if (noke.gatt != null) {
                    noke.gatt.disconnect();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    noke.gatt.close();
                    noke.gatt = null;
                }
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
                    String nokeMobileApiKey = getMobileApiKey();
                    NokeDeviceManagerService.this.restoreKeyCallback(NokeMobileApiClient.POST(url, jsonObject.toString(), nokeMobileApiKey, proxyAddress, port), noke);
                } catch (Exception e) {
                    e.printStackTrace();
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
                    String nokeMobileApiKey = getMobileApiKey();
                    NokeDeviceManagerService.this.confirmRestoreCallback(NokeMobileApiClient.POST(url, jsonObject.toString(), nokeMobileApiKey, proxyAddress, port));
                } catch (Exception e) {
                    e.printStackTrace();
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

    public void useProxy(String address, int port){
        this.proxyAddress = address;
        this.port = port;
    }

    private void invalidateConnectionTimer(){
        currentNoke = null;
        if(connectionTimer!=null) {
            connectionTimer.removeCallbacksAndMessages(null);
        }
    }
    private void initializeConnectionTimer(){
        connectionTimer = new Handler(Looper.getMainLooper());
        connectionTimer.postDelayed(connectionTimerRunnable(), numberOfSecondsToDetectTheConnectionError*1000);
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

}
