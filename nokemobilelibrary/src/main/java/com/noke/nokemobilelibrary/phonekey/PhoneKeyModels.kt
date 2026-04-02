package com.noke.nokemobilelibrary.phonekey

import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope

/**
 * Extension functions and convenience helpers for phone key models.
 *
 * Provides computed properties similar to iOS PhoneKeyModels extensions:
 * - `isValid`: Check if ACL is currently valid (not expired)
 * - `isExpired`: Check if ACL has passed expiration time
 * - `timeUntilExpiration`: Calculate milliseconds until expiration
 *
 * ## Design Philosophy
 *
 * These extensions follow the TIDY Architecture principle of making common
 * patterns simple and explicit. Rather than repeating expiration checks,
 * developers can use `acl.isValid` for clarity.
 *
 * ## Usage Example
 *
 * ```kotlin
 * val acls = phoneKeyFacade.listValidACLs()
 * acls.forEach { acl ->
 *     if (acl.isValid) {
 *         println("${acl.lockMac} expires in ${acl.timeUntilExpiration} ms")
 *     }
 * }
 * ```
 */

// MARK: - BulkAclEnvelope Extensions

/**
 * Check if ACL is currently valid (not expired).
 *
 * @return true if current time < expiresAt, false otherwise
 */
val BulkAclEnvelope.isValid: Boolean
    get() {
        val now = System.currentTimeMillis() / 1000  // Convert to seconds
        return now < expiresAt
    }

/**
 * Check if ACL has expired.
 *
 * @return true if current time >= expiresAt, false otherwise
 */
val BulkAclEnvelope.isExpired: Boolean
    get() = !isValid

/**
 * Calculate time until expiration.
 *
 * @return milliseconds until expiration, or 0 if already expired
 */
val BulkAclEnvelope.timeUntilExpiration: Long
    get() {
        val now = System.currentTimeMillis() / 1000  // Convert to seconds
        val remainingSeconds = expiresAt - now
        return if (remainingSeconds > 0) remainingSeconds * 1000 else 0
    }

/**
 * Human-readable status message for ACL.
 *
 * @return Status string indicating validity or expiration
 */
val BulkAclEnvelope.statusMessage: String
    get() = when {
        isValid -> "Valid until ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(expiresAt * 1000))}"
        else -> "Expired at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(expiresAt * 1000))}"
    }
