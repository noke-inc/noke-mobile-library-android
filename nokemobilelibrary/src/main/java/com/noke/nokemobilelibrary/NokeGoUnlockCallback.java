package com.noke.nokemobilelibrary;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import nokego.Nokego;

/**
 * Created by Spencer on 1/31/18.
 */

public class NokeGoUnlockCallback implements nokego.UnlockCallback {

    NokeDevice noke;

    NokeGoUnlockCallback(NokeDevice noke) {
        this.noke = noke;
    }

    @Override
    public void receivedUnlockError(String s) {
        noke.mService.getNokeListener().onError(noke, NokeMobileError.GO_ERROR_UNLOCK, s);
    }

    @Override
    public void receivedUnlockResponse(String s) {
        Log.w("TAG", "RESPONSE: " + s);
        try{
            JSONObject obj = new JSONObject(s);
            int errorCode = obj.getInt("error_code");
            String message = obj.getString("message");

            if(errorCode == NokeMobileError.SUCCESS){
                JSONObject data = obj.getJSONObject("data");
                JSONArray array = data.getJSONArray("commands");
                if(noke.commands != null){
                    noke.commands.clear();
                }else{
                    noke.commands = new ArrayList<>();
                }

                for(int x = 0; x<array.length(); x++){
                    String command = array.getString(x);
                    noke.commands.add(command);
                }
                noke.mService.writeRXCharacteristic(noke);
            }else{
                noke.mService.getNokeListener().onError(noke, errorCode, message);
            }

        } catch(JSONException e){
            Log.e("DEVICE", "JSON EXCEPTION: " + e.toString());
        }

    }
}
