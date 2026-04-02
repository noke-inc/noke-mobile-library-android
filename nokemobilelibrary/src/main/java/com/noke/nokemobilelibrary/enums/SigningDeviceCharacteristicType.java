package com.noke.nokemobilelibrary.enums;

import java.util.UUID;

/**
 * Utility class providing the service UUID for ION-2 signing device characteristics.
 * This class cannot be instantiated.
 */
public final class SigningDeviceCharacteristicType {
    private SigningDeviceCharacteristicType() {
        throw new AssertionError("Cannot instantiate utility class");
    }
    
    public static UUID getServiceUuid() {
        return UUID.fromString("AE82FFB0-6AC4-4F9D-A917-308D52492513");
    }
}
