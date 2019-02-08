

package com.noke.nokemobilelibrary;
import java.util.UUID;

/************************************************************************************************************************************************
 * Copyright © 2018 Nokē Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Created by Spencer Apsley on 1/17/18.
 * Set of defines used in the Noke Mobile Library
 */

public class NokeDefines {

    /**
     * Default scan off time for foreground scanning
     */
    static final int BLUETOOTH_DEFAULT_SCAN_TIME                    = 10;
    /**
     * Default scan off time for background scanning
     */
    static final int BLUETOOTH_DEFAULT_SCAN_TIME_BACKGROUND         = 2000;
    /**
     * Default scan off time for background scanning
     */
    static final int BLUETOOTH_DEFAULT_SCAN_DURATION                = 8000;
    /**
     * Identifer string for Noke hardware devices
     */
    static final String NOKE_DEVICE_IDENTIFER_STRING                = "NOKE";
    /**
     * Should force gatt refresh
     */
    static final boolean SHOULD_FORCE_GATT_REFRESH                  = true;

    /**
     * Identifier for Noke Mobile API Key meta data
     */
    static final String NOKE_MOBILE_API_KEY                        = "noke-core-api-mobile-key";


    static final int OFFLINE_KEY_LENGTH                       = 32;
    static final int UNLOCK_COMMAND_LENGTH                    = 40;

    /**
     * Lock response types
     */
    static final byte SUCCESS_ResultType							=(byte)0x60;
    static final byte INVALIDKEY_ResultType						    =(byte)0x61;
    static final byte INVALIDCMD_ResultType						    =(byte)0x62;
    static final byte INVALIDPERMISSION_ResultType				    =(byte)0x63;
    static final byte SHUTDOWN_ResultType						    =(byte)0x64;
    static final byte INVALIDDATA_ResultType						=(byte)0x65;
    static final byte FAILEDTOLOCK_ResultType                       =(byte)0x68;
    static final byte FAILEDTOUNLOCK_ResultType                     =(byte)0x69;
    static final byte FAILEDTOUNSHACKLE_ResultType                  =(byte)0x6A;
    static final byte INVALID_ResultType							=(byte)0xFF;

    /**
     * Lock response destination types
     */
    static final int SERVER_Dest	                                = 0x50;
    static final int APP_Dest                                       = 0x51;

    /**
     * Noke device UUIDs
     */
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID RX_SERVICE_UUID = UUID.fromString("1bc50001-0200-d29e-e511-446c609db825");
    static final UUID RX_CHAR_UUID = UUID.fromString("1bc50002-0200-d29e-e511-446c609db825");
    static final UUID TX_CHAR_UUID = UUID.fromString("1bc50003-0200-d29e-e511-446c609db825");
    static final UUID STATE_CHAR_UUID = UUID.fromString("1bc50004-0200-d29e-e511-446c609db825");

    /**
     * Noke firmware mode UUIDs
     */
    static final UUID FIRMWARE_RX_SERVICE_UUID = UUID.fromString("00001530-1212-efde-1523-785feabcd123");
    static final UUID FIRMWARE_TX_CHAR_UUID = UUID.fromString("00001531-1212-efde-1523-785feabcd123");

    /**
     * Noke Connection States
     */
    static final int NOKE_STATE_DISCONNECTED         = 0;
    static final int NOKE_STATE_DISCOVERED           = 1;
    static final int NOKE_STATE_CONNECTING           = 2;
    static final int NOKE_STATE_CONNECTED            = 3;
    static final int NOKE_STATE_SYNCING              = 4;
    static final int NOKE_STATE_UNLOCKED             = 5;
    static final int NOKE_GATT_ERROR                 = 133;

    /**
     * Noke Lock States
     */
    public static final int NOKE_LOCK_STATE_UNKNOWN         = -1;
    public static final int NOKE_LOCK_STATE_UNLOCKED        = 0;
    public static final int NOKE_LOCK_STATE_UNSHACKLED      = 1;
    public static final int NOKE_LOCK_STATE_LOCKED          = 2;

    /**
     * Hardware Types
     */
    public static final String NOKE_HW_TYPE_1ST_GEN_PADLOCK         = "2P";
    public static final String NOKE_HW_TYPE_2ND_GEN_PADLOCK         = "3P";
    public static final String NOKE_HW_TYPE_ULOCK                   = "2U";
    public static final String NOKE_HW_TYPE_HD_LOCK                 = "2I";
    public static final String NOKE_HW_TYPE_DOOR_CONTROLLER         = "2E";
    public static final String NOKE_HW_TYPE_PB12                    = "1C";



    /**
     * Shared Preferences
     */
    static final String PREFS_NAME                   = "nokeAPILibaryFile";
    static final String PREF_DEVICES                 = "nokedevices";
    static final String PREF_UPLOADDATA              = "uploaddata";


    /**
     * Noke Library Modes
     * Determines where the logs from the lock are sent
     */
    public static final int NOKE_LIBRARY_SANDBOX           = 0;
    public static final int NOKE_LIBRARY_PRODUCTION        = 1;
    public static final int NOKE_LIBRARY_DEVELOP           = 2;
    public static final int NOKE_LIBRARY_OPEN              = 3;


    /**
     * Request URLS
     */
    static String uploadURL = "";

    static final String sandboxUploadURL            = "https://coreapi-sandbox.appspot.com/upload/";
    static final String productionUploadURL         = "https://coreapi-beta.appspot.com/upload/";
    static final String developUploadURL            = "https://lock-api-dev.appspot.com/upload/";


    /**
     * Used for converting bytes to hex
     */
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    /**
     * Converts byte array to hex string
     * @param bytes byte array to convert
     * @return a hex string
     */
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

    /**
     * Converts hex string to a byte array
     * @param hexstring String to be converted
     * @return byte array
     */
    static byte[] hexToBytes(String hexstring) {
        hexstring=hexstring.toUpperCase();
        int len=hexstring.length()/2;
        byte[] bytes = new byte[len];
        for(int x=0;x<len;x++){
            for(int y=0;y<hexArray.length;y++)
            {
                if(hexArray[y]==hexstring.charAt(2*x)){
                    bytes[x]+=(byte)(y<<4);
                }
                if(hexArray[y]==hexstring.charAt(2*x+1)){
                    bytes[x]+=(byte)y;
                }
            }
        }
        return bytes;
    }

    public static int toUnsigned(byte val) {
        int out = val;
        if (out < 0)
            out = val & 0xFF;

        return out;
    }
}
