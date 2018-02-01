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
import android.widget.Button;

import com.noke.nokemobilelibrary.NokeBluetoothService;
import com.noke.nokemobilelibrary.NokeDevice;
import com.noke.nokemobilelibrary.NokeMobileError;
import com.noke.nokemobilelibrary.NokeServiceListener;


public class MainActivity extends AppCompatActivity {

    public static final String TAG = MainActivity.class.getSimpleName();

    Button scanButton;
    private NokeBluetoothService mService = null;
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Initiate Noke Service
        initiateNokeService();

        scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(mService != null) {
                    mService.startScanningForNokeDevices();
                }else{
                    Log.e(TAG, "SERVICE HAS NOT BEEN INITIALIZED");
                }
            }
        });
    }

    private void initiateNokeService(){
        Intent nokeServiceIntent = new Intent(this, NokeBluetoothService.class);
        bindService(nokeServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            Log.w(TAG, "ON SERVICE CONNECTED");

            //Store reference to service
            mService = ((NokeBluetoothService.LocalBinder) rawBinder).getService();

            //Register callback listener
            mService.registerNokeListener(mNokeServiceListener);

            //Set unlock URL
            mService.setUnlockUrl("https://lock-api-dev.appspot.com/unlock/");

            //Add locks to device manager
            NokeDevice noke1 = new NokeDevice("PAH-CAT-SAEU", "D7:EE:3C:07:8F:68");
            mService.addNokeDevice(noke1);
            noke1.setTrackingKey("saeu key");

            NokeDevice noke2 = new NokeDevice("PAH-CAT-SAET", "C1:86:3D:EE:67:84");
            mService.addNokeDevice(noke2);
            noke2.setTrackingKey("saet key");

            if (!mService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
            mService = null;
        }
    };

    private NokeServiceListener mNokeServiceListener = new NokeServiceListener() {
        @Override
        public void onNokeDiscovered(NokeDevice noke) {
            Log.w(TAG, "NOKE DISCOVERED: " + noke.getName());
            mService.connectToNoke(noke);
        }

        @Override
        public void onNokeConnecting(NokeDevice noke) {
            Log.w(TAG, "NOKE CONNECTING: " + noke.getName());
        }

        @Override
        public void onNokeConnected(NokeDevice noke) {
            Log.w(TAG, "NOKE CONNECTED: " + noke.getName());
            mService.stopScanning();
            noke.unlock();
        }

        @Override
        public void onNokeSyncing(NokeDevice noke) {
            Log.w(TAG, "NOKE SYNCING: " + noke.getName());
        }

        @Override
        public void onNokeUnlocked(NokeDevice noke) {
            Log.w(TAG, "NOKE UNLOCKED: " + noke.getName());
        }

        @Override
        public void onNokeDisconnected(NokeDevice noke) {
            Log.w(TAG, "NOKE DISCONNECTED: " + noke.getName());
        }

        @Override
        public void onBluetoothStatusChanged(int bluetoothStatus) {

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
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                Log.d(TAG, "GRANT RESULTS: " + grantResults[0]);
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                {
                    mService.startScanningForNokeDevices();
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
}
