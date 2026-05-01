package com.noke.nokemobilelibrary.phonekey.internal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noke.nokemobilelibrary.phonekey.models.AclPermission
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyAcl
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope
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
import kotlin.math.ceil
 
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
 * Provisioning state information for a phone key
 * @property phoneKeyId The unique identifier assigned by the backend after provisioning
 * @property issuedAt Timestamp when the phone key was issued
 * @property expiresAt Optional expiration timestamp for the phone key
 */
data class ProvisioningInfo(
    val phoneKeyId: String,
    val issuedAt: Instant,
    val expiresAt: Instant?
)

/**
 * Envelope containing ACL with signature and metadata for verification
 * @property acl The phone key ACL containing permissions and schedule
 * @property aclSignature Base64-encoded ECDSA signature from the lock
 * @property aclBinary Binary representation of the ACL
 * @property storedAt Timestamp when this envelope was stored locally
 */
data class AclEnvelope(
    val acl: PhoneKeyAcl,
    val aclSignature: String,
    val aclBinary: String,
    val storedAt: Long = System.currentTimeMillis()
)

/**
 * PhoneKeyManager - Cryptographic key management and ACL lifecycle for ION-2 devices
 * 
 * This class manages:
 * - ECDSA P-256 key pair generation and storage in Android Keystore (hardware-backed)
 * - Phone key provisioning with backend
 * - ACL (Access Control List) storage and retrieval with signature verification
 * - Per-device isolation with unique keys for each (userId, deviceId) combination
 * 
 * Security features:
 * - Hardware-backed keys (Android Keystore) - keys never leave secure hardware
 * - EncryptedSharedPreferences (AES256-GCM) for sensitive data
 * - Device-specific storage isolation (each device has separate keys and ACLs)
 * - ACL signature verification to prevent tampering
 * - Granular device revocation capability
 * 
 * Per-Device Architecture (Production Ready):
 * - Each device gets a unique ECDSA key pair: noke_phone_key_pair_{userId}_{udid}
 * - Each device gets a dedicated MasterKey: noke_master_key_{userId}_{udid}
 * - Each device gets a separate encrypted file: phone_keys_{userId}_{udid}
 * - True storage isolation at file, encryption, and cryptographic key levels
 * - No cross-device data leakage possible
 * - Backend can track and manage multiple devices per user independently
 * 
 * @param context Application context (not Activity context to prevent leaks)
 * @param userId The unique user identifier (must not be empty)
 * @param udid Unique device identifier (Android ANDROID_ID, must not be empty)
 * @throws IllegalArgumentException if userId or udid is empty
 */
internal class PhoneKeyManager constructor(
    private val context: Context,
    private val userId: String,
    private val udid: String
) {
    
    /**
     * Secondary constructor for backward compatibility.
     * Automatically extracts device UDID from Android system.
     * 
     * @param context Application context
     * @param userId The unique user identifier
     */
    constructor(context: Context, userId: String) : this(context, userId, getDeviceUdid(context))

    companion object {
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
        
        // Singleton instance cache per (userId + udid) combination
        // Ensures same PhoneKeyManager instance is reused for same user+device
        // Fixes ACL caching issue where multiple instances don't share SharedPreferences updates
        private val instanceCache = mutableMapOf<String, PhoneKeyManager>()
        private val instanceLock = Any()
        
        // CRITICAL: EncryptedSharedPreferences cache per user+device
        // EncryptedSharedPreferences has in-memory caching that isn't shared across instances.
        // Even with singleton PhoneKeyManager, recreating it (after logout) would create a NEW
        // EncryptedSharedPreferences instance with a separate cache, causing bulk ACLs stored
        // in one instance to be invisible in another.
        // Solution: Cache EncryptedSharedPreferences separately so it persists across manager recreation.
        private val prefsCache = mutableMapOf<String, SharedPreferences>()
        
        // CRITICAL: Per-user locks for SharedPreferences access
        // EncryptedSharedPreferences has cross-thread cache coherency issues where writes on one
        // thread aren't immediately visible on other threads even with the same instance.
        // Solution: Synchronize ALL SharedPreferences access (reads AND writes) per user+device.
        private val prefsLocks = mutableMapOf<String, Any>()
        
        /**
         * Get singleton PhoneKeyManager instance for a user+device combination.
         * 
         * This ensures that all code paths (NokeDeviceManagerService, PhoneKeyFacade, etc.)
         * use the same PhoneKeyManager instance, preventing EncryptedSharedPreferences
         * caching issues where writes from one instance aren't visible to another.
         * 
         * @param context Application context
         * @param userId User identifier
         * @param udid Device identifier  
         * @return Cached PhoneKeyManager instance for this user+device
         */
        @JvmStatic
        fun getInstance(context: Context, userId: String, udid: String): PhoneKeyManager {
            val cacheKey = "${userId}_$udid"
            
            synchronized(instanceLock) {
                return instanceCache.getOrPut(cacheKey) {
                    Log.d("PhoneKeyManager", "ION-2 - Creating NEW singleton instance for user=$userId, device=$udid")
                    PhoneKeyManager(context.applicationContext, userId, udid)
                }
            }
        }
        
        /**
         * Get singleton PhoneKeyManager instance (auto-extracts device ID).
         * 
         * @param context Application context
         * @param userId User identifier
         * @return Cached PhoneKeyManager instance for this user+device
         */
        @JvmStatic
        fun getInstance(context: Context, userId: String): PhoneKeyManager {
            val udid = getDeviceUdid(context)
            return getInstance(context, userId, udid)
        }
        
        /**
         * Clear cached instance for a specific user+device.
         * Should be called on logout to free resources.
         * 
         * NOTE: Does NOT clear EncryptedSharedPreferences cache to preserve ACL data
         * across logout/login cycles. ACL cleanup is handled separately via cleanupAclsForUser().
         * 
         * @param userId User identifier
         * @param udid Device identifier
         */
        @JvmStatic
        fun clearInstance(userId: String, udid: String) {
            val cacheKey = "${userId}_$udid"
            synchronized(instanceLock) {
                instanceCache.remove(cacheKey)
                // NOTE: Do NOT remove from prefsCache - keep EncryptedSharedPreferences alive
                // to maintain cache consistency. ACL data is cleared via clearAll() separately.
                Log.d("PhoneKeyManager", "ION-2 - Cleared singleton instance for user=$userId, device=$udid")
            }
        }
        
        /**
         * Get or create EncryptedSharedPreferences for a specific user+device.
         * 
         * CRITICAL: This method ensures only ONE EncryptedSharedPreferences instance
         * exists per (userId + udid) combination, even across PhoneKeyManager recreation.
         * This fixes ACL caching issues where bulk ACLs stored in one instance weren't
         * visible in another instance.
         * 
         * @param context Application context
         * @param userId User identifier
         * @param udid Device identifier
         * @return Cached EncryptedSharedPreferences instance
         */
        @JvmStatic
        private fun getOrCreatePrefs(context: Context, userId: String, udid: String): SharedPreferences {
            val cacheKey = "${userId}_$udid"
            
            synchronized(instanceLock) {
                return prefsCache.getOrPut(cacheKey) {
                    val prefsFileName = "${PREFS_FILE_PREFIX}_${userId}_${udid}"
                    val masterKeyAlias = "${MASTER_KEY_ALIAS_PREFIX}_${userId}_${udid}"
                    
                    // Create device-specific MasterKey in Android Keystore
                    val masterKey = MasterKey.Builder(context, masterKeyAlias)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .setUserAuthenticationRequired(false)
                        .build()
                    
                    // Create device-specific EncryptedSharedPreferences file
                    val prefs = EncryptedSharedPreferences.create(
                        context,
                        prefsFileName,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    
                    Log.d("PhoneKeyManager", "ION-2 - Created NEW EncryptedSharedPreferences for user=$userId, device=$udid")
                    prefs
                }
            }
        }
        
        /**
         * Get synchronization lock for SharedPreferences access for a user+device.
         * 
         * This ensures all SharedPreferences operations (reads AND writes) for a given
         * user+device are synchronized to prevent cross-thread cache coherency issues.
         * 
         * @param userId User identifier
         * @param udid Device identifier  
         * @return Synchronization lock object for this user+device
         */
        internal fun getPrefsLock(userId: String, udid: String): Any {
            val lockKey = "${userId}_$udid"
            synchronized(instanceLock) {
                return prefsLocks.getOrPut(lockKey) { Any() }
            }
        }
        
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
    private val securityService: SecurityService
    
    // CRITICAL: Lock for ALL SharedPreferences access to ensure cross-thread cache coherency
    // EncryptedSharedPreferences has per-thread caching that causes writes on one thread
    // to not be immediately visible on other threads. Synchronizing all access fixes this.
    private val prefsLock: Any = getPrefsLock(userId, udid)
    
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
            securityService = SecurityServiceImpl(context)
            
            // CRITICAL: Use cached EncryptedSharedPreferences instance
            // This ensures the same instance persists across PhoneKeyManager recreation
            // (e.g., after logout/login), preventing ACL caching issues.
            prefs = getOrCreatePrefs(context, userId, udid)
            
            Log.d("PhoneKeyManager", "ION-2 - Initialized PhoneKeyManager for user=$userId, device=$udid (using cached prefs)")
        } catch (e: Exception) {
            Log.e("PhoneKeyManager", "ION-2 - Failed to initialize PhoneKeyManager for user=$userId, device=$udid: ${e.message}", e)
            throw IllegalStateException("Failed to initialize encrypted storage for user=$userId, device=$udid", e)
        }
    }



    // ------------------------------------------------
    // Key management (ECDSA P-256) - Android Keystore
    // ------------------------------------------------

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
                    Log.d("PhoneKeyManager", "ION-2 - Loaded device-specific ECDSA key pair for user=$userId, device=$udid")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("PhoneKeyManager", "ION-2 - Failed to load from Keystore: ${e.message}", e)
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

            Log.d("PhoneKeyManager", "ION-2 - Generated device-specific ECDSA key pair for user=$userId, device=$udid")
        } catch (e: Exception) {
            Log.e("PhoneKeyManager", "ION-2 - Failed to generate key pair: ${e.message}", e)
            throw e
        }
    }

    /**
     * Returns the public key encoded as X9.62 uncompressed EC point (65 bytes)
     */
    fun getPublicKeyX962(): ByteArray {
        ensureKeys()

        val ecPub = publicKey as ECPublicKey
        val point = ecPub.w

        val x = point.affineX.toByteArray().stripLeadingZeroes(32)
        val y = point.affineY.toByteArray().stripLeadingZeroes(32)

        return byteArrayOf(0x04) + x + y
    }

    fun getPublicKey(): PublicKey {
        ensureKeys()
        return publicKey
            ?: throw IllegalStateException("Public key not initialized")
    }

    /**
     *
     * Public key as Base64-encoded X9.62
     * This is what you send to the lock
     * and what appears in `acl.phonePubKey`
     */

    fun getPublicKeyBase64(): String =
        Base64.encodeToString(getPublicKeyX962(), Base64.NO_WRAP)

    /**
     * HEX encoded public key (for transfer)
     */
    fun getPublicKeyHex(): String =
        getPublicKeyX962().toHex()

    // ------------------------------------------------
    // Signing (ECDSA + SHA256) - Using Keystore
    // ------------------------------------------------

    fun sign(data: ByteArray): ByteArray {
        ensureKeys()

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Sign data directly using Android Keystore private key
     * without loading it into application memory
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
            Log.e("PhoneKeyManager", "ION-2 - Failed to sign with Keystore: ${e.message}", e)
            throw e
        }
    }

    fun signHex(data: ByteArray): String =
        sign(data).toHex()

    // ------------------------------------------------
    // Canonical JSON
    // ------------------------------------------------

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
    // Provisioning
    // ------------------------------------------------

    // ------------------------------------------------
    // Provisioning State Management
    // ------------------------------------------------

    /**
     * Get the userId this manager is associated with
     */
    fun getUserId(): String = userId

    /**
     * Check if the phone has been provisioned for the current user
     */
    fun isProvisioned(): Boolean {
        synchronized(prefsLock) {
            return prefs.getBoolean(KEY_IS_PROVISIONED, false)
        }
    }

    /**
     * Check if provisioning is for a specific user
     * This method validates that the stored userId matches the current userId
     */
    fun isProvisionedForUser(userId: String): Boolean {
        if (!isProvisioned()) return false
        // Since manager is now user-specific, just verify the userId matches
        return this.userId == userId
    }

    /**
     * Save provisioning result after successful backend call
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun setProvisioned(keyId: String, userId: String) {
        synchronized(prefsLock) {
            // Validate that userId matches this manager's userId
            if (this.userId != userId) {
                Log.w("PhoneKeyManager", "ION-2 - WARNING: Attempting to provision for userId=$userId but manager is for ${this.userId}")
            }
            val success = prefs.edit()
                .putBoolean(KEY_IS_PROVISIONED, true)
                .putString(KEY_PHONE_KEY_ID, keyId)
                .putString(KEY_PROVISIONED_USER_ID, userId)
                .putString(KEY_PROVISIONED_AT, Instant.now().toString())
                .commit()
            
            if (!success) {
                Log.e("PhoneKeyManager", "ION-2 - Failed to persist provisioning state (storage may be full)")
                throw java.io.IOException("Failed to persist provisioning state to SharedPreferences")
            }
            Log.d("PhoneKeyManager", "ION-2 - Provisioning state saved for user $userId: keyId=$keyId")
        }
    }

    /**
     * Get the stored phone key ID for current user
     */
    fun getPhoneKeyId(): String? {
        synchronized(prefsLock) {
            return prefs.getString(KEY_PHONE_KEY_ID, null)
        }
    }

    /**
     * Clear all provisioning data (call on logout).
     * 
     * Removes:
     * - Phone key ID
     * - Provisioning timestamps
     * - User ID
     * - Provisioning status flag
     * 
     * Note: Does NOT delete cryptographic keys from Keystore.
     * Keys persist for future re-provisioning.
     * 
     * @throws IOException if SharedPreferences commit fails
     */
    fun clearProvisioningData() {
        synchronized(prefsLock) {
            val success = prefs.edit()
                .remove(KEY_IS_PROVISIONED)
                .remove(KEY_PHONE_KEY_ID)
                .remove(KEY_PROVISIONED_USER_ID)
                .remove(KEY_PROVISIONED_AT)
                .remove(KEY_EXPIRES_AT)
                .commit()
            
            if (!success) {
                Log.e("PhoneKeyManager", "ION-2 - Failed to clear provisioning data (storage may be full)")
                throw java.io.IOException("Failed to clear provisioning data from SharedPreferences")
            }
            Log.d("PhoneKeyManager", "ION-2 - Provisioning data cleared for user $userId")
        }
    }

    /**
     * Provision phone key with backend
     * Returns nullable Int - null indicates provisioning failure
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
        Log.d("PhoneKeyManager", "ION-2 - Calling provisionPhone for user $userId")
        securityService.provisionPhone(udid, publicKey, userId, object : SecurityService.ProvisionCallback {
            override fun onSuccess(keyId: Int) {
                try {
                    // Save provisioning state with user ID
                    setProvisioned(keyId.toString(), userId)
                    completion(keyId)
                } catch (e: Exception) {
                    // Storage failure should be treated as provisioning failure
                    Log.e("PhoneKeyManager", "ION-2 - Provisioning succeeded but storage failed: ${e.message}", e)
                    completion(null)
                }
            }

            override fun onFailure(exception: Exception) {
                Log.e("PhoneKeyManager", "ION-2 - Provisioning failed: ${exception.message}", exception)
                completion(null)
            }
        })
    }

    /**
     * Store provisioning result from backend
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
        synchronized(prefsLock) {
            val success = prefs.edit()
                .putString(KEY_PHONE_KEY_ID, phoneKeyId)
                .putString(KEY_PROVISIONED_AT, issuedAt.toString())
                .putString(KEY_EXPIRES_AT, expiresAt?.toString())
                .commit()
            
            if (!success) {
                Log.e("PhoneKeyManager", "ION-2 - Failed to persist provisioning result (storage may be full)")
                throw java.io.IOException("Failed to persist provisioning data to SharedPreferences")
            }
        }
    }

    fun getProvisioningInfo(): ProvisioningInfo {
        synchronized(prefsLock) {
            val phoneKeyId = prefs.getString(KEY_PHONE_KEY_ID, null)
                ?: error("Phone key not provisioned for user=$userId, device=$udid")

            val issuedAt = Instant.parse(
                prefs.getString(KEY_PROVISIONED_AT, null)
                    ?: error("Missing issuedAt for user=$userId, device=$udid")
            )

            val expiresAt = prefs.getString(KEY_EXPIRES_AT, null)?.let(Instant::parse)

            return ProvisioningInfo(phoneKeyId, issuedAt, expiresAt)
        }
    }

    fun getAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        completion: (Boolean) -> Unit
    ) {
        securityService.getACLEnvelope(userId, lockMac, phoneKeyId, object : SecurityService.AclEnvelopeCallback {
            override fun onSuccess(phoneKeyAcl: PhoneKeyAcl, aclSignature: String, aclBinary: String) {
                try {
                    val envelope = AclEnvelope(
                        acl = phoneKeyAcl,
                        aclSignature = aclSignature,
                        aclBinary = aclBinary
                    )
                    storeAclEnvelope(envelope)
                    Log.d("PhoneKeyManager", "ION-2 - ACL envelope stored for lock $lockMac with signature and binary (${aclBinary.length} chars)")
                    completion(true)
                } catch (e: Exception) {
                    // Storage failure should be treated as ACL fetch failure
                    Log.e("PhoneKeyManager", "ION-2 - ACL received but storage failed for lock $lockMac: ${e.message}", e)
                    completion(false)
                }
            }

            override fun onFailure(exception: Exception) {
                Log.e("PhoneKeyManager", "ION-2 - Failed to get ACL: ${exception.message}", exception)
                completion(false)
            }
        })
    }

    /**
     * Fetch bulk ACLs from backend for all locks accessible to the user
     *
     * This is the preferred method for fetching ACLs in most scenarios:
     * - On successful login
     * - After provisioning
     * - On app startup if user already logged in
     * - Explicit refresh via refreshAllAcls()
     *
     * Note: Bulk ACLs are stored in simplified format (without permissions/schedule)
     * and rely on aclBinary for lock operations. Individual getAcl() should only
     * be used for specific scenarios like refetchAcl during unlock failures.
     *
     * @param phoneKeyId Phone key ID from provisioning
     * @param completion Callback with (successCount, totalCount) - successCount is number of ACLs successfully stored
     */
    fun getBulkAcls(
        phoneKeyId: Int,
        completion: (successCount: Int, totalCount: Int) -> Unit
    ) {
        Log.d("PhoneKeyManager", "ION-2 - Starting bulk ACL fetch for phoneKeyId $phoneKeyId")
        
        securityService.getBulkACLEnvelopes(phoneKeyId, object : SecurityService.BulkAclEnvelopeCallback {
            override fun onSuccess(aclEnvelopes: List<BulkAclEnvelope>) {
                Log.d("PhoneKeyManager", "ION-2 - Received ${aclEnvelopes.size} bulk ACLs from backend")
                
                var successCount = 0
                val totalCount = aclEnvelopes.size
                
                // Store each bulk ACL individually
                for (bulkAcl in aclEnvelopes) {
                    try {
                        storeBulkAclEnvelope(bulkAcl)
                        successCount++
                        Log.d("PhoneKeyManager", "ION-2 - Stored bulk ACL for lock ${bulkAcl.lockMac}")
                    } catch (e: Exception) {
                        Log.e("PhoneKeyManager", "ION-2 - Failed to store bulk ACL for lock ${bulkAcl.lockMac}: ${e.message}", e)
                        // Continue storing remaining ACLs
                    }
                }
                
                // Store bulk fetch timestamp
                val timestampStored = synchronized(prefsLock) {
                    prefs.edit()
                        .putLong("bulk_acl_last_fetched", System.currentTimeMillis())
                        .commit()
                }
                
                if (!timestampStored) {
                    Log.w("PhoneKeyManager", "ION-2 - Failed to store bulk ACL fetch timestamp")
                }
                
                Log.d("PhoneKeyManager", "ION-2 - Bulk ACL fetch completed: $successCount/$totalCount ACLs stored successfully")
                completion(successCount, totalCount)
            }

            override fun onFailure(exception: Exception) {
                Log.e("PhoneKeyManager", "ION-2 - Failed to get bulk ACLs: ${exception.message}", exception)
                completion(0, 0)
            }
        })
    }

    /**
     * Explicitly refresh all ACLs by fetching bulk ACLs from backend
     * Can be called manually to force ACL refresh
     */
    fun refreshAllAcls(completion: (Boolean) -> Unit) {
        val phoneKeyId = getPhoneKeyId()
        if (phoneKeyId == null) {
            Log.e("PhoneKeyManager", "ION-2 - Cannot refresh ACLs: no phone key ID found")
            completion(false)
            return
        }
        
        val keyId = phoneKeyId.toIntOrNull()
        if (keyId == null || keyId <= 0) {
            Log.e("PhoneKeyManager", "ION-2 - Cannot refresh ACLs: invalid phone key ID: $phoneKeyId")
            completion(false)
            return
        }
        
        Log.d("PhoneKeyManager", "ION-2 - Explicit ACL refresh requested")
        getBulkAcls(keyId) { successCount, totalCount ->
            val success = successCount > 0
            Log.d("PhoneKeyManager", "ION-2 - ACL refresh completed: $successCount/$totalCount ACLs refreshed successfully")
            completion(success)
        }
    }


    /**
     * Verifies that the ACL was signed by the lock private key.
     *
     * @param acl ACL object (unsigned fields only)
     * @param aclSignatureB64 Base64 DER-encoded ECDSA signature
     * @param lockPublicKeyB64 Base64-encoded ECDSA P-256 public key of the lock
     */
    fun verifyAcl(
        acl: PhoneKeyAcl,
        aclSignatureB64: String,
        lockPublicKeyB64: String
    ): Boolean {
        try {
            // Decode lock's public key
            val keyData = Base64.decode(lockPublicKeyB64, Base64.NO_WRAP)
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            // For verification, we need to reconstruct the public key
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val publicKeySpec = java.security.spec.X509EncodedKeySpec(keyData)
            val lockPublicKey = keyFactory.generatePublic(publicKeySpec)

            return verifyAcl(acl, aclSignatureB64, lockPublicKey)
        } catch (e: Exception) {
            Log.e("PhoneKeyManager", "ION-2 - ACL verification failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Verifies that the ACL was signed by the lock private key.
     *
     * @param acl ACL object (unsigned fields only)
     * @param aclSignatureB64 Base64 DER-encoded ECDSA signature
     * @param lockPublicKey ECDSA P-256 public key of the lock
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

    /**
     * Store ACL with signature (envelope)
     * Storage is automatically isolated per user via separate EncryptedSharedPreferences file
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun storeAclEnvelope(envelope: AclEnvelope) {
        synchronized(prefsLock) {
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
                Log.e("PhoneKeyManager", "ION-2 - Failed to persist ACL envelope for lock ${envelope.acl.lockMac} (storage may be full)")
                throw java.io.IOException("Failed to persist ACL data to SharedPreferences")
            }
            
            // Safeguard: Warn if ACL has no recognized permissions
            if (envelope.acl.permissions.isEmpty()) {
                Log.w("PhoneKeyManager", "ION-2 - ⚠️ WARNING: Stored ACL for lock ${envelope.acl.lockMac} has NO recognized permissions! " +
                        "This ACL will fail validation. Backend may have sent only unknown permissions. " +
                        "Check aclBinary field for complete permission data.")
            } else if (!envelope.acl.permissions.any { it.name.equals("unlock", ignoreCase = true) }) {
                Log.w("PhoneKeyManager", "ION-2 - ⚠️ WARNING: Stored ACL for lock ${envelope.acl.lockMac} is missing 'unlock' permission! " +
                        "ACL validation will fail. Recognized permissions: ${envelope.acl.permissions.joinToString { it.name }}")
            }
            
            Log.d("PhoneKeyManager", "ION-2 - Stored ACL envelope in device-specific storage for lock ${envelope.acl.lockMac} " +
                    "with ${envelope.acl.permissions.size} recognized permission(s): ${envelope.acl.permissions.joinToString { it.name }}")
        }
    }

    /**
     * Store bulk ACL envelope (simplified format without permissions/schedule)
     * Stores in same format as individual ACL with placeholder values for missing fields
     * The aclBinary contains all actual ACL data needed for lock operations
     *
     * Storage is automatically isolated per user via separate EncryptedSharedPreferences file
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    fun storeBulkAclEnvelope(bulkAcl: BulkAclEnvelope) {
        synchronized(prefsLock) {
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
                Log.e("PhoneKeyManager", "ION-2 - Failed to persist bulk ACL for lock ${bulkAcl.lockMac} (storage may be full)")
                throw java.io.IOException("Failed to persist ACL data to SharedPreferences")
            }
            
            Log.d("PhoneKeyManager", "ION-2 - Stored bulk ACL envelope for lock ${bulkAcl.lockMac} (expires: ${bulkAcl.expiresAt})")
        }
    }

    /**
     * Retrieve bulk ACL envelope (simplified format)
     * Returns null if not found or if stored ACL is full format (not bulk)
     */
    fun getBulkAclEnvelope(lockMac: String): BulkAclEnvelope? {
        synchronized(prefsLock) {
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
                Log.e("PhoneKeyManager", "ION-2 - Failed to parse bulk ACL envelope: ${e.message}", e)
                return null
            }
        }
    }

    /**
     * List all bulk ACL envelopes stored locally.
     *
     * @return List of all stored bulk ACL envelopes (may include expired ones)
     */
    fun listBulkAclEnvelopes(): List<BulkAclEnvelope> {
        synchronized(prefsLock) {
            val allKeys = prefs.all.keys.filter { it.startsWith("acl_envelope_") }
            val envelopes = mutableListOf<BulkAclEnvelope>()
            
            for (key in allKeys) {
                val lockMac = key.removePrefix("acl_envelope_")
                val envelope = getBulkAclEnvelope(lockMac)
                if (envelope != null) {
                    envelopes.add(envelope)
                }
            }
            
            Log.d("PhoneKeyManager", "ION-2 - Listed ${envelopes.size} bulk ACL envelope(s)")
            return envelopes
        }
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
        synchronized(prefsLock) {
            val now = Instant.now().epochSecond
            
            // Check ESP for stored ACL
            val bulkAclJsonStr = prefs.getString("acl_envelope_$lockMac", null)
            if (bulkAclJsonStr != null) {
                try {
                    val envelopeJson = JSONObject(bulkAclJsonStr)
                    val isBulkAcl = envelopeJson.optBoolean("isBulkAcl", false)
                    
                    if (isBulkAcl) {
                        // Bulk ACL format - check expiration
                        val expiresAt = envelopeJson.getLong("expiresAt")
                        val isValid = expiresAt > now
                        
                        if (!isValid) {
                            Log.d("PhoneKeyManager", "ION-2 - Bulk ACL for lock $lockMac is expired")
                        }
                        return isValid
                    }
                    
                    // Full ACL format - check expiration and permissions
                    val aclJson = envelopeJson.getJSONObject("acl")
                    val expiresAt = aclJson.getLong("expiresAt")
                    
                    if (expiresAt <= now) {
                        Log.d("PhoneKeyManager", "ION-2 - Full ACL for lock $lockMac is expired")
                        return false
                    }
                    
                    // Check for UNLOCK permission
                    val permissionsArray = aclJson.getJSONArray("permissions")
                    var hasUnlock = false
                    for (i in 0 until permissionsArray.length()) {
                        if (permissionsArray.getString(i).equals("unlock", ignoreCase = true)) {
                            hasUnlock = true
                            break
                        }
                    }
                    
                    if (!hasUnlock) {
                        Log.w("PhoneKeyManager", "ION-2 - Full ACL for lock $lockMac missing UNLOCK permission")
                    }
                    return hasUnlock
                } catch (e: Exception) {
                    Log.e("PhoneKeyManager", "ION-2 - Failed to parse cached ACL for $lockMac: ${e.message}", e)
                    return false
                }
            }
            
            // No ACL found
            return false
        }
    }

    /**
     * Retrieve ACL envelope (with signature)
     * Retrieves from user-specific isolated storage
     * Supports both full ACL format and bulk ACL format
     */
    fun getAclEnvelope(lockMac: String): AclEnvelope? {
        synchronized(prefsLock) {
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
                        Log.w("PhoneKeyManager", "ION-2 - Unknown permission '$p' ignored for lock $lockMac")
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
                Log.e("PhoneKeyManager", "ION-2 - Failed to parse ACL envelope: ${e.message}", e)
                return null
            }
        }
    }

    /**
     * Legacy method - store ACL without signature
     * @deprecated Use storeAclEnvelope instead
     * @throws IOException if SharedPreferences commit fails (e.g., storage full)
     */
    @Deprecated("Use storeAclEnvelope to include signature")
    fun storeAcl(acl: PhoneKeyAcl) {
        synchronized(prefsLock) {
            val aclJson = JSONObject().apply {
                put("trackingId", acl.trackingId)
                put("lockMac", acl.lockMac)
                put("issuedAt", acl.issuedAt)
                put("expiresAt", acl.expiresAt)
                put("schedule", acl.schedule)
                put("phonePubKey", acl.phonePubKey)
                put("permissions", JSONArray(acl.permissions.map { it.name }))
            }

            val success = prefs.edit()
                .putString("acl_${acl.lockMac}", aclJson.toString())
                .commit()
            
            if (!success) {
                Log.e("PhoneKeyManager", "ION-2 - Failed to persist ACL for lock ${acl.lockMac} (storage may be full)")
                throw java.io.IOException("Failed to persist ACL data to SharedPreferences")
            }
        }
    }

    /**
     * Legacy method - get ACL without signature
     * @deprecated Use getAclEnvelope instead
     */
    @Deprecated("Use getAclEnvelope to retrieve signature")
    fun getAcl(lockMac: String): PhoneKeyAcl? {
        synchronized(prefsLock) {
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
                    Log.w("PhoneKeyManager", "ION-2 - Unknown permission '$p' ignored for lock $lockMac")
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
    }

    /**
     * Enhanced ACL validation with permissions and schedule checks
     */
    fun isAclValid(acl: PhoneKeyAcl): Boolean {
        // Check 1: Expiration
        val now = System.currentTimeMillis() / 1000
        if (acl.expiresAt <= now) {
            Log.d("PhoneKeyManager", "ION-2 - ACL expired: expiresAt=${acl.expiresAt}, now=$now")
            return false
        }

        // Check 2: Permissions (must have UNLOCK)
        if (!acl.permissions.any { it.name.equals("UNLOCK", ignoreCase = true) }) {
            Log.w("PhoneKeyManager", "ION-2 - ACL missing UNLOCK permission")
            return false
        }

        // Check 3: Schedule validation (if schedule is present and not empty)
        // TODO: Implement schedule parsing and time window validation
        // For now, if schedule is not empty, we assume it's valid
        // A full implementation would parse the schedule JSON and check current time

        return true
    }

    /**
     * Check if ACL has a specific permission
     */
    fun hasPermission(acl: PhoneKeyAcl, permission: String): Boolean {
        return acl.permissions.any { it.name.equals(permission, ignoreCase = true) }
    }

    /**
     * Clean up expired ACLs from storage
     * Automatically scoped to current user via isolated storage
     */
    fun cleanupExpiredAcls() {
        synchronized(prefsLock) {
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
                Log.d("PhoneKeyManager", "ION-2 - Cleaned up $removedCount expired ACL(s)")
            }
        }
    }

    /**
     * Delete ACL for a specific lock.
     * Automatically scoped to current user via isolated storage.
     *
     * @param userId User identifier (not used, scoped via isolated prefs)
     * @param lockMac Lock MAC address
     */
    fun deleteACL(userId: String, lockMac: String) {
        synchronized(prefsLock) {
            val aclKey = "acl_envelope_$lockMac"
            val legacyKey = "acl_$lockMac"
            
            val editor = prefs.edit()
            val removed = prefs.contains(aclKey) || prefs.contains(legacyKey)
            
            editor.remove(aclKey)
            editor.remove(legacyKey)
            editor.apply()
            
            if (removed) {
                Log.d("PhoneKeyManager", "ION-2 - Deleted ACL for lock=$lockMac")
            } else {
                Log.d("PhoneKeyManager", "ION-2 - No ACL found to delete for lock=$lockMac")
            }
        }
    }

    /**
     * Clean up ALL ACLs from storage (for logout/security purposes)
     * Automatically scoped to current user via isolated storage
     */
    fun cleanupAllAcls() {
        synchronized(prefsLock) {
            val allKeys = prefs.all.keys.filter { it.startsWith("acl_envelope_") || it.startsWith("acl_") }
            
            if (allKeys.isNotEmpty()) {
                val editor = prefs.edit()
                allKeys.forEach { key -> editor.remove(key) }
                editor.apply()
                Log.d("PhoneKeyManager", "ION-2 - Cleaned up all ACLs (${allKeys.size} total) on logout for security")
            } else {
                Log.d("PhoneKeyManager", "ION-2 - No ACLs to clean up")
            }
        }
    }

    /**
     * Fetch ACLs for all ION2 devices after provisioning
     * Now uses bulk ACL fetch for efficiency - fetches all ACLs in one API call
     * 
     * This method is called after device sync completes to ensure devices have ACLs ready
     * before first connection attempt.
     * 
     * @param userId User ID string (not used in bulk fetch - kept for compatibility)
     * @param callback Returns (successCount, totalCount) when fetch completes
     */
    fun fetchAclsForIon2Devices(userId: String, callback: (Int, Int) -> Unit) {
        val phoneKeyId = getPhoneKeyId()?.toIntOrNull()
        if (phoneKeyId == null) {
            Log.w("PhoneKeyManager", "ION-2 - Cannot fetch ACLs: Not provisioned")
            callback(0, 0)
            return
        }
        
        // Get database instance to check device count for logging
        val database = com.noke.smartentrycore.database.AppDatabase.getAppDatabase(context)
        val devices = database.persistedNokeDeviceDao().getAllUnitControllers()
        val ion2Devices = devices.filter { it.isIon2() }
        
        Log.d("PhoneKeyManager", "ION-2 - Device Inventory: Found ${devices.size} total unit controllers in database")
        Log.d("PhoneKeyManager", "ION-2 - Found ${ion2Devices.size} ION2 device(s)")
        
        if (ion2Devices.isEmpty()) {
            Log.w("PhoneKeyManager", "ION-2 - No ION2 devices found (hwVersionString == '5E')")
            callback(0, 0)
            return
        }
        
        // Check if all ION2 devices already have valid cached ACLs
        // This validation includes expiration checks - expired ACLs are treated as invalid
        val devicesWithValidAcls = ion2Devices.filter { device ->
            hasCachedAcl(device.mac) // Logs detailed reason (expired, missing, or invalid)
        }
        
        // If all devices have valid ACLs, skip the fetch
        if (devicesWithValidAcls.size == ion2Devices.size) {
            Log.d("PhoneKeyManager", "ION-2 - ✅ All ${ion2Devices.size} ION2 device(s) have valid cached ACLs, no fetch needed")
            callback(ion2Devices.size, ion2Devices.size)
            return
        }
        
        // At least one device needs ACL refresh (missing, expired, or invalid)
        val devicesNeedingAcls = ion2Devices.size - devicesWithValidAcls.size
        Log.d("PhoneKeyManager", "ION-2 - 🔄 ${devicesNeedingAcls}/${ion2Devices.size} device(s) need ACL refresh (see reasons above)")
        
        // Use bulk ACL fetch - fetches ALL user's ACLs in one API call
        Log.d("PhoneKeyManager", "ION-2 - Triggering bulk ACL fetch for all accessible locks")
        getBulkAcls(phoneKeyId) { successCount, totalCount ->
            Log.d("PhoneKeyManager", "ION-2 - Bulk ACL fetch complete: $successCount/$totalCount ACLs stored successfully")
            callback(successCount, totalCount)
        }
    }

    fun buildAclChunks(acl: ByteArray): List<String> {
        val signature = sign(acl)
        val fullPayload = acl + signature

        val chunkSize = 128
        val totalAclLength = acl.size
        val chunkCount = ceil(fullPayload.size / chunkSize.toDouble()).toInt()

        val chunks = mutableListOf<String>()

        for (i in 0 until chunkCount) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, fullPayload.size)
            val chunkBytes = fullPayload.copyOfRange(start, end)
            val hexPayload = chunkBytes.joinToString("") { "%02x".format(it) }

            val header = if (i < chunkCount - 1) {
                "%02x".format(i)
            } else {
                "xx" + "%04x".format(totalAclLength)
            }

            chunks.add(header + hexPayload)
        }

        return chunks
    }



    // ------------------------------------------------
    // Utilities
    // ------------------------------------------------

    private fun derivePublicKeyX509(): ByteArray =
        (publicKey ?: throw IllegalStateException()).encoded

    private fun ByteArray.stripLeadingZeroes(expected: Int): ByteArray {
        var out = this
        while (out.size > expected && out[0] == 0.toByte()) {
            out = out.copyOfRange(1, out.size)
        }
        return if (out.size == expected) out else ByteArray(expected - out.size) + out
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
