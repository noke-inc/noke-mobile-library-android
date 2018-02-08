# Noke Mobile Library for Android #

The Noke Mobile Library provides an easy-to-use and stable way to communicate with Noke Devices via Bluetooth.  It must be used in 
conjunction with the Noke Core API for full functionality such as unlocking locks and uploading activity.

### Requirements ###

This library is compatible with Android devices that support Bluetooth Low Energy (BLE) and are running Android version 4.4 or higher

### Usage ###

* The compat library may be found on Maven Central Repository.  Add it to your project by adding the following dependency:

    MAVEN REPO DEPENDENCY HERE
	
* Once you've add the dependency to your project, add the Mobile API Key to your Android Manifest:
    <meta-data android:name= "noke-core-api-mobile-key"
               android:value= "MOBILE_KEY_HERE"
        />

* The heart of the Noke Mobile Library is a service that when bound to an activity handles scanning for Noke Devices, connecting, sending commands, and receiving responses. To bind the NokeDeviceManagerService to an activity:
```#java
   
   private void initiateNokeService(){
        Intent nokeServiceIntent = new Intent(this, NokeDeviceManagerService.class);
        bindService(nokeServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder rawBinder) {

            //Store reference to service
            mNokeService = ((NokeDeviceManagerService.LocalBinder) rawBinder).getService();

            //Register callback listener
            mNokeService.registerNokeListener(mNokeServiceListener);

            //Add locks to device manager
            NokeDevice noke1 = new NokeDevice("LOCK NAME", "XX:XX:XX:XX:XX:XX");
            mNokeService.addNokeDevice(noke1);

            //Start bluetooth scanning
            mNokeService.startScanningForNokeDevices();

            if (!mNokeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
            mNokeService = null;
        }
    };```

*Use the `NokeServiceListener` class to receive callbacks from the `NokeDeviceManagerService`:

```#java
    private NokeServiceListener mNokeServiceListener = new NokeServiceListener() {
        @Override
        public void onNokeDiscovered(NokeDevice noke) {

        }

        @Override
        public void onNokeConnecting(NokeDevice noke) {

        }

        @Override
        public void onNokeConnected(NokeDevice noke) {

        }

        @Override
        public void onNokeSyncing(NokeDevice noke) {

        }

        @Override
        public void onNokeUnlocked(NokeDevice noke) {

        }

        @Override
        public void onNokeDisconnected(NokeDevice noke) {

        }

        @Override
        public void onBluetoothStatusChanged(int bluetoothStatus) {

        }

        @Override
        public void onError(NokeDevice noke, int error, String message) {
            Log.e(TAG, "NOKE SERVICE ERROR " + error + ": " + message);
         }
        }
    };```
