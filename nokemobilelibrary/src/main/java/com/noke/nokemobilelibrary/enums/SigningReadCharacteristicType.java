package com.noke.nokemobilelibrary.enums;

import com.noke.nokemobilelibrary.interfaces.CharacteristicType;

import java.util.UUID;

/**
 * Noke signing device Read Characteristics
 */

public enum SigningReadCharacteristicType implements CharacteristicType {
    STATUS("NokeDeviceManagerService_readStatusCharacteristic", UUID.fromString("AE82FFB4-6AC4-4F9D-A917-308D52492513")),
    COMMAND_ID("NokeDeviceManagerService_readCommandIdCharacteristic", UUID.fromString("AE82FFB2-6AC4-4F9D-A917-308D52492513"));

    private final String tag;
    private final UUID characteristicUuid;

    SigningReadCharacteristicType(String tag, UUID characteristicUuid) {
        this.tag = tag;
        this.characteristicUuid = characteristicUuid;
    }

    public String getTag() {
        return tag;
    }

    public UUID getCharacteristicUuid() {
        return characteristicUuid;
    }
}
