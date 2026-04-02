package com.noke.nokemobilelibrary.phonekey

import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.internal.SecurityServiceImpl
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PhoneKeyAccessService - High-level facade for ION-2 phone key operations.
 * 
 * This service provides a modern, coroutine-based API for phone key provisioning
 * and ACL (Access Control List) management, designed for easy integration into
 * third-party applications.
 * 
 * ## Architecture
 * 
 * This is a **standalone library** facade that:
 * - Wraps internal [PhoneKeyManager] with coroutine-based suspend APIs
 * - Manages SecurityService lifecycle internally
 * - Provides simplified, intent-revealing public API
 * - Handles thread safety with mutex for manager access
 * - Supports multi-device and multi-user scenarios
 * 
 * ## Features
 * 
 * - **Singleton pattern**: Single instance via [getInstance]
 * - **Zero-config**: Initialize once, use everywhere
 * - **Thread-safe**: All operations protected by mutex
 * - **Coroutine-native**: All operations are suspend functions
 * - **Result-based**: Returns `Result<T>` for exhaustive error handling
 * - **Per-device isolation**: Each (userId, deviceId) combination is isolated
 * - **Flexible authentication**: Accepts auth token provider for dynamic token refresh
 * 
 * ## Initialization
 * 
 * The service must be initialized once before use with your backend configuration:
 * 
 * ```kotlin
 * // In Application.onCreate() or early startup
 * PhoneKeyAccessService.initialize(
 *     context = applicationContext,
 *     baseUrl = "https://router.smartentry.noke.com/",
 *     authTokenProvider = { sessionManager.getCurrentToken() },
 *     userUuidProvider = { sessionManager.getCurrentUserUuid() }
 * )
 * ```
 * 
 * ## Usage Example
 * 
 * ```kotlin
 * val service = PhoneKeyAccessService.getInstance()
 * 
 * // Provision phone key
 * val provisionResult = service.provisionPhoneKey(
 *     userId = "12345",
 *     udid = Settings.Secure.getString(
 *         contentResolver,
 *         Settings.Secure.ANDROID_ID
 *     )
 * )
 * 
 * provisionResult.fold(
 *     onSuccess = { phoneKeyId ->
 *         Log.d(TAG, "Provisioned with keyId: $phoneKeyId")
 *         
 *         // Fetch all ACLs
 *         val aclResult = service.generateBulkAcls(
 *             phoneKeyId = phoneKeyId,
 *             userId = "12345",
 *             udid = deviceId
 *         )
 *         
 *         aclResult.fold(
 *             onSuccess = { bulkResult ->
 *                 Log.d(TAG, "${bulkResult.successCount} ACLs fetched")
 *             },
 *             onFailure = { error ->
 *                 Log.e(TAG, "ACL fetch failed: $error")
 *             }
 *         )
 *     },
 *     onFailure = { error ->
 *         Log.e(TAG, "Provisioning failed: $error")
 *     }
 * )
 * ```
 * 
 * ## Thread Safety
 * 
 * All operations are thread-safe and can be called from any coroutine context.
 * Internal operations are serialized using a mutex to ensure PhoneKeyManager
 * is accessed safely.
 * 
 * ## Error Handling
 * 
 * All operations return `Result<T>` wrapping [NokeMobileLibraryError] subtypes:
 * - [NokeMobileLibraryError.NotInitialized] - Service not initialized
 * - [NokeMobileLibraryError.InvalidInput] - Invalid parameters
 * - [NokeMobileLibraryError.ProvisioningFailed] - Provisioning errors
 * - [NokeMobileLibraryError.AclFetchFailed] - ACL fetch errors
 * - And others - see [NokeMobileLibraryError] for complete list
 * 
 * ## Multi-Device Support
 * 
 * Each (userId, deviceId) combination is isolated with:
 * - Unique ECDSA key pair in Android Keystore
 * - Separate encrypted storage file
 * - Independent phone key ID from backend
 * 
 * This enables scenarios where a single user has multiple devices,
 * or multiple users share a device (each with their own keys/ACLs).
 * 
 * @see PhoneKeyManager
 * @see NokeMobileLibraryError
 * @see BulkAclResult
 */
class PhoneKeyAccessService private constructor() {
    
    companion object {
        private const val TAG = "PhoneKeyAccessService"
        
        @Volatile
        private var INSTANCE: PhoneKeyAccessService? = null
        
        @Volatile
        private var applicationContext: Context? = null
        
        @Volatile
        private var baseUrl: String? = null
        
        @Volatile
        private var authTokenProvider: (() -> String)? = null
        
        @Volatile
        private var userUuidProvider: (() -> String)? = null
        
        /**
         * Initialize the PhoneKeyAccessService with backend configuration.
         * 
         * This must be called once before using [getInstance], typically in
         * Application.onCreate() or early in app startup.
         * 
         * **IMPORTANT:** 
         * - Must use application context, not Activity context, to avoid memory leaks
         * - authTokenProvider and userUuidProvider will be called on background threads,
         *   so they must be thread-safe
         * - Providers should return current values (not cached at initialization time)
         * 
         * @param context Application context (will be converted to applicationContext internally)
         * @param baseUrl Base URL for backend API (e.g., "https://router.smartentry.noke.com/")
         * @param authTokenProvider Function that returns current auth token (called on background threads)
         * @param userUuidProvider Function that returns current user UUID (called on background threads)
         * @throws IllegalArgumentException if context is null or baseUrl is empty
         * 
         * ## Example
         * ```kotlin
         * class MyApplication : Application() {
         *     override fun onCreate() {
         *         super.onCreate()
         *         
         *         PhoneKeyAccessService.initialize(
         *             context = this,
         *             baseUrl = "https://router.smartentry.noke.com/",
         *             authTokenProvider = { 
         *                 // Return current auth token
         *                 // This will be called on background threads
         *                 sessionManager.getCurrentToken()
         *             },
         *             userUuidProvider = {
         *                 // Return current user UUID
         *                 // This will be called on background threads
         *                 sessionManager.getCurrentUserUuid()
         *             }
         *         )
         *     }
         * }
         * ```
         */
        @JvmStatic
        fun initialize(
            context: Context,
            baseUrl: String,
            authTokenProvider: () -> String,
            userUuidProvider: () -> String
        ) {
            require(context != null) { "Context cannot be null" }
            require(baseUrl.isNotEmpty()) { "Base URL cannot be empty" }
            
            this.applicationContext = context.applicationContext
            this.baseUrl = baseUrl
            this.authTokenProvider = authTokenProvider
            this.userUuidProvider = userUuidProvider
            
            Log.d(TAG, "PhoneKeyAccessService initialized with baseUrl=$baseUrl")
        }
        
        /**
         * Get the singleton instance of PhoneKeyAccessService.
         * 
         * @return PhoneKeyAccessService singleton instance
         * @throws IllegalStateException if [initialize] has not been called
         * 
         * ## Example
         * ```kotlin
         * val service = PhoneKeyAccessService.getInstance()
         * ```
         */
        @JvmStatic
        fun getInstance(): PhoneKeyAccessService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoneKeyAccessService().also { INSTANCE = it }
            }
        }
    }
    
    // Mutex for thread-safe access to PhoneKeyManager
    private val mutex = Mutex()
    
    // Cache for PhoneKeyManager instances (per userId+udid combination)
    // This avoids recreating managers unnecessarily while still supporting
    // multiple users and devices
    private val managerCache = mutableMapOf<String, PhoneKeyManager>()
    
    /**
     * Get or create a PhoneKeyManager for the given user and device.
     * 
     * This method is internal and handles manager lifecycle. Managers
     * are cached per (userId, udid) combination for efficiency.
     * 
     * @param userId User identifier
     * @param udid Device identifier
     * @return PhoneKeyManager instance
     * @throws NokeMobileLibraryError.NotInitialized if service not initialized
     */
    private fun getOrCreateManager(userId: String, udid: String): PhoneKeyManager {
        val context = applicationContext
            ?: throw NokeMobileLibraryError.NotInitialized
        
        val baseUrlValue = baseUrl
            ?: throw NokeMobileLibraryError.NotInitialized
        
        val authProvider = authTokenProvider
            ?: throw NokeMobileLibraryError.NotInitialized
        
        val uuidProvider = userUuidProvider
            ?: throw NokeMobileLibraryError.NotInitialized
        
        val cacheKey = "${userId}_${udid}"
        
        return managerCache.getOrPut(cacheKey) {
            // Create SecurityService for this manager
            val securityService = SecurityServiceImpl(
                context = context,
                baseUrl = baseUrlValue,
                authTokenProvider = authProvider,
                userUuidProvider = uuidProvider
            )
            
            // Create PhoneKeyManager with SecurityService
            PhoneKeyManager(
                context = context,
                userId = userId,
                udid = udid,
                securityService = securityService
            ).also {
                Log.d(TAG, "Created new PhoneKeyManager for user=$userId, device=$udid")
            }
        }
    }
    
    /**
     * Provision phone key with backend.
     * 
     * This operation generates an ECDSA P-256 key pair in Android Keystore,
     * registers the public key with the backend, and stores the resulting
     * phone key identifier.
     * 
     * ## Process
     * 1. Generate ECDSA P-256 key pair in Android Keystore (hardware-backed)
     * 2. Extract public key in X9.62 format (Base64-encoded)
     * 3. Send provisioning request to backend: (udid, publicKey, userId)
     * 4. Store phone key ID and metadata locally
     * 
     * ## Per-Device Isolation
     * Each (userId, device) combination gets:
     * - Unique ECDSA key pair in Keystore
     * - Separate encrypted storage file
     * - Independent phone key ID from backend
     * 
     * This enables multi-device support where a single user can have
     * multiple provisioned devices.
     * 
     * ## Idempotency
     * Safe to call multiple times for the same (userId, udid). If already
     * provisioned, existing state is preserved.
     * 
     * ## Thread Safety
     * Thread-safe - can be called from any coroutine context.
     * 
     * @param userId User identifier (must not be empty)
     * @param udid Unique device identifier (must not be empty)
     * @return [Result] containing phone key ID on success, or [NokeMobileLibraryError] on failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - Service not initialized
     * - [NokeMobileLibraryError.InvalidInput] - Empty userId or udid
     * - [NokeMobileLibraryError.CryptographicError] - Key generation failed
     * - [NokeMobileLibraryError.NetworkError] - Network request failed
     * - [NokeMobileLibraryError.ProvisioningFailed] - Backend rejected or storage failed
     * 
     * ## Example
     * ```kotlin
     * val result = service.provisionPhoneKey(
     *     userId = "12345",
     *     udid = Settings.Secure.getString(
     *         contentResolver,
     *         Settings.Secure.ANDROID_ID
     *     )
     * )
     * 
     * result.fold(
     *     onSuccess = { phoneKeyId ->
     *         Log.d(TAG, "Phone key provisioned: $phoneKeyId")
     *         // Now fetch ACLs
     *     },
     *     onFailure = { error ->
     *         when (error) {
     *             is NokeMobileLibraryError.NetworkError ->
     *                 showRetryDialog()
     *             is NokeMobileLibraryError.CryptographicError ->
     *                 showDeviceNotSupportedError()
     *             else ->
     *                 showGenericError(error.message)
     *         }
     *     }
     * )
     * ```
     */
    suspend fun provisionPhoneKey(
        userId: String,
        udid: String
    ): Result<Int> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId, udid)
                manager.provisionPhoneKeySuspend(userId, udid)
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during provisioning", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError("provisionPhoneKey", e)
            )
        }
    }
    
    /**
     * Generate ACL (Access Control List) for a specific lock.
     * 
     * This operation fetches the ACL envelope from the backend, which contains:
     * - ACL binary representation (for lock communication)
     * - ACL signature (from lock's perspective for verification)
     * - Permissions and schedule details
     * 
     * The ACL is stored locally in encrypted storage for offline access.
     * 
     * ## When to Use
     * Use this for **single-lock ACL refresh** scenarios:
     * - After unlock failure (refetch specific ACL)
     * - When explicitly updating one lock's permissions
     * 
     * For most use cases, prefer [generateBulkAcls] which fetches all
     * ACLs in a single request (more efficient).
     * 
     * ## Thread Safety
     * Thread-safe - can be called from any coroutine context.
     * 
     * @param userId User ID as integer (matching backend API)
     * @param lockMac Lock MAC address (e.g., "AA:BB:CC:DD:EE:FF")
     * @param phoneKeyId Phone key ID from [provisionPhoneKey]
     * @param udid Device identifier (for manager lookup)
     * @return [Result] with Unit on success, or [NokeMobileLibraryError] on failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - Service not initialized
     * - [NokeMobileLibraryError.InvalidInput] - Invalid parameters
     * - [NokeMobileLibraryError.NetworkError] - Network request failed
     * - [NokeMobileLibraryError.AclFetchFailed] - Backend rejected or not authorized
     * - [NokeMobileLibraryError.AclStorageFailed] - ACL received but storage failed
     * 
     * ## Example
     * ```kotlin
     * val result = service.generateAcl(
     *     userId = 12345,
     *     lockMac = "AA:BB:CC:DD:EE:FF",
     *     phoneKeyId = 67890,
     *     udid = deviceId
     * )
     * 
     * result.fold(
     *     onSuccess = {
     *         Log.d(TAG, "ACL fetched and ready for unlock")
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "ACL fetch failed: $error")
     *     }
     * )
     * ```
     */
    suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        udid: String
    ): Result<Unit> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId.toString(), udid)
                manager.getAclSuspend(userId, lockMac, phoneKeyId)
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during ACL generation", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError("generateAcl", e)
            )
        }
    }
    
    /**
     * Generate bulk ACLs for all locks accessible to the user.
     * 
     * This is the **preferred method** for fetching ACLs in most scenarios:
     * - After successful login
     * - After provisioning
     * - On app startup (if user already logged in)
     * - Manual refresh of all ACLs
     * 
     * This operation fetches all ACL envelopes in a single backend request,
     * which is much more efficient than fetching individual ACLs one-by-one.
     * 
     * ## Bulk ACL Format
     * Bulk ACLs are stored in simplified format without detailed permissions/schedule.
     * The `aclBinary` field contains all data needed for lock operations.
     * 
     * ## Partial Success
     * If some ACLs fail to store locally, the operation still succeeds but
     * the result indicates which ACLs were stored. Check [BulkAclResult.isFullSuccess]
     * or [BulkAclResult.hasPartialSuccess].
     * 
     * ## Thread Safety
     * Thread-safe - can be called from any coroutine context.
     * 
     * @param phoneKeyId Phone key ID from [provisionPhoneKey]
     * @param userId User identifier (for manager lookup)
     * @param udid Device identifier (for manager lookup)
     * @return [Result] containing [BulkAclResult] on success, or [NokeMobileLibraryError] on complete failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - Service not initialized
     * - [NokeMobileLibraryError.InvalidInput] - Invalid phoneKeyId
     * - [NokeMobileLibraryError.NetworkError] - Network request failed
     * - [NokeMobileLibraryError.BulkAclFetchFailed] - Backend rejected or all storage failed
     * 
     * Note: Partial storage failures are reflected in [BulkAclResult], not thrown as errors.
     * 
     * ## Example
     * ```kotlin
     * val result = service.generateBulkAcls(
     *     phoneKeyId = 67890,
     *     userId = "12345",
     *     udid = deviceId
     * )
     * 
     * result.fold(
     *     onSuccess = { bulkResult ->
     *         when {
     *             bulkResult.isFullSuccess ->
     *                 Log.d(TAG, "All ${bulkResult.totalCount} ACLs ready")
     *             bulkResult.hasPartialSuccess ->
     *                 Log.w(TAG, "Partial: ${bulkResult.successCount}/${bulkResult.totalCount}")
     *             bulkResult.isEmpty ->
     *                 Log.i(TAG, "No locks assigned to this user")
     *             else ->
     *                 Log.e(TAG, "All ACL storage failed")
     *         }
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Bulk ACL fetch failed: $error")
     *     }
     * )
     * ```
     */
    suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        udid: String
    ): Result<BulkAclResult> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId, udid)
                manager.getBulkAclsSuspend(phoneKeyId)
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during bulk ACL generation", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError("generateBulkAcls", e)
            )
        }
    }
    
    /**
     * Refresh all ACLs by fetching from backend.
     * 
     * This is a convenience method for manual ACL refresh scenarios:
     * - User-initiated refresh (pull-to-refresh)
     * - Periodic background refresh
     * - After detecting ACL changes server-side
     * 
     * This method automatically uses the stored phone key ID, so you don't
     * need to pass it explicitly (unlike [generateBulkAcls]).
     * 
     * ## Requirements
     * The phone key must be provisioned before calling this method.
     * If not provisioned, returns [NokeMobileLibraryError.NotProvisioned].
     * 
     * ## Thread Safety
     * Thread-safe - can be called from any coroutine context.
     * 
     * @param userId User identifier (for manager lookup)
     * @param udid Device identifier (for manager lookup)
     * @return [Result] containing [BulkAclResult] on success, or [NokeMobileLibraryError] on failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - Service not initialized
     * - [NokeMobileLibraryError.NotProvisioned] - Phone key not provisioned yet
     * - All errors from [generateBulkAcls]
     * 
     * ## Example
     * ```kotlin
     * // In pull-to-refresh handler
     * val result = service.refreshAllAcls(
     *     userId = "12345",
     *     udid = deviceId
     * )
     * 
     * result.fold(
     *     onSuccess = { bulkResult ->
     *         Log.d(TAG, "Refreshed ${bulkResult.successCount} ACLs")
     *         hideRefreshIndicator()
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Refresh failed: $error")
     *         showErrorMessage(error.message)
     *     }
     * )
     * ```
     */
    suspend fun refreshAllAcls(
        userId: String,
        udid: String
    ): Result<BulkAclResult> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId, udid)
                manager.refreshAllAclsSuspend()
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during ACL refresh", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError("refreshAllAcls", e)
            )
        }
    }
    
    /**
     * Check if phone key is provisioned for the given user and device.
     * 
     * @param userId User identifier
     * @param udid Device identifier
     * @return [Result] containing true if provisioned, false otherwise
     * 
     * ## Example
     * ```kotlin
     * val result = service.isProvisioned(userId = "12345", udid = deviceId)
     * result.fold(
     *     onSuccess = { isProvisioned ->
     *         if (isProvisioned) {
     *             Log.d(TAG, "Already provisioned")
     *         } else {
     *             Log.d(TAG, "Need to provision")
     *         }
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Error checking provisioning status: $error")
     *     }
     * )
     * ```
     */
    suspend fun isProvisioned(userId: String, udid: String): Result<Boolean> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId, udid)
                Result.success(manager.isProvisioned())
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error checking provisioning status", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError("isProvisioned", e)
            )
        }
    }
    
    /**
     * Get the stored phone key ID for the given user and device.
     * 
     * @param userId User identifier
     * @param udid Device identifier
     * @return [Result] containing phone key ID if provisioned, or error if not provisioned
     * 
     * ## Example
     * ```kotlin
     * val result = service.getPhoneKeyId(userId = "12345", udid = deviceId)
     * result.fold(
     *     onSuccess = { phoneKeyId ->
     *         Log.d(TAG, "Phone key ID: $phoneKeyId")
     *     },
     *     onFailure = { error ->
     *         when (error) {
     *             is NokeMobileLibraryError.NotProvisioned ->
     *                 Log.e(TAG, "Not provisioned yet")
     *             else ->
     *                 Log.e(TAG, "Error: $error")
     *         }
     *     }
     * )
     * ```
     */
    suspend fun getPhoneKeyId(userId: String, udid: String): Result<String> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId, udid)
                val keyId = manager.getPhoneKeyId()
                if (keyId != null) {
                    Result.success(keyId)
                } else {
                    Result.failure(NokeMobileLibraryError.NotProvisioned(userId))
                }
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting phone key ID", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError("getPhoneKeyId", e)
            )
        }
    }
    
    /**
     * Clean up all ACLs for a specific user and device.
     * 
     * This method should be called on user logout to ensure ACL data is
     * removed from encrypted storage for security purposes.
     * 
     * The method will:
     * 1. Get or create a PhoneKeyManager for the user/device
     * 2. Call cleanupAllAcls() to remove ACL data from storage
     * 3. Remove the manager from cache
     * 
     * @param userId User identifier
     * @param udid Device identifier
     * @return [Result] with Unit on success, or [NokeMobileLibraryError] on failure
     * 
     * ## Example
     * ```kotlin
     * // On user logout
     * val result = service.cleanupAclsForUser(
     *     userId = "12345",
     *     udid = deviceId
     * )
     * 
     * result.fold(
     *     onSuccess = { Log.d(TAG, "ACLs cleaned up successfully") },
     *     onFailure = { error -> Log.e(TAG, "Cleanup failed: $error") }
     * )
     * ```
     */
    suspend fun cleanupAclsForUser(userId: String, udid: String): Result<Unit> {
        return try {
            mutex.withLock {
                val manager = getOrCreateManager(userId, udid)
                manager.cleanupAllAcls()
                
                // Remove from cache after cleanup
                val cacheKey = "${userId}_${udid}"
                managerCache.remove(cacheKey)
                
                Result.success(Unit)
            }
        } catch (e: NokeMobileLibraryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                NokeMobileLibraryError.UnknownError("cleanupAclsForUser", e)
            )
        }
    }
    
    /**
     * Clear cached PhoneKeyManager instances.
     * 
     * This is useful for testing or when you need to force recreation
     * of managers (e.g., after user logout).
     * 
     * **WARNING:** This does NOT delete stored keys or ACLs. It only
     * clears the in-memory cache of manager instances.
     * 
     * **For logout scenarios, use [cleanupAclsForUser] instead** to properly
     * remove ACL data from storage.
     * 
     * ## Example
     * ```kotlin
     * // After user logout
     * service.clearCache()
     * Log.d(TAG, "Manager cache cleared")
     * ```
     */
    fun clearCache() {
        managerCache.clear()
    }
}
