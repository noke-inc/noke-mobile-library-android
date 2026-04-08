# Phone Key Integration Guide - Noke Mobile Library for Android

**Complete integration guide for third-party developers using Phone Key features in Noke Mobile Library.**

**🚀 Production-Ready:** This guide uses **PhoneKeyAccessService**, the modern coroutine-based facade for ION-2 phone key operations.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation](#installation)
3. [Custom PhoneKeyCoreClient Implementation](#custom-phonekeycoreclient-implementation)
4. [Initialization](#initialization)
5. [Phone Key Lifecycle](#phone-key-lifecycle)
6. [ACL Management](#acl-management)
7. [Error Handling](#error-handling)
8. [Threading & Concurrency](#threading--concurrency)
9. [Best Practices](#best-practices)
10. [Common Pitfalls](#common-pitfalls)
11. [Testing](#testing)

---

## Prerequisites

### Required Knowledge

- Basic Android development experience
- Understanding of Activity/Application lifecycle
- Familiarity with **Kotlin coroutines** (suspend functions, launch, async)
- Basic cryptography concepts (public/private keys)

### System Requirements

- **minSdk 21** (Android 5.0+)
- **targetSdk 34** (Android 14)
- **Java 17** or higher
- Android Keystore support (on all devices since API 21)

### Backend Requirements

- Access to Noke Phone ACL backend endpoints
- Valid authentication mechanism
- User account with ION-2 lock access

---

## Installation

### Step 1: Add Library Dependency

Add Noke Mobile Library to your app's `build.gradle`:

```gradle
dependencies {
    // Noke Mobile Library (ION-2 phone key support)
    implementation project(':nokemobilelibrary')
    // OR from Maven/JCenter:
    // implementation "com.noke:nokemobilelibrary:x.x.x"

    // Required dependencies
    implementation "com.jakewharton.threetenabp:threetenabp:1.4.6"

    // Kotlin coroutines (if not already included)
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
}
```

### Step 2: Configure ProGuard (if using minification)

Add this to your `proguard-rules.pro`:

```proguard
# Keep PhoneKeyAccessService public API
-keep class com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService {
    public *;
}

# Keep PhoneKeyCoreClient interface (for custom implementations)
-keep interface com.noke.nokemobilelibrary.phonekey.PhoneKeyCoreClient { *; }

# Keep PhoneKeyManager (underlying implementation)
-keep class com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager {
    public *;
}

# Keep model classes
-keep class com.noke.nokemobilelibrary.phonekey.models.** { *; }

# Keep Android Keystore classes
-keep class android.security.keystore.** { *; }
```

### Step 3: Initialize ThreeTenABP

In your `Application` class:

```kotlin
import com.jakewharton.threetenabp.AndroidThreeTen

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this) // Required for Instant date/time handling
    }
}
```

---

## Custom PhoneKeyCoreClient Implementation

**IMPORTANT:** As a third-party developer, you **MUST** provide your own `PhoneKeyCoreClient` implementation integrated with your networking layer and authentication system.

### Why Custom Implementation?

NokeMobileLibrary is a standalone SDK that doesn't include networking or authentication. You need to integrate with your:

- Network layer (Retrofit, OkHttp, Ktor, etc.)
- Authentication system (token management)
- Backend API endpoints

### Implementation Template

See `TEMPLATE_PhoneKeyCoreClient.kt` in the root directory for a complete reference implementation. Here's a minimal example:

```kotlin
package com.example.myapp

import android.content.Context
import com.noke.nokemobilelibrary.phonekey.PhoneKeyCoreClient
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Custom implementation of PhoneKeyCoreClient integrated with your app's
 * networking layer and authentication system.
 */
class MyPhoneKeyCoreClient(
    private val context: Context,
    private val myApiClient: MyApiClient // Your network client
) : PhoneKeyCoreClient {

    companion object {
        private const val TAG = "MyPhoneKeyCoreClient"
    }

    // Single-threaded dispatcher for thread safety
    private val serialDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Mutex for protecting shared state
    private val mutex = Mutex()

    // Cache for PhoneKeyManager instances (keyed by userId_deviceId)
    private val managerCache = mutableMapOf<String, PhoneKeyManager>()

    // Track initialization state per (userId, deviceId)
    private val initializationState = mutableMapOf<String, Boolean>()

    override suspend fun initialize(userId: String, deviceId: String): Result<String> =
        withContext(serialDispatcher) {
            mutex.withLock {
                val cacheKey = "${userId}_$deviceId"

                // Return immediately if already initialized
                if (initializationState[cacheKey] == true) {
                    val manager = getOrCreateManager(userId, deviceId)
                    val publicKey = manager.getPublicKeyBase64()
                    return@withContext Result.success(publicKey)
                }

                try {
                    val manager = getOrCreateManager(userId, deviceId)
                    manager.ensureKeys()

                    val publicKey = manager.getPublicKeyBase64()
                    initializationState[cacheKey] = true

                    Result.success(publicKey)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    override val isInitialized: Boolean
        get() = initializationState.values.any { it }

    override suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse> = withContext(serialDispatcher) {
        try {
            // Call YOUR backend API to provision the phone key
            val response = myApiClient.provisionPhoneKey(
                userId = userId,
                deviceId = deviceId,
                publicKey = publicKey
            )

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int
    ): Result<Unit> = withContext(serialDispatcher) {
        try {
            // Call YOUR backend API to generate ACL
            myApiClient.generateAcl(
                userId = userId,
                lockMac = lockMac,
                phoneKeyId = phoneKeyId
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        deviceId: String
    ): Result<BulkAclResult> = withContext(serialDispatcher) {
        mutex.withLock {
            try {
                // IMPORTANT: Use these parameters to get correct manager instance
                val manager = getOrCreateManager(userId, deviceId)

                // Call YOUR backend API to fetch bulk ACLs
                val acls = myApiClient.fetchBulkAcls(phoneKeyId)

                // Store ACLs using manager
                var successCount = 0
                var failureCount = 0

                acls.forEach { acl ->
                    try {
                        manager.storeBulkAclEnvelope(acl)
                        successCount++
                    } catch (e: Exception) {
                        failureCount++
                    }
                }

                Result.success(
                    BulkAclResult(
                        totalCount = acls.size,
                        successCount = successCount,
                        failureCount = failureCount
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun validateCurrentKey(userId: String, deviceId: String): String? {
        return try {
            val manager = getOrCreateManager(userId, deviceId)
            manager.getPublicKeyBase64()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get or create PhoneKeyManager instance for the given user and device.
     * Uses cache to ensure singleton behavior per (userId, deviceId) pair.
     */
    private fun getOrCreateManager(userId: String, deviceId: String): PhoneKeyManager {
        val cacheKey = "${userId}_$deviceId"
        return managerCache.getOrPut(cacheKey) {
            PhoneKeyManager.getInstance(
                context = context.applicationContext,
                userId = userId,
                deviceId = deviceId
            )
        }
    }
}
```

### Key Implementation Points

1. **Thread Safety:** Use `Mutex` or single-threaded dispatcher
2. **Manager Cache:** Cache `PhoneKeyManager` instances by `(userId, deviceId)`
3. **Parameter Usage:** Always use provided `userId`/`deviceId` in `generateBulkAcls()`
4. **Error Handling:** Wrap your network calls in try-catch and return `Result`
5. **Context:** Use application context, not Activity context

---

## Initialization

### Application-Level Setup

**PhoneKeyAccessService** is the production-ready facade for ION-2 phone key operations. Initialize it with your custom `PhoneKeyCoreClient` in your `Application.onCreate()`:

```kotlin
package com.example.myapp

import android.app.Application
import android.util.Log
import com.jakewharton.threetenabp.AndroidThreeTen
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MyApplication : Application() {

    // Application-scoped coroutine scope for ION-2 operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize ThreeTenABP for date/time handling
        AndroidThreeTen.init(this)

        // Create your custom PhoneKeyCoreClient
        val myApiClient = MyApiClient(this) // Your network client
        val phoneKeyCoreClient = MyPhoneKeyCoreClient(this, myApiClient)

        // Initialize PhoneKeyAccessService with your client
        PhoneKeyAccessService.setSharedClient(phoneKeyCoreClient, this)

        Log.d("MyApp", "PhoneKeyAccessService initialized with custom client")
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }

    /**
     * Get current user ID from your authentication system
     */
    fun getCurrentUserId(): String {
        // Replace with your actual auth logic
        return "" // Return empty if not logged in
    }

    /**
     * Get the application coroutine scope for ION-2 operations
     */
    fun getAppScope(): CoroutineScope = applicationScope
}
```

### Accessing PhoneKeyAccessService

Access the service anywhere in your app:

```kotlin
val service = PhoneKeyAccessService.getInstance()
```

**Important:** Always call operations from a coroutine context (they are suspend functions).

### Per-Device Isolation

PhoneKeyAccessService automatically handles device isolation using the device's `ANDROID_ID`. Each combination of `(userId, deviceId)` gets:

- **Unique ECDSA key pair** in Android Keystore
- **Separate encrypted storage file** for ACLs
- **Independent master encryption key**

This architecture allows:

- ✅ Multiple devices per user (each device provisioned separately)
- ✅ Multiple users per device (separate storage per user)
- ✅ Granular device revocation by backend

```kotlin
// Device ID is automatically managed by PhoneKeyAccessService
// Use getDeviceUdid() to retrieve it when needed for backend calls
val deviceId = PhoneKeyAccessService.getDeviceUdid(context)
```

---

## Recent Changes

### April 2026: PhoneKeyCoreClient.generateBulkAcls() Parameter Enhancement

**📝 API Update**: The `generateBulkAcls()` method signature has been enhanced to fix a critical bug where ACLs were stored with the wrong user ID after user switches.

#### What Changed

**Previous Signature:**

```kotlin
suspend fun generateBulkAcls(phoneKeyId: Int): Result<BulkAclResult>
```

**Current Signature:**

```kotlin
suspend fun generateBulkAcls(
    phoneKeyId: Int,
    userId: String,      // NEW: Required parameter
    deviceId: String     // NEW: Required parameter
): Result<BulkAclResult>
```

#### Why This Change Was Made

**The Bug:** When a user logged out and a different user logged in, bulk ACL generation would sometimes use the previous user's manager instance, causing ACLs to be stored with the wrong user ID. This resulted in "No valid ACL for lock" errors when trying to unlock.

**The Fix:** By requiring explicit `userId` and `deviceId` parameters, the method now always uses the correct manager instance for the current user.

#### Implementation Guide

When implementing `PhoneKeyCoreClient.generateBulkAcls()`, **you MUST use these parameters** to get the correct manager instance:

```kotlin
class MyPhoneKeyCoreClient : PhoneKeyCoreClient {
    override suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,      // Use this parameter!
        deviceId: String     // Use this parameter!
    ): Result<BulkAclResult> {
        // CRITICAL: Use userId and deviceId to get correct manager
        val manager = getOrCreateManager(userId, deviceId)

        // Fetch ACLs from YOUR backend
        val acls = myApiClient.fetchBulkAcls(phoneKeyId)

        // Store using the correct manager instance
        acls.forEach { acl ->
            manager.storeBulkAclEnvelope(acl)
        }

        return Result.success(...)
    }

    // REQUIRED: Cache managers by (userId, deviceId) combination
    private fun getOrCreateManager(userId: String, deviceId: String): PhoneKeyManager {
        val cacheKey = "${userId}_$deviceId"
        return managerCache.getOrPut(cacheKey) {
            PhoneKeyManager.getInstance(context, userId, deviceId)
        }
    }
}
```

#### Using PhoneKeyAccessService

If you're using `PhoneKeyAccessService`, no special handling is needed. The service passes these parameters to your client automatically:

```kotlin
// Service handles parameter forwarding internally
val result = service.generateBulkAcls(phoneKeyId, userId, udid)
```

---

## Phone Key Lifecycle

### State Machine

```
┌─────────────────┐
│   NOT_CREATED   │ (Service not initialized)
└────────┬────────┘
         │ PhoneKeyAccessService.setSharedClient()
         ▼
┌─────────────────┐
│  NEEDS_PROV     │ (Service ready, not provisioned)
└────────┬────────┘
         │ provisionPhoneKey()
         ▼
┌─────────────────┐
│  PROVISIONED    │ (Backend registered, no ACLs yet)
└────────┬────────┘
         │ generateBulkAcls()
         ▼
┌─────────────────┐
│     READY       │ (Has valid ACLs, can unlock)
└─────────────────┘
```

### Step 1: Check Provisioning State

Before provisioning, check if the device is already provisioned:

```kotlin
import kotlinx.coroutines.launch
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

class MainActivity : AppCompatActivity() {

    private val service = PhoneKeyAccessService.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check provisioning state in coroutine
        lifecycleScope.launch {
            checkProvisioningState()
        }
    }

    private suspend fun checkProvisioningState() {
        val userId = (application as MyApplication).getCurrentUserId()
        if (userId.isEmpty()) {
            Log.d("App", "User not logged in")
            return
        }

        val udid = PhoneKeyAccessService.getDeviceUdid(this)

        val result = service.isProvisioned(userId, udid)
        result.fold(
            onSuccess = { isProvisioned ->
                if (isProvisioned) {
                    Log.d("App", "Device already provisioned")
                    // Skip to ACL fetch
                    fetchAllAcls()
                } else {
                    Log.d("App", "Not provisioned - starting provisioning flow")
                    startProvisioning()
                }
            },
            onFailure = { error ->
                Log.e("App", "Failed to check provisioning state: ${error.message}")
            }
        )
    }
}
```

### Step 2: Provisioning (One-Time Setup)

Provisioning registers your device's public key with the backend. This is a one-time operation per device.

```kotlin
private suspend fun startProvisioning() {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyAccessService.getDeviceUdid(this)

    Log.d("App", "Starting provisioning")

    // Call provisioning with suspend function
    val result = service.provisionPhoneKey(
        userId = userId,
        udid = udid
    )

    result.fold(
        onSuccess = { phoneKeyId ->
            Log.d("App", "✅ Provisioning successful: phoneKeyId=$phoneKeyId")

            // Immediately fetch ACLs after successful provisioning
            fetchAllAcls()
        },
        onFailure = { error ->
            Log.e("App", "❌ Provisioning failed: ${error.message}")
            // Handle error appropriately
        }
    )
}
```

**Important Notes:**

- ✅ Provisioning is **idempotent** - safe to call multiple times
- ✅ Backend will return existing `phoneKeyId` if already provisioned
- ✅ Private key **never leaves** Android Keystore
- ⚠️ Requires valid **authentication** in your custom client

---

## ACL Management

ACLs (Access Control Lists) contain cryptographic tokens that authorize your phone to unlock specific locks.

### ⚠️ CRITICAL: Manager Cache Initialization

**Before calling any ACL operations**, you **MUST first call `initialize(userId, deviceId)`** to populate the manager cache in your PhoneKeyCoreClient.

**Why this is required:**

- Your PhoneKeyCoreClient uses an internal manager cache keyed by `(userId, deviceId)`
- ACL operations need a cached manager instance to store/retrieve ACLs
- If `initialize()` wasn't called, the cache is empty → **`NotInitialized` error**

**Correct pattern:**

```kotlin
// ✅ CORRECT: Initialize FIRST, then fetch ACLs
// Step 1: Initialize (populates manager cache)
val initResult = service.initialize(userId, udid)
initResult.getOrElse {
    Log.e("App", "Initialization failed: ${it.message}")
    return
}

// Step 2: Now safe to fetch ACLs (manager is in cache)
val result = service.generateBulkAcls(phoneKeyId, userId, udid)
```

### Bulk ACL Fetch (Recommended)

Always use bulk fetch for initial ACL loading and periodic refreshes:

```kotlin
private suspend fun fetchAllAcls() {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyAccessService.getDeviceUdid(this)

    // STEP 1: CRITICAL - Initialize manager cache
    val initResult = service.initialize(userId, udid)
    initResult.getOrElse {
        Log.e("App", "Failed to initialize: ${it.message}")
        return
    }
    Log.d("App", "PhoneKeyCoreClient initialized")

    // STEP 2: Get phone key ID
    val phoneKeyIdResult = service.getPhoneKeyId(userId, udid)
    val phoneKeyId = phoneKeyIdResult.getOrElse {
        Log.e("App", "Invalid phone key ID")
        return
    }

    Log.d("App", "Fetching bulk ACLs")

    // STEP 3: Fetch ACLs (manager now in cache)
    val result = service.generateBulkAcls(
        phoneKeyId = phoneKeyId.toInt(),
        userId = userId,
        udid = udid
    )

    result.fold(
        onSuccess = { bulkResult ->
            Log.d("App", "ACL fetch complete: ${bulkResult.successCount}/${bulkResult.totalCount} stored")

            when {
                bulkResult.isFullSuccess -> {
                    Log.d("App", "✅ All ACLs ready")
                }
                bulkResult.hasPartialSuccess -> {
                    Log.w("App", "⚠️ Partial success")
                }
                bulkResult.isEmpty -> {
                    Log.i("App", "ℹ️ No locks assigned")
                }
            }
        },
        onFailure = { error ->
            Log.e("App", "❌ Failed to fetch ACLs: ${error.message}")
        }
    )
}
```

---

## Error Handling

### Result-Based Error Handling

PhoneKeyAccessService uses `Result<T>` for exhaustive error handling:

```kotlin
val result = service.provisionPhoneKey(userId, udid)

result.fold(
    onSuccess = { phoneKeyId ->
        Log.d("App", "Success: $phoneKeyId")
    },
    onFailure = { error ->
        Log.e("App", "Error: ${error.message}")
        // Handle error appropriately
    }
)
```

---

## Threading & Concurrency

### Coroutine-Based API

PhoneKeyAccessService uses **suspend functions** that must be called from a coroutine context:

```kotlin
class MainActivity : AppCompatActivity() {

    private val service = PhoneKeyAccessService.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use lifecycleScope for Activity-bound operations
        lifecycleScope.launch {
            val result = service.provisionPhoneKey(userId, udid)
            // Handle result
        }
    }
}
```

### Thread Safety

- ✅ `PhoneKeyAccessService` is **thread-safe** (uses mutex internally)
- ✅ Multiple concurrent suspend function calls are safe
- ✅ PhoneKeyManager uses `@Synchronized` for thread safety
- ⚠️ No need for additional synchronization in your code

### Per-User Storage Isolation

PhoneKeyAccessService ensures **complete isolation** between different users and devices. Each combination of `(userId, deviceId)` gets its own:

- **Unique ECDSA key pair** in Android Keystore
- **Separate encrypted storage file** for ACLs
- **Independent PhoneKeyManager instance** cached by the service

**The ION-2 Fix (April 2026):**

The `generateBulkAcls()` method now requires explicit `userId` and `deviceId` parameters to always get the correct manager instance:

```kotlin
// Always uses correct manager for the user
suspend fun generateBulkAcls(
    phoneKeyId: Int,
    userId: String,      // Ensures correct user isolation
    deviceId: String     // Ensures correct device isolation
): Result<BulkAclResult>
```

**Best Practices for User Switching:**

```kotlin
// On user logout - clean up the old user's data
val service = PhoneKeyAccessService.getInstance()
val udid = PhoneKeyAccessService.getDeviceUdid(context)

// Clean up ACLs for old user
service.cleanupAclsForUser(oldUserId, udid)

// Clear the manager instance cache
service.clearManagerInstance(oldUserId, udid)

// On user login - fresh state for new user
service.initialize(newUserId, udid)
service.provisionPhoneKey(newUserId, udid)
service.generateBulkAcls(phoneKeyId, newUserId, udid)
```

---

## Best Practices

### 1. Initialization Pattern

```kotlin
// ✅ GOOD: Initialize once in Application.onCreate()
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this)

        val client = MyPhoneKeyCoreClient(this, myApiClient)
        PhoneKeyAccessService.setSharedClient(client, this)
    }
}
```

### 2. Logout Handling

```kotlin
// ✅ GOOD: Clean up ACLs and manager instance on logout
class MyApplication : Application() {
    fun onUserLogout() {
        val userId = getCurrentUserId()
        val udid = PhoneKeyAccessService.getDeviceUdid(this)

        applicationScope.launch {
            val service = PhoneKeyAccessService.getInstance()

            // Step 1: Clean up ACLs
            service.cleanupAclsForUser(userId, udid)

            // Step 2: Clear manager instance
            service.clearManagerInstance(userId, udid)
        }
    }
}
```

**Why Both Steps Are Important:**

1. **`cleanupAclsForUser()`** - Removes ACL data for security
2. **`clearManagerInstance()`** - Frees manager resources

---

## Common Pitfalls

### 1. ❌ Not Implementing PhoneKeyCoreClient

```kotlin
// ERROR: Must provide custom PhoneKeyCoreClient implementation

// FIX: Implement PhoneKeyCoreClient with your networking layer
class MyPhoneKeyCoreClient(
    private val context: Context,
    private val apiClient: MyApiClient
) : PhoneKeyCoreClient {
    // Implement all interface methods
}
```

### 2. ❌ Forgetting to Initialize ThreeTenABP

```kotlin
// ERROR: ExceptionInInitializerError

// FIX: Add to Application.onCreate()
AndroidThreeTen.init(this)
```

### 3. ❌ Not Calling initialize() Before ACL Operations

```kotlin
// ERROR: NotInitialized error

// FIX: Always initialize first
service.initialize(userId, udid)
service.generateBulkAcls(phoneKeyId, userId, udid)
```

### 4. ❌ Not Using userId/deviceId Parameters in generateBulkAcls()

```kotlin
// ERROR: ACLs stored with wrong user after user switching

// ❌ BAD: Using wrong manager or cached instance
override suspend fun generateBulkAcls(...): Result<BulkAclResult> {
    val manager = managerCache.values.firstOrNull() // WRONG!
}

// ✅ GOOD: Always use the provided parameters
override suspend fun generateBulkAcls(
    phoneKeyId: Int,
    userId: String,
    deviceId: String
): Result<BulkAclResult> {
    val manager = getOrCreateManager(userId, deviceId) // CORRECT!
}
```

---

## Testing

### Unit Testing with Coroutines

```kotlin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhoneKeyAccessServiceTest {

    @Test
    fun testProvisioningFlow() = runTest {
        val service = PhoneKeyAccessService.getInstance()

        val result = service.provisionPhoneKey(
            userId = "test_user",
            udid = "test_device"
        )

        assertTrue(result.isSuccess)
    }
}
```

### Integration Testing

```kotlin
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndToEndFlowTest {

    @Test
    fun testCompleteProvisioningAndAclFlow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Set up custom client
        val client = MyPhoneKeyCoreClient(context, mockApiClient)
        PhoneKeyAccessService.setSharedClient(client, context)

        val service = PhoneKeyAccessService.getInstance()
        val userId = "test_user"
        val udid = "test_device"

        // Step 1: Provision
        val provisionResult = service.provisionPhoneKey(userId, udid)
        assertTrue(provisionResult.isSuccess)

        // Step 2: Initialize
        val initResult = service.initialize(userId, udid)
        assertTrue(initResult.isSuccess)

        // Step 3: Fetch ACLs
        val phoneKeyId = provisionResult.getOrThrow()
        val aclResult = service.generateBulkAcls(phoneKeyId, userId, udid)
        assertTrue(aclResult.isSuccess)
    }
}
```

---

**Version:** 2.0.0 (Third-Party SDK)  
**Last Updated:** April 7, 2026  
**Maintainer:** Noke Engineering

---

**Need help?** Check logcat for detailed error messages with `ION-2` prefix.

**Reference Implementation:** See `TEMPLATE_PhoneKeyCoreClient.kt` for a complete example of custom client integration.
