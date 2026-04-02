# Phone Key Integration Guide - Noke Mobile Library for Android

**Complete integration guide for third-party developers using Phone Key features in Noke Mobile Library.**

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation](#installation)
3. [Quick Start](#quick-start)
4. [Initialization](#initialization)
5. [Phone Key Workflow](#phone-key-workflow)
6. [API Reference](#api-reference)
7. [Error Handling](#error-handling)
8. [Best Practices](#best-practices)
9. [Troubleshooting](#troubleshooting)
10. [Testing](#testing)

---

## Prerequisites

### Required Knowledge

- Basic Android development with Kotlin
- Understanding of Kotlin coroutines
- Familiarity with async/await patterns
- Basic cryptography concepts (public/private keys)
- Understanding of Android Application lifecycle

### System Requirements

- **minSdk 21** (Android 5.0 Lollipop) - Required for Android Keystore P-256 ECDSA support
- **targetSdk 34** (Android 14)
- **JDK 17** or higher
- Android Keystore support (available on all devices since API 21)
- Internet connectivity for backend communication

### Backend Requirements

- Access to Noke Phone ACL backend endpoints
- Valid authentication token (JWT or similar)
- User account with ION-2 lock access permissions

---

## Installation

### Step 1: Add Library Dependency

Add Noke Mobile Library to your app's `build.gradle`:

```gradle
dependencies {
    implementation 'com.noke.nokemobilelibrary:noke-mobile-library:0.11.0'

    // Required: ThreeTenABP for date/time handling
    implementation 'com.jakewharton.threetenabp:threetenabp:1.4.5'
}
```

### Step 2: Update your app's minSdk

Ensure your app supports API 21+:

```gradle
android {
    defaultConfig {
        minSdkVersion 21  // Required for Phone Key support
        targetSdkVersion 34
    }
}
```

### Step 3: Add Required Permissions

Add to your `AndroidManifest.xml`:

```xml
<manifest>
    <!-- Required for backend communication -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Required for BLE (if using legacy BLE features) -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
</manifest>
```

---

## Quick Start

### Minimal Integration (5 Minutes)

```kotlin
import android.app.Application
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import com.jakewharton.threetenabp.AndroidThreeTen
import kotlinx.coroutines.launch

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize date/time library
        AndroidThreeTen.init(this)

        // 2. Initialize PhoneKeyAccessService
        PhoneKeyAccessService.initialize(
            context = this,
            baseUrl = "https://router.smartentry.noke.com/",
            authTokenProvider = { getAuthToken() },
            userUuidProvider = { getUserUuid() }
        )
    }

    private fun getAuthToken(): String {
        // Return current authentication token from your auth system
        return MyAuthManager.getCurrentToken()
    }

    private fun getUserUuid(): String {
        // Return current user UUID from your auth system
        return MyAuthManager.getCurrentUserUuid()
    }
}

// 3. Use in your activity
class UnlockActivity : AppCompatActivity() {

    private val phoneKeyService = PhoneKeyAccessService.getInstance()

    fun setupPhoneKey() = lifecycleScope.launch {
        val userId = "user_12345"
        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        // Provision phone key
        phoneKeyService.provisionPhoneKey(userId, deviceId)
            .onSuccess { phoneKeyId ->
                Log.d(TAG, "Provisioned: $phoneKeyId")
                fetchAcls(phoneKeyId, userId, deviceId)
            }
            .onFailure { error ->
                Log.e(TAG, "Error: ${error.message}")
            }
    }

    private suspend fun fetchAcls(phoneKeyId: Int, userId: String, deviceId: String) {
        phoneKeyService.generateBulkAcls(phoneKeyId, userId, deviceId)
            .onSuccess { result ->
                Log.d(TAG, "Fetched ${result.successCount} ACLs")
                // ACLs are now cached and ready for unlock
            }
            .onFailure { error ->
                Log.e(TAG, "ACL fetch failed: ${error.message}")
            }
    }
}
```

---

## Initialization

### Application-Level Setup (Standalone Library Pattern)

Unlike internal modules, Noke Mobile Library is a **standalone library** that requires explicit initialization:

```kotlin
import android.app.Application
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import com.jakewharton.threetenabp.AndroidThreeTen

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Required: Initialize ThreeTenABP for date/time handling
        AndroidThreeTen.init(this)

        // Initialize PhoneKeyAccessService with your configuration
        PhoneKeyAccessService.initialize(
            context = this,
            baseUrl = getBaseUrl(),
            authTokenProvider = { getCurrentAuthToken() },
            userUuidProvider = { getCurrentUserUuid() }
        )

        Log.d(TAG, "Noke Mobile Library initialized")
    }

    private fun getBaseUrl(): String {
        // Production: "https://router.smartentry.noke.com/"
        // Sandbox: "https://sandbox-router.smartentry.noke.com/"
        return BuildConfig.NOKE_BASE_URL
    }

    /**
     * Provide current authentication token.
     * This function is called dynamically whenever a token is needed,
     * ensuring fresh tokens are always used.
     */
    private fun getCurrentAuthToken(): String {
        // Get token from your authentication system
        val token = SessionManager.getInstance().getAuthToken()
        if (token.isNullOrEmpty()) {
            throw IllegalStateException("User not authenticated")
        }
        return token
    }

    /**
     * Provide current user UUID.
     * This function is called dynamically whenever user ID is needed.
     */
    private fun getCurrentUserUuid(): String {
        val uuid = SessionManager.getInstance().getUserUuid()
        if (uuid.isNullOrEmpty()) {
            throw IllegalStateException("User UUID not available")
        }
        return uuid
    }
}
```

### Configuration Options

| Parameter           | Type           | Required | Description                                  |
| ------------------- | -------------- | -------- | -------------------------------------------- |
| `context`           | `Context`      | ✅ Yes   | Application context (not Activity context)   |
| `baseUrl`           | `String`       | ✅ Yes   | Backend API base URL (production or sandbox) |
| `authTokenProvider` | `() -> String` | ✅ Yes   | Lambda function returning current auth token |
| `userUuidProvider`  | `() -> String` | ✅ Yes   | Lambda function returning current user UUID  |

**Why Provider Functions?**

- Dynamic token resolution (always fresh tokens)
- No need to re-initialize on login/logout
- Handles token refresh automatically
- Decouples library from your auth system

---

## Phone Key Workflow

### Complete Provisioning & ACL Workflow

```kotlin
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhoneKeySetupActivity : AppCompatActivity() {

    private val service = PhoneKeyAccessService.getInstance()
    private lateinit var userId: String
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userId = intent.getStringExtra("USER_ID") ?: return
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        lifecycleScope.launch {
            setupPhoneKeyFlow()
        }
    }

    private suspend fun setupPhoneKeyFlow() {
        // Step 1: Check if already provisioned
        val provisionedResult = service.isProvisioned(userId, deviceId)

        provisionedResult.fold(
            onSuccess = { isProvisioned ->
                if (isProvisioned) {
                    Log.d(TAG, "Already provisioned")
                    fetchExistingAcls()
                } else {
                    Log.d(TAG, "Not provisioned, starting provisioning...")
                    provisionNewDevice()
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Error checking provisioning status", error)
                showError("Failed to check provisioning status")
            }
        )
    }

    private suspend fun provisionNewDevice() {
        // Step 2: Provision phone key
        service.provisionPhoneKey(userId, deviceId).fold(
            onSuccess = { phoneKeyId ->
                Log.d(TAG, "Provisioned successfully: phoneKeyId=$phoneKeyId")
                showSuccess("Device provisioned")

                // Step 3: Fetch ACLs after provisioning
                fetchAclsAfterProvisioning(phoneKeyId)
            },
            onFailure = { error ->
                Log.e(TAG, "Provisioning failed", error)
                handleProvisioningError(error)
            }
        )
    }

    private suspend fun fetchExistingAcls() {
        // Get phone key ID
        service.getPhoneKeyId(userId, deviceId).fold(
            onSuccess = { phoneKeyId ->
                val keyId = phoneKeyId.toIntOrNull()
                if (keyId != null && keyId > 0) {
                    fetchAcls(keyId)
                } else {
                    Log.e(TAG, "Invalid phone key ID: $phoneKeyId")
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to get phone key ID", error)
            }
        )
    }

    private suspend fun fetchAclsAfterProvisioning(phoneKeyId: Int) {
        // Small delay to allow backend propagation
        delay(1000)
        fetchAcls(phoneKeyId)
    }

    private suspend fun fetchAcls(phoneKeyId: Int) {
        // Step 4: Fetch bulk ACLs (preferred method)
        service.generateBulkAcls(phoneKeyId, userId, deviceId).fold(
            onSuccess = { result ->
                Log.d(TAG, "Bulk ACL fetch complete: " +
                    "${result.successCount}/${result.totalCount} successful")

                when {
                    result.isFullSuccess -> {
                        showSuccess("All ACLs fetched (${result.successCount})")
                        navigateToLockList()
                    }
                    result.hasPartialSuccess -> {
                        showWarning("Partial success: " +
                            "${result.successCount} fetched, ${result.failureCount} failed")
                        navigateToLockList() // Can still use successful ACLs
                    }
                    result.isFailure -> {
                        showError("Failed to fetch ACLs")
                    }
                    result.isEmpty -> {
                        showInfo("No locks assigned to this user")
                    }
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Bulk ACL fetch failed", error)
                handleAclFetchError(error)
            }
        )
    }

    private fun handleProvisioningError(error: Throwable) {
        when (error) {
            is NokeMobileLibraryError.NetworkError -> {
                showError("Network error. Please check your connection.")
            }
            is NokeMobileLibraryError.ProvisioningFailed -> {
                showError("Provisioning failed: ${error.reason}")
            }
            is NokeMobileLibraryError.CryptographicError -> {
                showError("Device security error. Please contact support.")
            }
            else -> {
                showError("Unexpected error: ${error.message}")
            }
        }
    }

    private fun handleAclFetchError(error: Throwable) {
        when (error) {
            is NokeMobileLibraryError.NetworkError -> {
                showError("Network error fetching ACLs")
            }
            is NokeMobileLibraryError.BulkAclFetchFailed -> {
                showError("ACL fetch failed: ${error.reason}")
            }
            else -> {
                showError("Error: ${error.message}")
            }
        }
    }
}
```

### Core APIs

#### PhoneKeyAccessService

```kotlin
// Initialization (call once in Application.onCreate())
PhoneKeyAccessService.initialize(
    context: Context,
    baseUrl: String,
    authTokenProvider: () -> String,
    userUuidProvider: () -> String
)

// Get singleton instance
val service = PhoneKeyAccessService.getInstance()

// Provisioning
suspend fun provisionPhoneKey(userId: String, udid: String): Result<Int>

// ACL Operations
suspend fun generateBulkAcls(phoneKeyId: Int, userId: String, udid: String): Result<BulkAclResult>
suspend fun generateAcl(userId: Int, lockMac: String, phoneKeyId: Int, udid: String): Result<Unit>
suspend fun refreshAllAcls(userId: String, udid: String): Result<BulkAclResult>

// Status Queries
suspend fun isProvisioned(userId: String, udid: String): Result<Boolean>
suspend fun getPhoneKeyId(userId: String, udid: String): Result<String>

// Cache Management
fun clearCache()
```

---

## Error Handling

### Error Types

All errors are instances of `NokeMobileLibraryError` sealed class:

```kotlin
import com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError

when (error) {
    is NokeMobileLibraryError.NotInitialized ->
        // Library not initialized - call initialize() first

    is NokeMobileLibraryError.InvalidInput ->
        // Invalid parameters: error.reason

    is NokeMobileLibraryError.NetworkError ->
        // Network issue: error.operation

    is NokeMobileLibraryError.ProvisioningFailed ->
        // Provisioning error: error.reason

    is NokeMobileLibraryError.AclFetchFailed ->
        // Single ACL fetch failed: error.lockMac, error.reason

    is NokeMobileLibraryError.BulkAclFetchFailed ->
        // Bulk ACL fetch failed: error.phoneKeyId, error.reason

    is NokeMobileLibraryError.StorageError ->
        // Local storage error: error.operation

    is NokeMobileLibraryError.CryptographicError ->
        // Crypto operation failed: error.operation

    is NokeMobileLibraryError.NotProvisioned ->
        // User/device not provisioned: error.userId

    else ->
        // Unknown error: error.message
}
```

### Error Handling Patterns

#### Pattern 1: fold() for Inline Handling

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

val service = PhoneKeyAccessService.getInstance()

service.provisionPhoneKey(userId, udid).fold(
    onSuccess = { phoneKeyId ->
        // Handle success
    },
    onFailure = { error ->
        // Handle error
    }
)
```

#### Pattern 2: when() for Comprehensive Error Handling

```kotlin
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError

val service = PhoneKeyAccessService.getInstance()
val result = service.generateBulkAcls(phoneKeyId, userId, udid)

result.fold(
    onSuccess = { bulkResult ->
        Log.d(TAG, "Success: ${bulkResult.successCount} ACLs")
    },
    onFailure = { error ->
        when (error) {
            is NokeMobileLibraryError.NetworkError -> retryWithBackoff()
            is NokeMobileLibraryError.BulkAclFetchFailed -> showUserFriendlyError()
            else -> logAndReport(error)
        }
    }
)
```

#### Pattern 3: getOrNull() for Optional Handling

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

val service = PhoneKeyAccessService.getInstance()
val phoneKeyId = service.getPhoneKeyId(userId, udid).getOrNull()
if (phoneKeyId != null) {
    // Use phoneKeyId
} else {
    // Handle missing/error case
}
```

---

## Best Practices

### 1. Initialize Once, Use Everywhere

```kotlin
import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

// ✅ GOOD: Initialize in Application.onCreate()
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneKeyAccessService.initialize(...)
    }
}

// ❌ BAD: Don't initialize in activities
class MyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        PhoneKeyAccessService.initialize(...) // ❌ Wrong!
    }
}
```

### 2. Use Bulk ACL Fetch

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

val service = PhoneKeyAccessService.getInstance()

// ✅ GOOD: Bulk fetch (one network call)
service.generateBulkAcls(phoneKeyId, userId, deviceId)

// ❌ BAD: Multiple individual fetches
lockMacs.forEach { lockMac ->
    service.generateAcl(userId.toInt(), lockMac, phoneKeyId, deviceId) // ❌ Inefficient!
}
```

### 3. Handle Partial Success in Bulk Operations

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

val service = PhoneKeyAccessService.getInstance()

service.generateBulkAcls(phoneKeyId, userId, deviceId).fold(
    onSuccess = { result ->
        if (result.hasPartialSuccess) {
            // Some ACLs failed - inform user
            showWarning("${result.failureCount} locks unavailable")
        }
        // Continue with successful ACLs
        proceedWithAvailableLocks(result.successCount)
    },
    onFailure = { error -> /* Handle error */ }
)
```

### 4. Refresh ACLs Periodically

```kotlin
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.launch

// Refresh ACLs on app foreground or pull-to-refresh
lifecycleScope.launch {
    service.refreshAllAcls(userId, deviceId).fold(
        onSuccess = { result ->
            Log.d(TAG, "ACLs refreshed: ${result.successCount}")
        },
        onFailure = { error ->
            // Fail silently or show non-intrusive notification
            Log.w(TAG, "ACL refresh failed (non-critical)")
        }
    )
}
```

### 5. Clear Cache on Logout

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

fun logout() {
    // 1. Clear phone key cache
    PhoneKeyAccessService.getInstance().clearCache()

    // 2. Clear your app's auth data
    sessionManager.clear()

    // 3. Navigate to login
    navigateToLogin()
}
```

### 6. Handle Network Errors Gracefully

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError
import kotlinx.coroutines.delay

val service = PhoneKeyAccessService.getInstance()

suspend fun provisionWithRetry(maxRetries: Int = 3) {
    repeat(maxRetries) { attempt ->
        service.provisionPhoneKey(userId, deviceId).fold(
            onSuccess = { phoneKeyId ->
                return // Success, exit retry loop
            },
            onFailure = { error ->
                when (error) {
                    is NokeMobileLibraryError.NetworkError -> {
                        if (attempt < maxRetries - 1) {
                            delay(2000 * (attempt + 1)) // Exponential backoff
                            // Retry
                        } else {
                            showError("Network error. Please try again later.")
                        }
                    }
                    else -> {
                        // Non-retryable error
                        showError(error.message ?: "Provisioning failed")
                        return
                    }
                }
            }
        )
    }
}
```

---

## Troubleshooting

### Common Issues

#### Issue: "Library not initialized" error

**Solution:**

```kotlin
// Ensure PhoneKeyAccessService.initialize() is called in Application.onCreate()
// BEFORE using getInstance()
```

#### Issue: "User not authenticated" exception in auth token provider

**Solution:**

```kotlin
// Ensure user is logged in before calling phone key APIs
if (!sessionManager.isLoggedIn()) {
    // Don't call phone key APIs yet
    return
}
```

#### Issue: Network timeout errors

**Solution:**

```kotlin
// SecurityService has built-in retry logic
// If timeouts persist, check:
// 1. Network connectivity
// 2. Backend URL is correct
// 3. Firewall rules allow HTTPS to backend
```

#### Issue: Provisioning succeeds but ACL fetch fails

**Solution:**

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.delay

val service = PhoneKeyAccessService.getInstance()

// Add small delay after provisioning to allow backend propagation
service.provisionPhoneKey(userId, deviceId).fold(
    onSuccess = { phoneKeyId ->
        delay(1000) // Wait for backend
        service.generateBulkAcls(phoneKeyId, userId, deviceId)
    },
    onFailure = { /* */ }
)
```

#### Issue: ACLs not cached after fetch

**Solution:**

```kotlin
// Check Result<T> status - only successful fetches are cached
// Verify no storage errors in logs
```

---

## Testing

### Unit Testing with MockK

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Test
fun `test phone key provisioning success`() = runTest {
    // Given
    val mockService = mockk<PhoneKeyAccessService>()
    coEvery { mockService.provisionPhoneKey(any(), any()) } returns Result.success(42)

    // When
    val result = mockService.provisionPhoneKey("user123", "device456")

    // Then
    assertTrue(result.isSuccess)
    assertEquals(42, result.getOrNull())
}
```

### Integration Testing

```kotlin
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PhoneKeyIntegrationTest {

    @Test
    fun fullProvisioningFlow() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize
        PhoneKeyAccessService.initialize(
            context = context,
            baseUrl = "https://sandbox-router.smartentry.noke.com/",
            authTokenProvider = { "test_token" },
            userUuidProvider = { "test_user_uuid" }
        )

        val service = PhoneKeyAccessService.getInstance()

        // Provision
        val provisionResult = service.provisionPhoneKey("test_user", "test_device")
        assertTrue(provisionResult.isSuccess)

        // Fetch ACLs
        val keyId = provisionResult.getOrNull()!!
        val aclResult = service.generateBulkAcls(keyId, "test_user", "test_device")
        assertTrue(aclResult.isSuccess)
    }
}
```

---

## Support

For questions or issues:

- GitHub Issues: [noke-inc/noke-mobile-library-android](https://github.com/noke-inc/noke-mobile-library-android)
- Email: support@noke.com
