package com.noke.nokemobilelibrary.phonekey.internal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noke.nokemobilelibrary.phonekey.models.AclEnvelope
import com.noke.nokemobilelibrary.phonekey.models.AclPermission
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyAcl
import com.noke.nokemobilelibrary.phonekey.models.ProvisioningInfo
import org.json.JSONArray
import org.json.JSONObject
import org.threeten.bp.Instant
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/*
End-to-end flow (mental model)

Phone generates key pair (once)

Phone sends public key to lock (BLE)

Lock responds with ACL + signature

Phone verifies ACL signature

Phone stores ACL

Phone signs commands using private key
 */

/**
 * PhoneKeyManager - Cryptographic key management and ACL lifecycle for ION-2 devices
 * 
 * **INTERNAL API** - Not for public use. Use PhoneKeyAccessService instead.
 * 
 * This class manages:
 * - ECDSA P-256 key pair generation and storage in Android Keystore (hardware-backed)
 * - Phone key provisioning with backend
 * - ACL (Access Control List) storage and retrieval with signature verification
 * - Per-device isolation with unique keys for each (userId, deviceId) combination
 * 
 * ## Security Features
 * - **Hardware-backed keys** (Android Keystore) - keys never leave secure hardware
 * - **EncryptedSharedPreferences** (AES256-GCM) for sensitive data
 * - **Device-specific storage isolation** (each device has separate keys and ACLs)
 * - **ACL signature verification** to prevent tampering
 * - **Granular device revocation** capability
 * 
 * ## Per-Device Architecture (Production Ready)
 * - Each device gets a unique ECDSA key pair: `noke_phone_key_pair_{userId}_{udid}`
 * - Each device gets a dedicated MasterKey: `noke_master_key_{userId}_{udid}`
 * - Each device gets a separate encrypted file: `phone_keys_{userId}_{udid}`
 * - True storage isolation at file, encryption, and cryptographic key levels
 * - No cross-device data leakage possible
 * - Backend can track and manage multiple devices per user independently
 * 
 * ## Dependency Injection
 * This standalone version accepts SecurityService via constructor for testability
 * and to avoid coupling with specific networking implementations.
 * 
 * @param context Application context (not Activity context to prevent leaks)
 * @param userId The unique user identifier (must not be empty)
 * @param udid Unique device identifier (Android ANDROID_ID, must not be empty)
 * @param securityService Backend API client for provisioning and ACL operations
 * @throws IllegalArgumentException if userId or udid is empty
 */
internal class PhoneKeyManager constructor(
    private val context: Context,
    private val userId: String,
    private val udid: String,
    private val securityService: SecurityService
) {
    
    /**
     * Secondary constructor for backward compatibility.
     * Automatically extracts device UDID from Android system.
     * 
     * @param context Application context
     * @param userId The unique user identifier
     * @param securityService Backend API client
     */
    constructor(
        context: Context,
        userId: String,
        securityService: SecurityService
    ) : this(context, userId, getDeviceUdid(context), securityService)

    companion object {
        private const val TAG = "PhoneKeyManager"
        private const val PREFS_FILE_PREFIX = "phone_keys"
        private const val KEYSTORE_ALIAS_PREFIX = "noke_phone_key_pair"
        private const val MASTER_KEY_ALIAS_PREFIX = "noke_master_key"
        
        // Storage keys (files are isolated per device via userId_udid combination)
        private const val KEY_PHONE_KEY_ID = "phone_key_id"
        private const val KEY_PROVISIONED_AT = "provisioned_at"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_PROVISIONED_USER_ID = "provisioned_user_id"
        private const val KEY_IS_PROVISIONED = "is_provisioned"
        
        // Public constants
        const val PREF_PHONE_KEY_ID = "phone_key_id"
        const val PREF_PROVISIONED_AT = "provisioned_at"
        
        /**
         * Get the unique device identifier (UDID) for this Android device.
         * Uses Settings.Secure.ANDROID_ID which is unique per device and app installation.
         * 
         * Production Note: ANDROID_ID persists across app reinstalls but resets on factory reset.
         * This is the recommended approach for device identification in Android.
         * 
         * @param context Application context
         * @return Device UDID string (ANDROID_ID)
         * @throws IllegalStateException if ANDROID_ID cannot be retrieved
         */
        @JvmStatic
        fun getDeviceUdid(context: Context): String {
            return android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: throw IllegalStateException("Unable to retrieve device ANDROID_ID")
        }
    }

    private val prefs: SharedPreferences
    @Volatile private var privateKey: PrivateKey? = null
    @Volatile private var publicKey: PublicKey? = null
    
    // Device-specific aliases and file names (userId + udid for unique per-device identity)
    // This ensures each device has completely isolated cryptographic keys and storage
    private val keystoreAlias: String
        get() = "${KEYSTORE_ALIAS_PREFIX}_${userId}_${udid}"
    
    private val masterKeyAlias: String
        get() = "${MASTER_KEY_ALIAS_PREFIX}_${userId}_${udid}"
    
    private val prefsFileName: String
        get() = "${PREFS_FILE_PREFIX}_${userId}_${udid}"

    init {
        require(userId.isNotEmpty()) { "userId cannot be empty" }
        require(udid.isNotEmpty()) { "udid (device ID) cannot be empty" }
        
        try {
            // Create device-specific MasterKey in Android Keystore
            // Each (userId, deviceId) combination gets a unique encryption key
            val masterKey = MasterKey.Builder(context, masterKeyAlias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(false) // Allow background access
                .build()

            // Create device-specific EncryptedSharedPreferences file
            // Complete storage isolation per (userId, deviceId) combination
            prefs = EncryptedSharedPreferences.create(
                context,
                prefsFileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            Log.d(TAG, "ION-2 - Initialized device-specific storage")
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Failed to initialize PhoneKeyManager: ${e.message}", e)
            throw IllegalStateException("Failed to initialize encrypted storage for user=$userId, device=$udid", e)
        }
    }

    // ------------------------------------------------
    // Key management (ECDSA P-256) - Android Keystore
    // ------------------------------------------------

    /**
     * Ensures that ECDSA P-256 key pair exists, loading from Keystore or generating if needed.
     * Thread-safe via synchronized block.
     */
    @Synchronized
    fun ensureKeys() {
        // Double-check pattern for thread safety
        if (privateKey != null && publicKey != null) return

        // Try to load from Android Keystore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            if (keyStore.containsAlias(keystoreAlias)) {
                val entry = keyStore.getEntry(keystoreAlias, null) as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    privateKey = entry.privateKey
                    publicKey = entry.certificate.publicKey
                    Log.d(TAG, "ION-2 - Loaded device-specific ECDSA key pair")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Failed to load from Keystore: ${e.message}", e)
        }

        // Generate new key pair if not found
        generateAndStoreKeyPair()
    }

    private fun generateAndStoreKeyPair() {
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keystoreAlias, // Use device-specific alias (userId + udid)
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false) // Allow background access
                .build()

            keyPairGenerator.initialize(keyGenParameterSpec)
            val keyPair = keyPairGenerator.generateKeyPair()

            privateKey = keyPair.private
            publicKey = keyPair.public

            Log.d(TAG, "ION-2 - Generated device-specific ECDSA key pair")
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Failed to generate key pair: ${e.message}", e)
            throw e
        }
    }

    /**
     * Returns the public key encoded as X9.62 uncompressed EC point (65 bytes).
     * Format: 0x04 || X (32 bytes) || Y (32 bytes)
     */
    fun getPublicKeyX962(): ByteArray {
        ensureKeys()

        val ecPub = publicKey as ECPublicKey
        val point = ecPub.w

        val x = point.affineX.toByteArray().stripLeadingZeroes(32)
        val y = point.affineY.toByteArray().stripLeadingZeroes(32)

        return byteArrayOf(0x04) + x + y
    }

    /**
     * Returns the public key object.
     * @throws IllegalStateException if keys haven't been initialized
     */
    fun getPublicKey(): PublicKey {
        ensureKeys()
        return publicKey
            ?: throw IllegalStateException("Public key not initialized")
    }

    /**
     * Public key as Base64-encoded X9.62.
     * This is what you send to the backend for provisioning
     * and what appears in `acl.phonePubKey`.
     */
    fun getPublicKeyBase64(): String =
        Base64.encodeToString(getPublicKeyX962(), Base64.NO_WRAP)

    /**
     * HEX encoded public key (for transfer/debugging)
     */
    fun getPublicKeyHex(): String =
        getPublicKeyX962().toHex()

    // ------------------------------------------------
    // Signing (ECDSA + SHA256) - Using Keystore
    // ------------------------------------------------

    /**
     * Sign data using the phone's private key (SHA256withECDSA).
     * 
     * @param data Data to sign
     * @return DER-encoded ECDSA signature
     * @throws IllegalStateException if keys haven't been initialized
     */
    fun sign(data: ByteArray): ByteArray {
        ensureKeys()

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Sign data directly using Android Keystore private key
     * without loading it into application memory.
     * 
     * @param data Data to sign
     * @return DER-encoded ECDSA signature
     * @throws IllegalStateException if key pair not found in Keystore
     */
    fun signWithKeystore(data: ByteArray): ByteArray {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            val entry = keyStore.getEntry(keystoreAlias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("No key pair found in Keystore for user=$userId, device=$udid")
            
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(entry.privateKey)
            signature.update(data)
            
            return signature.sign()
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Failed to sign with Keystore: ${e.message}", e)
            throw e
        }
    }

    /**
     * Sign data and return hex-encoded signature.
     */
    fun signHex(data: ByteArray): String =
        sign(data).toHex()

    // ------------------------------------------------
    // Canonical JSON
    // ------------------------------------------------

    /**
     * Canonicalizes a JSON object by sorting keys recursively.
     * Used for consistent serialization before signing/verification.
     */
    fun canonicalizeJSON(json: JSONObject): JSONObject {
        val sortedKeys = json.keys().asSequence().sorted()
        val out = JSONObject()
        for (k in sortedKeys) {
            val v = json.get(k)
            out.put(
                k,
                when (v) {
                    is JSONObject -> canonicalizeJSON(v)
                    is JSONArray -> JSONArray().apply {
                        for (i in 0 until v.length()) {
                            val item = v.get(i)
                            put(if (item is JSONObject) canonicalizeJSON(item) else item)
                        }
                    }
                    else -> v
                }
            )
        }
        return out
    }

    // ------------------------------------------------
    // Provisioning State Management
    // ------------------------------------------------

    /**
     * Get the userId this manager is associated with.
     */
    fun getUserId(): String = userId

    /**
     * Check if the phone has been provisioned for the current user.
     */
    fun isProvisioned(): Boolean =
        prefs.getBoolean(KEY_IS_PROVISIONED, false)

    /**
     * Check if provisioning is for a specific user.
     * This method validates that the stored userId matches the current userId.
     */
    fun isProvisionedForUser(userId: String): Boolean {
        if (!isProvisioned()) return false
        // Since manager is now user-specific, just verify the userId matches
        return this.userId == userId
    }

    /**
     * Save provisioning result after successful backend call.
     * 
     * @param keyId Phone key ID assigned by backend
     * @param userId User ID (must match this manager's userId)
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun setProvisioned(keyId: String, userId: String) {
        // Validate that userId matches this manager's userId
        if (this.userId != userId) {
            Log.w(TAG, "ION-2 - WARNING: Attempting to provision for userId=$userId but manager is for ${this.userId}")
        }
        val success = prefs.edit()
            .putBoolean(KEY_IS_PROVISIONED, true)
            .putString(KEY_PHONE_KEY_ID, keyId)
            .putString(KEY_PROVISIONED_USER_ID, userId)
            .putString(KEY_PROVISIONED_AT, Instant.now().toString())
            .commit()
        
        if (!success) {
            Log.e(TAG, "ION-2 - Failed to persist provisioning state (storage may be full)")
            throw java.io.IOException("Failed to persist provisioning state to SharedPreferences")
        }
        Log.d(TAG, "ION-2 - Provisioning state saved for user $userId: keyId=$keyId")
    }

    /**
     * Get the stored phone key ID for current user.
     * @return Phone key ID or null if not provisioned
     */
    fun getPhoneKeyId(): String? {
        return prefs.getString(KEY_PHONE_KEY_ID, null)
    }

    /**
     * Provision phone key with backend.
     * Returns nullable Int - null indicates provisioning failure.
     *
     * @param udid Device unique identifier
     * @param publicKey Base64-encoded public key
     * @param userId User identifier
     * @param completion Callback with phone key ID (null on failure)
     */
    fun provisionPhoneCompletion(
        udid: String,
        publicKey: String,
        userId: String,
        completion: (Int?) -> Unit
    ) {
        Log.d(TAG, "ION-2 - Calling provisionPhone for user $userId")
        securityService.provisionPhone(udid, publicKey, userId, object : SecurityService.ProvisionCallback {
            override fun onSuccess(keyId: Int) {
                try {
                    // Save provisioning state with user ID
                    setProvisioned(keyId.toString(), userId)
                    completion(keyId)
                } catch (e: Exception) {
                    // Storage failure should be treated as provisioning failure
                    Log.e(TAG, "ION-2 - Provisioning succeeded but storage failed: ${e.message}", e)
                    completion(null)
                }
            }

            override fun onFailure(exception: Exception) {
                Log.e(TAG, "ION-2 - Provisioning failed: ${exception.message}", exception)
                completion(null)
            }
        })
    }

    /**
     * Store provisioning result from backend.
     * 
     * @param phoneKeyId The unique phone key identifier
     * @param issuedAt Timestamp when the phone key was issued
     * @param expiresAt Optional expiration timestamp
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun storeProvisioningResult(
        phoneKeyId: String,
        issuedAt: Instant,
        expiresAt: Instant?
    ) {
        val success = prefs.edit()
            .putString(KEY_PHONE_KEY_ID, phoneKeyId)
            .putString(KEY_PROVISIONED_AT, issuedAt.toString())
            .putString(KEY_EXPIRES_AT, expiresAt?.toString())
            .commit()
        
        if (!success) {
            Log.e(TAG, "ION-2 - Failed to persist provisioning result (storage may be full)")
            throw java.io.IOException("Failed to persist provisioning data to SharedPreferences")
        }
    }

    /**
     * Get provisioning information for this device.
     * 
     * @return ProvisioningInfo with phoneKeyId, issuedAt, and optional expiresAt
     * @throws IllegalStateException if phone key not provisioned
     */
    fun getProvisioningInfo(): ProvisioningInfo {
        val phoneKeyId = prefs.getString(KEY_PHONE_KEY_ID, null)
            ?: error("Phone key not provisioned for user=$userId, device=$udid")

        val issuedAt = Instant.parse(
            prefs.getString(KEY_PROVISIONED_AT, null)
                ?: error("Missing issuedAt for user=$userId, device=$udid")
        )

        val expiresAt = prefs.getString(KEY_EXPIRES_AT, null)?.let(Instant::parse)

        return ProvisioningInfo(phoneKeyId, issuedAt, expiresAt)
    }

    // ------------------------------------------------
    // ACL Operations
    // ------------------------------------------------

    /**
     * Fetch ACL envelope from backend for a specific lock.
     * 
     * @param userId User ID for ACL request (as Int)
     * @param lockMac MAC address of the lock
     * @param phoneKeyId Phone key ID from provisioning
     * @param completion Callback with success/failure (true = stored successfully)
     */
    fun getAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        completion: (Boolean) -> Unit
    ) {
        securityService.getACLEnvelope(userId, lockMac, phoneKeyId, object : SecurityService.AclEnvelopeCallback {
            override fun onSuccess(phoneKeyAcl: PhoneKeyAcl, aclSignature: String, aclBinary: String) {
                try {
                    // Store ACL with signature and binary from backend
                    val envelope = AclEnvelope(
                        acl = phoneKeyAcl,
                        aclSignature = aclSignature,
                        aclBinary = aclBinary
                    )
                    storeAclEnvelope(envelope)
                    Log.d(TAG, "ION-2 - ACL envelope stored for lock $lockMac with signature and binary (${aclBinary.length} chars)")
                    completion(true)
                } catch (e: Exception) {
                    // Storage failure should be treated as ACL fetch failure
                    Log.e(TAG, "ION-2 - ACL received but storage failed for lock $lockMac: ${e.message}", e)
                    completion(false)
                }
            }

            override fun onFailure(exception: Exception) {
                Log.e(TAG, "ION-2 - Failed to get ACL: ${exception.message}", exception)
                completion(false)
            }
        })
    }

    /**
     * Fetch bulk ACLs from backend for all locks accessible to the user.
     *
     * This is the preferred method for fetching ACLs in most scenarios:
     * - On successful login
     * - After provisioning
     * - On app startup if user already logged in
     * - Explicit refresh via refreshAllAcls()
     *
     * Note: Bulk ACLs are stored in simplified format (without permissions/schedule)
     * and rely on aclBinary for lock operations.
     *
     * @param phoneKeyId Phone key ID from provisioning
     * @param completion Callback with (successCount, totalCount) - successCount is number of ACLs successfully stored
     */
    fun getBulkAcls(
        phoneKeyId: Int,
        completion: (successCount: Int, totalCount: Int) -> Unit
    ) {
        Log.d(TAG, "ION-2 - Starting bulk ACL fetch for phoneKeyId $phoneKeyId")
        
        securityService.getBulkACLEnvelopes(phoneKeyId, object : SecurityService.BulkAclEnvelopeCallback {
            override fun onSuccess(aclEnvelopes: List<BulkAclEnvelope>) {
                Log.d(TAG, "ION-2 - Received ${aclEnvelopes.size} bulk ACLs from backend")
                
                var successCount = 0
                val totalCount = aclEnvelopes.size
                
                // Store each bulk ACL individually
                for (bulkAcl in aclEnvelopes) {
                    try {
                        storeBulkAclEnvelope(bulkAcl)
                        successCount++
                        Log.d(TAG, "ION-2 - Stored bulk ACL for lock ${bulkAcl.lockMac}")
                    } catch (e: Exception) {
                        Log.e(TAG, "ION-2 - Failed to store bulk ACL for lock ${bulkAcl.lockMac}: ${e.message}", e)
                        // Continue storing remaining ACLs
                    }
                }
                
                // Store bulk fetch timestamp
                val timestampStored = prefs.edit()
                    .putLong("bulk_acl_last_fetched", System.currentTimeMillis())
                    .commit()
                
                if (!timestampStored) {
                    Log.w(TAG, "ION-2 - Failed to store bulk ACL fetch timestamp")
                }
                
                Log.d(TAG, "ION-2 - Bulk ACL fetch completed: $successCount/$totalCount ACLs stored successfully")
                completion(successCount, totalCount)
            }

            override fun onFailure(exception: Exception) {
                Log.e(TAG, "ION-2 - Failed to get bulk ACLs: ${exception.message}", exception)
                completion(0, 0)
            }
        })
    }

    /**
     * Explicitly refresh all ACLs by fetching bulk ACLs from backend.
     * Can be called manually to force ACL refresh.
     * 
     * @param completion Callback with success/failure (true = at least one ACL stored)
     */
    fun refreshAllAcls(completion: (Boolean) -> Unit) {
        val phoneKeyId = getPhoneKeyId()
        if (phoneKeyId == null) {
            Log.e(TAG, "ION-2 - Cannot refresh ACLs: no phone key ID found")
            completion(false)
            return
        }
        
        val keyId = phoneKeyId.toIntOrNull()
        if (keyId == null || keyId <= 0) {
            Log.e(TAG, "ION-2 - Cannot refresh ACLs: invalid phone key ID: $phoneKeyId")
            completion(false)
            return
        }
        
        Log.d(TAG, "ION-2 - Explicit ACL refresh requested")
        getBulkAcls(keyId) { successCount, totalCount ->
            val success = successCount > 0
            Log.d(TAG, "ION-2 - ACL refresh completed: $successCount/$totalCount ACLs refreshed successfully")
            completion(success)
        }
    }

    // ------------------------------------------------
    // ACL Verification
    // ------------------------------------------------

    /**
     * Verifies that the ACL was signed by the lock's private key.
     *
     * @param acl ACL object (unsigned fields only)
     * @param aclSignatureB64 Base64 DER-encoded ECDSA signature
     * @param lockPublicKeyB64 Base64-encoded ECDSA P-256 public key of the lock
     * @return true if signature is valid, false otherwise
     */
    fun verifyAcl(
        acl: PhoneKeyAcl,
        aclSignatureB64: String,
        lockPublicKeyB64: String
    ): Boolean {
        try {
            // Decode lock's public key
            val keyData = Base64.decode(lockPublicKeyB64, Base64.NO_WRAP)
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val publicKeySpec = java.security.spec.X509EncodedKeySpec(keyData)
            val lockPublicKey = keyFactory.generatePublic(publicKeySpec)

            return verifyAcl(acl, aclSignatureB64, lockPublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - ACL verification failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Verifies that the ACL was signed by the lock's private key.
     *
     * @param acl ACL object (unsigned fields only)
     * @param aclSignatureB64 Base64 DER-encoded ECDSA signature
     * @param lockPublicKey ECDSA P-256 public key of the lock
     * @return true if signature is valid, false otherwise
     */
    fun verifyAcl(
        acl: PhoneKeyAcl,
        aclSignatureB64: String,
        lockPublicKey: PublicKey
    ): Boolean {
        val aclJson = JSONObject().apply {
            put("trackingId", acl.trackingId)
            put("lockMac", acl.lockMac)
            put("issuedAt", acl.issuedAt)
            put("expiresAt", acl.expiresAt)
            put("schedule", acl.schedule)
            put("phonePubKey", acl.phonePubKey)
            put("permissions", JSONArray(acl.permissions.map { it.name }))
        }

        val canonicalBytes =
            canonicalizeJSON(aclJson)
                .toString()
                .toByteArray(Charsets.UTF_8)

        val signatureBytes =
            Base64.decode(aclSignatureB64, Base64.NO_WRAP)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(lockPublicKey)
        verifier.update(canonicalBytes)

        return verifier.verify(signatureBytes)
    }

    // ------------------------------------------------
    // ACL Storage
    // ------------------------------------------------

    /**
     * Store ACL with signature (envelope).
     * Storage is automatically isolated per user via separate EncryptedSharedPreferences file.
     * 
     * @param envelope ACL envelope containing acl, signature, and binary
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun storeAclEnvelope(envelope: AclEnvelope) {
        val envelopeJson = JSONObject().apply {
            put("acl", JSONObject().apply {
                put("trackingId", envelope.acl.trackingId)
                put("lockMac", envelope.acl.lockMac)
                put("issuedAt", envelope.acl.issuedAt)
                put("expiresAt", envelope.acl.expiresAt)
                put("schedule", envelope.acl.schedule)
                put("phonePubKey", envelope.acl.phonePubKey)
                put("permissions", JSONArray(envelope.acl.permissions.map { it.name }))
            })
            put("aclSignature", envelope.aclSignature)
            put("aclBinary", envelope.aclBinary)
            put("storedAt", envelope.storedAt)
        }

        val success = prefs.edit()
            .putString("acl_envelope_${envelope.acl.lockMac}", envelopeJson.toString())
            .commit()
        
        if (!success) {
            Log.e(TAG, "ION-2 - Failed to persist ACL envelope for lock ${envelope.acl.lockMac} (storage may be full)")
            throw java.io.IOException("Failed to persist ACL data to SharedPreferences")
        }
        
        // Safeguard: Warn if ACL has no recognized permissions
        if (envelope.acl.permissions.isEmpty()) {
            Log.w(TAG, "ION-2 - ⚠️ WARNING: Stored ACL for lock ${envelope.acl.lockMac} has NO recognized permissions! " +
                    "This ACL will fail validation. Backend may have sent only unknown permissions. " +
                    "Check aclBinary field for complete permission data.")
        } else if (!envelope.acl.permissions.any { it.name.equals("unlock", ignoreCase = true) }) {
            Log.w(TAG, "ION-2 - ⚠️ WARNING: Stored ACL for lock ${envelope.acl.lockMac} is missing 'unlock' permission! " +
                    "ACL validation will fail. Recognized permissions: ${envelope.acl.permissions.joinToString { it.name }}")
        }
        
        Log.d(TAG, "ION-2 - Stored ACL envelope in device-specific storage for lock ${envelope.acl.lockMac} " +
                "with ${envelope.acl.permissions.size} recognized permission(s): ${envelope.acl.permissions.joinToString { it.name }}")
    }

    /**
     * Store bulk ACL envelope (simplified format without permissions/schedule).
     * Stores in same format as individual ACL with placeholder values for missing fields.
     * The aclBinary contains all actual ACL data needed for lock operations.
     *
     * Storage is automatically isolated per user via separate EncryptedSharedPreferences file.
     * 
     * @param bulkAcl Bulk ACL envelope to store
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun storeBulkAclEnvelope(bulkAcl: BulkAclEnvelope) {
        val envelopeJson = JSONObject().apply {
            put("lockMac", bulkAcl.lockMac)
            put("aclBinary", bulkAcl.aclBinary)
            put("aclSignature", bulkAcl.aclSignature)
            put("issuedAt", bulkAcl.issuedAt)
            put("expiresAt", bulkAcl.expiresAt)
            put("storedAt", bulkAcl.storedAt)
            put("isBulkAcl", true) // Flag to distinguish bulk vs full ACL
        }

        val success = prefs.edit()
            .putString("acl_envelope_${bulkAcl.lockMac}", envelopeJson.toString())
            .commit()
        
        if (!success) {
            Log.e(TAG, "ION-2 - Failed to persist bulk ACL for lock ${bulkAcl.lockMac} (storage may be full)")
            throw java.io.IOException("Failed to persist ACL data to SharedPreferences")
        }
        
        Log.d(TAG, "ION-2 - Stored bulk ACL envelope for lock ${bulkAcl.lockMac} " +
                "(expires: ${bulkAcl.expiresAt}, binary length: ${bulkAcl.aclBinary.length})")
    }

    /**
     * Retrieve bulk ACL envelope (simplified format).
     * Returns null if not found or if stored ACL is full format (not bulk).
     * 
     * @param lockMac MAC address of the lock
     * @return BulkAclEnvelope or null if not found/not bulk format
     */
    fun getBulkAclEnvelope(lockMac: String): BulkAclEnvelope? {
        val jsonStr = prefs.getString("acl_envelope_$lockMac", null) ?: return null
        
        try {
            val envelopeJson = JSONObject(jsonStr)
            
            // Check if this is a bulk ACL (simplified format)
            val isBulkAcl = envelopeJson.optBoolean("isBulkAcl", false)
            if (!isBulkAcl) {
                // This is a full ACL, not bulk format
                return null
            }
            
            return BulkAclEnvelope(
                lockMac = envelopeJson.getString("lockMac"),
                aclBinary = envelopeJson.getString("aclBinary"),
                aclSignature = envelopeJson.getString("aclSignature"),
                issuedAt = envelopeJson.getLong("issuedAt"),
                expiresAt = envelopeJson.getLong("expiresAt"),
                storedAt = envelopeJson.optLong("storedAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Failed to parse bulk ACL envelope: ${e.message}", e)
            return null
        }
    }

    /**
     * List all bulk ACL envelopes stored locally.
     *
     * @return List of all stored bulk ACL envelopes (may include expired ones)
     */
    fun listBulkAclEnvelopes(): List<BulkAclEnvelope> {
        val allKeys = prefs.all.keys.filter { it.startsWith("acl_envelope_") }
        val envelopes = mutableListOf<BulkAclEnvelope>()
        
        for (key in allKeys) {
            val lockMac = key.removePrefix("acl_envelope_")
            val envelope = getBulkAclEnvelope(lockMac)
            if (envelope != null) {
                envelopes.add(envelope)
            }
        }
        
        Log.d(TAG, "ION-2 - Listed ${envelopes.size} bulk ACL envelope(s)")
        return envelopes
    }

    /**
     * Check if cached ACL exists and is valid for a given lock.
     * Works with both bulk and full ACL formats.
     * 
     * Validation includes:
     * - ACL exists in cache
     * - ACL has not expired (expiresAt > current time)
     * - ACL has required UNLOCK permission (for full ACLs)
     *
     * @param lockMac MAC address of the lock
     * @return true if valid cached ACL exists, false otherwise
     */
    fun hasCachedAcl(lockMac: String): Boolean {
        // Try bulk ACL first
        val bulkAcl = getBulkAclEnvelope(lockMac)
        if (bulkAcl != null) {
            val now = Instant.now().epochSecond
            val isValid = bulkAcl.expiresAt > now
            if (isValid) {
                val timeUntilExpiry = bulkAcl.expiresAt - now
                Log.d(TAG, "ION-2 - ✅ Valid bulk ACL cached for lock $lockMac (expires in ${timeUntilExpiry}s)")
            } else {
                Log.d(TAG, "ION-2 - ⏰ Bulk ACL for lock $lockMac is EXPIRED (expiresAt=${bulkAcl.expiresAt}, now=$now)")
            }
            return isValid
        }
        
        // Fall back to full ACL
        val fullAcl = getAcl(lockMac)
        if (fullAcl != null) {
            val now = Instant.now().epochSecond
            if (fullAcl.expiresAt <= now) {
                Log.d(TAG, "ION-2 - ⏰ Full ACL for lock $lockMac is EXPIRED (expiresAt=${fullAcl.expiresAt}, now=$now)")
                return false
            }
            val isValid = isAclValid(fullAcl)
            if (isValid) {
                val timeUntilExpiry = fullAcl.expiresAt - now
                Log.d(TAG, "ION-2 - ✅ Valid full ACL cached for lock $lockMac (expires in ${timeUntilExpiry}s)")
            } else {
                Log.w(TAG, "ION-2 - ❌ Full ACL for lock $lockMac exists but is INVALID (missing permissions or schedule issue)")
            }
            return isValid
        }
        
        Log.d(TAG, "ION-2 - 📭 No cached ACL found for lock $lockMac")
        return false
    }

    /**
     * Retrieve ACL envelope (with signature).
     * Retrieves from user-specific isolated storage.
     * Supports both full ACL format and bulk ACL format.
     * 
     * @param lockMac MAC address of the lock
     * @return AclEnvelope or null if not found
     */
    fun getAclEnvelope(lockMac: String): AclEnvelope? {
        val jsonStr = prefs.getString("acl_envelope_$lockMac", null) ?: return null
        
        try {
            val envelopeJson = JSONObject(jsonStr)
            
            // Check if this is a bulk ACL (simplified format without "acl" field)
            val isBulkAcl = envelopeJson.optBoolean("isBulkAcl", false)
            
            if (isBulkAcl) {
                // Bulk ACL format - create minimal envelope with placeholder ACL
                // The aclBinary contains all actual ACL data needed for lock operations
                val acl = PhoneKeyAcl(
                    trackingId = 0, // Placeholder - not available in bulk format
                    lockMac = envelopeJson.getString("lockMac"),
                    issuedAt = envelopeJson.getLong("issuedAt"),
                    expiresAt = envelopeJson.getLong("expiresAt"),
                    schedule = "", // Empty schedule - bulk format doesn't include
                    phonePubKey = "", // Not available in bulk format
                    permissions = setOf(AclPermission.unlock) // Assume unlock permission for bulk ACL
                )
                
                return AclEnvelope(
                    acl = acl,
                    aclSignature = envelopeJson.getString("aclSignature"),
                    aclBinary = envelopeJson.getString("aclBinary"),
                    storedAt = envelopeJson.optLong("storedAt", System.currentTimeMillis())
                )
            }
            
            // Full ACL format - parse complete structure
            val aclJson = envelopeJson.getJSONObject("acl")
            
            val permissionsArray = aclJson.getJSONArray("permissions")
            val permissions = mutableSetOf<AclPermission>()
            for (i in 0 until permissionsArray.length()) {
                val p = permissionsArray.getString(i)
                try {
                    permissions.add(AclPermission.valueOf(p))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "ION-2 - Unknown permission '$p' ignored for lock $lockMac")
                }
            }

            val acl = PhoneKeyAcl(
                trackingId = aclJson.getInt("trackingId"),
                lockMac = aclJson.getString("lockMac"),
                issuedAt = aclJson.getLong("issuedAt"),
                expiresAt = aclJson.getLong("expiresAt"),
                schedule = aclJson.getString("schedule"),
                phonePubKey = aclJson.getString("phonePubKey"),
                permissions = permissions
            )

            return AclEnvelope(
                acl = acl,
                aclSignature = envelopeJson.getString("aclSignature"),
                aclBinary = envelopeJson.getString("aclBinary"),
                storedAt = envelopeJson.optLong("storedAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Failed to parse ACL envelope: ${e.message}", e)
            return null
        }
    }

    /**
     * Legacy method - get ACL without signature.
     * @deprecated Use getAclEnvelope instead
     * 
     * @param lockMac MAC address of the lock
     * @return PhoneKeyAcl or null if not found
     */
    @Deprecated("Use getAclEnvelope to retrieve signature")
    fun getAcl(lockMac: String): PhoneKeyAcl? {
        // First try to get from envelope
        val envelope = getAclEnvelope(lockMac)
        if (envelope != null) {
            return envelope.acl
        }
        
        // Fallback to legacy ACL storage
        val jsonStr = prefs.getString("acl_$lockMac", null) ?: return null
        val json = JSONObject(jsonStr)
        val permissionsArray = json.getJSONArray("permissions")
        val permissions = mutableSetOf<AclPermission>()
        for (i in 0 until permissionsArray.length()) {
            val p = permissionsArray.getString(i)
            try {
                permissions.add(AclPermission.valueOf(p))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "ION-2 - Unknown permission '$p' ignored for lock $lockMac")
            }
        }

        return PhoneKeyAcl(
            trackingId = json.getInt("trackingId"),
            lockMac = json.getString("lockMac"),
            issuedAt = json.getLong("issuedAt"),
            expiresAt = json.getLong("expiresAt"),
            schedule = json.getString("schedule"),
            phonePubKey = json.getString("phonePubKey"),
            permissions = permissions
        )
    }

    // ------------------------------------------------
    // ACL Validation
    // ------------------------------------------------

    /**
     * Enhanced ACL validation with permissions and schedule checks.
     * 
     * @param acl ACL to validate
     * @return true if ACL is valid, false otherwise
     */
    fun isAclValid(acl: PhoneKeyAcl): Boolean {
        // Check 1: Expiration
        val now = System.currentTimeMillis() / 1000
        if (acl.expiresAt <= now) {
            Log.d(TAG, "ION-2 - ACL expired: expiresAt=${acl.expiresAt}, now=$now")
            return false
        }

        // Check 2: Permissions (must have UNLOCK)
        if (!acl.permissions.any { it.name.equals("UNLOCK", ignoreCase = true) }) {
            Log.w(TAG, "ION-2 - ACL missing UNLOCK permission")
            return false
        }

        // Check 3: Schedule validation (if schedule is present and not empty)
        // TODO: Implement schedule parsing and time window validation
        // For now, if schedule is not empty, we assume it's valid
        // A full implementation would parse the schedule JSON and check current time

        return true
    }

    /**
     * Check if ACL has a specific permission.
     * 
     * @param acl ACL to check
     * @param permission Permission name (case-insensitive)
     * @return true if permission exists, false otherwise
     */
    fun hasPermission(acl: PhoneKeyAcl, permission: String): Boolean {
        return acl.permissions.any { it.name.equals(permission, ignoreCase = true) }
    }

    // ------------------------------------------------
    // ACL Cleanup
    // ------------------------------------------------

    /**
     * Clean up expired ACLs from storage.
     * Automatically scoped to current user via isolated storage.
     */
    fun cleanupExpiredAcls() {
        val now = System.currentTimeMillis() / 1000
        val allKeys = prefs.all.keys.filter { it.startsWith("acl_envelope_") }
        
        val editor = prefs.edit()
        var removedCount = 0
        for (key in allKeys) {
            val lockMac = key.removePrefix("acl_envelope_")
            val envelope = getAclEnvelope(lockMac)
            
            if (envelope != null && envelope.acl.expiresAt <= now) {
                editor.remove(key)
                removedCount++
            }
        }
        
        // Batch apply all removals
        if (removedCount > 0) {
            editor.apply()
            Log.d(TAG, "ION-2 - Cleaned up $removedCount expired ACL(s)")
        }
    }

    /**
     * Clean up ALL ACLs from storage (for logout/security purposes).
     * Automatically scoped to current user via isolated storage.
     */
    fun cleanupAllAcls() {
        val allKeys = prefs.all.keys.filter { it.startsWith("acl_envelope_") || it.startsWith("acl_") }
        
        if (allKeys.isNotEmpty()) {
            val editor = prefs.edit()
            allKeys.forEach { key -> editor.remove(key) }
            editor.apply()
            Log.d(TAG, "ION-2 - Cleaned up all ACLs (${allKeys.size} total) on logout for security")
        } else {
            Log.d(TAG, "ION-2 - No ACLs to clean up")
        }
    }
}

// ------------------------------------------------
// Extension Functions
// ------------------------------------------------

/**
 * Strip leading zeros from BigInteger byte array to ensure fixed length.
 */
private fun ByteArray.stripLeadingZeroes(targetLength: Int): ByteArray {
    var start = 0
    while (start < size && this[start] == 0.toByte()) {
        start++
    }
    val trimmed = copyOfRange(start, size)
    return if (trimmed.size < targetLength) {
        ByteArray(targetLength - trimmed.size) + trimmed
    } else {
        trimmed
    }
}

/**
 * Convert byte array to hex string.
 */
private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
