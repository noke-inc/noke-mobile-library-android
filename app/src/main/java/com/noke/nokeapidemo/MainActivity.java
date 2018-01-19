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
        LocalBroadcastManager.getInstance(this).registerReceiver(NokeStatusChangeReceiver, nokeUpdateIntentFilter());
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            mService = ((NokeBackgroundService.LocalBinder) rawBinder).getService();
            Log.d(TAG, "onServiceConnected mService= " + mService);
            if (!mService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
            mService = null;
        }
    };

    private final BroadcastReceiver NokeStatusChangeReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            //*********************//
            if (action.equals(NokeDefines.NOKE_CONNECTING)) {
                Log.d(TAG, "NOKE_CONNECT_MSG");
                btnConnect.setText(R.string.connecting);
            }

            //*********************//
            if (action.equals(NokeDefines.NOKE_DISCONNECTED)) {

                serverPacketCount = 0;

                Log.d(TAG, "NOKE_DISCONNECT_MSG");
                btnConnect.setText(R.string.connect);

                nokeDevices.remove(mDevice.mac);
                textDetails.setText("");
                mService.cancelScanning();

                disableButtons();

            }

            if (action.equals(NokeDefines.NOKE_CONNECTED))
            {

                mService.cancelScanning();

                serverPacketCount = 0;

                String mac = intent.getStringExtra(NokeDefines.MAC_ADDRESS);
                mDevice = mService.nokeDevices.get(mac);
                textDetails.setText(mDevice.name + " Version: " + mDevice.versionString);

                btnConnect.setText(R.string.disconnect);

                if(mDevice.name.contains("2F_"))
                {
                    enableFobButtons();
                }
                else
                {
                    Log.d(TAG, "NOKE CONNECTED!!!");
                    enableButtons();
                }

                //Auto Unlock after connecting
                if(autoUnlock)
                {
                    //mDevice.unlockLock();
                }
            }

            //*********************//
            if (action.equals(NokeDefines.INVALID_NOKE_DEVICE)){
                showMessage("Not a valid Noke device. Disconnecting");
                //mService.disconnect();
            }

            if (action.equals(NokeDefines.LOCATION_PERMISSION_NEEDED))
            {
                //Log.d(TAG, "LOCATION PERMISSION NEEDED!!!");
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
            }

            //This allows you to handle 133 Errors.  Restart scanning, retry connecting, etc.
            if (action.equals(NokeDefines.BLUETOOTH_GATT_ERROR)){
                showMessage("Bluetooth Gatt Error 133.  Please try reconnecting");

                btnConnect.setText(R.string.connect);
            }

            if (action.equals(NokeDefines.RECEIVED_SERVER_DATA))
            {
                serverPacketCount++;

                String mac = intent.getStringExtra(NokeDefines.MAC_ADDRESS);
                Device noke = mService.nokeDevices.get(mac);

                byte[] data = intent.getByteArrayExtra(NokeDefines.EXTRA_DATA);
                Log.w(TAG, "DATA RECEIVED: " + NokeDefines.bytesToHex(data) + " PACKET COUNT: " + serverPacketCount);

                serverSdk.RxDataFromLock(mac,data,noke.Status);

                //client.addDataToArray(NokeDefines.bytesToHex(data));//USED FOR INTERNAL TESTING

            }

            if (action.equals(NokeDefines.RECEIVED_APP_DATA))
            {
                String mac = intent.getStringExtra(NokeDefines.MAC_ADDRESS);
                Device noke = mService.nokeDevices.get(mac);

                byte[] data = intent.getByteArrayExtra(NokeDefines.EXTRA_DATA);

                Log.w(TAG, "APP DATA RECEIVED: " + NokeDefines.bytesToHex(data));

                byte resulttype = data[1];

                if(noke.TxToLockPackets.size()==0) {
                    //client.sendData(NokeDefines.bytesToHex(noke.Status), noke);
                }

                switch (resulttype)
                {
                    case NokeDefines.SUCCESS_ResultType:
                        showMessage("Command Success");
                        break;
                    case NokeDefines.INVALIDKEY_ResultType:
                        showMessage("Invalid Key");
                        break;
                    case NokeDefines.INVALIDCMD_ResultType:
                        showMessage("Invalid Command");
                        break;
                    case NokeDefines.INVALIDPERMISSION_ResultType:
                        showMessage("Invalid Permission");
                        break;
                    case NokeDefines.SHUTDOWN_ResultType:
                        showMessage("Shutdown");
                        byte shutdowntype = data[3];
                        if(shutdowntype == NokeDefines.MANUAL_ShutdownType)
                        {
                            Log.d(TAG, "MANUAL SHUTDOWN");
                        }
                        else if(shutdowntype == NokeDefines.TIMEOUT_ShutdownType)
                        {
                            Log.d(TAG, "TIMEOUT SHUTDOWN");
                        }

                        if(!addLockMac.equals(""))
                        {
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setTitle("Add Fob");

                            builder.setMessage("Key has been added successfully.  Click next to scan for fob.  Once connected, click the Fobs button to add the lock to your fob");
                            builder.setPositiveButton("Next", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which)
                                {
                                    mService.startBLE();
                                    btnConnect.setText(R.string.stop_scan);
                                }
                            });
                            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                    addLockMac = "";
                                    dialog.cancel();
                                }
                            });

                            builder.show();
                        }


                        break;
                    case NokeDefines.INVALIDDATA_ResultType:
                        showMessage("Invalid Data");
                        break;
                    case NokeDefines.INVALID_ResultType:
                        showMessage("Invalid");
                        break;
                }
            }
        }
    };
}
