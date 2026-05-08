package com.noke.nokemobilelibrary.phonekey

import android.util.Log
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Coroutine-based extension functions for [PhoneKeyManager].
 * 
 * These extensions wrap internal PhoneKeyManager methods with modern Kotlin suspend functions,
 * providing structured concurrency support while maintaining compatibility with the callback-based
 * internal API.
 * 
 * ## Thread Safety
 * All suspend functions are thread-safe and can be called from any coroutine context.
 * They will automatically dispatch to appropriate background threads via the underlying
 * PhoneKeyManager and SecurityService implementations.
 * 
 * ## Error Handling
 * All errors are wrapped in [NokeMobileLibraryError] subtypes and returned as [Result] objects.
 * Callers can use [Result.getOrThrow], [Result.getOrNull], or pattern matching on the Result.
 * 
 * ## Cancellation
 * All suspend functions properly support coroutine cancellation. If the calling coroutine
 * is cancelled, the underlying callback-based operation will be cancelled if possible.
 * 
 * @see PhoneKeyManager
 * @see NokeMobileLibraryError
 */
private const val TAG = "PhoneKeyManagerExt"

/**
 * Provision phone key with backend using suspend function.
 * 
 * This suspending version generates an ECDSA P-256 key pair in Android Keystore,
 * sends the public key to the backend, and stores the resulting phone key ID.
 * 
 * ## Process
 * 1. Ensures ECDSA P-256 key pair exists in Android Keystore
 * 2. Extracts public key in X9.62 format (Base64)
 * 3. Sends provisioning request to backend with (udid, publicKey, userId)
 * 4. Stores phone key ID and provisioning metadata on success
 * 
 * ## Thread Safety
 * This function is thread-safe and can be called from any coroutine context.
 * The underlying PhoneKeyManager and SecurityService handle thread dispatching.
 * 
 * ## Cancellation
 * Supports coroutine cancellation. If cancelled before the backend responds,
 * the coroutine will be cancelled but the network request may still complete.
 * 
 * ## Idempotency
 * Safe to call multiple times. If already provisioned, the existing phone key ID
 * will be returned. To re-provision, first call [PhoneKeyManager.clear] or create
 * a new PhoneKeyManager instance.
 * 
 * @receiver PhoneKeyManager instance (must have userId and udid set)
 * @param userId User identifier (must match manager's userId)
 * @param udid Unique device identifier (must match manager's udid)
 * @return [Result] containing the phone key ID on success, or [NokeMobileLibraryError] on failure
 * 
 * ## Error Types
 * - [NokeMobileLibraryError.InvalidInput] - Empty userId or udid
 * - [NokeMobileLibraryError.CryptographicError] - Key generation failed
 * - [NokeMobileLibraryError.NetworkError] - Network request failed
 * - [NokeMobileLibraryError.ProvisioningFailed] - Backend rejected provisioning or storage failed
 * - [NokeMobileLibraryError.StorageError] - Failed to persist provisioning state
 * 
 * ## Example
 * ```kotlin
 * val result = manager.provisionPhoneKeySuspend(userId, udid)
 * 
 * result.fold(
 *     onSuccess = { phoneKeyId ->
 *         Log.d(TAG, "Provisioned with keyId: $phoneKeyId")
 *     },
 *     onFailure = { error ->
 *         when (error) {
 *             is NokeMobileLibraryError.NetworkError -> 
 *                 Log.e(TAG, "Network error, retry later")
 *             is NokeMobileLibraryError.ProvisioningFailed ->
 *                 Log.e(TAG, "Provisioning rejected: ${error.reason}")
 *             else -> 
 *                 Log.e(TAG, "Unexpected error: $error")
 *         }
 *     }
 * )
 * ```
 */
internal suspend fun PhoneKeyManager.provisionPhoneKeySuspend(
    userId: String,
    udid: String
): Result<Int> = suspendCancellableCoroutine { continuation ->
    // Input validation
    if (userId.isEmpty()) {
        continuation.resume(
            Result.failure(NokeMobileLibraryError.InvalidInput("userId cannot be empty"))
        )
        return@suspendCancellableCoroutine
    }
    
    if (udid.isEmpty()) {
        continuation.resume(
            Result.failure(NokeMobileLibraryError.InvalidInput("udid cannot be empty"))
        )
        return@suspendCancellableCoroutine
    }
    
    try {
        // Ensure keys are generated (may throw CryptographicError)
        ensureKeys()
        val publicKeyBase64 = getPublicKeyBase64()
        
        Log.d(TAG, "Starting phone key provisioning for user=$userId, device=$udid")
        
        // Call callback-based provisioning
        provisionPhoneCompletion(udid, publicKeyBase64, userId) { keyId ->
            when {
                continuation.isActive && keyId != null -> {
                    Log.d(TAG, "Provisioning succeeded with keyId=$keyId")
                    continuation.resume(Result.success(keyId))
                }
                continuation.isActive && keyId == null -> {
                    Log.e(TAG, "Provisioning failed: backend returned null keyId")
                    continuation.resume(
                        Result.failure(
                            NokeMobileLibraryError.ProvisioningFailed(
                                "Backend provisioning failed or storage error occurred"
                            )
                        )
                    )
                }
                else -> {
                    Log.d(TAG, "Provisioning callback invoked but continuation already completed (cancelled)")
                }
            }
        }
    } catch (e: IOException) {
        Log.e(TAG, "Storage error during provisioning", e)
        continuation.resume(
            Result.failure(
                NokeMobileLibraryError.StorageError("provisioning", e)
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error during provisioning", e)
        continuation.resume(
            Result.failure(
                when {
                    e.message?.contains("keystore", ignoreCase = true) == true ||
                    e.message?.contains("key generation", ignoreCase = true) == true ->
                        NokeMobileLibraryError.CryptographicError("key generation", e)
                    else ->
                        NokeMobileLibraryError.UnknownError("provisioning", e)
                }
            )
        )
    }
}

/**
 * Fetch ACL (Access Control List) for a specific lock using suspend function.
 * 
 * This suspending version retrieves the ACL envelope from the backend,
 * validates the signature, and stores it locally.
 * 
 * ## Process
 * 1. Sends ACL request to backend with (userId, lockMac, phoneKeyId)
 * 2. Receives ACL envelope with signature and binary representation
 * 3. Stores envelope locally in encrypted storage
 * 
 * ## When to Use
 * Use this for **single-lock ACL refresh** scenarios:
 * - After unlock failure (refetch ACL)
 * - When specifically updating one lock's permissions
 * 
 * For most use cases, prefer [getBulkAclsSuspend] which fetches all ACLs
 * in a single request (more efficient).
 * 
 * ## Thread Safety
 * This function is thread-safe and can be called from any coroutine context.
 * 
 * ## Cancellation
 * Supports coroutine cancellation. If cancelled before the backend responds,
 * the coroutine will be cancelled but the network request may still complete.
 * 
 * @receiver PhoneKeyManager instance
 * @param userId User ID (as Int, matching backend API)
 * @param lockMac Lock MAC address (e.g., "AA:BB:CC:DD:EE:FF")
 * @param phoneKeyId Phone key ID from provisioning
 * @return [Result] with Unit on success, or [NokeMobileLibraryError] on failure
 * 
 * ## Error Types
 * - [NokeMobileLibraryError.InvalidInput] - Empty lockMac or invalid IDs
 * - [NokeMobileLibraryError.NetworkError] - Network request failed
 * - [NokeMobileLibraryError.AclFetchFailed] - Backend rejected request
 * - [NokeMobileLibraryError.AclStorageFailed] - ACL received but storage failed
 * 
 * ## Example
 * ```kotlin
 * val result = manager.getAclSuspend(
 *     userId = 12345,
 *     lockMac = "AA:BB:CC:DD:EE:FF",
 *     phoneKeyId = 67890
 * )
 * 
 * result.fold(
 *     onSuccess = { 
 *         Log.d(TAG, "ACL fetched and stored successfully")
 *     },
 *     onFailure = { error ->
 *         Log.e(TAG, "ACL fetch failed: $error")
 *     }
 * )
 * ```
 */
internal suspend fun PhoneKeyManager.getAclSuspend(
    userId: Int,
    lockMac: String,
    phoneKeyId: Int
): Result<Unit> = suspendCancellableCoroutine { continuation ->
    // Input validation
    if (lockMac.isEmpty()) {
        continuation.resume(
            Result.failure(NokeMobileLibraryError.InvalidInput("lockMac cannot be empty"))
        )
        return@suspendCancellableCoroutine
    }
    
    if (userId <= 0) {
        continuation.resume(
            Result.failure(NokeMobileLibraryError.InvalidInput("userId must be positive"))
        )
        return@suspendCancellableCoroutine
    }
    
    if (phoneKeyId <= 0) {
        continuation.resume(
            Result.failure(NokeMobileLibraryError.InvalidInput("phoneKeyId must be positive"))
        )
        return@suspendCancellableCoroutine
    }
    
    Log.d(TAG, "Fetching ACL for lock=$lockMac, userId=$userId, phoneKeyId=$phoneKeyId")
    
    // Call callback-based ACL fetch
    getAcl(userId, lockMac, phoneKeyId) { success ->
        when {
            continuation.isActive && success -> {
                Log.d(TAG, "ACL fetch succeeded for lock=$lockMac")
                continuation.resume(Result.success(Unit))
            }
            continuation.isActive && !success -> {
                Log.e(TAG, "ACL fetch failed for lock=$lockMac")
                continuation.resume(
                    Result.failure(
                        NokeMobileLibraryError.AclFetchFailed(
                            lockMac = lockMac,
                            reason = "Backend request failed or storage error occurred"
                        )
                    )
                )
            }
            else -> {
                Log.d(TAG, "ACL fetch callback invoked but continuation already completed (cancelled)")
            }
        }
    }
}

/**
 * Fetch bulk ACLs for all locks accessible to the user using suspend function.
 * 
 * This is the **preferred method** for fetching ACLs in most scenarios:
 * - On successful login
 * - After provisioning
 * - On app startup (if user already logged in)
 * - Explicit ACL refresh
 * 
 * This suspending version retrieves all ACL envelopes in a single request,
 * which is much more efficient than fetching individual ACLs one-by-one.
 * 
 * ## Process
 * 1. Sends bulk ACL request to backend with phoneKeyId
 * 2. Receives list of simplified ACL envelopes (without full permissions/schedule)
 * 3. Stores each envelope locally
 * 4. Returns count of successful vs. total ACLs
 * 
 * ## Bulk ACL Format
 * Bulk ACLs are stored in simplified format without permissions/schedule details.
 * The `aclBinary` field contains all data needed for lock operations.
 * 
 * ## Partial Success
 * This function handles partial success gracefully. If some ACLs fail to store,
 * the result will indicate which ones succeeded. Check [BulkAclResult.isFullSuccess]
 * or [BulkAclResult.hasPartialSuccess] to determine the outcome.
 * 
 * ## Thread Safety
 * This function is thread-safe and can be called from any coroutine context.
 * 
 * ## Cancellation
 * Supports coroutine cancellation. If cancelled before the backend responds,
 * the coroutine will be cancelled but the network request may still complete.
 * 
 * @receiver PhoneKeyManager instance
 * @param phoneKeyId Phone key ID from provisioning
 * @return [Result] containing [BulkAclResult] on success, or [NokeMobileLibraryError] on complete failure
 * 
 * ## Error Types
 * - [NokeMobileLibraryError.InvalidInput] - Invalid phoneKeyId
 * - [NokeMobileLibraryError.NetworkError] - Network request failed
 * - [NokeMobileLibraryError.BulkAclFetchFailed] - Backend rejected request
 * 
 * Note: Partial storage failures are reflected in [BulkAclResult], not thrown as errors.
 * 
 * ## Example
 * ```kotlin
 * val result = manager.getBulkAclsSuspend(phoneKeyId = 67890)
 * 
 * result.fold(
 *     onSuccess = { bulkResult ->
 *         when {
 *             bulkResult.isFullSuccess -> 
 *                 Log.d(TAG, "All ${bulkResult.totalCount} ACLs stored successfully")
 *             bulkResult.hasPartialSuccess -> 
 *                 Log.w(TAG, "Partial success: ${bulkResult.successCount}/${bulkResult.totalCount} stored")
 *             bulkResult.isEmpty -> 
 *                 Log.i(TAG, "No ACLs available for this user")
 *             else -> 
 *                 Log.e(TAG, "All ACL storage failed")
 *         }
 *     },
 *     onFailure = { error ->
 *         Log.e(TAG, "Bulk ACL fetch failed completely: $error")
 *     }
 * )
 * ```
 */
internal suspend fun PhoneKeyManager.getBulkAclsSuspend(
    phoneKeyId: Int
): Result<BulkAclResult> = suspendCancellableCoroutine { continuation ->
    // Input validation
    if (phoneKeyId <= 0) {
        continuation.resume(
            Result.failure(NokeMobileLibraryError.InvalidInput("phoneKeyId must be positive"))
        )
        return@suspendCancellableCoroutine
    }
    
    Log.d(TAG, "Fetching bulk ACLs for phoneKeyId=$phoneKeyId")
    
    // Call callback-based bulk ACL fetch
    getBulkAcls(phoneKeyId) { successCount, totalCount ->
        if (!continuation.isActive) {
            Log.d(TAG, "Bulk ACL fetch callback invoked but continuation already completed (cancelled)")
            return@getBulkAcls
        }
        
        val result = BulkAclResult(successCount, totalCount)
        
        when {
            totalCount == 0 -> {
                // Empty result - could be no ACLs available or backend error
                // Treat as success with empty result
                Log.i(TAG, "Bulk ACL fetch returned no ACLs")
                continuation.resume(Result.success(result))
            }
            successCount == 0 && totalCount > 0 -> {
                // Complete failure - all ACLs failed to store
                Log.e(TAG, "Bulk ACL fetch failed: 0/$totalCount ACLs stored")
                continuation.resume(
                    Result.failure(
                        NokeMobileLibraryError.BulkAclFetchFailed(
                            phoneKeyId = phoneKeyId,
                            reason = "All $totalCount ACLs failed to store locally"
                        )
                    )
                )
            }
            successCount < totalCount -> {
                // Partial success
                Log.w(TAG, "Bulk ACL fetch partial success: $successCount/$totalCount ACLs stored")
                continuation.resume(Result.success(result))
            }
            else -> {
                // Full success
                Log.d(TAG, "Bulk ACL fetch succeeded: $successCount/$totalCount ACLs stored")
                continuation.resume(Result.success(result))
            }
        }
    }
}

/**
 * Refresh all ACLs by fetching bulk ACLs from backend using suspend function.
 * 
 * This is a convenience method that calls [getBulkAclsSuspend] using the
 * stored phone key ID from provisioning.
 * 
 * ## Use Cases
 * - Manual ACL refresh triggered by user
 * - Periodic background refresh
 * - After ACL changes detected server-side
 * 
 * ## Requirements
 * Phone key must be provisioned before calling this method. If not provisioned,
 * returns [NokeMobileLibraryError.NotProvisioned].
 * 
 * @receiver PhoneKeyManager instance
 * @return [Result] containing [BulkAclResult] on success, or [NokeMobileLibraryError] on failure
 * 
 * ## Error Types
 * - [NokeMobileLibraryError.NotProvisioned] - Phone key not provisioned yet
 * - All errors from [getBulkAclsSuspend]
 * 
 * ## Example
 * ```kotlin
 * val result = manager.refreshAllAclsSuspend()
 * 
 * result.fold(
 *     onSuccess = { bulkResult ->
 *         Log.d(TAG, "Refreshed ${bulkResult.successCount} ACLs")
 *     },
 *     onFailure = { error ->
 *         when (error) {
 *             is NokeMobileLibraryError.NotProvisioned ->
 *                 Log.e(TAG, "Must provision first")
 *             else ->
 *                 Log.e(TAG, "Refresh failed: $error")
 *         }
 *     }
 * )
 * ```
 */
internal suspend fun PhoneKeyManager.refreshAllAclsSuspend(): Result<BulkAclResult> {
    val phoneKeyIdString = getPhoneKeyId()
    
    if (phoneKeyIdString == null) {
        Log.e(TAG, "Cannot refresh ACLs: phone key not provisioned")
        return Result.failure(
            NokeMobileLibraryError.NotProvisioned(getUserId())
        )
    }
    
    val phoneKeyId = phoneKeyIdString.toIntOrNull()
    if (phoneKeyId == null || phoneKeyId <= 0) {
        Log.e(TAG, "Cannot refresh ACLs: invalid phone key ID: $phoneKeyIdString")
        return Result.failure(
            NokeMobileLibraryError.InvalidInput("Invalid phone key ID: $phoneKeyIdString")
        )
    }
    
    return getBulkAclsSuspend(phoneKeyId)
}
