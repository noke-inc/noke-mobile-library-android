package com.noke.nokemobilelibrary.phonekey.internal

import android.content.Context
import android.util.Log
import com.noke.smartentrycore.helpers.SharedPreferencesHelper
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp Interceptor that implements retry logic for SecurityService operations
 *
 * Retries requests that fail due to:
 * - Network errors (IOException)
 * - Authentication errors (401, 403) - fetches fresh token on retry
 * - Timeout errors (408, 504)
 * - Rate limiting (429)
 * - Server errors (500, 502, 503)
 *
 * Retry behavior:
 * - Max retries: 1 (2 total attempts) - matches ApiClient.doRequest default
 * - Retry delay: 3 seconds - matches RetryManager default
 * - Non-retryable errors (4xx except 401/403/408/429) fail immediately
 *
 * Thread safety: Runs on OkHttp's background dispatcher threads
 * 
 * @param context Android context (preferably application context to avoid memory leaks)
 * @param maxRetries Maximum number of retry attempts (must be non-negative)
 * @param retryDelayMs Delay in milliseconds between retries (must be non-negative)
 */
class SecurityServiceRetryInterceptor(
    private val context: Context,
    private val maxRetries: Int = 1,
    private val retryDelayMs: Long = 3000
) : Interceptor {

    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative, got: $maxRetries" }
        require(retryDelayMs >= 0) { "retryDelayMs must be non-negative, got: $retryDelayMs" }
        
        // Warn if not using application context (potential memory leak)
        if (context !== context.applicationContext) {
            Log.w(TAG, "SecurityServiceRetryInterceptor initialized with non-application context. " +
                    "Consider using applicationContext to avoid potential memory leaks.")
        }
    }

    companion object {
        private const val TAG = "SecurityService"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val operation = extractOperationFromUrl(originalRequest.url().toString())
        var attempt = 0
        var lastException: IOException? = null
        var response: Response? = null
        var lastStatusCode: Int? = null

        while (attempt <= maxRetries) {
            try {
                // Build request (with fresh token on auth error retries)
                val requestToExecute = if (attempt > 0 && lastStatusCode in listOf(401, 403)) {
                    // Auth error retry: fetch fresh token and rebuild request
                    Log.d(TAG, "ION-2 - Auth error detected, fetching fresh token before retry (attempt ${attempt + 1}/${maxRetries + 1})")
                    val freshToken = SharedPreferencesHelper(context).token.toString()
                    originalRequest.newBuilder()
                        .header("Authorization", "Bearer $freshToken")
                        .build()
                } else {
                    // First attempt or non-auth retry: use original request
                    originalRequest
                }

                // Clean up previous response before making new attempt
                response?.close()
                response = null

                // Execute request
                response = chain.proceed(requestToExecute)
                lastStatusCode = response.code()

                // Success - return immediately
                if (response.isSuccessful) {
                    return response
                }

                // Check if we should retry based on HTTP status code
                val shouldRetry = shouldRetryHttpError(response.code())
                
                if (!shouldRetry) {
                    // Non-retryable error (e.g., 400, 404) - fail immediately
                    return response
                }

                // Retryable HTTP error - attempt retry if allowed
                if (attempt < maxRetries) {
                    Log.d(TAG, "ION-2 - Retrying $operation (attempt ${attempt + 2}/${maxRetries + 1}) after HTTP ${response.code()}")
                    sleepWithInterruptHandling(retryDelayMs)
                    attempt++
                    // Continue to next iteration
                } else {
                    // Max retries exceeded
                    Log.w(TAG, "ION-2 - Max retries exceeded for $operation after HTTP ${response.code()}")
                    return response
                }

            } catch (e: IOException) {
                lastException = e
                
                if (attempt < maxRetries) {
                    Log.d(TAG, "ION-2 - Retrying $operation (attempt ${attempt + 2}/${maxRetries + 1}) after network error: ${e.message}")
                    sleepWithInterruptHandling(retryDelayMs)
                    attempt++
                    // Continue to next iteration
                } else {
                    // Max retries exceeded - close response if exists and rethrow
                    response?.close()
                    Log.w(TAG, "ION-2 - Max retries exceeded for $operation after network error: ${e.message}")
                    throw e
                }
            } catch (e: InterruptedException) {
                // Thread interrupted during sleep - clean up and fail fast
                response?.close()
                Thread.currentThread().interrupt() // Restore interrupt flag
                throw IOException("Request interrupted during retry delay", e)
            }
        }

        // Should not reach here in normal flow, but handle gracefully as a safeguard
        Log.w(TAG, "ION-2 - Unexpected: Exited retry loop without return or throw")
        val finalResponse = response
        val finalException = lastException
        
        return if (finalResponse != null) {
            finalResponse
        } else if (finalException != null) {
            throw finalException
        } else {
            throw IOException("Request failed with no response or exception")
        }
    }

    /**
     * Sleep with proper InterruptedException handling
     */
    private fun sleepWithInterruptHandling(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            // Re-interrupt the thread and throw to propagate the interruption
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * Determine if an HTTP error code should trigger a retry
     *
     * Retryable errors:
     * - 401, 403: Auth errors (will fetch fresh token)
     * - 408, 504: Timeout errors
     * - 429: Rate limiting
     * - 500, 502, 503: Server errors
     *
     * Non-retryable errors:
     * - Other 4xx errors (400, 404, etc.) - client errors that won't change on retry
     */
    private fun shouldRetryHttpError(statusCode: Int): Boolean {
        return when (statusCode) {
            401, 403 -> true  // Auth errors
            408, 504 -> true  // Timeout errors
            429 -> true       // Rate limit
            500, 502, 503 -> true  // Server errors
            else -> false     // All other errors (including 400, 404, etc.)
        }
    }

    /**
     * Extract operation name from URL for logging
     */
    private fun extractOperationFromUrl(url: String): String {
        return when {
            url.contains("nse-phone-acl/api/v1/acls/me") -> "bulk ACL fetch"
            url.contains("nse-phone-acl") -> "ACL fetch"
            url.contains("nse-provisioning-device") -> "phone provisioning"
            else -> "API request"
        }
    }


}
