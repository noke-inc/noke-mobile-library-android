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
    public void onNokeShutdown(NokeDevice noke, Boolean isLocked, Boolean didTimeout) {
        //empty default implementation
    }

    @Override
    public void onNokeDisconnected(NokeDevice noke) {
        //empty default implementation
    }

    @Override
    public void onDataUploaded(int result, String message) {
        //empty default implementation
    }

    @Override
    public void onBluetoothStatusChanged(int bluetoothStatus) {
        //empty default implementation
    }

    @Override
    public void onLocationStatusChanged(Boolean enabled) {
        //empty default implementation
    }

    @Override
    public void onError(NokeDevice noke, int error, String message) {
        //empty default implementation
    }
}
