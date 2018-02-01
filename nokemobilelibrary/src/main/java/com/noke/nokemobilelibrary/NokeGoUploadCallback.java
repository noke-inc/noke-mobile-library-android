package com.noke.nokemobilelibrary;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Created by Spencer on 1/31/18.
 * Class for handling upload callback from Noke Go Library
 */

public class NokeGoUploadCallback implements nokego.UploadCallback {

    private NokeBluetoothService mService;

    NokeGoUploadCallback(NokeBluetoothService mService) {
        this.mService = mService;
    }

    @Override
    public void receivedUploadError(String s) {
        mService.getNokeListener().onError(null, NokeMobileError.GO_ERROR_UNLOCK, s);
    }

    @Override
    public void receivedUploadResponse(String s) {
        Log.w("TAG", "RESPONSE: " + s);
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
            Log.e("DEVICE", "JSON EXCEPTION: " + e.toString());
        }
    }
}
