package com.noke.nokemobilelibrary.phonekey

import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse

/**
 * PhoneKeyCoreClient - Abstraction layer for all Phone Key Core operations.
 *
 * This interface defines the contract for managing phone key cryptographic operations,
 * provisioning, and ACL (Access Control List) management. It allows third-party
 * implementations to provide custom networking layers while maintaining API parity
 * with the iOS implementation.
 *
 * ## Design Philosophy (TIDY Architecture)
 *
 * **Thoughtful Intent-Driven Design** - This interface:
 * - Exposes **what** needs to be done (intent), not **how** to do it (implementation)
 * - Allows complete flexibility in networking, authentication, and backend integration
 * - Maintains clean separation between cryptographic operations and networking
 * - Enables testability through interface-based dependency injection
 *
 * ## Third-Party Integration
 *
 * Third parties can implement this interface to integrate with their own:
 * - Network layer (Retrofit, OkHttp, Fuel, Ktor, etc.)
 * - Authentication system
 * - Backend API endpoints
 * - Error handling and logging strategies
 *
 * ## Default Implementation
 *
 * A default implementation is provided that integrates with StorageSmartEntry's
 * existing infrastructure. See [PhoneKeyCoreClientImpl] for reference.
 *
 * ## Thread Safety
 *
 * Implementations must be thread-safe. All suspend functions should be safe to call
 * from any coroutine context. Consider using a Mutex or single-threaded dispatcher
 * to serialize access to PhoneKeyManager if needed.
 *
 * ## Example Implementation
 *
 * ```kotlin
 * class MyPhoneKeyCoreClient(
 *     private val context: Context,
 *     private val myApiClient: MyApiClient
 * ) : PhoneKeyCoreClient {
 *
 *     private val phoneKeyManager = PhoneKeyManager(context, userId, udid)
 *     private val mutex = Mutex()
 *     private var initialized = false
 *
 *     override suspend fun initialize(userId: String, deviceId: String): Result<String> =
 *         withContext(Dispatchers.IO) {
 *             mutex.withLock {
 *                 if (initialized) return@withContext Result.success(getPublicKey())
 *
 *                 phoneKeyManager.ensureKeys()
 *                 initialized = true
 *                 Result.success(phoneKeyManager.getPublicKey())
 *             }
 *         }
 *
 *     override val isInitialized: Boolean
 *         get() = initialized
 *
 *     override suspend fun provisionPhoneKey(
 *         userId: String,
 *         deviceId: String,
 *         publicKey: String
 *     ): Result<PhoneKeyInfoResponse> {
 *         return myApiClient.provisionPhoneKey(userId, deviceId, publicKey)
 *     }
 *
 *     override suspend fun generateAcl(
 *         userId: Int,
 *         lockMac: String,
 *         phoneKeyId: Int
 *     ): Result<Unit> {
 *         return myApiClient.generateAcl(userId, lockMac, phoneKeyId)
 *     }
 *
 *     override suspend fun generateBulkAcls(phoneKeyId: Int): Result<BulkAclResult> {
 *         return myApiClient.generateBulkAcls(phoneKeyId)
 *     }
 *
 *     override suspend fun validateCurrentKey(userId: String, deviceId: String): String? {
 *         return phoneKeyManager.getPublicKey()
 *     }
 * }
 * ```
 *
 * ## Usage in PhoneKeyAccessService
 *
 * ```kotlin
 * // In Application.onCreate()
 * val client = MyPhoneKeyCoreClient(this, myApiClient)
 * PhoneKeyAccessService.setSharedClient(client)
 * ```
 *
 * @see PhoneKeyAccessService
 * @see PhoneKeyCoreClientImpl
 */
interface PhoneKeyCoreClient {

    /**
     * Initialize the Phone Key Core client and ensure cryptographic keys are generated.
     *
     * This operation should:
     * 1. Generate ECDSA P-256 key pair in secure storage (e.g., Android Keystore)
     * 2. Verify keys are accessible and valid
     * 3. Return the public key in Base64-encoded X9.62 format
     *
     * Implementations should cache initialization state and return immediately
     * if already initialized (idempotent).
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param userId User identifier for key isolation
     * @param deviceId Device identifier (UDID) for key isolation
     * @return [Result] containing Base64-encoded public key on success, or error on failure
     *
     * ## Example
     * ```kotlin
     * val result = client.initialize(userId = "12345", deviceId = androidId)
     * result.fold(
     *     onSuccess = { publicKey ->
     *         Log.d(TAG, "Initialized with public key: $publicKey")
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Initialization failed: ${error.message}")
     *     }
     * )
     * ```
     */
    suspend fun initialize(userId: String, deviceId: String): Result<String>

    /**
     * Check if the client has been initialized.
     *
     * @return true if [initialize] has been called successfully, false otherwise
     */
    val isInitialized: Boolean

    /**
     * Provision a new phone key with the backend.
     *
     * This operation registers the device's public key with the backend and
     * receives a phone key identifier that will be used for all subsequent
     * ACL operations.
     *
     * ## Backend Integration
     * Implementations should:
     * 1. Make authenticated request to phone key provisioning endpoint
     * 2. Send payload: { userId, phoneUdid, publicKey }
     * 3. Parse response and extract phone key ID
     * 4. Store phone key ID locally for future use
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param userId User identifier as String
     * @param deviceId Device identifier (UDID)
     * @param publicKey Base64-encoded X9.62 format public key
     * @return [Result] containing [PhoneKeyInfoResponse] on success, or error on failure
     *
     * ## Example
     * ```kotlin
     * val result = client.provisionPhoneKey(
     *     userId = "12345",
     *     deviceId = androidId,
     *     publicKey = base64PublicKey
     * )
     * result.fold(
     *     onSuccess = { response ->
     *         Log.d(TAG, "Provisioned with ID: ${response.keyId}")
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Provisioning failed: ${error.message}")
     *     }
     * )
     * ```
     */
    suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse>

    /**
     * Generate ACL (Access Control List) for a specific lock.
     *
     * This operation fetches the ACL envelope from the backend for a single lock,
     * including the ACL binary, signature, and permissions. The ACL should be
     * stored locally in encrypted storage for offline access.
     *
     * ## Backend Integration
     * Implementations should:
     * 1. Make authenticated request to ACL generation endpoint
     * 2. Send payload: { userId, lockMac, phoneKeyId }
     * 3. Parse response and extract ACL envelope (binary, signature, permissions)
     * 4. Store ACL locally via PhoneKeyManager
     *
     * ## When to Use
     * Use for single-lock ACL refresh scenarios (after unlock failure or explicit update).
     * For most cases, prefer [generateBulkAcls] for efficiency.
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param userId User ID as integer
     * @param lockMac Lock MAC address (e.g., "AA:BB:CC:DD:EE:FF")
     * @param phoneKeyId Phone key ID from provisioning
     * @return [Result] with Unit on success (ACL stored), or error on failure
     *
     * ## Example
     * ```kotlin
     * val result = client.generateAcl(
     *     userId = 12345,
     *     lockMac = "AA:BB:CC:DD:EE:FF",
     *     phoneKeyId = 67890
     * )
     * result.fold(
     *     onSuccess = { Log.d(TAG, "ACL stored successfully") },
     *     onFailure = { error -> Log.e(TAG, "ACL fetch failed: ${error.message}") }
     * )
     * ```
     */
    suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int
    ): Result<Unit>

    /**
     * Generate multiple ACLs in bulk for all locks accessible to the user.
     *
     * This is the **preferred method** for ACL fetching:
     * - More efficient than individual requests
     * - Fetches all authorized locks in one call
     * - Returns summary with success/failure counts
     *
     * ## Backend Integration
     * Implementations should:
     * 1. Make authenticated request to bulk ACL endpoint
     * 2. Send payload: { phoneKeyId }
     * 3. Parse response containing array of ACL envelopes
     * 4. Store each ACL locally via PhoneKeyManager
     * 5. Track success/failure for each ACL
     * 6. Return [BulkAclResult] with summary
     *
     * ## Partial Success Handling
     * If some ACLs fail to store, the operation should NOT fail completely.
     * Instead, return success with [BulkAclResult] indicating which ACLs
     * were stored successfully.
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param phoneKeyId Phone key ID from provisioning
     * @return [Result] containing [BulkAclResult] with storage summary
     *
     * ## Example
     * ```kotlin
     * val result = client.generateBulkAcls(phoneKeyId = 67890)
     * result.fold(
     *     onSuccess = { bulkResult ->
     *         when {
     *             bulkResult.isFullSuccess ->
     *                 Log.d(TAG, "All ${bulkResult.totalCount} ACLs stored")
     *             bulkResult.hasPartialSuccess ->
     *                 Log.w(TAG, "${bulkResult.successCount}/${bulkResult.totalCount} stored")
     *             bulkResult.isEmpty ->
     *                 Log.i(TAG, "No locks assigned")
     *             else ->
     *                 Log.e(TAG, "All ACL storage failed")
     *         }
     *     },
     *     onFailure = { error ->
     *         Log.e(TAG, "Bulk ACL fetch failed: ${error.message}")
     *     }
     * )
     * ```
     */
    suspend fun generateBulkAcls(phoneKeyId: Int): Result<BulkAclResult>

    /**
     * Validate and retrieve the current public key.
     *
     * This operation verifies that the stored key pair is valid and accessible,
     * and returns the public key if successful.
     *
     * ## Use Cases
     * - Pre-flight check before provisioning
     * - Verify key integrity after app update or system restore
     * - Debug/logging scenarios
     *
     * ## Return Value
     * - Returns Base64-encoded public key if valid
     * - Returns null if no key exists or validation fails
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any context.
     *
     * @param userId User identifier for key lookup
     * @param deviceId Device identifier for key lookup
     * @return Base64-encoded public key, or null if invalid/missing
     *
     * ## Example
     * ```kotlin
     * val publicKey = client.validateCurrentKey(userId = "12345", deviceId = androidId)
     * if (publicKey != null) {
     *     Log.d(TAG, "Valid key found: $publicKey")
     * } else {
     *     Log.w(TAG, "No valid key - need to initialize")
     * }
     * ```
     */
    suspend fun validateCurrentKey(userId: String, deviceId: String): String?
}
