package com.noke.nokemobilelibrary.phonekey

import android.util.Log
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse

/**
 * Protocol for persisting phone key data (keys, ACLs, metadata).
 *
 * Abstracts storage mechanism (EncryptedSharedPreferences, Room, custom storage, etc.)
 * to allow flexible persistence strategies while maintaining a consistent API.
 *
 * ## Design Philosophy (TIDY Architecture)
 *
 * **Thoughtful Intent-Driven Design** - This interface:
 * - Exposes **what** to persist, not **how** to persist it
 * - Allows complete flexibility in storage implementation
 * - Maintains clean separation between business logic and persistence concerns
 * - Enables testability through interface-based dependency injection
 *
 * ## Third-Party Integration
 *
 * Third parties can implement this interface to integrate with their own:
 * - Storage systems (Room, SQLite, Realm, Cloud Storage)
 * - Data migration strategies
 * - Backup/sync mechanisms
 * - Encryption schemes
 *
 * ## Default Implementation
 *
 * A reference implementation is provided: [DefaultPhoneKeyPersistence]
 *
 * @see PhoneKeyFacade
 * @see DefaultPhoneKeyPersistence
 */
interface PhoneKeyPersistence {

    // MARK: - Phone Key Info

    /**
     * Retrieve phone key info for a user.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return PhoneKeyInfoResponse if exists, null if not provisioned
     * @throws Exception if storage access fails
     */
    fun getPhoneKeyInfo(userId: String, deviceId: String): PhoneKeyInfoResponse?

    /**
     * Save phone key info for a user.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @param info Phone key info response from provisioning
     * @throws Exception if storage write fails
     */
    fun savePhoneKeyInfo(userId: String, deviceId: String, info: PhoneKeyInfoResponse)

    /**
     * Delete phone key info for a user.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @throws Exception if storage access fails
     */
    fun deletePhoneKeyInfo(userId: String, deviceId: String)

    // MARK: - Bulk ACLs

    /**
     * Get ACL for a specific lock.
     *
     * @param userId User identifier
     * @param lockMac Lock MAC address
     * @return BulkAclEnvelope if found, null otherwise
     * @throws Exception if storage access fails
     */
    fun getACL(userId: String, lockMac: String): BulkAclEnvelope?

    /**
     * List all ACLs for current user.
     *
     * @return List of all stored ACLs (may include expired ones)
     * @throws Exception if storage access fails
     */
    fun listACLs(): List<BulkAclEnvelope>

    /**
     * Save ACL for a lock.
     *
     * @param userId User identifier
     * @param lockMac Lock MAC address
     * @param acl ACL to save
     * @throws Exception if storage write fails
     */
    fun saveACL(userId: String, lockMac: String, acl: BulkAclEnvelope)

    /**
     * Delete ACL for a specific lock.
     *
     * @param userId User identifier
     * @param lockMac Lock MAC address
     * @throws Exception if storage access fails
     */
    fun deleteACL(userId: String, lockMac: String)

    /**
     * Delete all ACLs for current user.
     *
     * @throws Exception if storage access fails
     */
    fun deleteAllACLs()
}

/**
 * Default implementation using PhoneKeyManager's EncryptedSharedPreferences.
 *
 * Stores data in EncryptedSharedPreferences (AES-256-GCM) via PhoneKeyManager.
 * Each (userId, deviceId) combination gets isolated storage.
 *
 * ## Thread Safety
 * This implementation is thread-safe. PhoneKeyManager uses EncryptedSharedPreferences
 * which is thread-safe for read/write operations.
 *
 * ## Storage Location
 * - Phone key info: Stored as provisioning metadata in PhoneKeyManager
 * - ACLs: Stored as ACL envelopes in PhoneKeyManager
 *
 * @param manager PhoneKeyManager instance for the specific user/device
 */
internal class DefaultPhoneKeyPersistence(
    private val manager: PhoneKeyManager
) : PhoneKeyPersistence {

    companion object {
        private const val TAG = "PhoneKeyPersistence"
    }

    override fun getPhoneKeyInfo(userId: String, deviceId: String): PhoneKeyInfoResponse? {
        // Check if provisioned
        if (!manager.isProvisioned()) {
            return null
        }

        // Get stored phone key ID
        val keyId = manager.getPhoneKeyId()?.toIntOrNull()
            ?: return null

        // Construct PhoneKeyInfoResponse from stored data
        return PhoneKeyInfoResponse.success(keyId)
    }

    override fun savePhoneKeyInfo(userId: String, deviceId: String, info: PhoneKeyInfoResponse) {
        if (info.isSuccess && info.keyId != null) {
            // PhoneKeyManager stores this via setProvisioned
            manager.setProvisioned(info.keyId.toString(), userId)
        }
    }

    override fun deletePhoneKeyInfo(userId: String, deviceId: String) {
        try {
            manager.clearProvisioningData()
            Log.d("PhoneKeyPersistence", "Deleted phone key info for user=$userId, device=$deviceId")
        } catch (e: Exception) {
            Log.e("PhoneKeyPersistence", "Failed to delete phone key info: ${e.message}", e)
            throw e
        }
    }

    override fun getACL(userId: String, lockMac: String): BulkAclEnvelope? {
        return manager.getBulkAclEnvelope(lockMac)
    }

    override fun listACLs(): List<BulkAclEnvelope> {
        return manager.listBulkAclEnvelopes()
    }

    override fun saveACL(userId: String, lockMac: String, acl: BulkAclEnvelope) {
        manager.storeBulkAclEnvelope(acl)
    }

    override fun deleteACL(userId: String, lockMac: String) {
        manager.deleteACL(userId, lockMac)
    }

    override fun deleteAllACLs() {
        manager.cleanupAllAcls()
    }
}
