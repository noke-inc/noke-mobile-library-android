package com.noke.nokemobilelibrary.phonekey

/**
 * Sealed class hierarchy for all Noke Mobile Library errors.
 * 
 * Provides a type-safe, exhaustive error handling mechanism for phone key operations.
 * All errors thrown by phone key operations will be wrapped in one of these error types.
 * 
 * ## Error Categories
 * 
 * - **Initialization Errors**: [NotInitialized], [InvalidConfiguration]
 * - **Input Validation Errors**: [InvalidInput]
 * - **Provisioning Errors**: [ProvisioningFailed]
 * - **ACL Errors**: [AclFetchFailed], [AclStorageFailed], [BulkAclFetchFailed]
 * - **Storage Errors**: [StorageError]
 * - **Network Errors**: [NetworkError]
 * - **Crypto Errors**: [CryptographicError]
 * - **State Errors**: [NotProvisioned]
 * - **Generic**: [UnknownError]
 * 
 * ## Usage Example
 * 
 * ```kotlin
 * try {
 *     val result = phoneKeyService.provisionPhoneKey(
 *         userId = "12345",
 *         udid = deviceId
 *     ).getOrThrow()
 * } catch (e: NokeMobileLibraryError) {
 *     when (e) {
 *         is NokeMobileLibraryError.NotInitialized -> 
 *             Log.e(TAG, "Library not initialized")
 *         is NokeMobileLibraryError.ProvisioningFailed -> 
 *             Log.e(TAG, "Provisioning failed: ${e.reason}")
 *         is NokeMobileLibraryError.NetworkError -> 
 *             Log.e(TAG, "Network error", e.underlying)
 *         else -> Log.e(TAG, "Unexpected error: ${e.message}")
 *     }
 * }
 * ```
 * 
 * @param message Human-readable error description
 * @param cause Optional underlying exception that caused this error
 */
sealed class NokeMobileLibraryError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    
    /**
     * Library has not been initialized or configured properly.
     * 
     * This typically means the phone key service was called before initialization,
     * or required components are not available.
     * 
     * ## Recovery
     * Ensure the library is initialized before calling any operations.
     */
    object NotInitialized : NokeMobileLibraryError(
        "Noke Mobile Library is not initialized. Please initialize before use."
    )
    
    /**
     * Invalid input parameters provided to an operation.
     * 
     * @param reason Specific explanation of what input was invalid
     * 
     * ## Examples
     * - Empty userId or lockMac
     * - Invalid phoneKeyId (negative or zero)
     * - Malformed time windows
     * 
     * ## Recovery
     * Validate inputs before calling library methods.
     */
    data class InvalidInput(
        val reason: String
    ) : NokeMobileLibraryError("Invalid input: $reason")
    
    /**
     * Invalid library configuration detected.
     * 
     * @param reason Explanation of the configuration issue
     * 
     * ## Examples
     * - Missing required context
     * - Invalid credentials
     * - Incompatible Android version (minSdk 21 required)
     */
    data class InvalidConfiguration(
        val reason: String
    ) : NokeMobileLibraryError("Invalid configuration: $reason")
    
    /**
     * Phone key provisioning operation failed.
     * 
     * @param reason Human-readable explanation of the failure
     * @param underlying Optional underlying exception from backend or crypto layer
     * 
     * ## Common Causes
     * - Network connectivity issues
     * - Backend authentication/authorization failure
     * - Keystore errors during key generation
     * - Storage errors when persisting provisioning state
     * 
     * ## Recovery
     * - Check network connectivity
     * - Verify user authentication status
     * - Retry with exponential backoff
     */
    data class ProvisioningFailed(
        val reason: String,
        val underlying: Throwable? = null
    ) : NokeMobileLibraryError("Provisioning failed: $reason", underlying)
    
    /**
     * ACL (Access Control List) fetch operation failed.
     * 
     * @param lockMac MAC address of the lock for which ACL fetch failed
     * @param reason Human-readable explanation of the failure
     * @param underlying Optional underlying exception
     * 
     * ## Common Causes
     * - Network connectivity issues
     * - Lock not found in backend
     * - User doesn't have access to the lock
     * - Backend service unavailable
     * 
     * ## Recovery
     * - Check network connectivity
     * - Verify user has access to the lock
     * - Retry with exponential backoff
     * - Try bulk ACL fetch instead
     */
    data class AclFetchFailed(
        val lockMac: String,
        val reason: String,
        val underlying: Throwable? = null
    ) : NokeMobileLibraryError("ACL fetch failed for lock $lockMac: $reason", underlying)
    
    /**
     * Bulk ACL fetch operation failed.
     * 
     * This error indicates that the entire bulk ACL request failed.
     * For partial failures (some ACLs fetched successfully), check the
     * result object instead - this error is only thrown for complete failures.
     * 
     * @param phoneKeyId The phone key ID used for the bulk fetch
     * @param reason Human-readable explanation of the failure
     * @param underlying Optional underlying exception
     * 
     * ## Common Causes
     * - Network connectivity issues
     * - Invalid or expired phone key ID
     * - Backend service unavailable
     * 
     * ## Recovery
     * - Check network connectivity
     * - Verify phone key is still valid
     * - Retry with exponential backoff
     */
    data class BulkAclFetchFailed(
        val phoneKeyId: Int,
        val reason: String,
        val underlying: Throwable? = null
    ) : NokeMobileLibraryError("Bulk ACL fetch failed for phoneKeyId $phoneKeyId: $reason", underlying)
    
    /**
     * ACL storage operation failed after successful fetch.
     * 
     * This indicates the ACL was successfully fetched from the backend
     * but could not be persisted locally.
     * 
     * @param lockMac MAC address of the lock
     * @param reason Human-readable explanation of the storage failure
     * @param underlying Optional underlying exception
     * 
     * ## Common Causes
     * - Insufficient storage space
     * - Encrypted SharedPreferences errors
     * - File system errors
     * 
     * ## Recovery
     * - Check available storage space
     * - Clear app cache if needed
     * - Retry the operation
     */
    data class AclStorageFailed(
        val lockMac: String,
        val reason: String,
        val underlying: Throwable? = null
    ) : NokeMobileLibraryError("ACL storage failed for lock $lockMac: $reason", underlying)
    
    /**
     * Generic storage error (not ACL-specific).
     * 
     * @param operation Description of the operation that failed
     * @param underlying Optional underlying exception
     * 
     * ## Common Causes
     * - SharedPreferences commit failure
     * - Insufficient storage space
     * - EncryptedSharedPreferences errors
     * - File system errors
     */
    data class StorageError(
        val operation: String,
        val underlying: Throwable? = null
    ) : NokeMobileLibraryError("Storage error during $operation", underlying)
    
    /**
     * Network-related error.
     * 
     * @param operation Description of the operation that failed
     * @param underlying The underlying network exception
     * 
     * ## Common Causes
     * - No internet connectivity
     * - DNS resolution failure
     * - Connection timeout
     * - SSL/TLS errors
     * 
     * ## Recovery
     * - Check network connectivity
     * - Retry with exponential backoff
     * - Verify backend service status
     */
    data class NetworkError(
        val operation: String,
        val underlying: Throwable
    ) : NokeMobileLibraryError("Network error during $operation: ${underlying.message}", underlying)
    
    /**
     * Cryptographic operation failed.
     * 
     * @param operation Description of the crypto operation (e.g., "key generation", "signing")
     * @param underlying Optional underlying exception from crypto layer
     * 
     * ## Common Causes
     * - Android Keystore errors
     * - Hardware-backed key unavailable
     * - Key generation failure
     * - Signing operation failure
     * 
     * ## Recovery
     * - Check device compatibility (minSdk 21)
     * - Verify lockscreen is enabled (some devices require this for Keystore)
     * - Retry key generation
     */
    data class CryptographicError(
        val operation: String,
        val underlying: Throwable? = null
    ) : NokeMobileLibraryError("Cryptographic error during $operation", underlying)
    
    /**
     * Phone key is not provisioned for the user.
     * 
     * This error indicates that the user must complete provisioning
     * before performing the requested operation.
     * 
     * @param userId The user ID that is not provisioned
     * 
     * ## Recovery
     * Call the provisioning method first before attempting ACL operations.
     */
    data class NotProvisioned(
        val userId: String
    ) : NokeMobileLibraryError("Phone key not provisioned for user $userId")
    
    /**
     * An unexpected error occurred that doesn't fit other categories.
     * 
     * @param operation Description of the operation that failed
     * @param underlying The underlying exception
     * 
     * This is a catch-all for unexpected errors. If you encounter this frequently,
     * consider adding a more specific error type.
     */
    data class UnknownError(
        val operation: String,
        val underlying: Throwable
    ) : NokeMobileLibraryError("Unknown error during $operation: ${underlying.message}", underlying)
}
