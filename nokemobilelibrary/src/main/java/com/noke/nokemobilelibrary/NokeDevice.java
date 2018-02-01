package com.noke.nokemobilelibrary;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.util.Log;
import java.util.ArrayList;
import nokego.Nokego;

/**
 * Created by Spencer on 1/17/18.
 * Class stores information about the Noke device and contains methods for interacting with the Noke device
 */

public class NokeDevice {

    /**
     * Name of the Noke device (strictly cosmetic)
     */
    private String name;
    /**
     * MAC address of the Noke device. This can be found in the peripheral name
     */
    private String mac;
    /**
     * Serial number of Noke device. Laser engraved onto the device during manufacturing
     */
    private String serial;
    /**
     * Firmware and hardware version of the lock. Follows format: '3P-2.10' where '3P' is the hardware version and '2.10' is the firmware version
     */
    private String version;
    /**
     * Tracking key used to track Noke device usage and activity
     */
    private String trackingKey;
    /**
     * BluetoothDevice used for interacting with the Noke device via bluetooth
     */
    BluetoothDevice bluetoothDevice;
    /**
     * Provides Bluetooth GATT functionality to enable communication with Bluetooth Smart devices
     */
    transient BluetoothGatt gatt;
    /**
     * 40 char string read from the session characteristic upon connecting to the Noke device
     */
    transient String session;
    /**
     * Battery level of the Noke device in millivolts
     */
    private Integer battery;
    /**
     * Connection state of the Noke device
     */
    transient int connectionState;
    /**
     * Lock state of the Noke device
     */
    transient int lockState;
    /**
     * Attempts made to connect. Sometimes if the gatt throws an error, simply retrying will work. We make 4 attempts to connect before throwing an error
     */
    transient int connectionAttempts;
    /**
     * Single strength of the Noke device broadcast
     */
    transient int rssi;
    /**
     * Array of commands to be sent to the Noke device
     */
    transient ArrayList<String> commands;
    /**
     * Reference to the bluetooth service that manages to the device
     */
    transient NokeBluetoothService mService;

    /**
     * Initializes Noke Device
     * @param name name of the device
     * @param mac MAC address of the device
     */
    public NokeDevice(String name, String mac){

        this.name = name;
        this.mac = mac;

        commands = new ArrayList<>();
        connectionAttempts = 0;
    }

    /**
     * Parses through the broadcast data and pulls out the version
     * @param broadcastBytes broadcast data from the lock
     * @param deviceName bluetooth device name of the lock. This contains the hardware version
     * @return version string. @see #version
     */
    @SuppressWarnings("WeakerAccess")
    public String getVersion(byte[] broadcastBytes, String deviceName) {
        String version = "";
        if(deviceName != null) {
            if(deviceName.contains(NokeDefines.NOKE_DEVICE_IDENTIFER_STRING)) {
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

    /**
     * Sets the session of the lock after connecting.  Session is only valid for the duration that the lock is connected
     * @param sessionIn 20 byte array of the session read from the session characteristic
     */
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

    @SuppressWarnings("WeakerAccess")
    public String getMac() {
        return mac;
    }

    @SuppressWarnings("unused")
    public void setMac(String mac) {
        this.mac = mac;
    }

    @SuppressWarnings("unused")
    public String getSerial() {
        return serial;
    }

    @SuppressWarnings("unused")
    public void setSerial(String serial) {
        this.serial = serial;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    @SuppressWarnings("unused")
    public String getSession() {
        return session;
    }

    @SuppressWarnings("unused")
    public void setSession(String session) {
        this.session = session;
    }

    @SuppressWarnings("unused")
    public Integer getBattery() {
        return battery;
    }

    @SuppressWarnings("unused")
    public void setBattery(Integer battery) {
        this.battery = battery;
    }

    @SuppressWarnings("unused")
    public String getTrackingKey() {
        return trackingKey;
    }

    public void setTrackingKey(String trackingKey) {
        this.trackingKey = trackingKey;
    }

    /**
     * Unlocks the lock via the Noke Go Library
     */
    public void unlock(){
        NokeGoUnlockCallback callback = new NokeGoUnlockCallback(this);
        Nokego.unlockNoke(this.mac, this.session, this.trackingKey, NokeDefines.unlockURL, callback);
    }
}
