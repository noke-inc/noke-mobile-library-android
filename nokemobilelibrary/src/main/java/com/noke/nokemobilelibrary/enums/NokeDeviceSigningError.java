package com.noke.nokemobilelibrary.enums;

import com.noke.nokemobilelibrary.NokeDeviceSigningException;

public enum NokeDeviceSigningError {

    MISSING_COMMAND_ID_CHARACTERITIC(
            "Empty commands",
            false,
            false,
            false
    ),

    INVALID_COMMAND_ID(
            "Your command is invalid. Please try again.",
            false,
            false,
            false
    ),

    INVALID_SIGNATURE(
            "Error fetching commands from the server",
            false,
            true,
            false
    ),

    MISSING_ACL(
            "Sorry, you don't have access to unlock this device.",
            false,
            false,
            false
    ),

    ACL_REJECTED(
            "Sorry, your access to this device has either expired or is invalid.",
            false,
            true,
            false
    ),

    INVALID_ACL_SIGNATURE(
            "Error fetching commands from the server",
            false,
            true,
            false
    ),

    PERIPHERAL_NOT_FOUND(
            "Could not find device locally",
            true,
            false,
            false
    ),

    CHARACTERISTIC_NOT_FOUND(
            "We weren't able to find your device. It's possible it's not broadcasting on Bluetooth.",
            false,
            false,
            false
    ),

    OFFLINE_UNLOCK_TIMEOUT(
            "Offline unlock commands were sent, but did not receive a response",
            false,
            false,
            true
    ),

    OUT_OF_SCHEDULE(
            "Out of Schedule",
            false,
            true,
            false
    ),

    REQUIRES_EMERGENCY_UNLOCK(
      "This operation requires an emergency unlock.",
      false,
      false,
      true
    ),
    REQUIRES_OVERRIDE_UNLOCK(
      "This operation requires an override unlock.",
      false,
      false,
      true
    ),

    UNLOCK_DENIED(
            "You don't have access to unlock this device.",
            false,
            true,
            false
    ),

    UNLOCK_OVERLOCKED(
            "Your unit is currently in overlock. Please make a payment to gain access.",
            false,
            true,
            false
    ),

    LOCK_LOCKED(
            "You don't have access to lock this device.",
            false,
            true,
            false
    ),

    LOCK_ALREADY_UNLOCKED(
            "This device is already unlocked.",
            false,
            false,
            true
    ),

    COMMAND_SIGNATURE_FAILED(
            "Command signature failed",
            false,
            false,
            false
    ),

    CMDSIG_VERIFY_FAIL(
            "Command signature failed",
            false,
            false,
            false
    ),

    TIME_OFFSET_ERROR(
            "Time offset error",
            false,
            false,
            false
    ),

    ACL_TIME_INVALID(
            "ACL time invalid",
            false,
            true,
            false
    ),

    UNKNOWN(
            "An unknown error occurred",
            false,
            false,
            false
    );

    private final String description;
    private final boolean isValid;
    private final boolean shouldRefreshKeys;
    private final boolean willUseFallback;

    NokeDeviceSigningError(
            String description,
            boolean isValid,
            boolean shouldRefreshKeys,
            boolean willUseFallback
    ) {
        this.description = description;
        this.isValid = isValid;
        this.shouldRefreshKeys = shouldRefreshKeys;
        this.willUseFallback = willUseFallback;
    }


    public String getDescription() { return description; }
    public boolean isValid() { return isValid; }
    public boolean shouldRefreshKeys() { return shouldRefreshKeys; }
    public boolean willUseFallback() { return willUseFallback; }

    /** Create a checked exception you can `throw` and `catch`. */
    public NokeDeviceSigningException asException() {
        return new NokeDeviceSigningException(this);
    }

    /**
     * Create an unchecked (runtime) exception for scenarios where checked exceptions cannot be used.
     * 
     * <p><b>WARNING:</b> Use with caution. This converts a checked error into an unchecked exception,
     * which may bypass proper error handling. Prefer {@link #asException()} and Result types.
     * Only use this in callback-based code or where checked exceptions are not possible.
     * 
     * @return RuntimeException wrapping the error description
     */
    public RuntimeException asRuntime() {
        return new RuntimeException(getDescription());
    }

}
