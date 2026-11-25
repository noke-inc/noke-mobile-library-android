package com.noke.nokemobilelibrary.phonekey

import android.content.Context
import android.util.Base64
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.internal.SecurityServiceImpl
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Ready-to-use [PhoneKeyCoreClient] for the Noke phone key backend.
 *
 * It manages the Keystore key pair, provisioning and ACL storage, so the app only supplies the base URL and
 * how to authenticate each request:
 *
 * ```kotlin
 * val client = DefaultPhoneKeyCoreClient(
 *     context = applicationContext,
 *     baseUrl = "https://api-gateway.smartentry.noke.dev/",
 *     tokenScheme = "ApiKey",
 *     authToken = { apiKey },
 *     extraHeaders = { mapOf("Email" to email, "CompanyId" to companyId, "SiteId" to siteId) },
 * )
 * client.prewarm(userId)                                   // once the user id is known
 * PhoneKeyAccessService.setSharedClient(client, applicationContext)
 * ```
 *
 * Call [prewarm] before the library's own unlock flow runs, so the manager shared with
 * `NokeDeviceManagerService` uses this client's base URL and credentials.
 * Apps with a different backend can still implement [PhoneKeyCoreClient] themselves.
 *
 * @param baseUrl Root of the phone key API.
 * @param authToken Returns the credential sent on every request; evaluated each time, so it can change.
 * @param tokenScheme Word placed before the token in the `Authorization` header (e.g. "Bearer" or "ApiKey").
 * @param extraHeaders Additional headers (e.g. Email, CompanyId, SiteId); evaluated on every request.
 */
class DefaultPhoneKeyCoreClient @JvmOverloads constructor(
    context: Context,
    private val baseUrl: String,
    private val authToken: () -> String,
    private val tokenScheme: String = "Bearer",
    private val extraHeaders: () -> Map<String, String> = { emptyMap() },
) : PhoneKeyCoreClient {

    /** Result of checking a cached ACL before it is sent to a lock. See [checkCachedAcl]. */
    enum class CachedAclStatus { MISSING, VALID, EXPIRED, KEY_MISMATCH, UNVERIFIABLE }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val managerCache = mutableMapOf<String, PhoneKeyManager>()
    @Volatile private var initialized = false

    override val isInitialized: Boolean
        get() = initialized

    /** Creates the manager for [userId] ahead of time. [deviceId] defaults to the device's Android id. */
    @JvmOverloads
    fun prewarm(userId: String, deviceId: String = PhoneKeyAccessService.getDeviceUdid(appContext)) {
        if (userId.isEmpty() || deviceId.isEmpty()) return
        try {
            manager(userId, deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "prewarm failed (non-fatal): ${e.message}")
        }
    }

    override suspend fun initialize(userId: String, deviceId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val manager = manager(userId, deviceId)
                    manager.ensureKeys()
                    initialized = true
                    Result.success(manager.getPublicKeyBase64())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed for user=$userId", e)
                Result.failure(NokeMobileLibraryError.CryptographicError("key generation", e) as Throwable)
            }
        }

    override suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String,
    ): Result<PhoneKeyInfoResponse> =
        withContext(Dispatchers.IO) {
            if (userId.isEmpty()) return@withContext Result.failure(NokeMobileLibraryError.InvalidInput("userId cannot be empty"))
            if (deviceId.isEmpty()) return@withContext Result.failure(NokeMobileLibraryError.InvalidInput("udid cannot be empty"))
            try {
                mutex.withLock {
                    val manager = manager(userId, deviceId)

                    // Already provisioned in a previous session: reuse the stored id (avoids 409 Conflict).
                    if (manager.isProvisioned()) {
                        manager.getPhoneKeyId()?.toIntOrNull()?.let {
                            return@withLock Result.success(PhoneKeyInfoResponse.success(it))
                        }
                    }

                    val phoneKeyId = suspendCancellableCoroutine<Int?> { continuation ->
                        manager.provisionPhoneCompletion(deviceId, publicKey, userId) { keyId ->
                            if (continuation.isActive) continuation.resume(keyId)
                        }
                    }
                    if (phoneKeyId != null) {
                        Result.success(PhoneKeyInfoResponse.success(phoneKeyId))
                    } else {
                        Result.failure(
                            NokeMobileLibraryError.ProvisioningFailed("Backend provisioning failed or storage error occurred"),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Storage error during provisioning for user=$userId", e)
                Result.failure(NokeMobileLibraryError.StorageError("provisioning", e))
            } catch (e: Exception) {
                Log.e(TAG, "Provisioning error for user=$userId", e)
                Result.failure(NokeMobileLibraryError.ProvisioningFailed(e.message ?: "Unexpected provisioning error", e))
            }
        }

    // The interface's generateAcl carries no device id, so it is resolved from the Android id here.
    override suspend fun generateAcl(userId: Int, lockMac: String, phoneKeyId: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (lockMac.isEmpty()) return@withContext Result.failure(NokeMobileLibraryError.InvalidInput("lockMac cannot be empty"))
            if (userId <= 0) return@withContext Result.failure(NokeMobileLibraryError.InvalidInput("userId must be positive"))
            if (phoneKeyId <= 0) return@withContext Result.failure(NokeMobileLibraryError.InvalidInput("phoneKeyId must be positive"))
            try {
                mutex.withLock {
                    val manager = manager(userId.toString(), PhoneKeyAccessService.getDeviceUdid(appContext))

                    var aclException: Exception? = null
                    val success = suspendCancellableCoroutine<Boolean> { continuation ->
                        manager.getAcl(userId, lockMac, phoneKeyId) { result, exception ->
                            aclException = exception
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                    if (success) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            NokeMobileLibraryError.AclFetchFailed(
                                lockMac = lockMac,
                                reason = aclException?.message ?: "Backend request failed or ACL storage error",
                                underlying = aclException,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "generateAcl error for lock=$lockMac", e)
                Result.failure(NokeMobileLibraryError.UnknownError("generateAcl", e))
            }
        }

    override suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        deviceId: String,
    ): Result<BulkAclResult> =
        withContext(Dispatchers.IO) {
            if (phoneKeyId <= 0) return@withContext Result.failure(NokeMobileLibraryError.InvalidInput("phoneKeyId must be positive"))
            try {
                mutex.withLock {
                    val manager = manager(userId, deviceId)

                    var bulkException: Exception? = null
                    val (successCount, totalCount) = suspendCancellableCoroutine<Pair<Int, Int>> { continuation ->
                        manager.getBulkAcls(phoneKeyId) { success, total, exception ->
                            bulkException = exception
                            if (continuation.isActive) continuation.resume(Pair(success, total))
                        }
                    }

                    // Backend errors are surfaced rather than treated as an empty result.
                    bulkException?.let { error ->
                        return@withLock Result.failure(
                            NokeMobileLibraryError.BulkAclFetchFailed(phoneKeyId, error.message ?: "Backend request failed", error),
                        )
                    }
                    if (totalCount > 0 && successCount == 0) {
                        return@withLock Result.failure(
                            NokeMobileLibraryError.BulkAclFetchFailed(phoneKeyId, "All $totalCount ACLs failed to store locally"),
                        )
                    }
                    Result.success(BulkAclResult(successCount, totalCount))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "generateBulkAcls error", e)
                Result.failure(NokeMobileLibraryError.BulkAclFetchFailed(phoneKeyId, e.message ?: "Unexpected error", e))
            }
        }

    override suspend fun validateCurrentKey(userId: String, deviceId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val manager = manager(userId, deviceId)
                    manager.ensureKeys()
                    manager.getPublicKeyBase64()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Key validation failed for user=$userId", e)
                null
            }
        }

    /**
     * Checks the cached ACL for [lockMac]: it must be unexpired and embed this phone's current public key.
     * The lock verifies the command signature against the key inside the ACL and drops the BLE link when they
     * differ (e.g. an ACL cached under an older Keystore key), so such an ACL must not be sent.
     * [CachedAclStatus.UNVERIFIABLE] means the ACL could not be parsed; callers should proceed.
     */
    suspend fun checkCachedAcl(userId: String, deviceId: String, lockMac: String): CachedAclStatus =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    checkAcl(manager(userId, deviceId), lockMac)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "checkCachedAcl failed for lock=$lockMac: ${e.message}")
                    CachedAclStatus.UNVERIFIABLE
                }
            }
        }

    /** Removes the cached ACL for [lockMac], e.g. after [checkCachedAcl] reports it unusable. */
    suspend fun discardCachedAcl(userId: String, deviceId: String, lockMac: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    manager(userId, deviceId).let { it.deleteACL(it.getUserId(), lockMac) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "discardCachedAcl failed for lock=$lockMac: ${e.message}")
                }
            }
        }
    }

    // prewarm() runs outside [mutex] (e.g. Application.onCreate), so the cache needs its own guard.
    private fun manager(userId: String, deviceId: String): PhoneKeyManager =
        synchronized(managerCache) {
            managerCache.getOrPut("${userId}_$deviceId") {
                val security = SecurityServiceImpl(
                    appContext, baseUrl, authToken, { "" }, 1, 3000L, tokenScheme, extraHeaders,
                )
                PhoneKeyManager.getInstance(appContext, userId, deviceId, security)
            }
        }

    private fun checkAcl(manager: PhoneKeyManager, lockMac: String): CachedAclStatus {
        val envelope = manager.getAclEnvelope(lockMac) ?: return CachedAclStatus.MISSING
        val now = System.currentTimeMillis() / 1000
        if (envelope.acl.expiresAt <= now) return CachedAclStatus.EXPIRED

        return try {
            // Layout of PhoneKeyAcl.toBytes(): trackingId, mac, issuedAt, expiresAt, schedule, X9.62 key, permissions.
            val buf = ByteBuffer.wrap(Base64.decode(envelope.aclBinary, Base64.DEFAULT))
            buf.int
            val mac = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
            buf.long
            val expiresAt = buf.long
            buf.get(ByteArray(buf.short.toInt() and 0xFFFF))
            val aclKey = ByteArray(X962_UNCOMPRESSED_KEY_SIZE).also { buf.get(it) }
            buf.int

            val parsedCleanly = !buf.hasRemaining() &&
                aclKey[0] == 0x04.toByte() &&
                String(mac, StandardCharsets.UTF_8).equals(lockMac, ignoreCase = true)
            when {
                !parsedCleanly -> CachedAclStatus.UNVERIFIABLE
                expiresAt <= now -> CachedAclStatus.EXPIRED
                MessageDigest.isEqual(Base64.decode(manager.getPublicKeyBase64(), Base64.NO_WRAP), aclKey) -> CachedAclStatus.VALID
                else -> CachedAclStatus.KEY_MISMATCH
            }
        } catch (e: RuntimeException) {
            CachedAclStatus.UNVERIFIABLE
        }
    }

    private companion object {
        const val TAG = "DefaultPhoneKeyClient"
        const val X962_UNCOMPRESSED_KEY_SIZE = 65
    }
}
