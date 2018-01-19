package com.noke.nokemobilelibrary;

/**
 * Created by Spencer on 1/19/18.
 */

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
    public void onNokeUnlocked(NokeDevice noke) {

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
    public void onError(NokeDevice noke, int error) {
        //empty default implementation
    }
}
