package com.noke.nokeapidemo;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import com.noke.nokemobilelibrary.NokeBluetoothService;
import com.noke.nokemobilelibrary.NokeDevice;
import com.noke.nokemobilelibrary.NokeServiceListener;

import java.util.LinkedHashMap;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = MainActivity.class.getSimpleName();

    Button scanButton;
    private NokeBluetoothService mService = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        scanButton = (Button) findViewById(R.id.scan_button);
    }

    private void initiateNokeService(){
        Intent nokeServiceIntent = new Intent(this, NokeBluetoothService.class);
        bindService(nokeServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            mService = ((NokeBluetoothService.LocalBinder) rawBinder).getService();
            mService.registerNokeListener(MainActivity.this, mNokeServiceListener);

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

        }

        @Override
        public void onNokeConnecting(NokeDevice noke) {

        }

        @Override
        public void onNokeConnected(NokeDevice noke) {

        }

        @Override
        public void onNokeDisconnected(NokeDevice noke) {

        }

        @Override
        public void onBluetoothStatusChanged(int bluetoothStatus) {

        }

        @Override
        public void onError(NokeDevice noke, int error) {

        }
    };
}
