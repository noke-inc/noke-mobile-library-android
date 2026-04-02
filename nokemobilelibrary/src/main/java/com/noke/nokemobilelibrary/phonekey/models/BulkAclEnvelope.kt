package com.noke.nokemobilelibrary.phonekey.models

/**
 * Simplified ACL envelope from bulk ACL fetch API.
 *
 * Unlike [AclEnvelope], this contains only essential fields without the full ACL object
 * (no permissions, schedule, or trackingId). The [aclBinary] field contains all the
 * actual ACL data needed for lock operations in binary-encoded format.
 *
 * ## Why Simplified?
 * Bulk ACL fetches return hundreds of ACLs. By omitting redundant fields and providing
 * only the binary representation, the payload size is significantly reduced, improving
 * network efficiency and storage footprint.
 *
 * ## Storage
 * Bulk ACL envelopes are stored in encrypted SharedPreferences with a per-lock key:
 * ```
 * Key: "bulk_acl_{lockMac}"
 * Value: JSON-serialized BulkAclEnvelope
 * ```
 *
 * ## Validation
 * The [aclSignature] field allows the lock to verify the ACL hasn't been tampered with.
 * The lock validates the signature using its own public key.
 *
 * ## Usage
 * ```kotlin
 * val envelope = BulkAclEnvelope(
 *     lockMac = "AA:BB:CC:DD:EE:FF",
 *     aclBinary = "base64EncodedBinaryAcl...",
 *     aclSignature = "base64EncodedSignature...",
 *     issuedAt = Instant.now().epochSecond,
 *     expiresAt = Instant.now().plusDays(30).epochSecond
 * )
 *
 * // Check if expired
 * val isExpired = envelope.expiresAt < Instant.now().epochSecond
 * ```
 *
 * @property lockMac MAC address of the lock (e.g., "AA:BB:CC:DD:EE:FF")
 * @property aclBinary Base64-encoded binary ACL data (contains all info for lock operations)
 * @property aclSignature Base64-encoded ECDSA signature for tamper prevention
 * @property issuedAt Unix epoch seconds when this ACL was issued
 * @property expiresAt Unix epoch seconds when this ACL expires
 * @property storedAt Unix epoch milliseconds when this envelope was stored locally (for cache management)
 *
 * @see AclEnvelope
 * @see BulkAclResponse
 */
data class BulkAclEnvelope(
    val lockMac: String,
    val aclBinary: String,
    val aclSignature: String,
    val issuedAt: Long,  // Unix epoch seconds
    val expiresAt: Long,  // Unix epoch seconds
    val storedAt: Long = System.currentTimeMillis()  // Unix epoch milliseconds
)

/**
 * Response from bulk ACL fetch API.
 *
 * Contains the result status and a list of simplified ACL envelopes.
 *
 * ## Backend API
 * This mirrors the response from the bulk ACL endpoint:
 * ```
 * GET /api/phone-keys/{phoneKeyId}/acls
 * Response: { "result": "success", "acls": [...] }
 * ```
 *
 * ## Empty Results
 * If the user has no accessible locks, [acls] will be an empty list with result="success".
 * If the backend encounters an error, result will indicate the error type.
 *
 * ## Usage
 * ```kotlin
 * val response = BulkAclResponse(
 *     result = "success",
 *     acls = listOf(
 *         BulkAclEnvelope(...),
 *         BulkAclEnvelope(...)
 *     )
 * )
 *
 * when (response.result) {
 *     "success" -> Log.d(TAG, "Fetched ${response.acls.size} ACLs")
 *     else -> Log.e(TAG, "Bulk fetch failed: ${response.result}")
 * }
 * ```
 *
 * @property result Result status string from backend (e.g., "success", "error", "unauthorized")
 * @property acls List of simplified ACL envelopes
 *
 * @see BulkAclEnvelope
 * @see AclResponse
 */
data class BulkAclResponse(
    val result: String,
    val acls: List<BulkAclEnvelope>
)
