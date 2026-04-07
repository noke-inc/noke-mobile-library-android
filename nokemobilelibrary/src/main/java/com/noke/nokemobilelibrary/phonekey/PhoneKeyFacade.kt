package com.noke.nokemobilelibrary.phonekey

import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-level facade for phone key operations.
 *
 * Provides simplified API for common phone key workflows:
 * - Provisioning with key generation
 * - ACL caching and retrieval
 * - Key lifecycle management
 *
 * ## Design Philosophy (TIDY Architecture)
 *
 * **Thoughtful Intent-Driven Design** - PhoneKeyFacade:
 * - Provides **high-level convenience methods** for common workflows
 * - Abstracts complexity of key generation + provisioning + ACL management
 * - Coordinates between [PhoneKeyManager] (crypto) and [PhoneKeyPersistence] (storage)
 * - Uses interface-based design for testability and flexibility
 *
 * ## Threading
 * All public suspend functions are thread-safe via Mutex.
 * Safe to call from any coroutine context.
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Initialize once
 * val facade = PhoneKeyFacade.getInstance(context)
 *
 * // Ensure provisioned (generates keys, checks phone key info, returns cached ACLs)
 * val result = facade.ensureProvisioned(userId, deviceId)
 * result.fold(
 *     onSuccess = { cachedAcls ->
 *         println("Provisioned with ${cachedAcls.size} cached ACLs")
 *     },
 *     onFailure = { error ->
 *         println("Provisioning failed: ${error.message}")
 *     }
 * )
 *
 * // List valid ACLs
 * val validAcls = facade.listValidACLs()
 * validAcls.forEach { acl ->
 *     if (acl.isValid) {
 *         println("Lock ${acl.lockMac} accessible")
 *     }
 * }
 * ```
 *
 * @see PhoneKeyPersistence
 * @see PhoneKeyManager
 */
class PhoneKeyFacade private constructor(
    private val context: Context,
    private val persistence: PhoneKeyPersistence? = null
) {
    private val mutex = Mutex()
    private var currentUserId: String? = null
    private var currentDeviceId: String? = null
    private var currentManager: PhoneKeyManager? = null
    private var currentPersistence: PhoneKeyPersistence? = null

    companion object {
        private const val TAG = "PhoneKeyFacade"

        @Volatile
        private var instance: PhoneKeyFacade? = null

        /**
         * Get singleton instance.
         *
         * @param context Application context
         * @param persistence Optional custom persistence implementation (defaults to DefaultPhoneKeyPersistence)
         * @return PhoneKeyFacade singleton
         */
        @JvmStatic
        fun getInstance(context: Context, persistence: PhoneKeyPersistence? = null): PhoneKeyFacade {
            return instance ?: synchronized(this) {
                instance ?: PhoneKeyFacade(context.applicationContext, persistence).also { instance = it }
            }
        }

        /**
         * Reset singleton (for testing purposes).
         * ⚠️ Should only be used in test environments.
         */
        @JvmStatic
        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }

    /**
     * Get or create PhoneKeyManager + PhoneKeyPersistence for user/device.
     * 
     * **WARNING for noke-mobile-library-android users:**
     * This method will throw UnsupportedOperationException at runtime in the standalone library.
     * PhoneKeyFacade requires backend infrastructure not available in standalone SDK.
     * 
     * Use PhoneKeyAccessService with your own PhoneKeyCoreClient implementation instead.
     * See TEMPLATE_PhoneKeyCoreClient.kt for an example.
     */
    @Suppress("DEPRECATION_ERROR")  // Allow compilation despite ERROR-level deprecation
    private fun getOrCreateManager(userId: String, deviceId: String): Pair<PhoneKeyManager, PhoneKeyPersistence> {
        // Reuse if same user/device
        if (currentUserId == userId && currentDeviceId == deviceId && currentManager != null && currentPersistence != null) {
            return currentManager!! to currentPersistence!!
        }

        // Create new manager for this user/device
        // NOTE: This will throw UnsupportedOperationException in noke-mobile-library-android
        val manager = PhoneKeyManager(context, userId, deviceId)
        val persist = persistence ?: DefaultPhoneKeyPersistence(manager)

        // Cache for reuse
        currentUserId = userId
        currentDeviceId = deviceId
        currentManager = manager
        currentPersistence = persist

        return manager to persist
    }

    /**
     * Ensure phone is provisioned with keys and phone key info.
     *
     * This is the **main entry point** for phone key setup. Call this:
     * - After successful login
     * - On app startup (if already logged in)
     * - Before needing to access locks
     *
     * ## Flow
     * 1. Generate keys if not already present
     * 2. Check if phone key info exists in persistence
     * 3. Return cached ACLs (may be empty if no locks yet)
     *
     * ## Notes
     * - Does NOT call backend provisioning API (caller must provision if phone key info missing)
     * - Safe to call multiple times (idempotent)
     * - Thread-safe
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return Result containing list of cached ACLs, or error
     */
    suspend fun ensureProvisioned(userId: String, deviceId: String): Result<List<BulkAclEnvelope>> = mutex.withLock {
        try {
            val (manager, persist) = getOrCreateManager(userId, deviceId)

            // Step 1: Ensure keys exist
            Log.d(TAG, "ensureProvisioned - Ensuring keys exist for user=$userId, device=$deviceId")
            manager.ensureKeys()

            // Step 2: Check phone key info
            val phoneKeyInfo = persist.getPhoneKeyInfo(userId, deviceId)
            if (phoneKeyInfo == null) {
                Log.d(TAG, "ensureProvisioned - No phone key info found, caller must provision")
            } else {
                Log.d(TAG, "ensureProvisioned - Phone key info exists: keyId=${phoneKeyInfo.keyId}")
            }

            // Step 3: Return cached ACLs
            val cachedAcls = persist.listACLs()
            Log.d(TAG, "ensureProvisioned - Found ${cachedAcls.size} cached ACL(s)")

            Result.success(cachedAcls)
        } catch (e: Exception) {
            Log.e(TAG, "ensureProvisioned - Failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get public key for provisioning.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return Base64-encoded X9.62 uncompressed EC public key (65 bytes), or null if keys not generated
     */
    suspend fun getPublicKey(userId: String, deviceId: String): String? = mutex.withLock {
        try {
            val (manager, _) = getOrCreateManager(userId, deviceId)
            manager.ensureKeys()
            val publicKey = manager.getPublicKeyBase64()
            Log.d(TAG, "getPublicKey - Retrieved public key for user=$userId, device=$deviceId")
            publicKey
        } catch (e: Exception) {
            Log.e(TAG, "getPublicKey - Failed: ${e.message}", e)
            null
        }
    }

    /**
     * Save phone key info from provisioning response.
     *
     * Call this after successful backend provisioning to persist the phone key ID.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @param info Phone key info response from backend
     * @throws Exception if storage write fails
     */
    suspend fun savePhoneKeyInfo(userId: String, deviceId: String, info: PhoneKeyInfoResponse) = mutex.withLock {
        try {
            val (_, persist) = getOrCreateManager(userId, deviceId)
            persist.savePhoneKeyInfo(userId, deviceId, info)
            Log.d(TAG, "savePhoneKeyInfo - Saved phone key info for user=$userId: keyId=${info.keyId}")
        } catch (e: Exception) {
            Log.e(TAG, "savePhoneKeyInfo - Failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Get stored phone key info.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return PhoneKeyInfoResponse if exists, null if not provisioned
     */
    suspend fun getPhoneKeyInfo(userId: String, deviceId: String): PhoneKeyInfoResponse? = mutex.withLock {
        try {
            val (_, persist) = getOrCreateManager(userId, deviceId)
            val info = persist.getPhoneKeyInfo(userId, deviceId)
            Log.d(TAG, "getPhoneKeyInfo - Retrieved phone key info for user=$userId: ${info?.keyId ?: "none"}")
            info
        } catch (e: Exception) {
            Log.e(TAG, "getPhoneKeyInfo - Failed: ${e.message}", e)
            null
        }
    }

    /**
     * Save ACL for a lock.
     *
     * Note: Requires deviceId to be known. If deviceId is unknown, derive it from ACL or use ensureProvisioned() first.
     *
     * @param userId User identifier
     * @param lockMac Lock MAC address
     * @param acl ACL envelope to save
     * @throws Exception if storage write fails or no active session
     */
    suspend fun saveACL(userId: String, lockMac: String, acl: BulkAclEnvelope) = mutex.withLock {
        try {
            // Use current session if available and matches userId
            if (currentUserId == userId && currentPersistence != null) {
                currentPersistence!!.saveACL(userId, lockMac, acl)
                Log.d(TAG, "saveACL - Saved ACL for lock=$lockMac, user=$userId")
            } else {
                throw IllegalStateException("No active session for user=$userId. Call ensureProvisioned() first.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveACL - Failed for lock=$lockMac: ${e.message}", e)
            throw e
        }
    }

    /**
     * Get ACL for a specific lock.
     *
     * Works independently - does not require ensureProvisioned() to be called first.
     * However, requires a deviceId to initialize the manager if no session exists.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @param lockMac Lock MAC address
     * @return BulkAclEnvelope if found and valid, null otherwise
     */
    suspend fun getACL(userId: String, deviceId: String, lockMac: String): BulkAclEnvelope? = mutex.withLock {
        try {
            val (_, persist) = getOrCreateManager(userId, deviceId)
            val acl = persist.getACL(userId, lockMac)
            if (acl != null && acl.isValid) {
                Log.d(TAG, "getACL - Retrieved valid ACL for lock=$lockMac")
                acl
            } else {
                Log.d(TAG, "getACL - No valid ACL for lock=$lockMac")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getACL - Failed for lock=$lockMac: ${e.message}", e)
            null
        }
    }

    /**
     * Get ACL for a specific lock (legacy overload).
     *
     * Uses current session if available. Requires ensureProvisioned() to have been called.
     *
     * @param userId User identifier
     * @param lockMac Lock MAC address
     * @return BulkAclEnvelope if found and valid, null otherwise
     */
    suspend fun getACL(userId: String, lockMac: String): BulkAclEnvelope? = mutex.withLock {
        try {
            if (currentUserId == userId && currentDeviceId != null && currentPersistence != null) {
                return@withLock getACL(userId, currentDeviceId!!, lockMac)
            } else {
                Log.w(TAG, "getACL - No current session for user=$userId, call ensureProvisioned() first or use getACL(userId, deviceId, lockMac)")
                return@withLock null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getACL - Failed for lock=$lockMac: ${e.message}", e)
            null
        }
    }

    /**
     * List all valid (non-expired) ACLs for a user/device.
     *
     * Works independently - does not require ensureProvisioned() to be called first.
     *
     * Filters out expired ACLs automatically.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return List of valid ACLs
     */
    suspend fun listValidACLs(userId: String, deviceId: String): List<BulkAclEnvelope> = mutex.withLock {
        try {
            val (_, persist) = getOrCreateManager(userId, deviceId)
            val allAcls = persist.listACLs()
            val validAcls = allAcls.filter { it.isValid }
            Log.d(TAG, "listValidACLs - Found ${validAcls.size} valid ACL(s) out of ${allAcls.size} total for user=$userId, device=$deviceId")
            validAcls
        } catch (e: Exception) {
            Log.e(TAG, "listValidACLs - Failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * List all valid (non-expired) ACLs (legacy overload).
     *
     * Uses current session if available. Requires ensureProvisioned() to have been called.
     *
     * Filters out expired ACLs automatically.
     *
     * @return List of valid ACLs
     */
    suspend fun listValidACLs(): List<BulkAclEnvelope> = mutex.withLock {
        try {
            if (currentUserId != null && currentDeviceId != null) {
                return@withLock listValidACLs(currentUserId!!, currentDeviceId!!)
            } else {
                Log.w(TAG, "listValidACLs - No current session, call ensureProvisioned() first or use listValidACLs(userId, deviceId)")
                return@withLock emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "listValidACLs - Failed: ${e.message}", e)
            emptyList()
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
     * @param deviceId Device identifier
     * @param lockMac MAC address of the lock
     * @return true if valid cached ACL exists, false otherwise
     */
    suspend fun hasCachedAcl(userId: String, deviceId: String, lockMac: String): Boolean = mutex.withLock {
        try {
            val (manager, _) = getOrCreateManager(userId, deviceId)
            manager.hasCachedAcl(lockMac)
        } catch (e: Exception) {
            Log.e(TAG, "hasCachedAcl - Failed for lock=$lockMac: ${e.message}", e)
            false
        }
    }

    /**
     * Delete ACL for a specific lock.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @param lockMac Lock MAC address
     */
    suspend fun deleteACL(userId: String, deviceId: String, lockMac: String) = mutex.withLock {
        try {
            val (_, persist) = getOrCreateManager(userId, deviceId)
            persist.deleteACL(userId, lockMac)
            Log.d(TAG, "deleteACL - Deleted ACL for lock=$lockMac, user=$userId")
        } catch (e: Exception) {
            Log.e(TAG, "deleteACL - Failed for lock=$lockMac: ${e.message}", e)
            throw e
        }
    }

    /**
     * Delete ACL for a specific lock (legacy overload).
     *
     * Uses current session if available. Requires ensureProvisioned() to have been called.
     *
     * @param userId User identifier
     * @param lockMac Lock MAC address
     */
    suspend fun deleteACL(userId: String, lockMac: String) = mutex.withLock {
        try {
            if (currentUserId == userId && currentDeviceId != null && currentPersistence != null) {
                currentPersistence!!.deleteACL(userId, lockMac)
                Log.d(TAG, "deleteACL - Deleted ACL for lock=$lockMac, user=$userId")
            } else {
                throw IllegalStateException("No active session for user=$userId. Call ensureProvisioned() first or use deleteACL(userId, deviceId, lockMac)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteACL - Failed for lock=$lockMac: ${e.message}", e)
            throw e
        }
    }

    /**
     * Clear all phone key data for a user (logout cleanup).
     *
     * Removes:
     * - Phone key info
     * - All stored ACLs
     *
     * Note: Does NOT delete cryptographic keys from Keystore.
     * Keys persist for future re-provisioning.
     *
     * @param userId User identifier
     */
    suspend fun clearAll(userId: String) = mutex.withLock {
        try {
            if (currentUserId == userId && currentPersistence != null && currentDeviceId != null) {
                // Delete provisioning data (matches iOS behavior)
                currentPersistence!!.deletePhoneKeyInfo(userId, currentDeviceId!!)
                // Delete all ACLs
                currentPersistence!!.deleteAllACLs()
                Log.d(TAG, "clearAll - Cleared phone key info and ACLs for user=$userId")
            } else {
                Log.w(TAG, "clearAll - User mismatch or no current session")
            }
            
            // Clear cached session
            currentUserId = null
            currentDeviceId = null
            currentManager = null
            currentPersistence = null
        } catch (e: Exception) {
            Log.e(TAG, "clearAll - Failed for user=$userId: ${e.message}", e)
            throw e
        }
    }
}
