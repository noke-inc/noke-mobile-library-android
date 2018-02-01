package com.noke.nokemobilelibrary;

/**
 * Created by Spencer on 1/19/18.
 * Adapter for NokeServiceListener
 */

@SuppressWarnings("unused")
public class NokeServiceListenerAdapter implements NokeServiceListener{

    @Override
    public void onNokeDiscovered(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onNokeConnecting(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onNokeConnected(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onNokeSyncing(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onNokeUnlocked(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onNokeDisconnected(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onBluetoothStatusChanged(int bluetoothStatus) {
        //empty default implementation
    }

    @Override
    public void onError(NokeDevice noke, int error, String message) {
        //empty default implementation
    }
}
