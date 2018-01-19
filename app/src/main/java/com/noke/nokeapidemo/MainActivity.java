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
import com.noke.nokemobilelibrary.NokeDefines;
import com.noke.nokemobilelibrary.NokeDevice;
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

        initiateNokeService();

        scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(mService != null) {
                    mService.startBLE();
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
            mService = ((NokeBluetoothService.LocalBinder) rawBinder).getService();
            mService.registerNokeListener(MainActivity.this, mNokeServiceListener);

            NokeDevice testNoke = new NokeDevice("testNoke", "FE:FE:A7:84:87:7D");
            mService.addNokeDevice(testNoke);

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
        public void onError(NokeDevice noke, int error) {
            Log.e(TAG, "NOKE SERVICE ERROR: " + error);
            switch (error){
                case NokeDefines.ERROR_LOCATION_PERMISSIONS_NEEDED:
                    break;
                case NokeDefines.ERROR_LOCATION_SERVICES_DISABLED:
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
                case NokeDefines.ERROR_BLUETOOTH_DISABLED:
                    break;
                case NokeDefines.ERROR_BLUETOOTH_GATT:
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
                    mService.startBLE();
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
