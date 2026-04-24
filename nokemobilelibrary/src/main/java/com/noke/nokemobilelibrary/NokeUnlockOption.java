package com.noke.nokemobilelibrary;

/**
 * Represents the type of unlock operation being performed.
 * Mirrors UnlockOperationOptions from Kotlin.
 * 
 * This enum is used to determine if special unlock permissions (emergency or override)
 * are required before attempting to unlock a lock. This helps prevent unauthorized
 * offline unlock attempts when special authorization is needed.
 */
public enum NokeUnlockOption {
    /**
     * Standard unlock operation with normal access permissions
     */
    NONE,
    
    /**
     * Override unlock with PIN - requires AccessOverrideUnlock permission
     * Used when tenant has locked themselves out and manager needs to unlock with PIN
     */
    OVERRIDE,
    
    /**
     * Emergency unlock with reason - requires AccessEmergencyUnlock permission  
     * Used in emergency situations (fire, flood, tenant locked in, etc.)
     */
    EMERGENCY,
    /**
     * Online unlock - requires AccessOnlineUnlock permission
     * Used when tenant is present and can authenticate online, but lock is offline
     */
    ONLINE
}
