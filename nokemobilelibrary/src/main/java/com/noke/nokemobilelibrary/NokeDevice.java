package com.noke.nokemobilelibrary;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.util.Log;


import org.json.JSONArray;

import java.util.ArrayList;

import nokego.Nokego;


/**
 * Created by Spencer on 1/17/18.
 * Class that contains methods and properties of Noke Devices
 */

public class NokeDevice {

    private String name;
    private String mac;
    private String serial;
    private String version;
    private Integer battery;
    private String trackingKey;

    transient String session;

    BluetoothDevice bluetoothDevice;
    transient BluetoothGatt gatt;

    transient int connectionState;
    transient int lockState;
    transient int connectionAttempts;
    transient int rssi;

    private transient JSONArray responses = new JSONArray();
    protected transient ArrayList<String> commands;

    public transient NokeBluetoothService mService;

    public NokeDevice(String name, String mac){

        this.name = name;
        this.mac = mac;

        commands = new ArrayList<>();
        connectionAttempts = 0;
    }

    public String getVersion(byte[] broadcastBytes, String deviceName) {
        String version = "";
        if(deviceName != null) {
            if(deviceName.contains("NOKE") || deviceName.contains("FOB")) {
                if(broadcastBytes != null && broadcastBytes.length > 0) {
                    int majorVersion = broadcastBytes[1];
                    int minorVersion = broadcastBytes[2];
                    String hardwareVersion = deviceName.substring(4,6);
                    return hardwareVersion + "-"+majorVersion+"."+minorVersion;
                }
                else {
                    if(deviceName.contains("2P")) {
                        return "P2.0";
                    }
                    else {
                        if(deviceName.contains("FOB")) {
                            return "1F-1.0";
                        }
                        else {
                            return "1P-1.0";
                        }
                    }
                }
            }
            else {
                return "NOT A NOKE DEVICE";
            }
        }
        return version;
    }

    void setSession(byte[] sessionIn) {
        byte[] batteryArray = new byte[]{sessionIn[3], sessionIn[2]};
        String batteryString = NokeDefines.bytesToHex(batteryArray);
        battery = Integer.parseInt(batteryString, 16);
        Log.w("Device", "Battery: " + battery);

        if(sessionIn.length>=20) {
            session = NokeDefines.bytesToHex(sessionIn);
        }
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public Integer getBattery() {
        return battery;
    }

    public void setBattery(Integer battery) {
        this.battery = battery;
    }

    public String getTrackingKey() {
        return trackingKey;
    }

    public void setTrackingKey(String trackingKey) {
        this.trackingKey = trackingKey;
    }

    public void unlock(){
        NokeGoUnlockCallback callback = new NokeGoUnlockCallback(this);
        Nokego.unlockNoke(this.mac, this.session, this.trackingKey, NokeBluetoothService.unlockURL, callback);
    }
}
