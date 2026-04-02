package com.noke.nokemobilelibrary.enums;

import com.noke.nokemobilelibrary.interfaces.CharacteristicType;

import java.util.UUID;

/**
 * Noke signing device Write Characteristics
 */

public enum SigningWriteCharacteristicType implements CharacteristicType {
    TIME("NokeDeviceManagerService_writeTimeCharacteristic", UUID.fromString("AE82FFB6-6AC4-4F9D-A917-308D52492513")),
    ACL("NokeDeviceManagerService_writeAclCharacteristic", UUID.fromString("AE82FFB1-6AC4-4F9D-A917-308D52492513")),
    ACL_SIGNATURE("NokeDeviceManagerService_writeAclSignatureCharacteristic", UUID.fromString("AE82FFB5-6AC4-4F9D-A917-308D52492513")),
    COMMAND("NokeDeviceManagerService_writeCommandCharacteristic", UUID.fromString("AE82FFB3-6AC4-4F9D-A917-308D52492513")),
    COMMAND_SIGNATURE("NokeDeviceManagerService_writeCommandSignatureCharacteristic", UUID.fromString("AE82FFB7-6AC4-4F9D-A917-308D52492513"));

    private final String tag;
    private final UUID characteristicUuid;

    SigningWriteCharacteristicType(String tag, UUID characteristicUuid) {
        this.tag = tag;
        this.characteristicUuid = characteristicUuid;
    }

    public String getTag() {
        return tag;
    }

    public UUID getCharacteristicUuid() {
        return characteristicUuid;
    }

    public UUID getServiceUUID() {
        return SigningDeviceCharacteristicType.getServiceUuid();
    }
}
