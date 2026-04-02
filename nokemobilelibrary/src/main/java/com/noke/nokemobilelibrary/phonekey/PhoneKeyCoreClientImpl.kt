package com.noke.nokemobilelibrary.phonekey

import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.internal.SecurityService
import com.noke.nokemobilelibrary.phonekey.internal.SecurityServiceImpl
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Default implementation of [PhoneKeyCoreClient] using PhoneKeyManager and SecurityService.
 *
 * This implementation:
 * - Uses Android Keystore for ECDSA P-256 key pair management
 * - Uses OkHttp-based SecurityService for backend communication
 * - Manages multiple (userId, deviceId) contexts with cached PhoneKeyManager instances
 * - Ensures thread safety with Mutex and single-threaded dispatcher
 * - Converts callback-based APIs to coroutine-based Result<T>
 *
 * ## Architecture
 *
 * ```
 * PhoneKeyCoreClientImpl
 *   ├─ SecurityService (networking, provisioning, ACL fetch)
 *   └─ PhoneKeyManager (crypto, keystore, storage) [cached per user/device]
 * ```
 *
 * ## Thread Safety
 *
 * All operations are serialized via:
 * 1. Mutex for manager cache access
 * 2. Single-threaded dispatcher (serial execution)
 *
 * This matches iOS behavior (serial DispatchQueue) while preventing
 * race conditions in PhoneKeyManager state.
 *
 * ## Manager Caching
 *
 * PhoneKeyManager instances are cached per (userId, deviceId) to:
 * - Avoid redundant keystore access on repeated operations
 * - Maintain state consistency for each user/device combination
 * - Support multi-user and multi-device scenarios efficiently
 *
 * @param context Application context (to avoid memory leaks)
 * @param baseUrl Backend API base URL (e.g., "https://router.smartentry.noke.com/")
 * @param authTokenProvider Function returning current auth token (thread-safe, called on background threads)
 * @param userUuidProvider Function returning current user UUID (thread-safe, called on background threads)
 * @param dispatcher Coroutine dispatcher for operations (default: IO with single-thread parallelism)
 */
class PhoneKeyCoreClientImpl(
    private val context: Context,
    private val baseUrl: String,
    private val authTokenProvider: () -> String,
    private val userUuidProvider: () -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) : PhoneKeyCoreClient {

    companion object {
        private const val TAG = "PhoneKeyCoreClientImpl"
    }

    // Mutex for thread-safe manager cache access
    private val mutex = Mutex()

    // Cache PhoneKeyManager instances per (userId, deviceId)
    private val managerCache = mutableMapOf<String, PhoneKeyManager>()

    // Track initialization state
    private var initialized = false

    // Shared SecurityService for all operations
    private val securityService: SecurityService by lazy {
        SecurityServiceImpl(
            context = context.applicationContext,
            baseUrl = baseUrl,
            authTokenProvider = authTokenProvider,
            userUuidProvider = userUuidProvider
        )
    }

    override val isInitialized: Boolean
        get() = initialized

    /**
     * Get or create PhoneKeyManager for a specific (userId, deviceId) combination.
     *
     * Managers are cached to avoid redundant keystore access. Each manager is
     * isolated with its own encrypted storage file.
     *
     * @param userId User identifier
     * @param deviceId Device identifier
     * @return PhoneKeyManager instance for this user/device
     */
    private fun getOrCreateManager(userId: String, deviceId: String): PhoneKeyManager {
        val cacheKey = "${userId}_${deviceId}"
        return managerCache.getOrPut(cacheKey) {
            PhoneKeyManager(
                context = context.applicationContext,
                userId = userId,
                udid = deviceId,
                securityService = securityService
            ).also {
                Log.d(TAG, "Created PhoneKeyManager for user=$userId, device=$deviceId")
            }
        }
    }

    override suspend fun initialize(userId: String, deviceId: String): Result<String> {
        return withContext(dispatcher) {
            try {
                mutex.withLock {
                    val manager = getOrCreateManager(userId, deviceId)

                    // Ensure keys exist (synchronous operation)
                    manager.ensureKeys()
                    
                    // Get public key in Base64 X9.62 format
                    val publicKey = manager.getPublicKeyBase64()
                    
                    initialized = true
                    Log.d(TAG, "Initialized successfully for user=$userId, device=$deviceId")
                    Result.success(publicKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed for user=$userId, device=$deviceId: $e")
                Result.failure(
                    NokeMobileLibraryError.CryptographicError("key generation", e) as Throwable
                )
            }
        }
    }

    override suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse> {
        return withContext(dispatcher) {
            try {
                mutex.withLock {
                    val manager = getOrCreateManager(userId, deviceId)

                    // Call provisioning with suspend continuation
                    val phoneKeyId = suspendCoroutine<Int?> { continuation ->
                        manager.provisionPhoneCompletion(deviceId, publicKey, userId) { keyId ->
                            continuation.resume(keyId)
                        }
                    }

                    if (phoneKeyId != null) {
                        Log.d(TAG, "Provisioned phone key: $phoneKeyId for user=$userId")
                        Result.success(PhoneKeyInfoResponse.success(phoneKeyId))
                    } else {
                        Log.e(TAG, "Provisioning failed for user=$userId")
                        Result.success(
                            PhoneKeyInfoResponse.failure(
                                error = "Provisioning failed",
                                detail = "Backend rejected provisioning or storage failed"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during provisioning for user=$userId", e)
                Result.failure(
                    NokeMobileLibraryError.UnknownError("provisionPhoneKey", e) as Throwable
                )
            }
        }
    }

    override suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int
    ): Result<Unit> {
        return withContext(dispatcher) {
            try {
                mutex.withLock {
                    // For single ACL, we need to find the right manager
                    // Since we don't have deviceId here, we'll use the first available manager
                    // This is acceptable because ACLs are user-scoped, not device-scoped
                    val manager = managerCache.values.firstOrNull()
                        ?: return@withContext Result.failure(
                            NokeMobileLibraryError.NotInitialized as Throwable
                        )

                    // Fetch ACL using suspend continuation
                    val success = suspendCoroutine<Boolean> { continuation ->
                        manager.getAcl(userId, lockMac, phoneKeyId) { result ->
                            continuation.resume(result)
                        }
                    }

                    if (success) {
                        Log.d(TAG, "ACL generated for lock=$lockMac")
                        Result.success(Unit)
                    } else {
                        Log.e(TAG, "ACL generation failed for lock=$lockMac")
                        Result.failure(
                            NokeMobileLibraryError.AclFetchFailed(
                                lockMac = lockMac,
                                reason = "Backend request failed or storage error"
                            ) as Throwable
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during ACL generation", e)
                Result.failure(
                    NokeMobileLibraryError.UnknownError("generateAcl", e) as Throwable
                )
            }
        }
    }

    override suspend fun generateBulkAcls(phoneKeyId: Int): Result<BulkAclResult> {
        return withContext(dispatcher) {
            try {
                mutex.withLock {
                    // For bulk ACLs, use any available manager (ACLs are user-scoped)
                    val manager = managerCache.values.firstOrNull()
                        ?: return@withContext Result.failure(
                            NokeMobileLibraryError.NotInitialized as Throwable
                        )

                    // Fetch bulk ACLs using suspend continuation
                    val result = suspendCoroutine<Pair<Int, Int>> { continuation ->
                        manager.getBulkAcls(phoneKeyId) { successCount, totalCount ->
                            continuation.resume(Pair(successCount, totalCount))
                        }
                    }

                    val (successCount, totalCount) = result
                    val bulkResult = BulkAclResult(
                        successCount = successCount,
                        totalCount = totalCount
                    )
                    
                    Log.d(TAG, "Bulk ACLs generated: ${bulkResult.successCount}/${bulkResult.totalCount}")
                    Result.success(bulkResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during bulk ACL generation", e)
                Result.failure(
                    NokeMobileLibraryError.UnknownError("generateBulkAcls", e) as Throwable
                )
            }
        }
    }

    override suspend fun validateCurrentKey(userId: String, deviceId: String): String? {
        return withContext(dispatcher) {
            try {
                mutex.withLock {
                    val manager = getOrCreateManager(userId, deviceId)
                    // Ensure keys exist and return public key
                    manager.ensureKeys()
                    manager.getPublicKeyBase64()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Key validation failed for user=$userId, device=$deviceId: $e")
                null
            }
        }
    }
}
