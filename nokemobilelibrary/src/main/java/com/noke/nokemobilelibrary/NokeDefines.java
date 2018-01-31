package com.noke.nokemobilelibrary;

import android.os.ParcelUuid;

import java.util.UUID;

/**
 * Created by Spencer on 1/17/18.
 */

public class NokeDefines {

    public static final int KEYLEN = 16;

    public byte enableEncryption;

    public static String NOKECMD_VERSION = "2";
    public static String NOKECMD_VERSION_UPDATED = "3-11-16";

    public static final int PACKETSIZE = 20;

    public static byte STARTBYTE = 0x7E;

    //LockAppResponse 	used by the app to confirm command succeeded
    //ResultTypes
    public static final byte SUCCESS_ResultType							=(byte)0x60;
    public static final byte INVALIDKEY_ResultType						=(byte)0x61;
    public static final byte INVALIDCMD_ResultType						=(byte)0x62;
    public static final byte INVALIDPERMISSION_ResultType				=(byte)0x63;
    public static final byte SHUTDOWN_ResultType						=(byte)0x64;
    public static final byte INVALIDDATA_ResultType						=(byte)0x65;
    public static final byte INVALID_ResultType							=(byte)0xFF;
    //EOF ResultTypes

    //Communication Packet types
    //AppDataPacket 			Main structure for all commands from the app
    //CmdTypes
    public static final byte SHUTDOWN_PK				=(byte)0xA0;
    public static final byte SETUP_PK					=(byte)0xA1;
    public static final byte UNLOCK_PK					=(byte)0xA2;
    public static final byte REMOVEOFFLINEKEY_PK	    =(byte)0xA3;
    public static final byte ENABLEOFFLINEKEYS_PK		=(byte)0xA4;
    public static final byte GETLOGS_PK					=(byte)0xA5;
    public static final byte SETQC_PK					=(byte)0xA6;
    public static final byte GETQC_PK					=(byte)0xA7;
    public static final byte SETQCLOCKOUT_PK			=(byte)0xA8;
    public static final byte RESET_PK					=(byte)0xA9;
    public static final byte GETBATTERY_PK				=(byte)0xAA;
    public static final byte FIRMWAREUPDATE_PK			=(byte)0xAB;
    public static final byte TEST_PK					=(byte)0xAC;
    public static final byte SETRADIOTX_PK				=(byte)0xAD;
    public static final byte SETTIMEOUT_PK				=(byte)0xAE;
    public static final byte SETLONGPRESSTIME_PK		=(byte)0xAF;
    public static final byte SETKEY_PK		            =(byte)0xB0;
    public static final byte CLEARQC_PK                 =(byte)0xB1;
    public static final byte INVALID_PK		            =(byte)0xFF;
    //EOF CmdTypes

    //Key Types,        Type slot in app to lock packet
    public static final byte MCK_KeyType				=(byte)0x02;
    public static final byte SCK_KeyType				=(byte)0x03;
    public static final byte OCK_KeyType			    =(byte)120; //0x78
    public static final byte QCK_KeyType			    =(byte)0x10;
    //EOF KeyTypes

    //QuickClick Types
    public static byte QCFULL       =(byte) 0xFF;
    public static byte QCONETIME    =(byte) 0x01;
    public static byte QCDISABLED   =(byte) 0x00;

    //Destinations
    public static final int SERVER_Dest	=					0x50;
    public static final int APP_Dest =						0x51;
    public static final int LOCK_Dest =						0x52;


    //SDK2 UUIDS
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final ParcelUuid SCAN_SERVICE_UUID = ParcelUuid.fromString("1bc50001-0200-d29e-e511-446c609db825");

    public static final UUID RX_SERVICE_UUID = UUID.fromString("1bc50001-0200-d29e-e511-446c609db825");
    public static final UUID RX_CHAR_UUID = UUID.fromString("1bc50002-0200-d29e-e511-446c609db825");
    public static final UUID TX_CHAR_UUID = UUID.fromString("1bc50003-0200-d29e-e511-446c609db825");
    public static final UUID STATE_CHAR_UUID = UUID.fromString("1bc50004-0200-d29e-e511-446c609db825");


    //FIRMWAREUUIDS
    public static final UUID FIRMWARE_RX_SERVICE_UUID = UUID.fromString("00001530-1212-efde-1523-785feabcd123");
    public static final UUID FIRMWARE_RX_CHAR_UUID = UUID.fromString("00001531-1212-efde-1523-785feabcd123");
    public static final UUID FIRMWARE_TX_CHAR_UUID = UUID.fromString("00001531-1212-efde-1523-785feabcd123");

    public class QuickClick{
        public byte Id=1;
        public byte UseCmt= NokeDefines.QCFULL;
        public byte Code[]={0,0,0};
        public byte Length=3;
    }

    //Bluetooth States
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int GATT_ERROR = 133;

    //Bluetooth Gatt Callbacks
    public static final String ACTION_GATT_CONNECTED               = "NokeBluetoothService.ACTION_GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED            = "NokeBluetoothService.ACTION_GATT_DISCONNECTED";
    public static final String ACTION_GATT_SERVICES_DISCOVERED     = "NokeBluetoothService.ACTION_GATT_SERVICES_DISCOVERED";
    public static final String ACTION_DATA_AVAILABLE               = "NokeBluetoothService.ACTION_DATA_AVAILABLE";
    public static final String EXTRA_DATA                          = "NokeBluetoothService.EXTRA_DATA";
    public static final String MAC_ADDRESS                         = "NokeBluetoothService.MAC_ADDRESS";
    public static final String INVALID_NOKE_DEVICE                 = "NokeBluetoothService.INVALID_NOKE_DEVICE";
    public static final String TX_NOTIFICATION_ENABLED             = "NokeBluetoothService.TX_NOTIFICATION_ENABLED";
    public static final String STATE_CHARACTERISTIC_READ           = "NokeBluetoothService.STATE_CHARACTERISTIC_READ";
    public static final String BLUETOOTH_GATT_ERROR                = "NokeBluetoothService.BLUETOOTH_GATT_ERROR";
    public static final String BLUETOOTH_DISABLED                  = "NokeBluetoothService.BLUETOOTH_DISABLED";
    public static final String BLUETOOTH_ENABLED                   = "NokeBluetoothService.BLUETOOTH_ENABLED";

    //Noke Broadcasts
    public static final String NOKE_CONNECTING                     ="NokeBluetoothService.NOKE_CONNECTING";
    public static final String NOKE_CONNECTED                      ="NokeBluetoothService.NOKE_CONNECTED";
    public static final String NOKE_DISCONNECTED                   ="NokeBluetoothService.NOKE_DISCONNECTED";
    public static final String RECEIVED_SERVER_DATA                ="NokeBluetoothService.RECEIVED_SERVER_DATA";
    public static final String RECEIVED_APP_DATA                   ="NokeBluetoothService.RECEIVED_APP_DATA";
    public static final String FOUND_NOKE                          ="NokeBluetoothService.FOUND_NOKE";
    public static final String NOTIFICATION_UNLOCK                 ="NokeBluetoothService.NOTIFICATION_UNLOCK";
    public static final String UNLOCK_NOKE                         ="NokeBluetoothService.UNLOCK_NOKE";
    public static final String UPDATE_FIRMWARE                     ="NokeBluetoothService.UPDATE_FIRMWARE";
    public static final String LEGACY_FIRMWARE_ERROR               ="NokeBluetoothService.LEGACY_FIRMWARE_ERROR";
    public static final String LEGACY_LOGIN_ERROR                  ="NokeBluetoothService.LEGACY_LOGIN_ERROR";


    public static final String LOCKS_LOADED                        ="NokeBluetoothService.LOCKS_LOADED";
    public static final String FOBS_LOADED                         ="NokeBluetoothService.FOBS_LOADED";
    public static final String LOCATION_PERMISSION_NEEDED          ="NokeBluetoothService.LOCATION_PERMISSION_NEEDED";
    public static final String ENABLE_LOCATION_SERVICES            ="NokeBluetoothService.ENABLE_LOCATION_SERVICES";
    public static final String RELOAD_LOCK_TABLE                   ="NokeBluetoothService.RELOAD_LOCK_TABLE";
    public static final String RELOAD_FOB_TABLE                    ="NokeBluetoothService.RELOAD_FOB_TABLE";

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        if(bytes != null) {
            char[] hexChars = new char[bytes.length * 2];
            for (int j = 0; j < bytes.length; j++) {
                int v = bytes[j] & 0xFF;
                hexChars[j * 2] = hexArray[v >>> 4];
                hexChars[j * 2 + 1] = hexArray[v & 0x0F];
            }
            return new String(hexChars);
        }
        else
        {
            return "";
        }
    }

    public static byte[] hexToBytes(String hexstring) {
        hexstring=hexstring.toUpperCase();
        int len=hexstring.length()/2;
        int hexstringlen=len*2;
        byte[] bytes = new byte[len];
        for(int x=0;x<len;x++){
            for(int y=0;y<hexArray.length;y++)
            {
                if(hexArray[y]==hexstring.charAt(2*x)){
//                    bytes[len-1-x]+=(byte)(y<<4);
                    bytes[x]+=(byte)(y<<4);
                }
                if(hexArray[y]==hexstring.charAt(2*x+1)){
//                    bytes[len-1-x]+=(byte)y;
                    bytes[x]+=(byte)y;
                }
            }
        }
        return bytes;
    }

    public static String bytesToHex(byte[] bytes, int start, int len) {
        if(bytes.length<start+len)
            return "bytesToHex: Invalid length";

        char[] hexChars = new char[len * 2];
        for (int j = 0; j < len; j++) {
            int v = bytes[j+start] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static int copyArray(byte[] dataOut, byte[] dataIn, int size) {
        if(dataOut.length<size)
            return -1;
        if(dataIn.length<size)
            return -1;

        for (int x = 0; x < size; x++)
            dataOut[x] = dataIn[x];

        return 0;
    }
    public static int copyArray(byte[] dataOut,int outStartByte, byte[] dataIn, int inStartByte, int size) {
        if(dataOut.length<size+outStartByte)
            return -1;
        if(dataIn.length<size+inStartByte)
            return -1;

        for (int x = 0; x < size; x++)
            dataOut[x+outStartByte] = dataIn[x+inStartByte];

        return 0;
    }
    public static void clearArray(byte[] data, int size){
        for (int x = 0; x < size; x++)
            data[x] = 0;
    }

    //NOKE ERRORS
    public static final int ERROR_LOCATION_PERMISSIONS_NEEDED = 0;
    public static final int ERROR_LOCATION_SERVICES_DISABLED = 1;
    public static final int ERROR_BLUETOOTH_DISABLED = 2;
    public static final int ERROR_BLUETOOTH_GATT = 3;
    public static final int ERROR_INVALID_NOKE_DEVICE = 4;
    public static final int ERROR_GPS_ENABLED = 5;
    public static final int ERROR_NETWORK_ENABLED = 6;
    public static final int ERROR_BLUETOOTH_SCANNING = 7;

    //API ERRORS
    public static final int ERROR_SUCCESS = 0;



}
