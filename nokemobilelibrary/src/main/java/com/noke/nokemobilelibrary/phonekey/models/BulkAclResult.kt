package com.noke.nokemobilelibrary.phonekey.models

/**
 * Result of a bulk ACL fetch operation.
 *
 * Represents the outcome of fetching multiple ACLs in a single request, including
 * information about how many ACLs were successfully stored vs. total available.
 *
 * ## Partial Success Handling
 * Bulk ACL operations can experience partial success/failure:
 * - **Full Success**: All ACLs fetched and stored ([isFullSuccess] = true)
 * - **Partial Success**: Some ACLs stored, some failed ([hasPartialSuccess] = true)
 * - **Complete Failure**: No ACLs stored ([isFailure] = true)
 * - **Empty Result**: No ACLs available ([isEmpty] = true)
 *
 * Partial failures typically occur due to:
 * - Storage errors (insufficient space, EncryptedSharedPreferences issues)
 * - Malformed ACL data from backend
 * - Signature verification failures
 *
 * ## Usage
 * ```kotlin
 * val result = BulkAclResult(successCount = 42, totalCount = 50)
 *
 * when {
 *     result.isFullSuccess -> 
 *         Log.d(TAG, "All ${result.totalCount} ACLs stored successfully")
 *     
 *     result.hasPartialSuccess -> 
 *         Log.w(TAG, "Partial success: ${result.successCount}/${result.totalCount} stored")
 *     
 *     result.isFailure -> 
 *         Log.e(TAG, "All ACL storage failed (${result.totalCount} ACLs)")
 *     
 *     result.isEmpty -> 
 *         Log.i(TAG, "No ACLs available for this user")
 * }
 * ```
 *
 * @property successCount Number of ACLs successfully fetched and stored (0 to totalCount)
 * @property totalCount Total number of ACLs returned by backend
 *
 * @see BulkAclEnvelope
 * @see BulkAclResponse
 */
data class BulkAclResult(
    val successCount: Int,
    val totalCount: Int
) {
    /**
     * True if ALL ACLs were stored successfully AND at least one ACL was available.
     *
     * Note: Returns false if totalCount is 0 (use [isEmpty] to check for no ACLs).
     */
    val isFullSuccess: Boolean = successCount == totalCount && totalCount > 0
    
    /**
     * True if SOME (but not all) ACLs were stored successfully.
     *
     * Indicates partial failure - some ACLs are available but storage errors occurred.
     */
    val hasPartialSuccess: Boolean = successCount > 0 && successCount < totalCount
    
    /**
     * True if NO ACLs were stored despite some being available.
     *
     * This indicates complete failure - all ACL storage operations failed.
     * Note: Returns false if totalCount is 0 (use [isEmpty] for that case).
     */
    val isFailure: Boolean = successCount == 0 && totalCount > 0
    
    /**
     * True if the backend returned no ACLs (user has no accessible locks).
     *
     * This is a successful operation with an empty result set, not a failure.
     */
    val isEmpty: Boolean = totalCount == 0
}
