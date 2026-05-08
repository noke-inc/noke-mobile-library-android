package com.noke.nokemobilelibrary.phonekey.internal

import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.models.AclPermission
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyAcl
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SecurityService - Backend API client for ION-2 phone key provisioning and ACL management
 *
 * **INTERNAL API** - Not for public use. Use PhoneKeyAccessService instead.
 *
 * This service handles:
 * - Phone key provisioning with backend (NEEDS_PROVISIONING -> PROVISIONED)
 * - ACL envelope fetching with signatures (PROVISIONED -> READY)
 * - Error handling to support CryptoState transitions to FAILED
 *
 * All operations are asynchronous with callback-based results.
 * 
 * Note: All callbacks are invoked on background threads (OkHttp dispatcher threads).
 * Callers must handle thread switching if UI updates are needed.
 */
internal interface SecurityService {

    /**
     * Fetch ACL envelope from backend for a specific lock.
     *
     * Success transitions CryptoState: PROVISIONED -> READY
     * Failure transitions CryptoState: PROVISIONED -> FAILED
     *
     * @param userId User ID for ACL request
     * @param lockMac MAC address of the lock
     * @param phoneKeyId Phone key ID from provisioning
     * @param callback Result callback with ACL envelope or error
     */
    fun getACLEnvelope(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        callback: AclEnvelopeCallback
    )

    /**
     * Fetch bulk ACL envelopes from backend for all locks accessible to the user.
     *
     * This is the preferred method for fetching ACLs in most scenarios (login, startup, refresh).
     * Returns simplified ACL envelopes (bulk format) without permissions/schedule details.
     * The aclBinary field contains all necessary data for lock operations.
     *
     * Usage scenarios:
     * - On successful login
     * - After provisioning
     * - On app startup if user already logged in
     * - Explicit refresh via refreshAllAcls()
     *
     * Note: Individual getACLEnvelope() should only be used for specific scenarios like refetchAcl
     *
     * @param phoneKeyId Phone key ID from provisioning
     * @param callback Result callback with list of bulk ACL envelopes or error
     */
    fun getBulkACLEnvelopes(
        phoneKeyId: Int,
        callback: BulkAclEnvelopeCallback
    )

    /**
     * Provision phone key with backend.
     *
     * Success transitions CryptoState: NEEDS_PROVISIONING -> PROVISIONED
     * Failure transitions CryptoState: NEEDS_PROVISIONING -> FAILED
     *
     * @param udid Unique device identifier
     * @param publicKey Base64-encoded ECDSA P-256 public key
     * @param userID User ID string
     * @param callback Result callback with key ID or error
     */
    fun provisionPhone(
        udid: String,
        publicKey: String,
        userID: String,
        callback: ProvisionCallback
    )

    interface AclEnvelopeCallback {
        fun onSuccess(phoneKeyAcl: PhoneKeyAcl, aclSignature: String, aclBinary: String)
        fun onFailure(exception: Exception)
    }

    interface BulkAclEnvelopeCallback {
        fun onSuccess(aclEnvelopes: List<BulkAclEnvelope>)
        fun onFailure(exception: Exception)
    }

    interface ProvisionCallback {
        fun onSuccess(keyId: Int)
        fun onFailure(exception: Exception)
    }
}

/**
 * Default implementation of SecurityService with automatic retry logic.
 *
 * This standalone implementation uses dependency injection to avoid coupling with
 * specific auth/configuration implementations. Callers provide:
 * - Base URL for API endpoints
 * - Auth token provider function
 * - User UUID provider function
 *
 * Automatically retries failed requests for transient errors (network issues, auth errors,
 * timeouts, rate limits, server errors). Non-retryable client errors (400, 404, etc.) fail
 * immediately without retry.
 *
 * ## Thread Safety
 * All network calls run on OkHttp's background dispatcher threads.
 * Callbacks are invoked on background threads - callers must handle UI thread switching.
 *
 * @param context Android context (should be application context to avoid leaks)
 * @param baseUrl Base URL for API endpoints (e.g., "https://router.smartentry.noke.com/")
 * @param authTokenProvider Function that returns current auth token (invoked on background thread)
 * @param userUuidProvider Function that returns current user UUID (invoked on background thread)
 * @param maxRetries Maximum number of retry attempts (default: 1, matching ApiClient behavior)
 * @param retryDelayMs Delay in milliseconds between retry attempts (default: 3000ms, matching RetryManager)
 *
 * @see SecurityServiceRetryInterceptor for detailed retry logic
 */
internal class SecurityServiceImpl(
    private val context: Context,
    private val baseUrl: String,
    private val authTokenProvider: () -> String,
    private val userUuidProvider: () -> String,
    maxRetries: Int = 1,
    retryDelayMs: Long = 3000
) : SecurityService {

    companion object {
        private const val TAG = "SecurityService"
        
        /**
         * Shared OkHttpClient instance for efficient connection pooling across all SecurityService instances.
         * Thread-safe: OkHttpClient is designed for sharing and manages its own thread pool internally.
         * 
         * Connection pool, dispatcher, and cache are shared for optimal resource usage across all instances.
         */
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Instance-specific client with retry interceptor
    // Note: Uses shared connection pool from sharedClient but adds custom retry logic per instance
    private val client: OkHttpClient = sharedClient.newBuilder()
        .addInterceptor(
            SecurityServiceRetryInterceptor(
                context = context.applicationContext,
                authTokenProvider = authTokenProvider,
                maxRetries = maxRetries,
                retryDelayMs = retryDelayMs
            )
        )
        .build()

    override fun getACLEnvelope(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        callback: SecurityService.AclEnvelopeCallback
    ) {
        Log.d(TAG, "ION-2 - Starting ACL fetch for lock $lockMac")
        
        // Validate inputs
        if (lockMac.isBlank()) {
            Log.e(TAG, "ION-2 - Invalid lockMac: empty or blank")
            callback.onFailure(IllegalArgumentException("Lock MAC address cannot be empty"))
            return
        }
        
        if (phoneKeyId <= 0) {
            Log.e(TAG, "ION-2 - Invalid phoneKeyId: $phoneKeyId")
            callback.onFailure(IllegalArgumentException("Phone key ID must be positive"))
            return
        }
        
        val token = authTokenProvider()
        if (token.isBlank()) {
            Log.e(TAG, "ION-2 - Authentication token is not available")
            callback.onFailure(IllegalArgumentException("Authentication token is required"))
            return
        }
        
        try {
            val body = JSONObject().apply {
                put("userId", userId)
                put("lockMac", lockMac)
                put("phoneKeyId", phoneKeyId)
            }
            Log.d(TAG, "ION-2 - ACL request body: $body")

            val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl}nse-phone-acl/api/v1/acls")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            Log.d(TAG, "ION-2 - acl envelope url: ${baseUrl}nse-phone-acl/api/v1/acls")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "ION-2 - ACL network failure for lock $lockMac: ${e.message}", e)
                    callback.onFailure(IOException("Network error fetching ACL: ${e.message}", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val errorBody = it.body?.string()
                                val errorMessage = when (it.code) {
                                    401 -> "Unauthorized - authentication failed"
                                    403 -> "Forbidden - insufficient permissions"
                                    404 -> "ACL not found for lock $lockMac"
                                    500 -> "Server error - please retry"
                                    503 -> "Service unavailable - please retry"
                                    else -> "HTTP ${it.code}: ${it.message}"
                                }
                                Log.e(TAG, "ION-2 - ACL HTTP ${it.code} for lock $lockMac: $errorMessage")
                                Log.e(TAG, "ION-2 - ACL error body: $errorBody")
                                callback.onFailure(
                                    IOException("ACL fetch failed - $errorMessage")
                                )
                                return
                            }
                            
                            // Validate response body exists
                            val jsonString = it.body?.string()
                            if (jsonString.isNullOrBlank()) {
                                Log.e(TAG, "ION-2 - ACL response body is empty for lock $lockMac")
                                callback.onFailure(IOException("Empty response from server"))
                                return
                            }

                            val root = JSONObject(jsonString)
                            Log.d(TAG, "ION-2 - ACL response received for lock $lockMac")

                            // Validate required fields exist
                            if (!root.has("acl")) {
                                Log.e(TAG, "ION-2 - ACL object missing in response")
                                callback.onFailure(IOException("Invalid response: missing ACL object"))
                                return
                            }
                            
                            if (!root.has("aclSignature")) {
                                Log.e(TAG, "ION-2 - ACL signature missing in response")
                                callback.onFailure(IOException("Invalid response: missing ACL signature"))
                                return
                            }

                            if (!root.has("aclBinary")) {
                                Log.e(TAG, "ION-2 - ACL binary missing in response")
                                callback.onFailure(IOException("Invalid response: missing ACL binary"))
                                return
                            }

                            // Extract ACL object and envelope data from response
                            val aclJson = root.getJSONObject("acl")
                            val aclSignature = root.getString("aclSignature")
                            val aclBinary = root.getString("aclBinary")
                            
                            // Validate ACL fields
                            val requiredFields = listOf("trackingId", "lockMac", "issuedAt", "expiresAt", 
                                                        "schedule", "phonePubKey", "permissions")
                            for (field in requiredFields) {
                                if (!aclJson.has(field)) {
                                    Log.e(TAG, "ION-2 - ACL missing required field: $field")
                                    callback.onFailure(IOException("Invalid ACL: missing $field"))
                                    return
                                }
                            }

                            // Parse ISO8601 date strings to epoch seconds
                            val issuedAtEpoch = org.threeten.bp.Instant.parse(aclJson.getString("issuedAt")).epochSecond
                            val expiresAtEpoch = org.threeten.bp.Instant.parse(aclJson.getString("expiresAt")).epochSecond

                            // Convert schedule array to JSON string
                            val scheduleString = aclJson.getJSONArray("schedule").toString()

                            val phoneKeyAcl = PhoneKeyAcl(
                                trackingId = aclJson.getInt("trackingId"),
                                lockMac = aclJson.getString("lockMac"),
                                issuedAt = issuedAtEpoch,
                                expiresAt = expiresAtEpoch,
                                schedule = scheduleString,
                                phonePubKey = aclJson.getString("phonePubKey"),
                                permissions = aclJson.getJSONArray("permissions").let { arr ->
                                    val perms = mutableSetOf<AclPermission>()
                                    var unknownCount = 0
                                    for (i in 0 until arr.length()) {
                                        try {
                                            perms.add(AclPermission.valueOf(arr.getString(i)))
                                        } catch (e: IllegalArgumentException) {
                                            // Log unknown permission but continue processing
                                            Log.w(TAG, "ION-2 - Unknown permission '${arr.getString(i)}' ignored for lock $lockMac")
                                            unknownCount++
                                        }
                                    }
                                    
                                    // Safeguard: Warn if no recognized permissions
                                    if (perms.isEmpty() && arr.length() > 0) {
                                        Log.e(TAG, "ION-2 - ⚠️ CRITICAL: ACL for lock $lockMac has ${arr.length()} permission(s) but NONE are recognized! " +
                                                "Backend sent: ${(0 until arr.length()).map { arr.getString(it) }}. " +
                                                "This ACL will fail validation and lock cannot be unlocked.")
                                    } else if (unknownCount > 0) {
                                        Log.w(TAG, "ION-2 - ACL for lock $lockMac: Recognized ${perms.size} permission(s), ignored $unknownCount unknown permission(s)")
                                    }
                                    
                                    perms
                                }
                            )

                            Log.d(TAG, "ION-2 - ACL envelope validated for lock $lockMac (expires: ${phoneKeyAcl.expiresAt}) with binary (${aclBinary.length} chars)")
                            callback.onSuccess(phoneKeyAcl, aclSignature, aclBinary)
                        } catch (e: org.json.JSONException) {
                            Log.e(TAG, "ION-2 - ACL JSON parsing error for lock $lockMac: ${e.message}", e)
                            callback.onFailure(IOException("Invalid JSON in ACL response: ${e.message}", e))
                        } catch (e: Exception) {
                            Log.e(TAG, "ION-2 - ACL processing error for lock $lockMac: ${e.message}", e)
                            callback.onFailure(IOException("Error processing ACL: ${e.message}", e))
                        }
                    }
                }
            })
        } catch (e: IllegalArgumentException) {
            // Already logged in validation
            callback.onFailure(e)
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Unexpected error fetching ACL for lock $lockMac: ${e.message}", e)
            callback.onFailure(IOException("Unexpected error: ${e.message}", e))
        }
    }

    override fun getBulkACLEnvelopes(
        phoneKeyId: Int,
        callback: SecurityService.BulkAclEnvelopeCallback
    ) {
        Log.d(TAG, "ION-2 - Starting bulk ACL fetch for phoneKeyId $phoneKeyId")
        
        // Validate inputs
        if (phoneKeyId <= 0) {
            Log.e(TAG, "ION-2 - Invalid phoneKeyId: $phoneKeyId")
            callback.onFailure(IllegalArgumentException("Phone key ID must be positive"))
            return
        }
        
        val token = authTokenProvider()
        if (token.isBlank()) {
            Log.e(TAG, "ION-2 - Authentication token is not available")
            callback.onFailure(IllegalArgumentException("Authentication token is required"))
            return
        }
        
        try {
            val body = JSONObject().apply {
                put("phoneKeyId", phoneKeyId)
            }
            Log.d(TAG, "ION-2 - Bulk ACL request body: $body")

            val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl}nse-phone-acl/api/v1/acls/me")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            Log.d(TAG, "ION-2 - Bulk ACL URL: ${baseUrl}nse-phone-acl/api/v1/acls/me")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "ION-2 - Bulk ACL network failure: ${e.message}", e)
                    callback.onFailure(IOException("Network error fetching bulk ACL: ${e.message}", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val errorBody = it.body?.string()
                                val errorMessage = when (it.code) {
                                    401 -> "Unauthorized - authentication failed"
                                    403 -> "Forbidden - insufficient permissions"
                                    404 -> "Bulk ACL not found"
                                    500 -> "Server error - please retry"
                                    503 -> "Service unavailable - please retry"
                                    else -> "HTTP ${it.code}: ${it.message}"
                                }
                                Log.e(TAG, "ION-2 - Bulk ACL HTTP ${it.code}: $errorMessage")
                                Log.e(TAG, "ION-2 - Bulk ACL error body: $errorBody")
                                callback.onFailure(
                                    IOException("Bulk ACL fetch failed - $errorMessage")
                                )
                                return
                            }
                            
                            // Validate response body exists
                            val jsonString = it.body?.string()
                            if (jsonString.isNullOrBlank()) {
                                Log.e(TAG, "ION-2 - Bulk ACL response body is empty")
                                callback.onFailure(IOException("Empty response from server"))
                                return
                            }

                            val root = JSONObject(jsonString)
                            Log.d(TAG, "ION-2 - Bulk ACL response received")

                            // Validate response structure
                            if (!root.has("result")) {
                                Log.e(TAG, "ION-2 - Bulk ACL response missing 'result' field")
                                callback.onFailure(IOException("Invalid response: missing result field"))
                                return
                            }
                            
                            val result = root.getString("result")
                            if (result != "success") {
                                Log.e(TAG, "ION-2 - Bulk ACL request failed with result: $result")
                                callback.onFailure(IOException("Bulk ACL request failed: $result"))
                                return
                            }
                            
                            if (!root.has("acls")) {
                                Log.e(TAG, "ION-2 - Bulk ACL response missing 'acls' array")
                                callback.onFailure(IOException("Invalid response: missing acls array"))
                                return
                            }

                            // Parse ACL array
                            val aclsArray = root.getJSONArray("acls")
                            val bulkAcls = mutableListOf<BulkAclEnvelope>()
                            
                            Log.d(TAG, "ION-2 - Processing ${aclsArray.length()} ACLs from bulk response")
                            
                            for (i in 0 until aclsArray.length()) {
                                try {
                                    val aclObj = aclsArray.getJSONObject(i)
                                    
                                    // Validate required fields for each ACL
                                    val requiredFields = listOf("lockMac", "aclBinary", "aclSignature", "issuedAt", "expiresAt")
                                    var missingField = false
                                    for (field in requiredFields) {
                                        if (!aclObj.has(field)) {
                                            Log.w(TAG, "ION-2 - ACL at index $i missing required field: $field, skipping")
                                            missingField = true
                                            break
                                        }
                                    }
                                    if (missingField) continue
                                    
                                    val lockMac = aclObj.getString("lockMac")
                                    val aclBinary = aclObj.getString("aclBinary")
                                    val aclSignature = aclObj.getString("aclSignature")
                                    
                                    // Parse ISO8601 date strings to epoch seconds
                                    val issuedAtEpoch: Long
                                    val expiresAtEpoch: Long
                                    try {
                                        issuedAtEpoch = org.threeten.bp.Instant.parse(aclObj.getString("issuedAt")).epochSecond
                                        expiresAtEpoch = org.threeten.bp.Instant.parse(aclObj.getString("expiresAt")).epochSecond
                                    } catch (e: org.threeten.bp.format.DateTimeParseException) {
                                        Log.w(TAG, "ION-2 - Invalid date format for ACL at index $i (lockMac=$lockMac): ${e.message}, skipping")
                                        continue
                                    }
                                    
                                    val bulkAcl = BulkAclEnvelope(
                                        lockMac = lockMac,
                                        aclBinary = aclBinary,
                                        aclSignature = aclSignature,
                                        issuedAt = issuedAtEpoch,
                                        expiresAt = expiresAtEpoch
                                    )
                                    
                                    bulkAcls.add( bulkAcl)
                                    Log.d(TAG, "ION-2 - Parsed bulk ACL for lock $lockMac (expires: $expiresAtEpoch)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "ION-2 - Failed to parse ACL at index $i: ${e.message}, skipping")
                                    // Continue processing remaining ACLs
                                }
                            }
                            
                            if (bulkAcls.isEmpty() && aclsArray.length() > 0) {
                                Log.e(TAG, "ION-2 - Failed to parse any valid ACLs from ${aclsArray.length()} items")
                                callback.onFailure(IOException("No valid ACLs found in response"))
                                return
                            }
                            
                            Log.d(TAG, "ION-2 - Successfully parsed ${bulkAcls.size} bulk ACLs")
                            callback.onSuccess(bulkAcls)
                        } catch (e: org.json.JSONException) {
                            Log.e(TAG, "ION-2 - Bulk ACL JSON parsing error: ${e.message}", e)
                            callback.onFailure(IOException("Invalid JSON in bulk ACL response: ${e.message}", e))
                        } catch (e: Exception) {
                            Log.e(TAG, "ION-2 - Bulk ACL processing error: ${e.message}", e)
                            callback.onFailure(IOException("Error processing bulk ACL: ${e.message}", e))
                        }
                    }
                }
            })
        } catch (e: IllegalArgumentException) {
            // Already logged in validation
            callback.onFailure(e)
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Unexpected error fetching bulk ACL: ${e.message}", e)
            callback.onFailure(IOException("Unexpected error: ${e.message}", e))
        }
    }

    override fun provisionPhone(
        udid: String,
        publicKey: String,
        userID: String,
        callback: SecurityService.ProvisionCallback
    ) {
        Log.d(TAG, "ION-2 - Starting phone provisioning for user $userID")
        
        // Validate inputs
        if (udid.isBlank()) {
            Log.e(TAG, "ION-2 - Invalid udid: empty or blank")
            callback.onFailure(IllegalArgumentException("Device UDID cannot be empty"))
            return
        }
        
        if (publicKey.isBlank()) {
            Log.e(TAG, "ION-2 - Invalid publicKey: empty or blank")
            callback.onFailure(IllegalArgumentException("Public key cannot be empty"))
            return
        }
        
        if (userID.isBlank()) {
            Log.e(TAG, "ION-2 - Invalid userID: empty or blank")
            callback.onFailure(IllegalArgumentException("User ID cannot be empty"))
            return
        }
        
        val token = authTokenProvider()
        if (token.isBlank()) {
            Log.e(TAG, "ION-2 - Authentication token is not available")
            callback.onFailure(IllegalArgumentException("Authentication token is required"))
            return
        }
        
        try {
            val body = JSONObject().apply {
                put("phone_udid", udid)
                put("public_key", publicKey)
                put("user_id", userID)
            }
            Log.d(TAG, "ION-2 - Provisioning request body created for user $userID")

            val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl}nse-provisioning-device/v1/phone-keys")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            Log.d(TAG, "ION-2 - Provisioning URL: ${baseUrl}nse-provisioning-device/v1/phone-keys")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "ION-2 - Provisioning network failure for user $userID: ${e.message}", e)
                    callback.onFailure(IOException("Network error during provisioning: ${e.message}", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val errorBody = it.body?.string()
                                val errorMessage = when (it.code) {
                                    400 -> "Bad request - invalid provisioning data"
                                    401 -> "Unauthorized - authentication failed"
                                    403 -> "Forbidden - insufficient permissions"
                                    409 -> "Conflict - phone already provisioned"
                                    500 -> "Server error - please retry"
                                    503 -> "Service unavailable - please retry"
                                    else -> "HTTP ${it.code}: ${it.message}"
                                }
                                Log.e(TAG, "ION-2 - Provisioning HTTP ${it.code} for user $userID: $errorMessage")
                                Log.e(TAG, "ION-2 - Provisioning error body: $errorBody")
                                callback.onFailure(
                                    IOException("Provisioning failed - $errorMessage")
                                )
                                return
                            }
                            
                            // Validate response body exists
                            val jsonString = it.body?.string()
                            if (jsonString.isNullOrBlank()) {
                                Log.e(TAG, "ION-2 - Provisioning response body is empty for user $userID")
                                callback.onFailure(IOException("Empty response from server"))
                                return
                            }

                            val json = JSONObject(jsonString)
                            
                            // Validate response has key_id
                            if (!json.has("key_id")) {
                                Log.e(TAG, "ION-2 - Provisioning response missing key_id for user $userID")
                                callback.onFailure(IOException("Invalid response: missing key_id"))
                                return
                            }
                            
                            val keyId = json.getInt("key_id")
                            
                            if (keyId <= 0) {
                                Log.e(TAG, "ION-2 - Invalid key_id received: $keyId for user $userID")
                                callback.onFailure(IOException("Invalid key_id: $keyId"))
                                return
                            }
                            
                            Log.d(TAG, "ION-2 - Phone provisioned successfully for user $userID with keyId=$keyId")
                            callback.onSuccess(keyId)
                        } catch (e: org.json.JSONException) {
                            Log.e(TAG, "ION-2 - Provisioning JSON parsing error for user $userID: ${e.message}", e)
                            callback.onFailure(IOException("Invalid JSON in provisioning response: ${e.message}", e))
                        } catch (e: Exception) {
                            Log.e(TAG, "ION-2 - Provisioning processing error for user $userID: ${e.message}", e)
                            callback.onFailure(IOException("Error processing provisioning: ${e.message}", e))
                        }
                    }
                }
            })

        } catch (e: IllegalArgumentException) {
            // Already logged in validation
            callback.onFailure(e)
        } catch (e: Exception) {
            Log.e(TAG, "ION-2 - Unexpected error provisioning phone for user $userID: ${e.message}", e)
            callback.onFailure(IOException("Unexpected error: ${e.message}", e))
        }
    }
}
