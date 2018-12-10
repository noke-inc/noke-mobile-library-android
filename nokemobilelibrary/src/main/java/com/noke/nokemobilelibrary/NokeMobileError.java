package com.noke.nokemobilelibrary;

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
 * Created by Spencer on 1/31/18.
 * Contains defines for errors in the library
 */

@SuppressWarnings({"WeakerAccess", "unused"})
public class NokeMobileError {

    //API Errors
    public static final int SUCCESS                             = 0; //that's not an error
    public static final int API_ERROR_INTERNAL_SERVER           = 1;
    public static final int API_ERROR_API_KEY                   = 2;
    public static final int API_ERROR_INPUT                     = 3;
    public static final int API_ERROR_REQUEST_METHOD            = 4;
    public static final int API_ERROR_INVALID_ENPOINT           = 5;
    public static final int API_ERROR_COMPANY_NOT_FOUND         = 6;
    public static final int API_ERROR_LOCK_NOT_FOUND            = 7;
    public static final int API_ERROR_UNKNOWN                   = 99;

    //GO Library Errors
    public static final int GO_ERROR_UNLOCK                     = 100;
    public static final int GO_ERROR_UPLOAD                     = 101;

    //Noke Device Errors (200 + error code)
    //public static final int DEVICE_SUCCESS                        = 260; //that's not an error
    public static final int DEVICE_ERROR_INVALID_KEY                = 261;
    public static final int DEVICE_ERROR_INVALID_CMD                = 262;
    public static final int DEVICE_ERROR_INVALID_PERMISSION         = 263;
    public static final int DEVICE_SHUTDOWN_RESULT                  = 264;
    public static final int DEVICE_ERROR_INVALID_DATA               = 265;
    public static final int DEVICE_BATTERY_RESULT                   = 266;
    public static final int DEVICE_ERROR_INVALID_RESULT             = 267;
    public static final int DEVICE_ERROR_FAILED_TO_LOCK             = 268;
    public static final int DEVICE_ERROR_FAILED_TO_UNLOCK           = 269;
    public static final int DEVICE_ERROR_FAILED_TO_REMOVE_SHACKLE   = 270;
    public static final int DEVICE_ERROR_UNKNOWN                    = 299;

    //Noke Device Manager Service Errors
    public static final int ERROR_LOCATION_PERMISSIONS_NEEDED   = 300;
    public static final int ERROR_LOCATION_SERVICES_DISABLED    = 301;
    public static final int ERROR_BLUETOOTH_DISABLED            = 302;
    public static final int ERROR_BLUETOOTH_GATT                = 303;
    public static final int ERROR_INVALID_NOKE_DEVICE           = 304;
    public static final int ERROR_GPS_ENABLED                   = 305;
    public static final int ERROR_NETWORK_ENABLED               = 306;
    public static final int ERROR_BLUETOOTH_SCANNING            = 307;
    public static final int ERROR_MISSING_API_KEY               = 308;
    public static final int ERROR_INVALID_OFFLINE_KEY           = 309;
    public static final int ERROR_JSON_UPLOAD                   = 315;
    public static final int ERROR_MISSING_UPLOAD_URL            = 316;






}
