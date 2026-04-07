package com.noke.nokemobilelibrary.phonekey.models

/**
 * Simplified ACL envelope from bulk ACL fetch API
 * Contains only essential fields without permissions/schedule/trackingId
 * The aclBinary contains all the actual ACL data needed for lock operations
 */
data class BulkAclEnvelope(
    val lockMac: String,
    val aclBinary: String,
    val aclSignature: String,
    val issuedAt: Long,  // Unix epoch seconds
    val expiresAt: Long,  // Unix epoch seconds
    val storedAt: Long = System.currentTimeMillis()
)
