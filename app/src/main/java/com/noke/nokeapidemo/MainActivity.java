package com.noke.nokeapidemo;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.noke.nokemobilelibrary.NokeDefines;
import com.noke.nokemobilelibrary.NokeDeviceManagerService;
import com.noke.nokemobilelibrary.NokeDevice;
import com.noke.nokemobilelibrary.NokeMobileError;
import com.noke.nokemobilelibrary.NokeServiceListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DemoWebClient.DemoWebClientCallback {

    public static final String TAG = MainActivity.class.getSimpleName();

    LinearLayout lockLayout;
    TextView lockNameText, statusText;
    EditText emailEditText;
    private NokeDeviceManagerService mNokeService = null;
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private NokeDevice currentNoke;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Initiate Noke Service
        initiateNokeService();

        lockLayout = findViewById(R.id.lock_layout);
        lockLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(currentNoke == null){
                    setStatusText("No Device Connected");
                }
                else{
                    DemoWebClient demoWebClient = new DemoWebClient();
                    demoWebClient.setWebClientCallback(MainActivity.this);
                    demoWebClient.requestUnlock(currentNoke, emailEditText.getText().toString());
                }
            }
        });

        lockNameText = findViewById(R.id.lock_text);
        statusText = findViewById(R.id.status_text);
        emailEditText = findViewById(R.id.email_input);
        emailEditText.setVisibility(View.GONE);

    }

    private void initiateNokeService(){
        Intent nokeServiceIntent = new Intent(this, NokeDeviceManagerService.class);
        bindService(nokeServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            Log.w(TAG, "ON SERVICE CONNECTED");

            //Store reference to service
            mNokeService = ((NokeDeviceManagerService.LocalBinder) rawBinder).getService(NokeDefines.NOKE_LIBRARY_SANDBOX);

            //Uncomment to allow devices that aren't in the device array
            //mNokeService.setAllowAllDevices(true);

            //Register callback listener
            mNokeService.registerNokeListener(mNokeServiceListener);

            String[] macs = {"XX:XX:XX:XX:XX"};

            for (String mac:macs
                 ) {
                //Add locks to device manager
                NokeDevice noke1 = new NokeDevice(mac, mac);
                mNokeService.addNokeDevice(noke1);
            }


            //Start bluetooth scanning
            mNokeService.startScanningForNokeDevices();
            setStatusText("Scanning for Noke Devices");

            if (!mNokeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
            mNokeService = null;
        }
    };

    private NokeServiceListener mNokeServiceListener = new NokeServiceListener() {
        @Override
        public void onNokeDiscovered(NokeDevice noke) {


            setLockLayoutColor(getResources().getColor(R.color.nokeBlue));

            String lockState = "";
            switch (noke.getLockState()){
                case NokeDefines.NOKE_LOCK_STATE_LOCKED:
                    lockState = "Locked";
                    break;
                case NokeDefines.NOKE_LOCK_STATE_UNLOCKED:
                    lockState = "Unlocked";
                    break;
                case NokeDefines.NOKE_LOCK_STATE_UNSHACKLED:
                    lockState = "Unshackled";
                    break;
                case NokeDefines.NOKE_LOCK_STATE_UNKNOWN:
                    lockState = "Unknown";
                    break;
            }

            currentNoke = noke;
            setStatusText("NOKE DISCOVERED: " + noke.getName() + " (" + lockState + ")");
            mNokeService.connectToNoke(currentNoke);

        }

        @Override
        public void onNokeConnecting(NokeDevice noke) {
            setStatusText("NOKE CONNECTING: " + noke.getName());
        }

        @Override
        public void onNokeConnected(NokeDevice noke) {
            setStatusText("NOKE CONNECTED: " + noke.getName());
            setLockNameText(noke.getName());
            setLockLayoutColor(getResources().getColor(R.color.nokeBlue));
            currentNoke = noke;
            mNokeService.stopScanning();
        }

        @Override
        public void onNokeSyncing(NokeDevice noke) {
            setStatusText("NOKE SYNCING: " + noke.getName());
        }

        @Override
        public void onNokeUnlocked(NokeDevice noke) {
            setStatusText("NOKE UNLOCKED: " + noke.getName());
            setLockLayoutColor(getResources().getColor(R.color.unlockGreen));
        }

        @Override
        public void onNokeShutdown(NokeDevice noke, Boolean isLocked, Boolean didTimeout) {
            setStatusText("NOKE SHUTDOWN: " + noke.getName() + " LOCKED: " + isLocked + " TIMEOUT: " + didTimeout);
        }

        @Override
        public void onNokeDisconnected(NokeDevice noke) {
            setStatusText("NOKE DISCONNECTED: " + noke.getName());
            setLockLayoutColor(getResources().getColor(R.color.disconnectGray));
            setLockNameText("No Lock Connected");
            currentNoke = null;
            //mNokeService.uploadData();
            mNokeService.startScanningForNokeDevices();
            mNokeService.setBluetoothScanDuration(8000);
        }

        @Override
        public void onDataUploaded(int result, String message) {
            Log.w(TAG, "DATA UPLOADED: " + message);
            setStatusText(message);
        }

        @Override
        public void onBluetoothStatusChanged(int bluetoothStatus) {

        }

        @Override
        public void onLocationStatusChanged(Boolean enabled) {

        }

        @Override
        public void onError(NokeDevice noke, int error, String message) {
            Log.e(TAG, "NOKE SERVICE ERROR " + error + ": " + message);
            switch (error){
                case NokeMobileError.ERROR_LOCATION_PERMISSIONS_NEEDED:
                    break;
                case NokeMobileError.ERROR_LOCATION_SERVICES_DISABLED:
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    {
                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(new Runnable() {
                            @Override @TargetApi(23)
                            public void run() {
                                android.support.v7.app.AlertDialog alertDialog = new android.support.v7.app.AlertDialog.Builder(MainActivity.this).create();
                                alertDialog.setTitle(getString(R.string.location_access_required));
                                alertDialog.setMessage(getString(R.string.location_permission_request_message));
                                alertDialog.setButton(android.support.v7.app.AlertDialog.BUTTON_NEUTRAL, "OK",
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dialog, int which) {
                                                dialog.dismiss();
                                            }
                                        });
                                alertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                                    @Override
                                    public void onDismiss(DialogInterface dialog)
                                    {
                                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);

                                    }
                                });
                                alertDialog.show();
                            }
                        });
                    }
                    break;
                case NokeMobileError.ERROR_BLUETOOTH_DISABLED:
                    break;
                case NokeMobileError.ERROR_BLUETOOTH_GATT:
                    break;
                case NokeMobileError.DEVICE_ERROR_INVALID_KEY:
                    break;
            }
        }
    };

    public void setStatusText(final String message){
        Log.d(TAG, message);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                statusText.setText(message);
            }
        });
    }

    public void setLockNameText(final String message){
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                lockNameText.setText(message);
            }
        });
    }

    public void setLockLayoutColor(final int color){
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                lockLayout.setBackgroundColor(color);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                Log.d(TAG, "GRANT RESULTS: " + grantResults[0]);
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                {
                    mNokeService.startScanningForNokeDevices();
                } else {
                    final android.support.v7.app.AlertDialog.Builder builder = new android.support.v7.app.AlertDialog.Builder(this);
                    builder.setTitle(getString(R.string.functionality_limited));
                    builder.setMessage(getString(R.string.no_location_message));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                        @Override
                        public void onDismiss(DialogInterface dialog) {

                            //showLocationSnackbar(getString(R.string.enable_location_permissions));
                        }

                    });
                    builder.show();
                }
            }
        }
    }

    @Override
    public void onUnlockReceived(String response, NokeDevice noke) {

        /* Note: This is an example response from a demo server, it does not represent a response from the Noke Core API.
         * Requests should not be made to the Core API directly from the mobile app.
         * Please refer to the documentation for more details
         * (https://github.com/noke-inc/noke-mobile-library-android#nok%C4%93-mobile-library-for-android)
         */

        Log.d(TAG, "Unlock Received: "+ response);
        try{
            JSONObject obj = new JSONObject(response);
            Boolean result = obj.getBoolean("result");
            if(result){
                JSONObject data = obj.getJSONObject("data");
                String commandString = data.getString("commands");
                currentNoke.sendCommands(commandString);
            }else{
                setStatusText("Access Denied");
                setLockLayoutColor(getResources().getColor(R.color.alertRed));
            }

        }catch (JSONException e){
            Log.e(TAG, e.toString());
        }
    }
}
