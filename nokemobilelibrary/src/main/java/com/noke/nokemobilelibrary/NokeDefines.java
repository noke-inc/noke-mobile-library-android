package com.noke.nokemobilelibrary;
import java.util.UUID;

/**
 * Created by Spencer on 1/17/18.
 * Set of defines used in the Noke Mobile Library
 */

public class NokeDefines {

    //LockAppResponse 	used by the app to confirm command succeeded
    //ResultTypes
    static final byte SUCCESS_ResultType							=(byte)0x60;
    static final byte INVALIDKEY_ResultType						    =(byte)0x61;
    static final byte INVALIDCMD_ResultType						    =(byte)0x62;
    static final byte INVALIDPERMISSION_ResultType				    =(byte)0x63;
    static final byte SHUTDOWN_ResultType						    =(byte)0x64;
    static final byte INVALIDDATA_ResultType						=(byte)0x65;
    static final byte INVALID_ResultType							=(byte)0xFF;
    //EOF ResultTypes

    //Destinations
    static final int SERVER_Dest	=					0x50;
    static final int APP_Dest =						0x51;

    //SDK2 UUIDS
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final UUID RX_SERVICE_UUID = UUID.fromString("1bc50001-0200-d29e-e511-446c609db825");
    static final UUID RX_CHAR_UUID = UUID.fromString("1bc50002-0200-d29e-e511-446c609db825");
    static final UUID TX_CHAR_UUID = UUID.fromString("1bc50003-0200-d29e-e511-446c609db825");
    static final UUID STATE_CHAR_UUID = UUID.fromString("1bc50004-0200-d29e-e511-446c609db825");


    //FIRMWAREUUIDS
    static final UUID FIRMWARE_RX_SERVICE_UUID = UUID.fromString("00001530-1212-efde-1523-785feabcd123");
    static final UUID FIRMWARE_TX_CHAR_UUID = UUID.fromString("00001531-1212-efde-1523-785feabcd123");

    //Bluetooth States
    static final int STATE_DISCONNECTED = 0;
    static final int STATE_CONNECTING = 1;
    static final int STATE_CONNECTED = 2;
    static final int GATT_ERROR = 133;

    //Noke Connection States
    static final int NOKE_STATE_DISCONNECTED         = 0;
    static final int NOKE_STATE_DISCOVERED           = 1;
    static final int NOKE_STATE_CONNECTING           = 2;
    static final int NOKE_STATE_CONNECTED            = 3;
    static final int NOKE_STATE_SYNCING              = 4;
    static final int NOKE_STATE_UNLOCKED             = 5;

    //Noke Lock States
    static final int NOKE_LOCK_STATE_UNLOCKED        = 0;
    static final int NOKE_LOCK_STATE_LOCKED          = 1;

    //SHARED PREFERENCES
    static final String PREFS_NAME                   = "nokeAPILibaryFile";
    static final String PREF_DEVICES                 = "nokedevices";
    static final String PREF_UPLOADDATA              = "uploaddata";

    //URLS
    static String unlockURL = "https://lock-api-dev.appspot.com/unlock/";
    static String uploadURL = "https://lock-api-dev.appspot.com/upload/";



    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    static String bytesToHex(byte[] bytes) {
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

    static byte[] hexToBytes(String hexstring) {
        hexstring=hexstring.toUpperCase();
        int len=hexstring.length()/2;
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





}
