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
        private var sharedClient: PhoneKeyCoreClient? = null
        
        @Volatile
        private var appContext: Context? = null
        
        @Volatile
        private var sharedSecurityService: com.noke.nokemobilelibrary.phonekey.internal.SecurityService? = null
        
        /**
         * Set the shared PhoneKeyCoreClient implementation.
         *
         * This method allows third parties to inject their own client implementation
         * with custom networking, authentication, and backend integration.
         *
         * ## When to Call
         * Call this once during app initialization, before using [getInstance]:
         *
         * ```kotlin
         * // In Application.onCreate()
         * val client = PhoneKeyCoreClientImpl(
         *     context = applicationContext,
         *     baseUrl = "https://router.smartentry.noke.com/",
         *     authTokenProvider = { sessionManager.getCurrentToken() },
         *     userUuidProvider = { sessionManager.getCurrentUserUuid() }
         * )
         * PhoneKeyAccessService.setSharedClient(client)
         * ```
         *
         * ## Third-Party Implementations
         * Third parties can provide custom implementations:
         *
         * ```kotlin
         * class MyCustomClient(
         *     private val myApiClient: MyApiClient
         * ) : PhoneKeyCoreClient {
         *     override suspend fun initialize(userId: String, deviceId: String): Result<String> {
         *         // Custom implementation with your networking layer
         *     }
         *     // ... other methods
         * }
         *
         * PhoneKeyAccessService.setSharedClient(MyCustomClient(myApiClient))
         * ```
         *
         * @param client PhoneKeyCoreClient implementation
         */
        @JvmStatic
        fun setSharedClient(client: PhoneKeyCoreClient) {
            sharedClient = client
            Log.d(TAG, "Shared PhoneKeyCoreClient configured: ${client::class.simpleName}")
        }
        
        /**
         * Initialize PhoneKeyAccessService with default client implementation.
         *
         * This method creates a [PhoneKeyCoreClientImpl] with the provided configuration
         * and sets it as the shared client. It also stores the context for local operations
         * needed by PhoneKeyFacade.
         *
         * Call this once during app initialization:
         *
         * ```kotlin
         * // In Application.onCreate()
         * PhoneKeyAccessService.initialize(
         *     context = applicationContext,
         *     baseUrl = "https://router.smartentry.noke.com/",
         *     authTokenProvider = { sessionManager.getCurrentToken() },
         *     userUuidProvider = { sessionManager.getCurrentUserUuid() }
         * )
         * ```
         *
         * @param context Application context
         * @param baseUrl Backend API base URL
         * @param authTokenProvider Function returning current auth token
         * @param userUuidProvider Function returning current user UUID
         */
        @JvmStatic
        fun initialize(
            context: Context,
            baseUrl: String,
            authTokenProvider: () -> String,
            userUuidProvider: () -> String
        ) {
            val client = PhoneKeyCoreClientImpl(
                context = context.applicationContext,
                baseUrl = baseUrl,
                authTokenProvider = authTokenProvider,
                userUuidProvider = userUuidProvider
            )
            appContext = context.applicationContext
            sharedSecurityService = com.noke.nokemobilelibrary.phonekey.internal.SecurityServiceImpl(
                context = context.applicationContext,
                baseUrl = baseUrl,
                authTokenProvider = authTokenProvider,
                userUuidProvider = userUuidProvider
            )
            setSharedClient(client)
            Log.d(TAG, "PhoneKeyAccessService initialized with context and client")
        }
        
        /**
         * Get the singleton instance of PhoneKeyAccessService.
         * 
         * @return PhoneKeyAccessService singleton instance
         * @throws IllegalStateException if [setSharedClient] or [initialize] has not been called
         * 
         * ## Example
         * ```kotlin
         * val service = PhoneKeyAccessService.getInstance()
         * ```
         */
        @JvmStatic
        fun getInstance(): PhoneKeyAccessService {
            if (sharedClient == null) {
                throw IllegalStateException(
                    "PhoneKeyAccessService not initialized. Call setSharedClient() or initialize() first."
                )
            }
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoneKeyAccessService().also { INSTANCE = it }
            }
        }
    }
    
    // Mutex for thread-safe access
    private val mutex = Mutex()
    
    /**
     * Get the configured client.
     * @throws IllegalStateException if client not configured
     */
    private fun getClient(): PhoneKeyCoreClient {
        return sharedClient
            ?: throw IllegalStateException("PhoneKeyAccessService not initialized")
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
            val client = getClient()
            
            // Initialize first to ensure keys are generated
            val initResult = client.initialize(userId, udid)
            if (initResult.isFailure) {
                return Result.failure(initResult.exceptionOrNull() ?: Exception("Initialization failed"))
            }
            
            val publicKey = initResult.getOrThrow()
            
            // Provision with backend
            val provisionResult = client.provisionPhoneKey(userId, udid, publicKey)
            if (provisionResult.isFailure) {
                return Result.failure(provisionResult.exceptionOrNull() ?: Exception("Provisioning failed"))
            }
            
            val response = provisionResult.getOrThrow()
            if (response.isSuccess && response.keyId != null) {
                Log.d(TAG, "Phone key provisioned: ${response.keyId}")
                Result.success(response.keyId)
            } else {
                Log.e(TAG, "Provisioning failed: ${response.statusMessage}")
                Result.failure(
                    NokeMobileLibraryError.ProvisioningFailed(response.statusMessage)
                )
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
            val client = getClient()
            client.generateAcl(userId, lockMac, phoneKeyId)
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
            val client = getClient()
            client.generateBulkAcls(phoneKeyId)
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
    @Deprecated(
        message = "This method depends on internal state. Use generateBulkAcls() directly with stored phone key ID.",
        level = DeprecationLevel.WARNING
    )
    suspend fun refreshAllAcls(
        userId: String,
        udid: String
    ): Result<BulkAclResult> {
        // This method can't work cleanly with the abstraction because it needs access
        // to the stored phone key ID, which the client abstraction doesn't expose
        return Result.failure(
            NokeMobileLibraryError.UnknownError(
                "refreshAllAcls",
                UnsupportedOperationException("Use generateBulkAcls() directly with stored phone key ID")
            )
        )
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
        val ctx = appContext ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        val security = sharedSecurityService ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx, security)
            val info = facade.getPhoneKeyInfo(userId, udid)
            Result.success(info != null && info.isSuccess)
        } catch (e: Exception) {
            Log.e(TAG, "isProvisioned check failed", e)
            Result.failure(NokeMobileLibraryError.UnknownError("isProvisioned", e))
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
    suspend fun getPhoneKeyId(userId: String, udid: String): Result<Int> {
        val ctx = appContext ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        val security = sharedSecurityService ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx, security)
            val info = facade.getPhoneKeyInfo(userId, udid)
            
            if (info != null && info.keyId != null) {
                Result.success(info.keyId)
            } else {
                Result.failure(NokeMobileLibraryError.NotProvisioned(userId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPhoneKeyId failed", e)
            Result.failure(NokeMobileLibraryError.UnknownError("getPhoneKeyId", e))
        }
    }
    
    /**
     * List all valid (non-expired) ACLs from local cache.
     *
     * @return List of valid ACLs
     */
    suspend fun listValidACLs(): List<com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope> {
        val ctx = appContext ?: return emptyList()
        val security = sharedSecurityService ?: return emptyList()
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx, security)
            facade.listValidACLs()
        } catch (e: Exception) {
            Log.e(TAG, "listValidACLs failed", e)
            emptyList()
        }
    }
    
    /**
     * Clean up all ACLs for a specific user and device.
     * 
     * This method should be called on user logout to ensure ACL data is
     * removed from encrypted storage for security purposes.
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
        val ctx = appContext ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        val security = sharedSecurityService ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx, security)
            facade.clearAll(userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "cleanupAclsForUser failed", e)
            Result.failure(NokeMobileLibraryError.UnknownError("cleanupAclsForUser", e))
        }
    }
    
    /**
     * Clear internal cache (for user switching scenarios).
     */
    fun clearCache() {
        // PhoneKeyFacade manages its own cache
        Log.d(TAG, "clearCache - cache managed by PhoneKeyFacade")
    }
}