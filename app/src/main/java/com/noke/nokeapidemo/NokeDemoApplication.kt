package com.noke.nokeapidemo

import android.app.Application
import android.content.Context
import android.util.Log
import com.jakewharton.threetenabp.AndroidThreeTen
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

/**
 * Application class for the Nokē Mobile Library Demo.
 *
 * Performs one-time initialization required before the library can be used:
 * - Initializes ThreeTenABP (backport of java.time for API < 26) for Phone Key date/time handling
 * - Stores the Phone Key credentials (userId, apiKey, email, companyId, siteId) in
 *   SharedPreferences so they are available to [NokeDeviceManagerService] and
 *   [NokeDemoPhoneKeyCoreClient] at runtime
 * - Pre-warms the [PhoneKeyManager] singleton with the correct [SecurityServiceImpl] BEFORE
 *   any service call can race to create it with wrong credentials
 * - Registers [NokeDemoPhoneKeyCoreClient] as the shared [PhoneKeyAccessService] client so
 *   the provisioning + ACL flow can be triggered from [DeviceDetailActivity]
 *
 * ## Configuration
 *
 * Set the following in `local.properties` before building (never commit to version control):
 *
 * ```
 * phoneKeyUserId=5461
 * phoneKeyApiKey=b836343d-...
 * phoneKeyEmail=user@example.com
 * phoneKeyCompanyId=340
 * phoneKeySiteId=729
 * phoneKeyBaseUrl=https://api-gateway.smartentry.noke.dev/
 * ```
 * These are injected as `BuildConfig` fields and stored in SharedPreferences under the same
 * keys that [NokeDeviceManagerService] reads internally.
 *
 * ## Phone Key Setup
 *
 * To enable Phone Key support (ION-2 devices), implement [PhoneKeyCoreClient] and register it:
 *
 * ```kotlin
 * val client = MyCustomPhoneKeyCoreClient(this, myApiClient)
 * PhoneKeyAccessService.setSharedClient(client, this)
 * ```
 *
 * See TEMPLATE_PhoneKeyCoreClient.kt in the library for a full implementation template.
 */
class NokeDemoApplication : Application() {

    companion object {
        private const val TAG = "NokeDemoApplication"

        /** SharedPreferences file name — must match NokeDeviceManagerService (NokeDefines.PREFS_NAME). */
        private const val PREFS_NAME = "nokeAPILibaryFile"
        private const val PREF_USER_UUID    = "user_uuid"
        private const val PREF_API_KEY      = "apikey"
        private const val PREF_EMAIL        = "email"
        private const val PREF_COMPANY_ID   = "companyId"
        private const val PREF_SITE_ID      = "siteId"
    }

    override fun onCreate() {
        super.onCreate()

        // Required for Phone Key date/time operations (ThreeTen backport for API 21+)
        AndroidThreeTen.init(this)

        // Store Phone Key credentials and register the client
        setupPhoneKey()

        Log.d(TAG, "NokeDemoApplication initialized")
    }

    // -------------------------------------------------------------------------
    // Phone Key Setup
    // -------------------------------------------------------------------------

    /**
     * Stores the configured credentials in the SharedPreferences file that
     * [NokeDeviceManagerService] reads from, pre-warms the [PhoneKeyManager] singleton
     * with the correct [SecurityServiceImpl], then registers [NokeDemoPhoneKeyCoreClient]
     * with [PhoneKeyAccessService].
     *
     * ## Singleton pre-warming
     *
     * [NokeDeviceManagerService.hasAclForDevice] is called from `updateDeviceInfo()` the
     * moment an ION-2 device connects. Internally it calls [PhoneKeyManager.getInstance]
     * which would create the singleton with the service's own hardcoded URL and Bearer
     * auth scheme if no instance exists yet. By calling [NokeDemoPhoneKeyCoreClient.prewarmManager]
     * here (before any activity/service is running), we ensure our correctly-configured
     * [SecurityServiceImpl] (ApiKey scheme, gateway URL, extra headers) is installed in the
     * singleton cache first, preventing that race.
     *
     * If credentials are not configured (empty BuildConfig values), Phone Key provisioning
     * will fail gracefully with a logged warning — classic device unlock is unaffected.
     */
    private fun setupPhoneKey() {
        val userId    = BuildConfig.PHONE_KEY_USER_ID.trim()
        val apiKey    = BuildConfig.PHONE_KEY_API_KEY.trim()
        val email     = BuildConfig.PHONE_KEY_EMAIL.trim()
        val company   = BuildConfig.PHONE_KEY_COMPANY_ID.trim()
        val site      = BuildConfig.PHONE_KEY_SITE_ID.trim()
        val baseUrl   = BuildConfig.PHONE_KEY_BASE_URL.trim()
            .takeIf { it.isNotEmpty() } ?: "https://api-gateway.smartentry.noke.dev/"

        if (userId.isEmpty() || apiKey.isEmpty()) {
            Log.w(
                TAG,
                "Phone Key credentials not configured — ION-2 provisioning unavailable. " +
                    "Set phoneKeyUserId and phoneKeyApiKey in local.properties."
            )
        } else {
            // Persist all gateway credentials into SharedPreferences so SecurityServiceImpl
            // and NokeDeviceManagerService can read them at request time.
            storePhoneKeyCredentials(userId, apiKey, email, company, site)
            Log.d(TAG, "Phone Key credentials stored for user=$userId")
        }

        // Register the client with PhoneKeyAccessService so DeviceDetailActivity can call
        // initialize(), provisionPhoneKey(), and generateBulkAcls() for ION-2 devices.
        val client = NokeDemoPhoneKeyCoreClient(
            context = applicationContext,
            baseUrl = baseUrl
        )

        // Pre-warm the PhoneKeyManager singleton with our correct SecurityServiceImpl
        // BEFORE the service or any activity has a chance to create it with wrong credentials.
        // Must be called AFTER storePhoneKeyCredentials() so SharedPreferences are populated.
        if (userId.isNotEmpty() && apiKey.isNotEmpty()) {
            val udid = PhoneKeyAccessService.getDeviceUdid(applicationContext)
            client.prewarmManager(userId, udid)
        }

        PhoneKeyAccessService.setSharedClient(client, applicationContext)
        Log.d(TAG, "NokeDemoPhoneKeyCoreClient registered with PhoneKeyAccessService (baseUrl=$baseUrl)")
    }

    /**
     * Writes the Phone Key credentials into the shared preferences file used
     * by [NokeDeviceManagerService]. Values are only written if they are non-empty, so
     * existing values (e.g., from a previous login flow) are not overwritten with blanks.
     */
    private fun storePhoneKeyCredentials(
        userId: String,
        apiKey: String,
        email: String,
        companyId: String,
        siteId: String
    ) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (userId.isNotEmpty())    putString(PREF_USER_UUID,    userId)
            // apikey is sent as "Authorization: ApiKey <apikey>" for all Phone Key requests.
            if (apiKey.isNotEmpty())    putString(PREF_API_KEY,      apiKey)
            if (email.isNotEmpty())     putString(PREF_EMAIL,        email)
            if (companyId.isNotEmpty()) putString(PREF_COMPANY_ID,   companyId)
            if (siteId.isNotEmpty())    putString(PREF_SITE_ID,      siteId)
        }.apply()
    }
}

