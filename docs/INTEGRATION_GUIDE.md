# Phone Key Integration Guide - Noke Mobile Library for Android

**Complete integration guide for third-party developers using Phone Key features in Noke Mobile Library.**

**🚀 Production-Ready:** This guide uses **PhoneKeyAccessService**, the modern coroutine-based facade for ION-2 phone key operations.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation](#installation)
3. [Initialization](#initialization)
4. [Phone Key Lifecycle](#phone-key-lifecycle)
5. [ACL Management](#acl-management)
6. [Error Handling](#error-handling)
7. [Threading & Concurrency](#threading--concurrency)
8. [Best Practices](#best-practices)
9. [Common Pitfalls](#common-pitfalls)
10. [Testing](#testing)

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

- Access to NSE Phone ACL backend endpoints
- Valid authentication token (JWT)
- User account with ION-2 lock access

---

## Installation

### Step 1: Add Module Dependency

Add SmartEntryCore to your app's `build.gradle`:

```gradle
dependencies {
    // SmartEntryCore module (ION-2 phone key support)
    implementation project(':smartentrycore')

    // Required dependencies
    implementation "com.jakewharton.threetenabp:threetenabp:1.4.6"

    // Kotlin coroutines (if not already included)
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
}
```

### Step 2: Configure ProGuard (if using minification)

Add this to your `proguard-rules.pro`:

```proguard
# Keep PhoneKeyAccessService public API (modern facade)
-keep class com.noke.smartentrycore.phonekey.PhoneKeyAccessService {
    public *;
}

# Keep PhoneKeyManager (underlying implementation)
-keep class com.noke.smartentrycore.controllers.PhoneKeyManager {
    public *;
}

# Keep SecurityService interfaces
-keep interface com.noke.smartentrycore.controllers.SecurityService$** { *; }

# Keep model classes
-keep class com.noke.smartentrycore.models.** { *; }
-keep class com.noke.smartentrycore.phonekey.** { *; }

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

## Initialization

### Application-Level Setup

**PhoneKeyAccessService** is the production-ready facade for ION-2 phone key operations. It must be initialized once in your `Application.onCreate()` method before any usage.

**Why PhoneKeyAccessService?**

- ✅ Modern coroutine-based API (suspend functions)
- ✅ Result-based error handling (`Result<T>`)
- ✅ Thread-safe singleton pattern
- ✅ Simplified API - no manual manager lifecycle
- ✅ Production-tested in Storage Smart Entry app

```kotlin
package com.example.myapp

import android.app.Application
import android.util.Log
import com.jakewharton.threetenabp.AndroidThreeTen
import com.noke.smartentrycore.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MyApplication : Application() {

    // Application-scoped coroutine scope for ION-2 operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // PhoneKeyAccessService singleton - access via getInstance()
    private lateinit var phoneKeyAccessService: PhoneKeyAccessService

    companion object {
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize ThreeTenABP for date/time handling
        AndroidThreeTen.init(this)

        // Initialize PhoneKeyAccessService (REQUIRED before any usage)
        PhoneKeyAccessService.initialize(this)
        phoneKeyAccessService = PhoneKeyAccessService.getInstance()

        Log.d("MyApp", "PhoneKeyAccessService initialized")
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up coroutine scope
        applicationScope.cancel()
    }

    /**
     * Lifecycle observer - matches iOS applicationDidBecomeActive pattern.
     * Checks provisioning on EVERY resume, not just after backgrounding.
     * This ensures ACLs are refreshed if permissions changed on server.
     */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                appWasBackgrounded = true
                // Optional: Stop BLE scanning in background
            }
            Lifecycle.Event.ON_RESUME -> {
                // ION-2: Always check provisioning (matches iOS behavior)
                checkProvisioningAndRefreshACLs()
                appWasBackgrounded = false
            }
            else -> {}
        }
    }

    /**
     * Check if phone key is provisioned and refresh ACLs if needed.
     * Called on EVERY app resume (matches iOS applicationDidBecomeActive).
     */
    private fun checkProvisioningAndRefreshACLs() {
        val userId = getCurrentUserId()
        if (userId.isEmpty()) return

        applicationScope.launch {
            val udid = PhoneKeyAccessService.getDeviceUdid(this@MyApplication)

            // Check provisioning status
            val provisionedResult = phoneKeyAccessService.isProvisioned(userId, udid)
            provisionedResult.fold(
                onSuccess = { isProvisioned ->
                    if (isProvisioned) {
                        // Fetch ACLs (service checks cache internally)
                        refreshACLsIfNeeded(userId, udid)
                    } else {
                        // Not provisioned - trigger provisioning flow
                        startProvisioning(userId, udid)
                    }
                },
                onFailure = { error ->
                    Log.e("MyApp", "Failed to check provisioning: ${error.message}")
                }
            )
        }
    }

    /**
     * Get current user ID from your authentication system
     * This should return the user's unique identifier (UUID, email, etc.)
     */
    fun getCurrentUserId(): String {
        // Replace with your actual auth logic
        // Example: SharedPreferencesHelper(this).userUUID
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
// No need to manually handle it in most cases

// Get device ID programmatically (if needed for backend registration)
val deviceId = com.noke.smartentrycore.controllers.PhoneKeyManager.getDeviceUdid(context)
Log.d("App", "Device ID retrieved for provisioning")
```

---

## Phone Key Lifecycle

### State Machine

```
┌─────────────────┐
│   NOT_CREATED   │ (Service not initialized)
└────────┬────────┘
         │ PhoneKeyAccessService.initialize()
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
import com.noke.smartentrycore.phonekey.PhoneKeyAccessService
import com.noke.smartentrycore.controllers.PhoneKeyManager

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

        val udid = PhoneKeyManager.getDeviceUdid(this)

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
    val udid = PhoneKeyManager.getDeviceUdid(this)

    Log.d("App", "Starting provisioning") // ⚠️ Never log userId or udid

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

            // Handle specific error types
            when (error) {
                is com.noke.smartentrycore.phonekey.NokeMobileLibraryError.NetworkError -> {
                    showError("Network error - please try again")
                }
                is com.noke.smartentrycore.phonekey.NokeMobileLibraryError.CryptographicError -> {
                    showError("Device not supported")
                }
                else -> {
                    showError("Provisioning failed: ${error.message}")
                }
            }
        }
    )
}
```

**Important Notes:**

- ✅ Provisioning is **idempotent** - safe to call multiple times
- ✅ Backend will return existing `phoneKeyId` if already provisioned
- ✅ Private key **never leaves** Android Keystore
- ⚠️ Requires valid **authentication token** in SharedPreferences

### Step 3: Verify Provisioning Success

```kotlin
private suspend fun verifyProvisioning(): Boolean {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyManager.getDeviceUdid(this)

    val result = service.isProvisioned(userId, udid)
    return result.fold(
        onSuccess = { isProvisioned ->
            if (isProvisioned) {
                Log.d("App", "✅ Provisioning verified")
                true
            } else {
                Log.e("App", "Provisioning verification failed: not provisioned")
                false
            }
        },
        onFailure = { error ->
            Log.e("App", "Provisioning verification failed: ${error.message}")
            false
        }
    )
}
```

---

## ACL Management

ACLs (Access Control Lists) contain cryptographic tokens that authorize your phone to unlock specific locks. ACLs have:

- **Lock MAC address** - Which lock this ACL is for
- **Permissions** - `unlock`, `overrideOverlock`, `manage_locks`
- **Schedule** - Time windows when access is allowed
- **Expiration** - ACLs automatically expire after period (typically 24-48 hours)
- **Signature** - ECDSA signature from lock's private key

### ⚠️ CRITICAL: Manager Cache Initialization

**Before calling any ACL operations** (`generateBulkAcls()`, `generateAcl()`), you **MUST first call `initialize(userId, deviceId)`** to populate the PhoneKeyCoreClient's manager cache.

**Why this is required:**

- PhoneKeyCoreClient uses an internal manager cache keyed by `(userId, deviceId)`
- ACL operations (`generateBulkAcls()`, `generateAcl()`) retrieve ACLs using a cached manager instance
- If `initialize()` wasn't called, the cache is empty → **`NotInitialized` error**
- This pattern mirrors iOS PhoneKeyFacade lazy initialization

**Common mistake:**

```kotlin
// ❌ WRONG: Calling generateBulkAcls without initialize()
val result = service.generateBulkAcls(phoneKeyId, userId, udid)
// → Returns NotInitialized error!
```

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

**Why provisioning doesn't initialize:**

Provisioning creates a `PhoneKeyManager` instance internally, but doesn't add it to the PhoneKeyCoreClient cache. This is intentional to keep provisioning logic isolated. Always call `initialize()` explicitly before ACL operations, even after successful provisioning.

**When to call initialize():**

- ✅ Before bulk ACL fetch (after login)
- ✅ Before individual ACL fetch
- ✅ On app startup (if user already logged in)
- ✅ After provisioning completes
- ℹ️ Safe to call multiple times (idempotent - returns cached instance)

### Bulk ACL Fetch (Recommended)

Always use bulk fetch for initial ACL loading and periodic refreshes:

```kotlin
private suspend fun fetchAllAcls() {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyManager.getDeviceUdid(this)

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
            Log.d("App", "ACL fetch complete: ${bulkResult.successCount}/${bulkResult.totalCount} stored successfully")

            when {
                bulkResult.isFullSuccess -> {
                    Log.d("App", "✅ All ACLs ready - can now unlock locks")
                    // Update UI to show locks ready
                }
                bulkResult.hasPartialSuccess -> {
                    Log.w("App", "⚠️ Partial success: ${bulkResult.successCount}/${bulkResult.totalCount} ACLs stored")
                    // Some ACLs failed to store
                }
                bulkResult.isEmpty -> {
                    Log.i("App", "ℹ️ No locks assigned to this user")
                    // Show "no locks" message
                }
                else -> {
                    Log.e("App", "❌ No ACLs fetched - user may have no lock access")
                    // Show error or "no locks" message
                }
            }
        },
        onFailure = { error ->
            Log.e("App", "❌ Failed to fetch ACLs: ${error.message}")
            when (error) {
                is com.noke.smartentrycore.phonekey.NokeMobileLibraryError.NotInitialized -> {
                    // ⚠️ Did you forget to call initialize() first?
                    showError("Manager not initialized - please restart app")
                    Log.e("App", "CRITICAL: initialize() was not called before generateBulkAcls()")
                }
                is com.noke.smartentrycore.phonekey.NokeMobileLibraryError.NetworkError -> {
                    showError("Network error - please try again")
                }
                else -> {
                    showError("Failed to load locks: ${error.message}")
                }
            }
        }
    )
}
```

**Best Practices for Bulk Fetch:**

- ✅ Call on **successful login**
- ✅ Call after **provisioning**
- ✅ Call on **app startup** (if already logged in)
- ✅ Call on **explicit refresh** (swipe-to-refresh)
- ⚠️ Don't call on every unlock attempt (use cache instead)

### Individual ACL Fetch (Fallback Only)

Only use individual ACL fetch for specific failure recovery scenarios:

```kotlin
private suspend fun refetchAcl(lockMac: String) {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyManager.getDeviceUdid(this)

    val phoneKeyIdResult = service.getPhoneKeyId(userId, udid)
    val phoneKeyId = phoneKeyIdResult.getOrElse {
        Log.e("App", "Invalid phone key ID")
        return
    }

    Log.d("App", "Refetching ACL for lock")

    val result = service.generateAcl(
        userId = userId.toInt(),
        lockMac = lockMac,
        phoneKeyId = phoneKeyId.toInt(),
        udid = udid
    )

    result.fold(
        onSuccess = {
            Log.d("App", "✅ ACL refetched successfully")
            // Retry unlock
        },
        onFailure = { error ->
            Log.e("App", "❌ Failed to refetch ACL: ${error.message}")
        }
    )
}
```

### Check ACL Cache Before Unlock

**Note:** For cache checking, you need to access PhoneKeyManager directly (PhoneKeyAccessService doesn't expose cache check):

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

private suspend fun prepareUnlock(lockMac: String) {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyManager.getDeviceUdid(this)

    // Create PhoneKeyManager to check cache
    val phoneKeyManager = PhoneKeyManager(
        applicationContext,
        userId,
        udid
    )

    // Check cache first
    if (phoneKeyManager.hasCachedAcl(lockMac)) {
        Log.d("App", "✅ Valid ACL cached")
        proceedWithUnlock(lockMac, phoneKeyManager)
    } else {
        Log.d("App", "❌ No valid ACL - fetching...")

        // Fetch individual ACL if not cached
        refetchAcl(lockMac)
    }
}

private fun proceedWithUnlock(lockMac: String, phoneKeyManager: PhoneKeyManager) {
    // Get ACL envelope with signature and binary
    val aclEnvelope = phoneKeyManager.getAclEnvelope(lockMac)
    if (aclEnvelope == null) {
        Log.e("App", "Failed to get ACL envelope")
        return
    }

    // Use aclBinary for BLE unlock command
    val aclBinary = aclEnvelope.aclBinary
    val aclSignature = aclEnvelope.aclSignature

    Log.d("App", "ACL binary length: ${aclBinary.length}")

    // Send to BLE service for unlock
    // sendUnlockCommand(lockMac, aclBinary, aclSignature)
}
```

### Explicit ACL Refresh

Allow users to manually refresh ACLs (e.g., swipe-to-refresh):

```kotlin
private suspend fun onSwipeToRefresh() {
    val userId = (application as MyApplication).getCurrentUserId()
    val udid = PhoneKeyManager.getDeviceUdid(this)

    Log.d("App", "User triggered ACL refresh")

    val result = service.refreshAllAcls(userId, udid)

    result.fold(
        onSuccess = { bulkResult ->
            Log.d("App", "✅ ACL refresh successful: ${bulkResult.successCount} ACLs")
            showSuccess("Locks refreshed")
        },
        onFailure = { error ->
            Log.e("App", "❌ ACL refresh failed: ${error.message}")
            showError("Failed to refresh locks")
        }
    )
}
```

### ACL Expiration Handling

ACLs automatically expire after a period (typically 24-48 hours). The SDK handles expiration checking automatically:

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

// hasCachedAcl() automatically checks expiration (via PhoneKeyManager)
val phoneKeyManager = PhoneKeyManager(
    applicationContext,
    userId,
    udid
)

if (phoneKeyManager.hasCachedAcl(lockMac)) {
    // ACL is valid AND not expired
} else {
    // ACL is either missing, expired, or invalid
    // Trigger refresh using PhoneKeyAccessService
    lifecycleScope.launch {
        service.refreshAllAcls(userId, udid).fold(
            onSuccess = { retryUnlock() },
            onFailure = { error -> Log.e("App", "Refresh failed: ${error.message}") }
        )
    }
}
```

### Using PhoneKeyFacade for Local Operations (Alternative API)

**PhoneKeyFacade** provides a high-level convenience API for local phone key operations with lazy initialization support. It's particularly useful for:

- Checking and listing valid ACLs without backend calls
- Deleting individual ACLs
- Working independently without requiring `ensureProvisioned()` first
- Simpler API for common operations

**iOS Parity:** This API matches the iOS PhoneKeyFacade behavior exactly, including lazy initialization and cache management.

#### Initialize PhoneKeyFacade

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyFacade

class MyActivity : AppCompatActivity() {
    private val phoneKeyFacade by lazy { PhoneKeyFacade.getInstance(this) }

    // ... your code
}
```

#### List Valid ACLs (No Backend Call)

```kotlin
private suspend fun showAccessibleLocks() {
    val userId = getCurrentUserId()
    val udid = PhoneKeyAccessService.getDeviceUdid(this)

    // List all valid (non-expired) ACLs from cache
    // Works independently - no ensureProvisioned() required!
    val validAcls = phoneKeyFacade.listValidACLs(userId, udid)

    if (validAcls.isEmpty()) {
        showMessage("No active locks found - refresh to fetch from server")
    } else {
        validAcls.forEach { acl ->
            Log.d("App", "Lock: ${acl.lockMac}")
            Log.d("App", "  Status: ${acl.statusMessage}")
            Log.d("App", "  Expires in: ${acl.timeUntilExpiration / 1000 / 60} minutes")

            // Display lock in UI
            addLockToList(acl)
        }
    }
}
```

#### Get ACL for Specific Lock

```kotlin
private suspend fun prepareUnlock(lockMac: String) {
    val userId = getCurrentUserId()
    val udid = PhoneKeyAccessService.getDeviceUdid(this)

    // Get ACL from cache (works without ensureProvisioned!)
    val acl = phoneKeyFacade.getACL(userId, udid, lockMac)

    if (acl != null && acl.isValid) {
        Log.d("App", "✅ Valid ACL found for lock $lockMac")
        Log.d("App", "  Expires in: ${acl.timeUntilExpiration / 1000 / 60} minutes")

        // Proceed with unlock using NokeDeviceManager
        proceedWithUnlock(lockMac, acl)
    } else {
        Log.d("App", "❌ No valid ACL - need to refresh")
        // Fetch from backend using PhoneKeyAccessService
        fetchAclForLock(lockMac)
    }
}
```

#### Delete Individual ACL (New Feature)

```kotlin
private suspend fun revokeAccessToLock(lockMac: String) {
    val userId = getCurrentUserId()
    val udid = PhoneKeyAccessService.getDeviceUdid(this)

    try {
        // Delete ACL from local storage
        phoneKeyFacade.deleteACL(userId, udid, lockMac)
        Log.d("App", "✅ Access revoked for lock $lockMac")

        // Update UI to remove lock from list
        removeLockFromUI(lockMac)
    } catch (e: Exception) {
        Log.e("App", "Failed to delete ACL: ${e.message}")
        showError("Failed to revoke access")
    }
}
```

#### Check Provisioning with Cached ACLs

```kotlin
private suspend fun initializePhoneKey() {
    val userId = getCurrentUserId()
    val udid = PhoneKeyAccessService.getDeviceUdid(this)

    // This generates keys if missing and returns cached ACLs
    val result = phoneKeyFacade.ensureProvisioned(userId, udid)

    result.fold(
        onSuccess = { cachedAcls ->
            Log.d("App", "Phone key ready with ${cachedAcls.size} cached ACL(s)")

            if (cachedAcls.isEmpty()) {
                // No ACLs cached - fetch from backend
                fetchAclsFromBackend()
            } else {
                // Have cached ACLs - filter to valid ones
                val validAcls = cachedAcls.filter { it.isValid }
                Log.d("App", "${validAcls.size} valid ACL(s) ready")
                displayLocks(validAcls)
            }
        },
        onFailure = { error ->
            Log.e("App", "Failed to ensure provisioning: ${error.message}")
        }
    )
}
```

#### Extension Properties for ACLs

```kotlin
import com.noke.nokemobilelibrary.phonekey.isValid
import com.noke.nokemobilelibrary.phonekey.isExpired
import com.noke.nokemobilelibrary.phonekey.timeUntilExpiration
import com.noke.nokemobilelibrary.phonekey.statusMessage
import com.noke.nokemobilelibrary.phonekey.models.BulkAclEnvelope

// Using extension properties (matches iOS PhoneKeyModels)
val acl: BulkAclEnvelope = // ... get ACL

if (acl.isValid) {
    Log.d("App", "ACL is valid")
    Log.d("App", "Status: ${acl.statusMessage}")
    Log.d("App", "Expires in: ${acl.timeUntilExpiration / 1000} seconds")
} else if (acl.isExpired) {
    Log.d("App", "ACL has expired - needs refresh")
}
```

#### Type Aliases (iOS Compatibility)

```kotlin
import com.noke.nokemobilelibrary.phonekey.Acl
import com.noke.nokemobilelibrary.phonekey.PhoneKeyProvisionResponse

// Short aliases matching iOS naming
val acl: Acl = phoneKeyFacade.getACL(userId, udid, lockMac) ?: return
val response: PhoneKeyProvisionResponse = // ... provision result
```

#### PhoneKeyFacade vs PhoneKeyAccessService

| Feature                    | PhoneKeyFacade       | PhoneKeyAccessService    |
| -------------------------- | -------------------- | ------------------------ |
| **Purpose**                | Local operations     | Backend operations       |
| **Lazy initialization**    | ✅ Yes               | ⚠️ Requires setup        |
| **List valid ACLs**        | ✅ `listValidACLs()` | ❌ Not available         |
| **Get specific ACL**       | ✅ `getACL()`        | ❌ Not available         |
| **Delete ACL**             | ✅ `deleteACL()`     | ❌ Not available         |
| **Provision with backend** | ❌ Not available     | ✅ `provisionPhoneKey()` |
| **Fetch ACLs from server** | ❌ Not available     | ✅ `generateBulkAcls()`  |
| **Check backend status**   | ❌ Not available     | ✅ `isProvisioned()`     |

**Recommendation:** Use both APIs together:

- **PhoneKeyFacade** for local cache operations (listing, getting, deleting ACLs)
- **PhoneKeyAccessService** for backend operations (provisioning, fetching ACLs)

---

## Error Handling

### Result-Based Error Handling

PhoneKeyAccessService uses `Result<T>` for exhaustive error handling. All operations return `Result` wrapping either success value or `NokeMobileLibraryError`:

```kotlin
import com.noke.smartentrycore.phonekey.NokeMobileLibraryError

// Example: Handle all possible outcomes
val result = service.provisionPhoneKey(userId, udid)

result.fold(
    onSuccess = { phoneKeyId ->
        // Success path
        Log.d("App", "Success: $phoneKeyId")
    },
    onFailure = { error ->
        // Error path - handle NokeMobileLibraryError types
        when (error) {
            is NokeMobileLibraryError.NotInitialized -> {
                // Service not initialized
                showError("App not initialized properly")
            }
            is NokeMobileLibraryError.InvalidInput -> {
                // Invalid parameters
                showError("Invalid input: ${error.message}")
            }
            is NokeMobileLibraryError.NetworkError -> {
                // Network failure
                showRetryDialog()
            }
            is NokeMobileLibraryError.CryptographicError -> {
                // Crypto operation failed
                showError("Device not supported")
            }
            is NokeMobileLibraryError.ProvisioningFailed -> {
                // Backend rejected provisioning
                showError("Provisioning failed: ${error.message}")
            }
            else -> {
                // Catch-all for unknown errors
                showError("Error: ${error.message}")
            }
        }
    }
)
```

### Common Error Scenarios

#### 1. Provisioning Errors

```kotlin
import com.noke.smartentrycore.phonekey.NokeMobileLibraryError

private suspend fun handleProvisioningErrors() {
    val result = service.provisionPhoneKey(userId, udid)

    result.fold(
        onSuccess = { phoneKeyId ->
            Log.d("App", "Provisioned successfully")
        },
        onFailure = { error ->
            when (error) {
                is NokeMobileLibraryError.NetworkError -> {
                    // No internet connection
                    if (!isNetworkAvailable()) {
                        showError("No internet connection")
                    } else {
                        showError("Network error - please try again")
                    }
                }
                is com.noke.smartentrycore.phonekey.NokeMobileLibraryError.CryptographicError -> {
                    // Device doesn't support required crypto
                    showError("This device is not supported")
                }
                is com.noke.smartentrycore.phonekey.NokeMobileLibraryError.ProvisioningFailed -> {
                    // Backend rejected (401, 403, 500, etc.)
                    showError("Provisioning failed - please contact support")
                }
                else -> {
                    showError("Provisioning failed: ${error.message}")
                }
            }
        }
    )
}
```

#### 2. ACL Fetch Errors

```kotlin
import com.noke.smartentrycore.phonekey.NokeMobileLibraryError

private suspend fun handleAclFetchErrors() {
    val result = service.generateBulkAcls(phoneKeyId, userId, udid)

    result.fold(
        onSuccess = { bulkResult ->
            if (bulkResult.totalCount == 0) {
                // User has no locks assigned
                showMessage("No locks available")
            } else if (bulkResult.successCount == 0) {
                // All ACLs failed to store (very rare)
                showError("Storage error - please free up device space")
            } else if (bulkResult.successCount < bulkResult.totalCount) {
                // Partial failure
                showWarning("Some locks may not be available (${bulkResult.successCount}/${bulkResult.totalCount})")
            } else {
                // Full success
                showMessage("${bulkResult.successCount} locks ready")
            }
        },
        onFailure = { error ->
            when (error) {
                is NokeMobileLibraryError.NetworkError -> {
                    showError("Could not fetch lock access. Please check your connection.")
                }
                is NokeMobileLibraryError.BulkAclFetchFailed -> {
                    showError("Failed to load locks: ${error.message}")
                }
                else -> {
                    showError("Error: ${error.message}")
                }
            }
        }
    )
}
```

#### 3. Missing ACL Errors

When ACL is not in cache, fetch it individually:

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager
import com.noke.smartentrycore.phonekey.NokeMobileLibraryError

private suspend fun handleMissingAcl(lockMac: String) {
    // Check if ACL exists in cache (using PhoneKeyManager)
    val phoneKeyManager = PhoneKeyManager(
        applicationContext,
        userId,
        udid
    )

    if (!phoneKeyManager.hasCachedAcl(lockMac)) {
        Log.e("App", "No ACL available for lock")

        // Try to fetch individual ACL
        val result = service.generateAcl(
            userId = userId.toInt(),
            lockMac = lockMac,
            phoneKeyId = phoneKeyId.toInt(),
            udid = udid
        )

        result.fold(
            onSuccess = {
                Log.d("App", "ACL fetched, ready to unlock")
                // Retry unlock
            },
            onFailure = { error ->
                when (error) {
                    is NokeMobileLibraryError.AclFetchFailed -> {
                        // User may not have access to this lock
                        showError("You don't have access to this lock")
                    }
                    else -> {
                        showError("Failed to get lock access: ${error.message}")
                    }
                }
            }
        )
    }
}
```

### Logging for Troubleshooting

All SmartEntryCore logs use the `ION-2` prefix for easy filtering:

```bash
# View all ION-2 logs
adb logcat | grep "ION-2"

# Common log patterns
# Success: "ION-2 - Provisioned successfully: keyId=42"
# Warning: "ION-2 - ⚠️ WARNING: Stored ACL has NO recognized permissions"
# Error:   "ION-2 - Failed to get ACL: HTTP 404 Not Found"
```

---

## Threading & Concurrency

### Coroutine-Based API

PhoneKeyAccessService uses **suspend functions** that must be called from a coroutine context. All operations are non-blocking and return when complete.

```kotlin
class MainActivity : AppCompatActivity() {

    private val service = PhoneKeyAccessService.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use lifecycleScope for Activity-bound operations
        lifecycleScope.launch {
            val result = service.provisionPhoneKey(userId, udid)

            result.fold(
                onSuccess = { phoneKeyId ->
                    // Already on main thread - safe to update UI
                    textView.text = "Provisioned: $phoneKeyId"
                },
                onFailure = { error ->
                    // Already on main thread - safe to update UI
                    showError(error.message)
                }
            )
        }
    }
}
```

### Coroutine Scopes

Choose the appropriate scope for your operations:

```kotlin
// Activity-bound operations (automatic cleanup on Activity destroy)
lifecycleScope.launch {
    service.provisionPhoneKey(userId, udid)
}

// Fragment-bound operations (automatic cleanup on Fragment destroy)
viewLifecycleOwner.lifecycleScope.launch {
    service.generateBulkAcls(phoneKeyId, userId, udid)
}

// Application-level operations (long-lived, manual cleanup)
(application as MyApplication).getAppScope().launch {
    service.refreshAllAcls(userId, udid)
}

// ViewModel-bound operations (automatic cleanup when ViewModel cleared)
class MyViewModel : ViewModel() {
    fun fetchAcls() {
        viewModelScope.launch {
            service.generateBulkAcls(phoneKeyId, userId, udid)
        }
    }
}
```

### Dispatchers

PhoneKeyAccessService handles dispatcher switching internally. You can call from any dispatcher:

```kotlin
// Call from Main dispatcher (default for lifecycleScope)
lifecycleScope.launch(Dispatchers.Main) {
    val result = service.provisionPhoneKey(userId, udid)
    // Result handling happens on Main thread - safe for UI updates
}

// Call from IO dispatcher for background work
lifecycleScope.launch(Dispatchers.IO) {
    val result = service.generateBulkAcls(phoneKeyId, userId, udid)

    withContext(Dispatchers.Main) {
        // Switch to Main for UI updates
        updateUI(result)
    }
}
```

### Handling Blocking Operations

Some PhoneKeyManager methods (like `hasCachedAcl`) are synchronous. Use `withContext(Dispatchers.IO)` for these:

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

lifecycleScope.launch {
    val hasCachedAcl = withContext(Dispatchers.IO) {
        // Create PhoneKeyManager for cache check (blocking operation)
        val manager = PhoneKeyManager(
            applicationContext,
            userId,
            udid
        )
        manager.hasCachedAcl(lockMac)
    }

    // Back on Main dispatcher
    if (hasCachedAcl) {
        proceedWithUnlock()
    } else {
        // Use suspend function for fetch
        service.generateAcl(userId.toInt(), lockMac, phoneKeyId.toInt(), udid)
    }
}
```

### Thread Safety

- ✅ `PhoneKeyAccessService` is **thread-safe** (uses mutex internally)
- ✅ Multiple concurrent suspend function calls are safe
- ✅ PhoneKeyManager (underlying implementation) uses `@Synchronized`
- ✅ EncryptedSharedPreferences handles concurrent access
- ⚠️ No need for additional synchronization in your code

#### Cross-Thread Cache Coherency (Production-Ready)

PhoneKeyManager implements **guaranteed cross-thread cache coherency** for ACL storage using EncryptedSharedPreferences. This solves a critical Android platform limitation where EncryptedSharedPreferences maintains **per-thread in-memory caches** that can become stale across threads.

**The Problem:**

```kotlin
// Thread A (background): Stores ACL
prefs.edit().putString("acl_CA:B3:AB", aclData).commit()

// Thread B (main): Reads ACL - STALE CACHE!
val acl = prefs.getString("acl_CA:B3:AB", null) // Returns null! ❌
```

**The Solution:**

PhoneKeyManager calls `forcePrefsReload()` internally:

- **BEFORE every read** - Forces reading thread to reload from disk
- **AFTER every write** - Invalidates writing thread's cache

This ensures:

- ✅ ACLs written on background thread are **immediately visible** to main thread
- ✅ User switching works correctly (ACLs don't "disappear")
- ✅ Zero redundant network requests for already-cached ACLs
- ✅ True cross-thread cache coherency

**You don't need to do anything** - this is handled automatically by PhoneKeyManager. Just be aware that:

1. ACLs stored on any thread are visible to all threads immediately
2. No race conditions or stale cache issues
3. Safe to call ACL operations from any dispatcher (Main, IO, Default)

**Implementation Details:**

```kotlin
// Inside PhoneKeyManager (you never call this directly)
private fun forcePrefsReload() {
    // Accessing ANY key forces EncryptedSharedPreferences to check
    // if its internal cache is stale and reload from disk if needed
    prefs.getString("__force_reload_${System.nanoTime()}", null)
}

// Read path
fun getBulkAclEnvelope(lockMac: String): BulkAclEnvelope? {
    synchronized(prefsLock) {
        forcePrefsReload()  // ← Invalidate cache BEFORE read
        return prefs.getString("acl_envelope_$lockMac", null)
    }
}

// Write path
fun storeBulkAclEnvelope(bulkAcl: BulkAclEnvelope) {
    synchronized(prefsLock) {
        prefs.edit().putString(...).commit()
        forcePrefsReload()  // ← Invalidate cache AFTER write
    }
}
```

**Testing Cross-Thread Coherency:**

If you want to verify this behavior in your integration tests:

```kotlin
@Test
fun testCrossThreadCacheCoherency() = runBlocking {
    val userId = "testUser"
    val udid = "testDevice"
    val lockMac = "CA:B3:AB:0D:30:F6"

    // Background thread: Store ACL
    withContext(Dispatchers.IO) {
        val service = PhoneKeyAccessService.getInstance()
        service.generateBulkAcls(phoneKeyId, userId, udid)
    }

    // Main thread: Read ACL - should be visible immediately
    withContext(Dispatchers.Main) {
        val manager = PhoneKeyManager(
            context,
            userId,
            udid,
            securityService
        )
        val acl = manager.getBulkAclEnvelope(lockMac)

        assertNotNull(acl) // ✅ ACL is visible across threads
    }
}
```

### Error Handling in Coroutines

Use try-catch or Result.fold() for error handling:

```kotlin
lifecycleScope.launch {
    try {
        val result = service.provisionPhoneKey(userId, udid)

        result.fold(
            onSuccess = { phoneKeyId ->
                Log.d("App", "Success: $phoneKeyId")
            },
            onFailure = { error ->
                Log.e("App", "Error: ${error.message}")
            }
        )
    } catch (e: Exception) {
        // Handle unexpected exceptions
        Log.e("App", "Unexpected error: ${e.message}", e)
    }
}
```

### Cancellation

Coroutines automatically handle cancellation when the scope is destroyed:

```kotlin
class MyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This job is automatically cancelled when Activity is destroyed
        lifecycleScope.launch {
            val result = service.generateBulkAcls(phoneKeyId, userId, udid)
            // If Activity is destroyed before this completes,
            // the coroutine is cancelled automatically
        }
    }
}
```

---

## Best Practices

### 1. ⭐ CRITICAL: Clear ACL Cache on Logout

**Problem:** ACLs cached locally may become stale after logout/login even if not expired, causing unlock failures with `CMDSIG_VERIFY_FAIL`.

**Root Cause:** When users log out and log back in, cached ACLs from the previous session may remain in encrypted storage. Even though these ACLs haven't expired locally, they could have been:

- Revoked server-side
- Regenerated with different signatures
- Invalidated due to permission changes

**Solution:** **Always clear ACL cache on logout** using `PhoneKeyAccessService.cleanupAclsForUser()`. This ensures fresh ACLs are fetched on the next login.

```kotlin
import com.noke.smartentrycore.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.launch

class MyApplication : Application() {
    private lateinit var phoneKeyAccessService: PhoneKeyAccessService
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        PhoneKeyAccessService.initialize(this)
        phoneKeyAccessService = PhoneKeyAccessService.getInstance()
    }

    /**
     * CRITICAL: Call this on logout to clear all cached ACLs
     * This prevents stale ACLs from being used after re-login
     */
    fun cleanupOnLogout() {
        val userId = getCurrentUserId()

        if (userId.isNotEmpty()) {
            // Clean up ACLs asynchronously
            applicationScope.launch {
                try {
                    val udid = PhoneKeyAccessService.getDeviceUdid(this@MyApplication)
                    val result = phoneKeyAccessService.cleanupAclsForUser(userId, udid)

                    result.fold(
                        onSuccess = {
                            Log.d("App", "✅ Successfully cleaned up ACLs on logout")
                        },
                        onFailure = { error ->
                            Log.e("App", "❌ Failed to cleanup ACLs: ${error.message}")
                        }
                    )
                } catch (e: Exception) {
                    Log.e("App", "Error during ACL cleanup: ${e.message}", e)
                }
            }
        }

        // Clear other provisioning state
        clearProvisioningFlags()
        Log.d("App", "Logout cleanup complete")
    }

    suspend fun fetchAclsForDevices() {
        val userId = getCurrentUserId()
        val udid = PhoneKeyAccessService.getDeviceUdid(this)

        // Check cached ACLs
        val devicesWithValidCachedAcls = ion2Devices.filter { device ->
            service.hasCachedAcl(userId, udid, device.mac).getOrElse { false }
        }

        // If all devices have valid cached ACLs, skip fetch
        // Note: ACLs are cleared on logout, so cache only persists during same session
        if (devicesWithValidCachedAcls.size == ion2Devices.size) {
            Log.d("App", "✅ All devices have valid cached ACLs, skipping backend fetch")
            return
        }

        // At least one device needs ACL - fetch from backend
        val devicesNeedingAcls = ion2Devices.size - devicesWithValidCachedAcls.size
        Log.d("App", "🔄 ${devicesNeedingAcls}/${ion2Devices.size} device(s) need ACLs")

        // Initialize and fetch ACLs from backend
        service.initialize(userId, udid).getOrElse { return }

        val result = service.generateBulkAcls(phoneKeyId, userId, udid)
        result.onSuccess {
            Log.d("App", "✅ ACLs fetched successfully")
        }
    }
}
```

**Benefits:**

- ✅ **Cleaner solution** - No session state management needed
- ✅ **100% reliable** - Physically removes stale ACLs from storage
- ✅ Prevents unlock failures from stale signatures
- ✅ Ensures server-side permission changes take effect
- ✅ Supports graceful user switching
- ✅ Maintains efficient caching during active sessions

**Why This is Better Than Session Flags:**

- No risk of flag state getting out of sync
- No need to track session lifecycle
- ACL storage is actually cleaned, not just bypassed
- Simpler code with fewer edge cases

### 2. Initialization Pattern

```kotlin
// ✅ GOOD: Initialize once in Application.onCreate()
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this)
        PhoneKeyAccessService.initialize(this)
    }
}

// ❌ BAD: Initializing multiple times
fun someMethod() {
    PhoneKeyAccessService.initialize(context) // Don't do this outside Application!
}
```

### 2. Coroutine Scope Selection

```kotlin
import kotlinx.coroutines.GlobalScope
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope

// ✅ GOOD: Use lifecycleScope for Activity/Fragment operations
lifecycleScope.launch {
    service.provisionPhoneKey(userId, udid)
}

// ✅ GOOD: Use viewModelScope for ViewModel operations
class MyViewModel : ViewModel() {
    fun provision() {
        viewModelScope.launch {
            service.provisionPhoneKey(userId, udid)
        }
    }
}

// ❌ BAD: Using GlobalScope (never gets cancelled)
GlobalScope.launch {
    service.provisionPhoneKey(userId, udid) // Don't do this!
}
```

### 3. ACL Caching Strategy

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

// ✅ GOOD: Check cache first, fetch only if needed
lifecycleScope.launch {
    val hasCachedAcl = withContext(Dispatchers.IO) {
        val manager = PhoneKeyManager(applicationContext, userId, udid)
        manager.hasCachedAcl(lockMac)
    }

    if (!hasCachedAcl) {
        service.generateAcl(userId.toInt(), lockMac, phoneKeyId.toInt(), udid)
    }
}

// ❌ BAD: Fetching ACL on every unlock attempt
fun unlock(lockMac: String) {
    lifecycleScope.launch {
        // Unnecessary API call every time!
        service.generateAcl(userId.toInt(), lockMac, phoneKeyId.toInt(), udid)
    }
}
```

### 4. Error Handling

```kotlin
import com.noke.smartentrycore.phonekey.NokeMobileLibraryError

// ✅ GOOD: Exhaustive error handling with Result.fold()
lifecycleScope.launch {
    val result = service.provisionPhoneKey(userId, udid)

    result.fold(
        onSuccess = { phoneKeyId ->
            proceedToNextStep(phoneKeyId)
        },
        onFailure = { error ->
            when (error) {
                is NokeMobileLibraryError.NetworkError -> showRetryDialog()
                else -> showErrorDialog(error.message)
            }
        }
    )
}

// ❌ BAD: Ignoring errors
lifecycleScope.launch {
    val result = service.provisionPhoneKey(userId, udid)
    val keyId = result.getOrThrow() // Will crash on failure!
}
```

### 5. Logout Handling

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager
import com.noke.smartentrycore.phonekey.PhoneKeyAccessService

// ✅ GOOD: Clean up ACLs on logout
class MyApplication : Application() {
    fun onUserLogout() {
        val userId = getCurrentUserId()
        val udid = PhoneKeyManager.getDeviceUdid(this)

        applicationScope.launch {
            PhoneKeyAccessService.getInstance()
                .cleanupAclsForUser(userId, udid)
                .fold(
                    onSuccess = { Log.d("App", "ACLs cleaned up") },
                    onFailure = { Log.e("App", "Cleanup failed: ${it.message}") }
                )
        }
    }
}

// ❌ BAD: Not cleaning up on logout
// This leaves ACL data from previous user!
```

### 6. Avoid Blocking Main Thread

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

// ✅ GOOD: Use withContext(Dispatchers.IO) for blocking operations
lifecycleScope.launch {
    val hasCachedAcl = withContext(Dispatchers.IO) {
        // Blocking PhoneKeyManager operation
        val manager = PhoneKeyManager(applicationContext, userId, udid)
        manager.hasCachedAcl(lockMac)
    }
    // Back on Main thread for UI updates
    updateUI(hasCachedAcl)
}

// ❌ BAD: Blocking main thread
fun checkAcl() {
    // This blocks the main thread!
    val manager = com.noke.smartentrycore.controllers.PhoneKeyManager(
        applicationContext,
        userId,
        udid
    )
    val hasCachedAcl = manager.hasCachedAcl(lockMac)
}
```

---

## Common Pitfalls

### 1. ❌ Forgetting to Initialize PhoneKeyAccessService

```kotlin
// ERROR: "java.lang.IllegalStateException: PhoneKeyAccessService not initialized"

// FIX: Add to Application.onCreate()
override fun onCreate() {
    super.onCreate()
    AndroidThreeTen.init(this)
    PhoneKeyAccessService.initialize(this) // Required!
}
```

### 2. ❌ Forgetting to Initialize AndroidThreeTen

```kotlin
// ERROR: "java.lang.ExceptionInInitializerError: The host application must call AndroidThreeTen.init()"

// FIX: Add to Application.onCreate() BEFORE PhoneKeyAccessService
override fun onCreate() {
    super.onCreate()
    AndroidThreeTen.init(this) // Required first!
    PhoneKeyAccessService.initialize(this)
}
```

### 3. ❌ Calling Suspend Functions Outside Coroutine Context

```kotlin
// ERROR: "Suspend function 'provisionPhoneKey' should be called only from a coroutine or another suspend function"

// FIX: Call from coroutine scope
lifecycleScope.launch {
    service.provisionPhoneKey(userId, udid)
}
```

### 4. ❌ Not Checking ACL Cache

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

// ERROR: "No ACL found" during unlock

// FIX: Always check cache before unlock
lifecycleScope.launch {
    val hasCachedAcl = withContext(Dispatchers.IO) {
        val manager = PhoneKeyManager(applicationContext, userId, udid)
        manager.hasCachedAcl(lockMac)
    }

    if (!hasCachedAcl) {
        service.generateAcl(userId.toInt(), lockMac, phoneKeyId.toInt(), udid)
    }
}
```

### 5. ❌ Using Activity Context for Long-Lived Operations

```kotlin
// MEMORY LEAK: Activity context holds Activity reference

// ❌ BAD: Using Activity context
PhoneKeyAccessService.initialize(activityContext) // Don't do this!

// ✅ GOOD: Always use Application context
PhoneKeyAccessService.initialize(applicationContext)
// Or in Application.onCreate():
PhoneKeyAccessService.initialize(this) // 'this' is Application context
```

### 6. ❌ Blocking Main Thread with PhoneKeyManager Operations

```kotlin
import com.noke.smartentrycore.controllers.PhoneKeyManager

// CRASH: ANR (Application Not Responding)

// ❌ BAD: Blocking main thread
val manager = PhoneKeyManager(context, userId, udid)
val hasCachedAcl = manager.hasCachedAcl(lockMac) // Can cause ANR!

// ✅ GOOD: Use withContext(Dispatchers.IO)
lifecycleScope.launch {
    val hasCachedAcl = withContext(Dispatchers.IO) {
        val manager = PhoneKeyManager(applicationContext, userId, udid)
        manager.hasCachedAcl(lockMac)
    }
}
```

### 7. ❌ Ignoring Result Failure Cases

```kotlin
import com.noke.smartentrycore.phonekey.PhoneKeyAccessService

// ❌ BAD: Using getOrNull and not handling null
lifecycleScope.launch {
    val result = service.provisionPhoneKey(userId, udid)
    val phoneKeyId = result.getOrNull() // Returns null on failure!
    // phoneKeyId might be null, but code continues without checking
}

// ✅ GOOD: Using fold to handle both cases
lifecycleScope.launch {
    val result = service.provisionPhoneKey(userId, udid)
    result.fold(
        onSuccess = { phoneKeyId -> /* Handle success */ },
        onFailure = { error -> /* Handle error */ }
    )
}
```

### 8. ❌ Using GlobalScope Instead of Proper Scope

```kotlin
import kotlinx.coroutines.GlobalScope

// ❌ BAD: GlobalScope never gets cancelled
GlobalScope.launch {
    service.provisionPhoneKey(userId, udid) // Continues even if Activity destroyed!
}

// ✅ GOOD: Use lifecycleScope for automatic cancellation
lifecycleScope.launch {
    service.provisionPhoneKey(userId, udid) // Cancelled when Activity destroyed
}
```

---

## Testing

### Manual Testing Checklist

- [ ] First-time provisioning flow
- [ ] Re-provisioning on same device (should reuse existing keyId)
- [ ] ACL fetch after provisioning
- [ ] ACL cache hit (second unlock attempt)
- [ ] ACL expiration (wait 24 hours or mock time)
- [ ] Network failure during provisioning
- [ ] Network failure during ACL fetch
- [ ] Logout and login with different user
- [ ] Multi-device scenario (same user on 2 devices)

### Unit Testing with Coroutines

```kotlin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class PhoneKeyAccessServiceTest {

    @Test
    fun testProvisioningFlow() = runTest {
        val service = PhoneKeyAccessService.getInstance()
        PhoneKeyAccessService.initialize(context)

        val result = service.provisionPhoneKey(
            userId = "test_user",
            udid = "test_device"
        )

        // Assert success
        assertTrue(result.isSuccess)
        val phoneKeyId = result.getOrNull()
        assertNotNull(phoneKeyId)
    }

    @Test
    fun testProvisioningState() = runTest {
        val service = PhoneKeyAccessService.getInstance()

        // Before provisioning
        val beforeResult = service.isProvisioned("test_user", "test_device")
        assertFalse(beforeResult.getOrDefault(true))

        // Provision
        service.provisionPhoneKey("test_user", "test_device")

        // After provisioning
        val afterResult = service.isProvisioned("test_user", "test_device")
        assertTrue(afterResult.getOrDefault(false))
    }
}
```

### Integration Testing

```kotlin
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noke.smartentrycore.phonekey.NokeMobileLibraryError
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndToEndFlowTest {

    @Test
    fun testCompleteProvisioningAndAclFlow() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        PhoneKeyAccessService.initialize(context)
        val service = PhoneKeyAccessService.getInstance()

        val userId = "test_user"
        val udid = "test_device"

        // Step 1: Provision
        val provisionResult = service.provisionPhoneKey(userId, udid)
        assertTrue(provisionResult.isSuccess)
        val phoneKeyId = provisionResult.getOrThrow()

        // Step 2: Fetch ACLs
        val aclResult = service.generateBulkAcls(phoneKeyId, userId, udid)
        assertTrue(aclResult.isSuccess)

        val bulkResult = aclResult.getOrThrow()
        assertTrue(bulkResult.successCount >= 0)
    }

    @Test
    fun testErrorHandling() = runTest {
        val service = PhoneKeyAccessService.getInstance()

        // Test with invalid input
        val result = service.provisionPhoneKey("", "") // Empty userId/udid

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is NokeMobileLibraryError.InvalidInput)
    }
}
```

### Testing with Test Dispatchers

```kotlin
import kotlinx.coroutines.test.*
import org.junit.Test

class ViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun testProvisioningInViewModel() = runTest(testDispatcher) {
        val service = PhoneKeyAccessService.getInstance()
        PhoneKeyAccessService.initialize(context)

        // Test with controlled test dispatcher
        val result = service.provisionPhoneKey("test_user", "test_device")

        // Advance time if needed
        advanceUntilIdle()

        assertTrue(result.isSuccess)
    }
}
```

---

**Version:** 2.0.0 (PhoneKeyAccessService)  
**Last Updated:** April 1, 2026  
**Maintainer:** Noke Engineering

---

**Need help?** Check logcat for detailed error messages with `ION-2` prefix.
