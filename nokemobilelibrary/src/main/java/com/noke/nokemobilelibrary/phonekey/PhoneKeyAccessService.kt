package com.noke.nokemobilelibrary.phonekey

import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse

/**
 * PhoneKeyAccessService - High-level facade for ION-2 phone key operations.
 * 
 * This service provides a modern, coroutine-based API for phone key provisioning
 * and ACL (Access Control List) management. It uses a pluggable [PhoneKeyCoreClient]
 * abstraction layer to allow third-party implementations with custom networking,
 * authentication, and backend integration.
 * 
 * ## Architecture (TIDY Principles)
 * 
 * This service follows **Thoughtful Intent-Driven Design**:
 * - **Public API focus**: Exposes **what** (intent), hides **how** (implementation)
 * - **Pluggable backend**: Uses [PhoneKeyCoreClient] interface for flexibility
 * - **Zero coupling**: No dependency on specific networking libraries or auth systems
 * - **Third-party friendly**: Easy to integrate with any Android architecture
 * 
 * ```
 * ┌─────────────────────────────┐
 * │  PhoneKeyAccessService      │  <- Public facade (this file)
 * │  (Coordinator/Orchestrator) │
 * └──────────┬──────────────────┘
 *            │ uses
 *            ▼
 * ┌─────────────────────────────┐
 * │  PhoneKeyCoreClient         │  <- Abstraction (interface)
 * │  (Protocol/Contract)        │
 * └──────────┬──────────────────┘
 *            │ implemented by
 *            ▼
 * ┌─────────────────────────────┐
 * │  PhoneKeyCoreClientImpl     │  <- Default implementation
 * │  (or CustomClientImpl)      │     (third parties can provide own)
 * └──────────┬──────────────────┘
 *            │ uses
 *            ▼
 * ┌─────────────────────────────┐
 * │  PhoneKeyManager            │  <- Crypto/Storage layer
 * │  + SecurityService          │     (networking layer)
 * └─────────────────────────────┘
 * ```
 * 
 * ## Features
 * 
 * - **Singleton pattern**: Single instance via [getInstance]
 * - **Pluggable client**: Set custom [PhoneKeyCoreClient] via [setSharedClient]
 * - **Thread-safe**: All operations are thread-safe
 * - **Coroutine-native**: All operations are suspend functions
 * - **Result-based**: Returns `Result<T>` for exhaustive error handling
 * - **Per-device isolation**: Each (userId, deviceId) combination is isolated
 * - **Zero-config default**: Works with [PhoneKeyCoreClientImpl] out of the box
 * 
 * ## Setup (Required)
 * 
 * Set the client implementation before using the service:
 * 
 * ### Option 1: Use Default Implementation (StorageSmartEntry)
 * ```kotlin
 * // In Application.onCreate()
 * val client = PhoneKeyCoreClientImpl(applicationContext)
 * PhoneKeyAccessService.setSharedClient(client)
 * ```
 * 
 * ### Option 2: Use Custom Implementation (Third Parties)
 * ```kotlin
 * // Implement PhoneKeyCoreClient with your own networking layer
 * class MyPhoneKeyCoreClient(
 *     private val context: Context,
 *     private val myApiClient: MyApiClient
 * ) : PhoneKeyCoreClient {
 *     // Implement interface methods with your networking layer
 * }
 * 
 * // In Application.onCreate()
 * val client = MyPhoneKeyCoreClient(applicationContext, myApiClient)
 * PhoneKeyAccessService.setSharedClient(client)
 * ```
 * 
 * ## Usage Example
 * 
 * ```kotlin
 * val service = PhoneKeyAccessService.getInstance()
 * 
 * // Initialize phone key system
 * val initResult = service.initialize(userId = "12345", deviceId = androidId)
 * initResult.fold(
 *     onSuccess = { publicKey ->
 *         Log.d(TAG, "Initialized with public key")
 *         
 *         // Provision phone key
 *         val provisionResult = service.provisionPhoneKey(
 *             userId = "12345",
 *             udid = androidId
 *         )
 *         
 *         provisionResult.fold(
 *             onSuccess = { response ->
 *                 if (response.isSuccess) {
 *                     Log.d(TAG, "Provisioned with keyId: ${response.keyId}")
 *                     
 *                     // Fetch all ACLs
 *                     val aclResult = service.generateBulkAcls(response.keyId!!)
 *                     // ... handle result
 *                 }
 *             },
 *             onFailure = { error ->
 *                 Log.e(TAG, "Provisioning failed: $error")
 *             }
 *         )
 *     },
 *     onFailure = { error ->
 *         Log.e(TAG, "Initialization failed: $error")
 *     }
 * )
 * ```
 * 
 * ## Thread Safety
 * 
 * All operations are thread-safe. The underlying [PhoneKeyCoreClient] implementation
 * is responsible for ensuring thread safety of its operations.
 * 
 * ## Error Handling
 * 
 * All operations return `Result<T>` wrapping [NokeMobileLibraryError] subtypes:
 * - [NokeMobileLibraryError.NotInitialized] - Client not set
 * - [NokeMobileLibraryError.InvalidInput] - Invalid parameters
 * - [NokeMobileLibraryError.ProvisioningFailed] - Provisioning errors
 * - [NokeMobileLibraryError.AclFetchFailed] - ACL fetch errors
 * - And others - see [NokeMobileLibraryError] for complete list
 * 
 * ## Backward Compatibility
 * 
 * This service does NOT replace existing PhoneKeyManager-based code.
 * All existing callback-based APIs continue to work unchanged. This provides
 * a modern, coroutine-based alternative for new code.
 * 
 * ## Third-Party Integration
 * 
 * Third parties can implement their own [PhoneKeyCoreClient] to integrate with:
 * - Custom network layers (Retrofit, Ktor, custom HTTP clients)
 * - Custom authentication systems
 * - Custom backend URLs and endpoints
 * - Mock implementations for testing
 * 
 * @see PhoneKeyCoreClient
 * @see PhoneKeyCoreClientImpl
 * @see NokeMobileLibraryError
 * @see BulkAclResult
 */
class PhoneKeyAccessService private constructor(
    private val client: PhoneKeyCoreClient?,
    private val context: Context?
) {
    
    companion object {
        private const val TAG = "PhoneKeyAccessService"
        
        @Volatile
        private var sharedInstance: PhoneKeyAccessService = PhoneKeyAccessService(null, null)
        
        /**
         * Initialize the service with a context.
         * 
         * **DEPRECATED:** This method is no longer available in noke-mobile-library-android.
         * Third-party developers must provide their own PhoneKeyCoreClient implementation.
         * 
         * Use [setSharedClient] instead with your own implementation.
         * See TEMPLATE_PhoneKeyCoreClient.kt for an example implementation.
         * 
         * @param context Application context
         * @deprecated Provide your own PhoneKeyCoreClient implementation via setSharedClient()
         */
        @Deprecated(
            message = "Provide your own PhoneKeyCoreClient implementation via setSharedClient()",
            replaceWith = ReplaceWith("setSharedClient(yourClientImpl, context)"),
            level = DeprecationLevel.ERROR
        )
        @JvmStatic
        fun initialize(context: Context) {
            throw UnsupportedOperationException(
                "PhoneKeyCoreClient implementation required. " +
                "See TEMPLATE_PhoneKeyCoreClient.kt for an example implementation."
            )
        }

        /**
         * Set the shared [PhoneKeyCoreClient] implementation.
         * 
         * This must be called before using [getInstance], typically in
         * Application.onCreate() or early in app startup.
         * 
         * **IMPORTANT:** This sets the client for all subsequent operations.
         * Call this once during app initialization.
         * 
         * @param client PhoneKeyCoreClient implementation (custom or default)
         * @param context Application context
         * 
         * ## Example with Default Implementation
         * ```kotlin
         * class MyApplication : Application() {
         *     override fun onCreate() {
         *         super.onCreate()
         *         
         *         val client = PhoneKeyCoreClientImpl(this)
         *         PhoneKeyAccessService.setSharedClient(client, this)
         *     }
         * }
         * ```
         * 
         * ## Example with Custom Implementation
         * ```kotlin
         * class MyApplication : Application() {
         *     override fun onCreate() {
         *         super.onCreate()
         *         
         *         val client = MyCustomPhoneKeyCoreClient(
         *             context = this,
         *             apiClient = myApiClient
         *         )
         *         PhoneKeyAccessService.setSharedClient(client, this)
         *     }
         * }
         * ```
         */
        @JvmStatic
        fun setSharedClient(client: PhoneKeyCoreClient, context: Context) {
            sharedInstance = PhoneKeyAccessService(client, context.applicationContext)
            Log.d(TAG, "PhoneKeyAccessService client set: ${client::class.java.simpleName}")
        }
        
        /**
         * Get the singleton instance of PhoneKeyAccessService.
         * 
         * @return PhoneKeyAccessService singleton instance
         * @throws IllegalStateException if [setSharedClient] has not been called
         * 
         * ## Example
         * ```kotlin
         * val service = PhoneKeyAccessService.getInstance()
         * ```
         */
        @JvmStatic
        fun getInstance(): PhoneKeyAccessService {
            return sharedInstance
        }
        
        /**
         * Get the device UDID (Android ID).
         * 
         * Uses Settings.Secure.ANDROID_ID which is unique per device and app installation.
         * 
         * **Production Note:** ANDROID_ID persists across app reinstalls but resets on factory reset.
         * This is the recommended approach for device identification in Android.
         * 
         * @param context Application context
         * @return Device UDID string (ANDROID_ID)
         * @throws IllegalStateException if ANDROID_ID cannot be retrieved
         * 
         * ## Example
         * ```kotlin
         * val udid = PhoneKeyAccessService.getDeviceUdid(applicationContext)
         * ```
         */
        @JvmStatic
        fun getDeviceUdid(context: Context): String {
            return android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: throw IllegalStateException("Unable to retrieve device ANDROID_ID")
        }
    }

    
    /**
     * Initialize the Phone Key system and ensure cryptographic keys are generated.
     *
     * This operation should be called once per (userId, deviceId) combination before
     * performing any other operations. It generates ECDSA P-256 keys in Android Keystore
     * and returns the public key.
     *
     * ## Idempotency
     * Safe to call multiple times - returns immediately if already initialized.
     *
     * ## Thread Safety
     * Thread-safe - can be called from any coroutine context.
     *
     * @param userId User identifier for key isolation
     * @param deviceId Device identifier (UDID) for key isolation
     * @return [Result] containing Base64-encoded public key on success, or [NokeMobileLibraryError] on failure
     *
     * ## Example
     * ```kotlin
     * val result = service.initialize(userId = "12345", deviceId = androidId)
     * result.fold(
     *     onSuccess = { publicKey ->
     *         Log.d(TAG, "Initialized with public key")
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Initialization failed: $error")
     *     }
     * )
     * ```
     */
    suspend fun initialize(userId: String, deviceId: String): Result<String> {
        val currentClient = client
            ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            currentClient.initialize(userId, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed", e)
            Result.failure(
                NokeMobileLibraryError.UnknownError(
                    operation = "initialize",
                    underlying = e
                )
            )
        }
    }

    /**
     * Check if the service has been initialized.
     *
     * @return true if a client is set and initialized, false otherwise
     */
    val isInitialized: Boolean
        get() = client?.isInitialized ?: false

    /**
     * Validate and retrieve the current public key.
     *
     * @param userId User identifier for key lookup
     * @param deviceId Device identifier for key lookup
     * @return Base64-encoded public key, or null if invalid/missing
     *
     * ## Example
     * ```kotlin
     * val publicKey = service.validateCurrentKey(userId = "12345", deviceId = androidId)
     * if (publicKey != null) {
     *     Log.d(TAG, "Valid key found")
     * } else {
     *     Log.w(TAG, "No valid key - need to initialize")
     * }
     * ```
     */
    suspend fun validateCurrentKey(userId: String, deviceId: String): String? {
        return try {
            client?.validateCurrentKey(userId, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Key validation failed", e)
            null
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
     * 1. Ensure keys are initialized via [initialize]
     * 2. Extract public key in X9.62 format (Base64-encoded)
     * 3. Send provisioning request to backend: (userId, deviceId, publicKey)
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
     * @return [Result] containing [PhoneKeyInfoResponse] on success, or [NokeMobileLibraryError] on failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - Client not set
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
     *     onSuccess = { response ->
     *         if (response.isSuccess) {
     *             Log.d(TAG, "Phone key provisioned: ${response.keyId}")
     *             // Now fetch ACLs
     *         } else {
     *             Log.e(TAG, "Provisioning failed: ${response.statusMessage}")
     *         }
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
    ): Result<PhoneKeyInfoResponse> {
        val currentClient = client
            ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            // Ensure client is initialized first
            val initResult = currentClient.initialize(userId, udid)
            if (initResult.isFailure) {
                return Result.failure(
                    initResult.exceptionOrNull() ?: NokeMobileLibraryError.UnknownError("initialize", Exception("Initialization failed"))
                )
            }
            
            val publicKey = initResult.getOrThrow()
            currentClient.provisionPhoneKey(userId, udid, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning failed", e)
            Result.failure(
                NokeMobileLibraryError.ProvisioningFailed(
                    reason = e.message ?: "Unknown error"
                )
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
     * @return [Result] with Unit on success, or [NokeMobileLibraryError] on failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - Client not set
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
     *     phoneKeyId = 67890
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
        val currentClient = client
            ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            currentClient.generateAcl(userId, lockMac, phoneKeyId)
        } catch (e: Exception) {
            Log.e(TAG, "ACL generation failed", e)
            Result.failure(
                NokeMobileLibraryError.AclFetchFailed(
                    lockMac = lockMac,
                    reason = e.message ?: "Unknown error"
                )
            )
        }
    }
    
    /**
     * Generate bulk ACLs for all locks accessible to the user.
     * 
     * **CRITICAL:** Must call [initialize] with userId and deviceId BEFORE calling this method.
     * The PhoneKeyCoreClient requires a manager instance in its cache to fetch ACLs.
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
     * ## Initialization Requirement
     * **Why initialize() is required:**
     * - PhoneKeyCoreClient uses a manager cache keyed by (userId, deviceId)
     * - generateBulkAcls() retrieves ACLs using a cached manager instance
     * - If initialize() wasn't called, the cache is empty → NotInitialized error
     * - This pattern mirrors iOS PhoneKeyFacade lazy initialization
     * 
     * **Common mistake:** Provisioning creates a manager internally, but doesn't
     * add it to the cache. Always call initialize() explicitly before ACL operations.
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
     * @param userId User identifier (for logging/debugging - not functionally used)
     * @param udid Device identifier (for logging/debugging - not functionally used)
     * @return [Result] containing [BulkAclResult] on success, or [NokeMobileLibraryError] on complete failure
     * 
     * ## Error Types
     * - [NokeMobileLibraryError.NotInitialized] - initialize() not called (manager cache empty)
     * - [NokeMobileLibraryError.InvalidInput] - Invalid phoneKeyId
     * - [NokeMobileLibraryError.NetworkError] - Network request failed
     * - [NokeMobileLibraryError.BulkAclFetchFailed] - Backend rejected or all storage failed
     * 
     * Note: Partial storage failures are reflected in [BulkAclResult], not thrown as errors.
     * 
     * ## Example
     * ```kotlin
     * // Step 1: REQUIRED - Initialize for this user/device
     * service.initialize(userId, deviceId).getOrThrow()
     * 
     * // Step 2: Fetch bulk ACLs (manager now in cache)
     * val result = service.generateBulkAcls(
     *     phoneKeyId = 67890,
     *     userId = userId,
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
     *         when (error) {
     *             is NokeMobileLibraryError.NotInitialized -> {
     *                 // MUST call initialize() before generateBulkAcls()!
     *                 Log.e(TAG, "Manager cache empty - call initialize() first")
     *             }
     *             else -> Log.e(TAG, "Bulk ACL fetch failed: $error")
     *         }
     *     }
     * )
     * ```
     */
    suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        udid: String
    ): Result<BulkAclResult> {
        val currentClient = client
            ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            currentClient.generateBulkAcls(phoneKeyId)
        } catch (e: Exception) {
            Log.e(TAG, "Bulk ACL generation failed", e)
            Result.failure(
                NokeMobileLibraryError.BulkAclFetchFailed(
                    phoneKeyId = phoneKeyId,
                    reason = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Refresh all ACLs by fetching from backend.
     * 
     * @deprecated This method depends on internal state. Use generateBulkAcls() directly with stored phone key ID.
     */
    @Deprecated(
        message = "This method depends on internal state. Use generateBulkAcls() directly with stored phone key ID.",
        level = DeprecationLevel.WARNING
    )
    suspend fun refreshAllAcls(
        userId: String,
        udid: String
    ): Result<BulkAclResult> {
        return Result.failure(
            NokeMobileLibraryError.UnknownError(
                "refreshAllAcls",
                UnsupportedOperationException("Use generateBulkAcls() directly with stored phone key ID")
            )
        )
    }

    // MARK: - Local Operations (via PhoneKeyFacade)

    /**
     * Check if phone is provisioned for a user.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return Result containing true if provisioned, false otherwise
     */
    suspend fun isProvisioned(userId: String, deviceId: String): Result<Boolean> {
        val ctx = context ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx)
            val info = facade.getPhoneKeyInfo(userId, deviceId)
            Result.success(info != null && info.isSuccess)
        } catch (e: Exception) {
            Log.e(TAG, "isProvisioned check failed", e)
            Result.failure(NokeMobileLibraryError.UnknownError("isProvisioned", e))
        }
    }

    /**
     * Get stored phone key ID for a user.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return Result containing phone key ID, or error if not provisioned
     */
    suspend fun getPhoneKeyId(userId: String, deviceId: String): Result<Int> {
        val ctx = context ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx)
            val info = facade.getPhoneKeyInfo(userId, deviceId)
            
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
    suspend fun listValidACLs(): List<BulkAclEnvelope> {
        val ctx = context ?: return emptyList()
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx)
            facade.listValidACLs()
        } catch (e: Exception) {
            Log.e(TAG, "listValidACLs failed", e)
            emptyList()
        }
    }

    /**
     * Cleanup all ACLs for a user (logout cleanup).
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return Result with Unit on success, or error
     */
    suspend fun cleanupAclsForUser(userId: String, deviceId: String): Result<Unit> {
        val ctx = context ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx)
            facade.clearAll(userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "cleanupAclsForUser failed", e)
            Result.failure(NokeMobileLibraryError.UnknownError("cleanupAclsForUser", e))
        }
    }

    /**
     * Check if a valid cached ACL exists for a specific lock.
     * 
     * Validation includes:
     * - ACL exists in cache
     * - ACL has not expired (expiresAt > current time)
     * - ACL has required UNLOCK permission (for full ACLs)
     *
     * @param userId User identifier
     * @param udid Device UDID (use [getDeviceUdid])
     * @param lockMac MAC address of the lock
     * @return Result with true if valid cached ACL exists, false otherwise
     * 
     * ## Example
     * ```kotlin
     * val service = PhoneKeyAccessService.getInstance()
     * val udid = PhoneKeyAccessService.getDeviceUdid(context)
     * val result = service.hasCachedAcl(userId = "12345", udid = udid, lockMac = "XX:XX:XX:XX:XX:XX")
     * 
     * result.fold(
     *     onSuccess = { hasCached ->
     *         if (hasCached) {
     *             Log.d(TAG, "Valid ACL exists in cache")
     *         } else {
     *             Log.d(TAG, "No valid ACL, need to fetch from backend")
     *         }
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Failed to check cached ACL", error)
     *     }
     * )
     * ```
     */
    suspend fun hasCachedAcl(userId: String, udid: String, lockMac: String): Result<Boolean> {
        val ctx = context ?: return Result.failure(NokeMobileLibraryError.NotInitialized)
        
        return try {
            val facade = PhoneKeyFacade.getInstance(ctx)
            val hasCached = facade.hasCachedAcl(userId, udid, lockMac)
            Result.success(hasCached)
        } catch (e: Exception) {
            Log.e(TAG, "hasCachedAcl failed for lock $lockMac", e)
            Result.failure(NokeMobileLibraryError.UnknownError("hasCachedAcl", e))
        }
    }

    /**
     * Clear cached state (for user switching).
     * 
     * **NOTE:** This is a no-op method. Actual cache clearing is handled by
     * [cleanupAclsForUser] which calls [PhoneKeyFacade.clearAll] to delete
     * both ACLs and provisioning data.
     * 
     * This method exists for API compatibility but does not need to be called
     * explicitly. Use [cleanupAclsForUser] for proper logout cleanup.
     * 
     * @see cleanupAclsForUser
     */
    fun clearCache() {
        // PhoneKeyFacade manages its own cache, no action needed here
        // Actual cleanup done via cleanupAclsForUser() → PhoneKeyFacade.clearAll()
        Log.d(TAG, "clearCache - no-op (use cleanupAclsForUser for logout)")
    }
}
