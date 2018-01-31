package com.noke.nokemobilelibrary;

import android.util.Log;

import nokego.Nokego;

/**
 * Created by Spencer on 1/31/18.
 */

public class NokeGoUnlockCallback implements nokego.UnlockCallback {

    NokeDevice noke;

    public NokeGoUnlockCallback(NokeDevice noke) {
        this.noke = noke;
    }

    @Override
    public void receivedUnlockError(String s) {

    }

    @Override
    public void receivedUnlockResponse(String s) {
        Log.w("TAG", "RESPONSE: " + s);
    }
}
