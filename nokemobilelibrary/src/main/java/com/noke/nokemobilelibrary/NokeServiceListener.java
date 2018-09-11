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
 * Listener for handling callbacks from NokeService
 */

public interface NokeServiceListener  {

    void onNokeDiscovered(NokeDevice noke);

    void onNokeConnecting(NokeDevice noke);

    void onNokeConnected(NokeDevice noke);

    void onNokeSyncing(NokeDevice noke);

    void onNokeUnlocked(NokeDevice noke);

    void onNokeShutdown(NokeDevice noke, Boolean isLocked, Boolean didTimeout);

    void onNokeDisconnected(NokeDevice noke);

    void onDataUploaded(int result, String message);

    void onBluetoothStatusChanged(int bluetoothStatus);

    void onError(NokeDevice noke, int error, String message);

}
