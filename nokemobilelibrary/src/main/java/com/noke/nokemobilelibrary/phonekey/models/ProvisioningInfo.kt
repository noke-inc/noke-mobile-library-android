package com.noke.nokemobilelibrary.phonekey.models

import org.threeten.bp.Instant

/**
 * Provisioning state information for a phone key.
 *
 * Represents the state of a provisioned phone key, including its unique identifier
 * and validity period. This information is stored locally after successful provisioning
 * and is used for subsequent ACL operations.
 *
 * ## Lifecycle
 * 1. **Provisioning**: Backend generates [phoneKeyId] and assigns validity period
 * 2. **Storage**: ProvisioningInfo is stored in encrypted SharedPreferences
 * 3. **Usage**: [phoneKeyId] is used for ACL fetch operations
 * 4. **Expiration**: After [expiresAt], the phone key must be re-provisioned
 *
 * ## Storage Location
 * Stored in encrypted SharedPreferences at:
 * ```
 * Key: "provisioning_info"
 * Value: JSON-serialized ProvisioningInfo
 * ```
 *
 * ## Expiration Handling
 * - If [expiresAt] is null, the phone key never expires (common for device keys)
 * - If [expiresAt] is set, the app should monitor expiration and re-provision when needed
 * - Expired phone keys will fail ACL fetch operations with authentication errors
 *
 * ## Usage
 * ```kotlin
 * val info = ProvisioningInfo(
 *     phoneKeyId = "67890",
 *     issuedAt = Instant.now(),
 *     expiresAt = Instant.now().plusDays(365)
 * )
 *
 * // Check expiration
 * val isExpired = info.expiresAt?.let { it < Instant.now() } ?: false
 *
 * if (isExpired) {
 *     // Re-provision phone key
 * }
 * ```
 *
 * @property phoneKeyId The unique identifier assigned by the backend after provisioning
 * @property issuedAt Timestamp when the phone key was issued
 * @property expiresAt Optional expiration timestamp for the phone key (null = never expires)
 *
 * @see PhoneKeyAcl
 * @see AclEnvelope
 */
data class ProvisioningInfo(
    val phoneKeyId: String,
    val issuedAt: Instant,
    val expiresAt: Instant?
)
