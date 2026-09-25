@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
// The suppress annotations above allow this demo app to access `internal` types from
// the nokemobilelibrary module (PhoneKeyManager and SecurityServiceImpl). These are
// `internal` Kotlin types but are accessible from the same Gradle project. In a
// production app distributed as an AAR, you would provide your own SecurityService
// implementation using your own networking layer — see TEMPLATE_PhoneKeyCoreClient.kt.

package com.noke.nokeapidemo

import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError
import com.noke.nokemobilelibrary.phonekey.PhoneKeyCoreClient
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.internal.SecurityServiceImpl
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Demo implementation of [PhoneKeyCoreClient] for the Nokē Mobile Library sample app.
 *
 * Connects to the Noke backend at [baseUrl] for phone key provisioning and ACL management.
 * Auth credentials are read from SharedPreferences (populated by [NokeDemoApplication] at
 * startup from `BuildConfig.PHONE_KEY_API_KEY`, `BuildConfig.PHONE_KEY_USER_ID`, etc.).
 *
 * ## Production Integration Note
 *
 * In a real app you would replace this with your own implementation that integrates your
 * existing authentication and networking layers. See `TEMPLATE_PhoneKeyCoreClient.kt` in
 * the repository root for a full walkthrough.
 *
 * ## Credential Setup
 *
 * 1. Add `phoneKeyUserId`, `phoneKeyApiKey`, `phoneKeyEmail`, `phoneKeyCompanyId`, and
 *    `phoneKeySiteId` to `local.properties` (never committed to version control).
 *    `phoneKeyApiKey` is sent as `Authorization: ApiKey <phoneKeyApiKey>` for all Phone Key
 *    gateway requests.
 * 2. Gradle injects them as `BuildConfig` fields
 * 3. [NokeDemoApplication] stores them in SharedPreferences at app startup
 * 4. [NokeDemoApplication] calls [prewarmManager] so the correct [SecurityServiceImpl]
 *    is in the [PhoneKeyManager] singleton cache before any service call races it
 *
 * @param context Application context — used to access SharedPreferences and Android Keystore
 * @param baseUrl Base URL for Noke backend (e.g., "https://api-gateway.smartentry.noke.dev/")
 */
class NokeDemoPhoneKeyCoreClient(
    private val context: Context,
    private val baseUrl: String
) : PhoneKeyCoreClient {

    companion object {
        private const val TAG = "NokeDemoPhoneKeyClient"

        /** SharedPreferences file used by [NokeDeviceManagerService] — must match. */
        private const val PREFS_NAME = "nokeAPILibaryFile"
        private const val PREF_USER_UUID    = "user_uuid"
        private const val PREF_API_KEY      = "apikey"
        private const val PREF_EMAIL        = "email"
        private const val PREF_COMPANY_ID   = "companyId"
        private const val PREF_SITE_ID      = "siteId"
    }

    private val mutex = Mutex()

    // Cache PhoneKeyManager instances per (userId, deviceId) to reuse keys and encrypted storage.
    // Uses the same singleton as NokeDeviceManagerService so provisioned state is shared.
    private val managerCache = mutableMapOf<String, PhoneKeyManager>()

    private var _isInitialized = false

    override val isInitialized: Boolean
        get() = _isInitialized

    // -------------------------------------------------------------------------
    // PhoneKeyCoreClient Interface
    // -------------------------------------------------------------------------

    /**
     * Generates ECDSA P-256 key pair in Android Keystore for the given user/device pair
     * and returns the Base64-encoded public key. Safe to call multiple times (idempotent).
     */
    override suspend fun initialize(userId: String, deviceId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val manager = getOrCreateManager(userId, deviceId)
                    manager.ensureKeys()
                    val publicKey = manager.getPublicKeyBase64()
                    _isInitialized = true
                    Log.d(TAG, "Initialized for user=$userId, device=$deviceId")
                    Result.success(publicKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed for user=$userId", e)
                @Suppress("UNCHECKED_CAST")
                Result.failure(NokeMobileLibraryError.CryptographicError("key generation", e) as Throwable)
            }
        }

    /**
     * Registers the device's [publicKey] with the Noke backend under [userId]/[deviceId].
     * Stores the returned phone key ID in encrypted local storage for future ACL requests.
     *
     * If the phone is already provisioned (state persisted from a previous session), the
     * stored key ID is returned immediately without a backend call. This avoids 409 Conflict
     * errors on re-connect and keeps the provisioning flow idempotent.
     */
    override suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse> =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val manager = getOrCreateManager(userId, deviceId)

                    // Fast-path: already provisioned from a previous session — return stored keyId.
                    if (manager.isProvisioned()) {
                        val storedId = manager.getPhoneKeyId()?.toIntOrNull()
                        if (storedId != null) {
                            Log.d(TAG, "Already provisioned for user=$userId — returning stored keyId=$storedId")
                            return@withLock Result.success(PhoneKeyInfoResponse.success(storedId))
                        }
                        // keyId missing despite provisioned flag — fall through to re-provision
                        Log.w(TAG, "Provisioned flag set but keyId missing — re-provisioning for user=$userId")
                    }

                    val phoneKeyId = suspendCoroutine<Int?> { continuation ->
                        manager.provisionPhoneCompletion(deviceId, publicKey, userId) { keyId ->
                            continuation.resume(keyId)
                        }
                    }
                    if (phoneKeyId != null) {
                        Log.d(TAG, "Provisioned for user=$userId with keyId=$phoneKeyId")
                        Result.success(PhoneKeyInfoResponse.success(phoneKeyId))
                    } else {
                        Log.e(TAG, "Provisioning failed for user=$userId — backend rejected or storage failed")
                        Result.success(
                            PhoneKeyInfoResponse.failure(
                                error = "Provisioning failed",
                                detail = "Backend rejected request or local storage failed"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Provisioning error for user=$userId", e)
                Result.success(
                    PhoneKeyInfoResponse.failure(
                        error = "Unexpected provisioning error",
                        detail = e.message
                    )
                )
            }
        }

    /**
     * Fetches and stores the ACL envelope for a single [lockMac] from the backend.
     * Prefer [generateBulkAcls] for most scenarios — this is for targeted single-lock refresh.
     */
    override suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val manager = managerCache.values.firstOrNull()
                        ?: return@withContext Result.failure(NokeMobileLibraryError.NotInitialized)

                    var aclException: Exception? = null
                    val success = suspendCoroutine<Boolean> { continuation ->
                        manager.getAcl(userId, lockMac, phoneKeyId) { result, exception ->
                            aclException = exception
                            continuation.resume(result)
                        }
                    }
                    if (success) {
                        Log.d(TAG, "ACL fetched and stored for lock=$lockMac")
                        Result.success(Unit)
                    } else {
                        val reason = aclException?.message ?: "Backend request failed or ACL storage error"
                        Log.e(TAG, "PROVISIONING_ISSUE_DEBUG ACL fetch failed for lock=$lockMac: $reason", aclException)
                        Result.failure(
                            NokeMobileLibraryError.AclFetchFailed(
                                lockMac = lockMac,
                                reason = reason
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateAcl error for lock=$lockMac", e)
                Result.failure(NokeMobileLibraryError.UnknownError("generateAcl", e))
            }
        }

    /**
     * Fetches ACL envelopes for ALL locks accessible to the user in a single backend request.
     * ACLs are stored locally for offline use by [NokeDeviceManagerService.unlockSigningDevice].
     *
     * **Must call [initialize] for the user/device pair before this.**
     */
    override suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        deviceId: String
    ): Result<BulkAclResult> =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    // Ensure manager is in cache (initialize must have been called first)
                    val manager = getOrCreateManager(userId, deviceId)

                    var bulkException: Exception? = null
                    val (successCount, totalCount) = suspendCoroutine<Pair<Int, Int>> { cont ->
                        manager.getBulkAcls(phoneKeyId) { success, total, exception ->
                            bulkException = exception
                            cont.resume(Pair(success, total))
                        }
                    }

                    if (bulkException != null) {
                        val reason = bulkException!!.message ?: "Backend request failed"
                        Log.e(TAG, "PROVISIONING_ISSUE_DEBUG generateBulkAcls backend error: $reason", bulkException)
                        return@withLock Result.failure(
                            NokeMobileLibraryError.UnknownError("generateBulkAcls", bulkException!!)
                        )
                    }

                    val bulkResult = BulkAclResult(successCount, totalCount)
                    Log.d(TAG, "Bulk ACL fetch: ${bulkResult.successCount}/${bulkResult.totalCount} stored")
                    Result.success(bulkResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateBulkAcls error", e)
                Result.failure(NokeMobileLibraryError.UnknownError("generateBulkAcls", e))
            }
        }

    /**
     * Validates the stored ECDSA key for [userId]/[deviceId] and returns the Base64 public key,
     * or null if keys are missing or invalid.
     */
    override suspend fun validateCurrentKey(userId: String, deviceId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val manager = getOrCreateManager(userId, deviceId)
                    manager.ensureKeys()
                    manager.getPublicKeyBase64()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Key validation failed for user=$userId", e)
                null
            }
        }

    // -------------------------------------------------------------------------
    // Pre-warm (call from Application.onCreate before any service can race)
    // -------------------------------------------------------------------------

    /**
     * Pre-creates the [PhoneKeyManager] singleton with the correct [SecurityServiceImpl].
     *
     * **Must be called from [Application.onCreate]** (via [NokeDemoApplication.setupPhoneKey])
     * before any [NokeDeviceManagerService] operation that triggers [PhoneKeyManager] creation,
     * specifically [NokeDeviceManagerService.hasAclForDevice] (invoked from `updateDeviceInfo()`
     * as soon as an ION-2 device connects).
     *
     * Without this call, `hasAclForDevice` causes [PhoneKeyManager.getInstance] to create the
     * singleton first — using the library's own base URL selection (sandbox/production based on
     * `libraryMode`) instead of this demo's configured [baseUrl] — and subsequent
     * [initialize]/[provisionPhoneKey]/[generateBulkAcls] calls return that wrong singleton,
     * causing all provisioning/ACL HTTP requests to hit the wrong environment.
     *
     * This call is idempotent — safe to call multiple times.
     */
    fun prewarmManager(userId: String, deviceId: String) {
        if (userId.isNotEmpty() && deviceId.isNotEmpty()) {
            try {
                getOrCreateManager(userId, deviceId)
                Log.d(TAG, "PhoneKeyManager pre-warmed for user=$userId")
            } catch (e: Exception) {
                // Non-fatal: provisioning will be re-attempted on first connect.
                Log.w(TAG, "prewarmManager failed (non-fatal): ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the [PhoneKeyManager] singleton for the given (userId, deviceId) pair,
     * creating it (and the [SecurityServiceImpl] it requires) if not already cached.
     *
     * Using [PhoneKeyManager.getInstance] guarantees the same singleton is returned as the
     * one that [NokeDeviceManagerService] uses internally, so provisioned ACLs are shared.
     */
    private fun getOrCreateManager(userId: String, deviceId: String): PhoneKeyManager {
        val cacheKey = "${userId}_${deviceId}"
        return managerCache.getOrPut(cacheKey) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val securityService = SecurityServiceImpl(
                context = context.applicationContext,
                baseUrl = baseUrl,
                // Gateway authenticates Phone Key requests via "Authorization: ApiKey <phoneKeyApiKey>".
                authTokenProvider = { prefs.getString(PREF_API_KEY, "") ?: "" },
                userUuidProvider = { prefs.getString(PREF_USER_UUID, "") ?: "" },
                tokenScheme = "ApiKey",
                extraHeaders = mapOf(
                    "Email"     to (prefs.getString(PREF_EMAIL,      "") ?: ""),
                    "CompanyId" to (prefs.getString(PREF_COMPANY_ID, "") ?: ""),
                    "SiteId"    to (prefs.getString(PREF_SITE_ID,    "") ?: "")
                )
            )

            PhoneKeyManager.getInstance(
                context.applicationContext,
                userId,
                deviceId,
                securityService
            ).also {
                Log.d(TAG, "Created PhoneKeyManager for user=$userId, device=$deviceId")
            }
        }
    }
}
