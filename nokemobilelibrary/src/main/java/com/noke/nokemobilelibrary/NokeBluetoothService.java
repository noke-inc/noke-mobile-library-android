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
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/**
 * Created by Spencer on 1/17/18.
 * Service for handling all bluetooth communication with the lock
 */

public class NokeBluetoothService extends Service {

    private final static String TAG = NokeBluetoothService.class.getSimpleName();
    static String unlockURL = "https://lock-api-dev.appspot.com/unlock/";

    //Bluetooth Scanning
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothScanner;
    private ScanCallback mNewBluetoothScanCallback;
    private BluetoothAdapter.LeScanCallback mOldBluetoothScanCallback;
    private boolean mReceiverRegistered;
    private boolean mScanning;

    private NokeServiceListener mGlobalNokeListener;

    //SDK Settings
    private int bluetoothDelayDefault;
    private int bluetoothDelayBackgroundDefault;

    //List of Noke Devices
    public LinkedHashMap<String, NokeDevice> nokeDevices;

    public class LocalBinder extends Binder{
        public NokeBluetoothService getService(){
            return NokeBluetoothService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();
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
        setBluetoothDelayDefault(250);
        setBluetoothDelayBackgroundDefault(2000);
    }

    public void registerNokeListener(Context context, NokeServiceListener listener){
        this.mGlobalNokeListener = listener;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId){
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    public void addNokeDevice(NokeDevice noke){
        if(nokeDevices == null){
            nokeDevices = new LinkedHashMap<>();
        }

        NokeDevice newNoke = nokeDevices.get(noke.mac);
        if(newNoke == null){
            noke.mService = this;
            nokeDevices.put(noke.mac, noke);
        }
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        if(mReceiverRegistered){
            unregisterReceiver(bluetoothBroadcastReceiver);
            mReceiverRegistered = false;
        }
        //TODO Handle restarting service
    }

    public boolean initialize(){
        if(mBluetoothManager == null){
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if(mBluetoothManager == null){
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        return mBluetoothAdapter != null;
    }

    public void startScanningForNokeDevices(){
        try {
            LocationManager lm = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            boolean gps_enabled = false;
            boolean network_enabled = false;
            try {
                if (lm != null) {
                    gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
                }
            } catch (Exception e) {
                mGlobalNokeListener.onError(null, NokeDefines.ERROR_GPS_ENABLED);
            }

            try {
                if (lm != null) {
                    network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception e) {
                mGlobalNokeListener.onError(null, NokeDefines.ERROR_NETWORK_ENABLED);
            }

            int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION);
            if (!gps_enabled && !network_enabled) {
                mGlobalNokeListener.onError(null, NokeDefines.ERROR_LOCATION_SERVICES_DISABLED);
            } else if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                mGlobalNokeListener.onError(null, NokeDefines.ERROR_LOCATION_SERVICES_DISABLED);
            } else if (mBluetoothAdapter != null) {
                if (!mBluetoothAdapter.isEnabled()) {
                    mGlobalNokeListener.onError(null, NokeDefines.ERROR_BLUETOOTH_DISABLED);
                } else {
                    initiateBackgroundBLEScan();
                }
            } else {
                mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                if (mBluetoothManager != null) {
                    mBluetoothAdapter = mBluetoothManager.getAdapter();
                }
                if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
                    //TODO Handle cases when bluetooth is turned off
                } else {
                    initiateBackgroundBLEScan();
                }
            }
        }catch (NullPointerException e) {
            mGlobalNokeListener.onError(null, NokeDefines.ERROR_BLUETOOTH_SCANNING);
        }
    }

    boolean scanLoopOn = false;
    boolean scanLoopOff = false;
    boolean backgroundScanning = false;

    private void initiateBackgroundBLEScan() {
        if(!backgroundScanning) {
            backgroundScanning = true;
            startBackgroundBLEScan();
        }
    }

    int bluetoothDelay = 10;
    private void startBackgroundBLEScan() {
        startLeScanning();
        final Handler refreshScan = new Handler();
        final int scanDelay = 4000;
        refreshScan.postDelayed(new Runnable(){
            @Override
            public void run() {
                stopBackgroundBLEScan();
                if(isServiceRunningInForeground()){
                    bluetoothDelay = bluetoothDelayDefault;
                }else{
                    bluetoothDelay = bluetoothDelayBackgroundDefault;
                }

            }
        }, scanDelay);
    }

    public void setBluetoothDelayDefault(int delay){
        bluetoothDelayDefault = delay;
    }

    public void setBluetoothDelayBackgroundDefault(int delay){
        bluetoothDelayBackgroundDefault = delay;
    }

    private void stopBackgroundBLEScan(){
        stopLeScanning();
        if(backgroundScanning){
            final Handler refreshScan = new Handler();
            refreshScan.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scanLoopOn = true;
                    scanLoopOff = false;
                    startBackgroundBLEScan();
                }
            }, bluetoothDelay);
        }
    }

    public void cancelScanning(){ backgroundScanning = false;}

    /**
     * Starts BLE scanning.
     */
    @SuppressWarnings("deprecation")
    private void startLeScanning()
    {
        if(!mScanning) {
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
            }
            else
            {
                mGlobalNokeListener.onError(null, NokeDefines.ERROR_BLUETOOTH_SCANNING);
            }
        }
    }

    /**
     * Stops BLE scanning.
     */
    @SuppressWarnings("deprecation")
    private void stopLeScanning()
    {
        mScanning = false;
        if (mBluetoothAdapter != null) {
            if (mBluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= 100) {
                    mBluetoothScanner.stopScan(mNewBluetoothScanCallback);
                } else {
                    //DEPRECTATED. INCLUDING FOR 4.0 SUPPORT
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
    private void initNewBluetoothCallback()
    {
        mNewBluetoothScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                //TODO NEW BLUETOOTH SCAN CALLBACK
            }
        };
    }

    /**
     * Initializes Bluetooth Scanning Callback for KitKat OS
     */
    private void initOldBluetoothCallback()
    {
        mOldBluetoothScanCallback = new BluetoothAdapter.LeScanCallback()
        {
            @Override
            public void onLeScan(final BluetoothDevice bluetoothDevice, final int rssi, byte[] scanRecord)
            {
                if(bluetoothDevice.getName() != null)
                {
                    if (bluetoothDevice.getName().contains("NOKE"))
                    {
                        NokeDevice noke = new NokeDevice(bluetoothDevice.getName(), bluetoothDevice.getAddress());
                        noke.bluetoothDevice = bluetoothDevice;

                        if(nokeDevices.get(noke.mac) != null){
                            byte[] broadcastData;
                            String nameVersion;

                            if (bluetoothDevice.getName().contains("FOB") && !bluetoothDevice.getName().contains("NFOB")) {
                                nameVersion = bluetoothDevice.getName().substring(3, 5);
                            } else {
                                nameVersion = bluetoothDevice.getName().substring(4, 6);
                            }

                            if (!nameVersion.equals("06") && !nameVersion.equals("04")) {
                                byte[] getdata = getManufacturerData(scanRecord);
                                broadcastData = new byte[]{getdata[2], getdata[3], getdata[4]};
                                String version = noke.getVersion(broadcastData, bluetoothDevice.getName());
                                int setupflag = (int) broadcastData[0];
                                noke.version = version;
                                noke.bluetoothDevice = bluetoothDevice;

                                if(nokeDevices.get(noke.mac) == null){
                                    nokeDevices.put(noke.mac, noke);
                                }

                                mGlobalNokeListener.onNokeDiscovered(noke);
                            }
                        }
                    }
                }
            }
        };
    }

    private byte[] getManufacturerData(byte[] scanRecord){
        int i = 0;
        do {
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
        }while(i < scanRecord.length);

        return new byte[]{0,0,0,0,0};
    }


    public void connectToNoke(NokeDevice noke){
        connectToDevice(noke.bluetoothDevice, noke.rssi);
    }

    /**
     * Attempts to match MAC address to device in nokeDevices list.  If device is found, stop scanning and
     * call connectToGatt to start service disovery and connect to device.
     * @param device Bluetooth device that was obtained from the scanner callback
     * @param rssi RSSI value obtained from the scanner.  Can be used for adjusting connecting range.
     */

    private void connectToDevice(BluetoothDevice device, int rssi)
    {
        if(device != null) {
            NokeDevice noke = nokeDevices.get(device.getAddress());
            if (noke != null) {
                noke.mService = this;
                noke.connectionAttempts = 0;
                noke.rssi = rssi;
                stopLeScanning();
                if (noke.bluetoothDevice == null) {
                    noke.bluetoothDevice = device;
                }

                if (noke.connectionState == NokeDefines.STATE_DISCONNECTED) {
                    mBluetoothAdapter.cancelDiscovery();
                    noke.connectionState = NokeDefines.STATE_CONNECTING;

                    Handler handler = new Handler(Looper.getMainLooper());
                    final NokeDevice finalNoke = noke;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finalNoke.gatt == null) {
                                connectToGatt(finalNoke);
                            } else {
                                /*Reusing GATT objects causes issues.  If the gatt object is not null when first
                                 * connecting to lock. Disconnect/null object and try reconnecting
                                 */
                                finalNoke.gatt.disconnect();
                                finalNoke.gatt.close();
                                finalNoke.gatt = null;
                                connectToGatt(finalNoke);
                            }
                        }
                    });
                }
            } else if (device.getName() != null) {
                if (device.getName().contains("NOKE")) {
                    stopLeScanning();
                    noke = new NokeDevice(device.getName(), device.getAddress());
                    if (noke.bluetoothDevice == null) {
                        noke.bluetoothDevice = device;
                    }
                    if (noke.connectionState == NokeDefines.STATE_DISCONNECTED) {
                        mBluetoothAdapter.cancelDiscovery();
                        noke.connectionState = NokeDefines.STATE_CONNECTING;
                        if (noke.gatt == null) {
                            connectToGatt(noke);
                        } else {
                            /*Reusing GATT objects causes issues.  If the gatt object is not null when first
                             * connecting to lock. Disconnect/null object and try reconnecting
                             */
                            noke.gatt.disconnect();
                            noke.gatt.close();
                            noke.gatt = null;

                            connectToGatt(noke);
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
    private boolean connectToGatt(final NokeDevice noke)
    {
        if (mBluetoothAdapter == null || noke == null) {
            return false;
        }

        if(noke.bluetoothDevice == null)
        {
            return false;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                {
                    noke.gatt = noke.bluetoothDevice.connectGatt(NokeBluetoothService.this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
                }
                else
                {
                    noke.gatt = noke.bluetoothDevice.connectGatt(NokeBluetoothService.this, false, mGattCallback);
                }
            }
        });

        return true;
    }

    //Implements callback methods for GATT events that the app cares about. For example,
    //connection change and services discovered
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {

            final NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            if(status == NokeDefines.GATT_ERROR) {
                if(noke.connectionAttempts > 4) {
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {

                            noke.gatt.disconnect();
                            noke.gatt.close();
                            noke.gatt = null;
                            noke.connectionState = NokeDefines.STATE_DISCONNECTED;
                            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_BLUETOOTH_GATT);

                        }
                    });
                }
                else
                {
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {

                            noke.connectionAttempts++;
                            refreshDeviceCache(noke.gatt, true);
                            if(noke.gatt != null) {
                                noke.gatt.disconnect();
                                noke.gatt.close();
                                noke.gatt = null;
                            }

                            try {
                                Thread.sleep(2600);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            connectToGatt(noke);
                        }
                    });
                }
            }
            else if (newState == BluetoothProfile.STATE_CONNECTED) {

                noke.connectionAttempts = 0;
                noke.connectionState = NokeDefines.STATE_CONNECTED;
                mGlobalNokeListener.onNokeConnecting(noke);

                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {

                        if(noke.gatt != null) {
                            Log.i(TAG, "Gatt not null. Attempting to start service discovery:" +
                                    noke.gatt.discoverServices());
                        }
                        else {
                            noke.gatt = gatt;
                            Log.i(TAG, "Gatt was null. Attempting to start service discovery:" +
                                    noke.gatt.discoverServices());
                        }
                    }
                });


            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                if(noke.connectionState == 2) {
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if(noke.gatt != null) {
                                noke.gatt.disconnect();
                            }
                            if(noke.gatt != null) {
                                noke.gatt.close();
                                noke.gatt = null;
                            }
                            connectToGatt(noke);
                        }
                    });
                }
                else {
                    if (noke.connectionAttempts == 0) {
                        refreshDeviceCache(noke.gatt, true);
                        mGlobalNokeListener.onNokeDisconnected(noke);
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
                    final Method refresh = gatt.getClass().getMethod("refresh");
                    if (refresh != null) {
                        final boolean success = (Boolean) refresh.invoke(gatt);
                        Log.d(TAG, "REFRESHING RESULT: " + success);
                    }
                } catch (Exception e) {
                    //Log.e(TAG, "REFRESH DEVICE CACHE");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            if (status == BluetoothGatt.GATT_SUCCESS)
            {
                if(gatt.getDevice().getName().contains("NOKE_FW") || gatt.getDevice().getName().contains("NFOB_FW") || gatt.getDevice().getName().contains("N3P_FW")) {
                    enableFirmwareTXNotification(noke);
                }
                else {
                    readStateCharacteristic(noke);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {

            if (status == BluetoothGatt.GATT_SUCCESS){

                if(NokeDefines.STATE_CHAR_UUID.equals(characteristic.getUuid())) {
                    NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
                    noke.setSession(characteristic.getValue());
                    enableTXNotification(noke);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            Log.w(TAG, "On Characteristic Changed: " + NokeDefines.bytesToHex(characteristic.getValue()));
            NokeDevice noke = nokeDevices.get(gatt.getDevice().getAddress());
            byte[] data=characteristic.getValue();

            byte destination = data[0];
            if (destination == NokeDefines.SERVER_Dest) {
                byte resultdata[] = new byte[20];
                NokeDefines.copyArray(resultdata, 0, data, 0, 20);
            } else if (destination == NokeDefines.APP_Dest) {
                    if (noke.commands.size() > 0) {
                        noke.commands.remove(0);
                        if (noke.commands.size() > 0) {
                            writeRXCharacteristic(noke);
                        }
                    }

                    byte resulttype = data[1];
                    if (resulttype == NokeDefines.SHUTDOWN_ResultType) {
                        disconnectNoke(noke);
                    }
                }
        }


        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            Log.w(TAG, "On Descriptor Write: " + descriptor.toString() + " Status: " + status);
            if(gatt.getDevice().getName().contains("NOKE_FW") || gatt.getDevice().getName().contains("NFOB_FW") || gatt.getDevice().getName().contains("N3P_FW"))
            {
                mGlobalNokeListener.onNokeConnected(nokeDevices.get(gatt.getDevice().getAddress()));
            }
            else
            {
                mGlobalNokeListener.onNokeConnected(nokeDevices.get(gatt.getDevice().getAddress()));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);


        }
    };

    /**
     * Reads the State Characteristic on Noke Device.  When read this contains Lock State, Battery State,
     * and Session Key
     *
     * @param noke The device to read the state characteristic from.
     */
    private void readStateCharacteristic(NokeDevice noke){
        if (mBluetoothAdapter == null || noke.gatt == null){
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);

        if(noke.gatt == null) {

        }

        if (RxService == null){
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }
        BluetoothGattCharacteristic StateChar = RxService.getCharacteristic(NokeDefines.STATE_CHAR_UUID);
        if (StateChar == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }
        noke.gatt.readCharacteristic(StateChar);
    }

    /**
     * Enable Notification on TX characteristic
     *
     * @param noke Noke device
     */

    private void enableTXNotification(NokeDevice noke)
    {

        if (noke.gatt == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);
        if (RxService == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }
        BluetoothGattCharacteristic TxChar = RxService.getCharacteristic(NokeDefines.TX_CHAR_UUID);
        if (TxChar == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }
        noke.gatt.setCharacteristicNotification(TxChar, true);

        BluetoothGattDescriptor descriptor = TxChar.getDescriptor(NokeDefines.CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        noke.gatt.writeDescriptor(descriptor);
    }


    private void enableFirmwareTXNotification(NokeDevice noke)
    {
        if (noke.gatt == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }

        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.FIRMWARE_RX_SERVICE_UUID);
        if (RxService == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }
        BluetoothGattCharacteristic TxChar = RxService.getCharacteristic(NokeDefines.FIRMWARE_TX_CHAR_UUID);
        if (TxChar == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
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

    void writeRXCharacteristic(NokeDevice noke)
    {
        BluetoothGattService RxService = noke.gatt.getService(NokeDefines.RX_SERVICE_UUID);
        if (noke.gatt == null)
        {
            return;
        }

        if (RxService == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }
        BluetoothGattCharacteristic RxChar = RxService.getCharacteristic(NokeDefines.RX_CHAR_UUID);
        if (RxChar == null) {
            mGlobalNokeListener.onError(noke, NokeDefines.ERROR_INVALID_NOKE_DEVICE);
            return;
        }

        RxChar.setValue(NokeDefines.hexToBytes(noke.commands.get(0)));
        boolean status = noke.gatt.writeCharacteristic(RxChar);
        Log.d(TAG, "write TXchar - status =" + status);
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnectNoke(final NokeDevice noke) {
        if (mBluetoothAdapter == null || noke.gatt == null) {
            return;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                if(noke.gatt != null) {
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

    private boolean isServiceRunningInForeground() {

        ActivityManager.RunningAppProcessInfo myProcess = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(myProcess);
        return myProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

    }

    private final BroadcastReceiver bluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            final String action = intent.getAction();
            if(action != null) {
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


}
