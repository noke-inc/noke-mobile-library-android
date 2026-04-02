package com.noke.nokemobilelibrary.enums;

public enum NokeDeviceSigningStatus {
    TIME_SYNCED("TIME_SYNCED"),
    ACL_VALIDATED("ACL_VALIDATED"),
    ACL_RECEIVED("ACL_RECEIVED"),
    ACL_REJECTED("ACL_REJECTED"),
    ACLSIG_VERIFIED("ACLSIG_VERIFIED"),
    ACLSIG_VERIFY_FAIL("ACLSIG_VERIFY_FAIL"),
    ACL_SETUP_ERR("ACL_SETUP_ERR"),
    ACL_SCHEDULE_BLOCKED("ACL_SCHEDULE_BLOCKED"),
    ACL_TIME_EXPIRED("ACL_TIME_EXPIRED"),
    CMD_RECEIVED("CMD_RECEIVED"),
    UNLOCK_EXECUTED("UNLOCK_EXECUTED"),
    UNLOCK_DENIED("UNLOCK_DENIED"),
    UNLOCK_OVERLOCKED("UNLOCK_OVERLOCKED"),
    LOCK_LOCKED("LOCK_LOCKED"),
    LOCK_ALREADY_UNLOCKED("LOCK_ALREADY_UNLOCKED");

    private final String value;

    NokeDeviceSigningStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    // Optional: reverse lookup (String → enum)
    public static NokeDeviceSigningStatus fromValue(String input) {
        for (NokeDeviceSigningStatus s : values()) {
            if (s.value.equalsIgnoreCase(input)) {
                return s;
            }
        }
        return null; // or throw IllegalArgumentException
    }
}
