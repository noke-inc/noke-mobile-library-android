package com.noke.nokemobilelibrary.phonekey.models

/**
 * Enum representing the permissions granted in a Phone Key ACL.
 *
 * Each permission controls specific lock operations:
 * - **unlock**: Standard unlock operation
 * - **overrideOverlock**: Can override overlocking state
 * - **manage_locks**: Administrative access for lock management
 *
 * Permissions are encoded as a bitmask in the binary ACL representation.
 */
enum class AclPermission {
    /** Standard unlock permission */
    unlock,
    
    /** Permission to override overlocking state */
    overrideOverlock,
    
    /** Administrative permission for lock management */
    manage_locks
}
