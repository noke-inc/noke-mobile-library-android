package com.noke.nokemobilelibrary;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;

/**
 * Created by Spencer on 1/17/18.
 * Class that contains methods and properties of Noke Devices
 */

public class NokeDevice {

    String name;
    String mac;
    String serial;
    String version;
    String battery;

    public transient String session;

    BluetoothDevice bluetoothDevice;
    public transient BluetoothGatt gatt;
    public transient int connectionState;
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

    void setSession(byte[] statusIn) {
        if(statusIn.length>=20) {
            session = NokeDefines.bytesToHex(statusIn);
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

    public String getBattery() {
        return battery;
    }

    public void setBattery(String battery) {
        this.battery = battery;
    }
}
