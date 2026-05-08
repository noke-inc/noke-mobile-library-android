package com.noke.nokemobilelibrary.phonekey.models

/**
 * Envelope containing an ACL with signature and metadata for verification.
 *
 * Unlike [BulkAclEnvelope], this contains the full [PhoneKeyAcl] object with all
 * permissions, schedule details, and tracking information. This is used for
 * single-ACL fetch operations where the complete ACL data is needed.
 *
 * ## Signature Verification
 * The [aclSignature] is a Base64-encoded ECDSA signature computed by the backend
 * using the lock's private key. The lock verifies this signature before granting
 * access, ensuring the ACL hasn't been tampered with.
 *
 * ## Storage
 * ACL envelopes are stored in encrypted SharedPreferences with a per-lock key:
 * ```
 * Key: "acl_{lockMac}"
 * Value: JSON-serialized AclEnvelope
 * ```
 *
 * ## When to Use
 * - Single-lock ACL refresh after unlock failure
 * - Fetching ACL with full metadata for debugging/logging
 * - When you need access to permissions/schedule details
 *
 * For most use cases (bulk synchronization), prefer [BulkAclEnvelope] which is
 * more network-efficient.
 *
 * ## Usage
 * ```kotlin
 * val envelope = AclEnvelope(
 *     acl = PhoneKeyAcl(...),
 *     aclSignature = "base64EncodedSignature...",
 *     aclBinary = "base64EncodedBinary..."
 * )
 *
 * // Check expiration
 * val isExpired = envelope.acl.expiresAt < Instant.now().epochSecond
 *
 * // Access permissions
 * val canUnlock = envelope.acl.permissions.contains(AclPermission.unlock)
 * ```
 *
 * @property acl The complete phone key ACL with permissions, schedule, and metadata
 * @property aclSignature Base64-encoded ECDSA signature for tamper prevention
 * @property aclBinary Base64-encoded binary representation of the ACL
 * @property storedAt Unix epoch milliseconds when this envelope was stored locally
 *
 * @see PhoneKeyAcl
 * @see BulkAclEnvelope
 */
data class AclEnvelope(
    val acl: PhoneKeyAcl,
    val aclSignature: String,
    val aclBinary: String,
    val storedAt: Long = System.currentTimeMillis()
)
