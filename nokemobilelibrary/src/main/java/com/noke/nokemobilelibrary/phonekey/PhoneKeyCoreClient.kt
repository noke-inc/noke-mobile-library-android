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
 * - Maintains clean separation between cryptographic operations and networking
 * - Enables testability through interface-based dependency injection
 * - Allows complete flexibility in networking, authentication, and backend integration
 *
 * ## Third-Party Integration
 *
 * Third parties can implement this interface to integrate with their own:
 * - Network layer (Retrofit, OkHttp, Fuel, Ktor, etc.)
 * - Authentication system (OAuth, JWT, API keys, etc.)
 * - Backend API endpoints (custom domains, staging vs production)
 * - Error handling and logging strategies
 *
 * ## Default Implementation
 *
 * A reference implementation is provided: [PhoneKeyCoreClientImpl]
 *
 * ## Multi-Context Support (Android-Specific Design)
 *
 * Unlike iOS which typically uses a single client per user/device, Android
 * implementations can manage multiple (userId, deviceId) contexts simultaneously.
 * This enables:
 * - Multi-device support (one user, multiple devices)
 * - Multi-user support (multiple users on one device)
 * - Flexible context management
 *
 * ## Thread Safety
 *
 * Implementations MUST be thread-safe. All suspend functions should be safe to call
 * from any coroutine context. Use appropriate synchronization primitives (Mutex,
 * single-threaded dispatcher, etc.).
 *
 * @see PhoneKeyAccessService
 * @see PhoneKeyCoreClientImpl
 */
interface PhoneKeyCoreClient {

    /**
     * Initialize the Phone Key Core client and ensure cryptographic keys are generated.
     *
     * This operation:
     * 1. Generates ECDSA P-256 key pair in Android Keystore (hardware-backed if available)
     * 2. Verifies keys are accessible and valid
     * 3. Returns the public key in Base64-encoded X9.62 uncompressed format (65 bytes)
     *
     * Implementations should cache initialization state per (userId, deviceId)
     * and return immediately if already initialized (idempotent behavior).
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param userId User identifier for key isolation
     * @param deviceId Device identifier (UDID) for key isolation
     * @return [Result] containing Base64-encoded public key on success, or error on failure
     */
    suspend fun initialize(userId: String, deviceId: String): Result<String>

    /**
     * Check if the client has been initialized for at least one user/device combination.
     *
     * @return true if [initialize] has been called successfully at least once, false otherwise
     */
    val isInitialized: Boolean

    /**
     * Provision a new phone key with the backend.
     *
     * This operation registers the device's public key with the backend and
     * receives a phone key identifier for all subsequent ACL operations.
     *
     * ## Backend Integration Points
     * Implementations must:
     * 1. Make authenticated request to phone key provisioning endpoint
     * 2. Send payload: `{ userId, phoneUdid, publicKey }`
     * 3. Parse response and extract phone key ID
     * 4. Store phone key ID locally via PhoneKeyManager
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param userId User identifier as String
     * @param deviceId Device identifier (UDID)
     * @param publicKey Base64-encoded X9.62 format public key
     * @return [Result] containing [PhoneKeyInfoResponse] with keyId on success, or error on failure
     */
    suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse>

    /**
     * Generate ACL (Access Control List) for a specific lock.
     *
     * This operation fetches the ACL envelope from the backend for a single lock.
     * The ACL envelope includes binary representation, signature, and permissions.
     * The ACL is stored locally in encrypted storage for offline access.
     *
     * ## Backend Integration Points
     * Implementations must:
     * 1. Make authenticated request to ACL generation endpoint
     * 2. Send payload: `{ userId, lockMac, phoneKeyId }`
     * 3. Parse response and extract ACL envelope (binary, signature, permissions)
     * 4. Store ACL locally via PhoneKeyManager for the appropriate user context
     *
     * ## Context Management
     * Implementations should use userId to lookup the appropriate PhoneKeyManager
     * for the user (since a single client can manage multiple users/devices).
     *
     * ## When to Use
     * Use for single-lock ACL refresh scenarios (after unlock failure or explicit update).
     * For most cases, prefer [generateBulkAcls] for efficiency.
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param userId User ID as integer (must match backend user ID)
     * @param lockMac Lock MAC address (format: "AA:BB:CC:DD:EE:FF")
     * @param phoneKeyId Phone key ID from provisioning
     * @return [Result] with Unit on success (ACL stored), or error on failure
     */
    suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int
    ): Result<Unit>

    /**
     * Generate multiple ACLs in bulk for all locks accessible to the user.
     *
     * This is the **preferred method** for ACL fetching in production:
     * - Significantly more efficient than individual requests
     * - Fetches all authorized locks in a single backend call
     * - Returns summary with success/failure counts
     * - Handles partial failures gracefully
     *
     * ## Backend Integration Points
     * Implementations must:
     * 1. Make authenticated request to bulk ACL endpoint
     * 2. Send payload: `{ phoneKeyId }`
     * 3. Parse response containing array of ACL envelopes
     * 4. Store each ACL locally via PhoneKeyManager
     * 5. Track success/failure for each ACL individually
     * 6. Return [BulkAclResult] with success/total counts
     *
     * ## Context Management
     * Implementations can use any available PhoneKeyManager since bulk ACLs
     * are associated with the phoneKeyId, not a specific manager instance.
     *
     * ## Partial Success Handling
     * If some ACLs fail to store, the operation should NOT fail completely.
     * Instead, return success with [BulkAclResult] indicating storage results.
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any coroutine context.
     *
     * @param phoneKeyId Phone key ID from provisioning
     * @return [Result] containing [BulkAclResult] with storage summary
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
     * - Debug/logging/analytics scenarios
     * - Key rotation verification
     *
     * ## Return Value
     * - Returns Base64-encoded X9.62 public key if valid
     * - Returns null if no key exists, keys are inaccessible, or validation fails
     *
     * ## Thread Safety
     * Must be thread-safe and safe to call from any context.
     *
     * @param userId User identifier for key lookup
     * @param deviceId Device identifier for key lookup
     * @return Base64-encoded public key in X9.62 format, or null if invalid/missing
     */
    suspend fun validateCurrentKey(userId: String, deviceId: String): String?
}
