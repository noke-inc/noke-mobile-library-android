package com.noke.nokemobilelibrary.phonekey.models

/**
 * Response wrapper for a single ACL fetch operation.
 *
 * Contains the result status, the ACL data, and cryptographic signature/binary representation.
 *
 * ## Usage
 * This is typically returned by backend APIs when fetching a single ACL.
 * For bulk operations, use [BulkAclResponse] instead.
 *
 * @property result Result status string from backend (e.g., "success", "error")
 * @property acl The phone key ACL with full permissions and schedule
 * @property aclSignature Base64-encoded ECDSA signature from the lock
 * @property aclBinary Base64-encoded binary representation of the ACL
 *
 * @see PhoneKeyAcl
 * @see BulkAclResponse
 */
data class AclResponse(
    val result: String,
    val acl: PhoneKeyAcl,
    val aclSignature: String,
    val aclBinary: String
)
