package com.noke.nokemobilelibrary;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;


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
 * Class for handling upload callback from Noke Go Library
 */

public class NokeGoUploadCallback implements nokego.UploadCallback {

    private NokeDeviceManagerService mService;

    NokeGoUploadCallback(NokeDeviceManagerService mService) {
        this.mService = mService;
    }

    @Override
    public void receivedUploadError(String s) {
        mService.getNokeListener().onError(null, NokeMobileError.GO_ERROR_UNLOCK, s);
    }

    @Override
    public void receivedUploadResponse(String s) {
        try{
            JSONObject obj = new JSONObject(s);
            int errorCode = obj.getInt("error_code");
            String message = obj.getString("message");

            if(errorCode == NokeMobileError.SUCCESS){
                mService.globalUploadQueue.clear();
                //TODO HANDLE UPLOAD SUCCESS
            }else{
                mService.getNokeListener().onError(null, errorCode, message);
            }
        } catch(JSONException e){
            Log.e("NokeGoUploadCallback", "JSON EXCEPTION: " + e.toString());
        }
    }
}
