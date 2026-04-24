# Phone Key Integration Guide for Third-Party Developers

**Version:** 1.0.0  
**Last Updated:** April 22, 2026  
**Library:** noke-mobile-library-android  
**Minimum Android SDK:** API 23  
**Language:** Kotlin with Coroutines

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Data Flow](#data-flow)
4. [Architecture Mapping (iOS → Android)](#architecture-mapping-ios--android)
5. [Platform-Specific Decisions](#platform-specific-decisions)
6. [Core Components](#core-components)
7. [Required Implementation](#required-implementation)
8. [Optional Customization](#optional-customization)
9. [View Model / State Management](#view-model--state-management)
10. [Complete Integration Example](#complete-integration-example)
11. [API Reference](#api-reference)
12. [Error Handling](#error-handling)
13. [Edge Cases / Considerations](#edge-cases--considerations)
14. [Testing Guide](#testing-guide)
15. [Best Practices](#best-practices)
16. [FAQ](#faq)

---

## Overview

The NokeMobileLibrary Phone Key system provides a complete solution for cryptographic key management, phone key provisioning, and ACL (Access Control List) management for Noke ION-2 smart locks. This guide explains how to integrate Phone Key functionality into your Android application.

### Key Features

- ✅ **Coroutine-Native API** — All operations are `suspend` functions returning `Result<T>`
- ✅ **Hardware-Backed Security** — ECDSA P-256 keys stored in Android Keystore (never extracted)
- ✅ **EncryptedSharedPreferences** — AES-256-GCM encrypted storage for ACLs and provisioning data
- ✅ **Protocol-Based Customization** — Implement `PhoneKeyCoreClient` to plug in your own networking layer
- ✅ **Reactive Flow Extensions** — Kotlin Flow wrappers for reactive UI updates
- ✅ **Per-Device Isolation** — Each (userId, deviceId) pair has completely isolated keys and storage
- ✅ **Comprehensive Error Handling** — Sealed `NokeMobileLibraryError` hierarchy for type-safe errors

---

## Quick Start

### Minimum Setup (3 Steps)

```kotlin
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

// 1. In Application.onCreate() — set your client implementation once
val client = YourPhoneKeyCoreClientImpl(this)
PhoneKeyAccessService.setSharedClient(client, this)

// 2. In your ViewModel or Activity — initialize, provision, and fetch ACLs
val udid = PhoneKeyAccessService.getDeviceUdid(context)
viewModelScope.launch {
    // Initialize keys (generates or restores ECDSA P-256 key pair)
    service.initialize(userId, udid).getOrElse { return@launch showError(it) }

    // Provision with backend if not already done
    if (!service.isProvisioned(userId, udid).getOrDefault(false)) {
        val provision = service.provisionPhoneKey(userId, udid).getOrElse { return@launch showError(it) }
        service.generateBulkAcls(provision.keyId!!, userId, udid)
    }
}

// 3. On BLE connection — retrieve cached ACL and write to lock
val aclEnvelope = service.getAcl(userId, udid, lockMac).getOrNull() ?: return
val aclBytes = android.util.Base64.decode(aclEnvelope.acl, android.util.Base64.DEFAULT)
writeToGattCharacteristic(gatt, ION_PROVISION_CHAR_UUID, aclBytes)
```

---

## Data Flow

```
1. App calls PhoneKeyAccessService.initialize(userId, deviceId)
   ↓
2. PhoneKeyAccessService delegates to PhoneKeyCoreClient
   ↓
3. PhoneKeyCoreClient generates ECDSA P-256 key pair in Android Keystore
   ↓
4. App calls PhoneKeyAccessService.provisionPhoneKey(userId, udid)
   ↓
5. PhoneKeyCoreClient sends publicKey to backend via YOUR API implementation
   ↓
6. Backend returns phoneKeyId → stored in EncryptedSharedPreferences
   ↓
7. App calls PhoneKeyAccessService.generateBulkAcls(phoneKeyId, userId, udid)
   ↓
8. PhoneKeyCoreClient fetches all ACLs from backend via YOUR API implementation
   ↓
9. ACLs stored in EncryptedSharedPreferences per lock MAC
   ↓
10. On BLE connection: retrieve ACL envelope via PhoneKeyFacade.getACL(userId, lockMac)
    ↓ Decode Base64 ACL bytes and write to lock over BLE GATT characteristic
```

---

## Architecture Mapping (iOS → Android)

| iOS Component                              | Android Equivalent                            | Notes                                     |
| ------------------------------------------ | --------------------------------------------- | ----------------------------------------- |
| `PhoneKeyFacade.shared`                    | `PhoneKeyAccessService.getInstance()`         | Singleton pattern, same intent            |
| `PhoneKeyAccessService`                    | `PhoneKeyAccessService`                       | Orchestrates operations, identical naming |
| `PhoneKeyCoreClient` (protocol)            | `PhoneKeyCoreClient` (interface)              | **YOU implement this**                    |
| `PhoneKeyPersistence` (protocol)           | `PhoneKeyPersistence` (interface)             | Optional custom storage                   |
| `DefaultPhoneKeyPersistence`               | `DefaultPhoneKeyPersistence`                  | Default keychain/keystore storage         |
| iOS Keychain                               | Android Keystore + EncryptedSharedPreferences | Platform-appropriate secure storage       |
| `completion: @escaping (Result<T, Error>)` | `suspend fun: Result<T>`                      | Kotlin coroutines instead of closures     |
| GCD serial queue                           | `Dispatchers.IO` + `Mutex`                    | Structured concurrency                    |
| `UIDevice.current.identifierForVendor`     | `Settings.Secure.ANDROID_ID`                  | Device UDID equivalent                    |
| `BulkPhoneKeyAcl`                          | `BulkAclEnvelope`                             | Same data structure, different naming     |
| `PhoneKeyInfoResponse.keyId: Int?`         | `PhoneKeyInfoResponse.keyId: Int?`            | ✅ Identical                              |

### Component Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│                     Your Application                         │
└────────────────────┬────────────────────────────────────────┘
                     │
        ┌────────────▼────────────┐
        │   PhoneKeyAccessService │ ◄── High-level coroutine API
        │   (Public Singleton)    │     for provisioning & ACL management
        └────────────┬────────────┘
                     │
        ┌────────────▼──────────────────────────┐
        │   PhoneKeyCoreClient (interface)      │ ◄── Interface YOU implement
        │   (Your Implementation)               │     to connect to your backend API
        └────────────┬──────────────────────────┘
                     │
        ┌────────────▼─────────────┐
        │   PhoneKeyFacade         │ ◄── Local cache access
        │   (Local Storage API)    │     cached ACLs & provisioning info
        └────────────┬─────────────┘
                     │
        ┌────────────▼──────────────┐
        │   PhoneKeyPersistence     │ ◄── Storage interface
        │   (Default or Custom)     │     EncryptedSharedPreferences by default
        └───────────────────────────┘
```

---

## Platform-Specific Decisions

### 1. Key Storage: Keychain → Android Keystore + EncryptedSharedPreferences

**iOS:** Private keys stored in iOS Keychain (OS-managed, device-bound encryption).

**Android:** Private keys are generated **inside Android Keystore** — they physically never leave the secure hardware. They cannot be exported or read from outside the Keystore.

```kotlin
// Private key STAYS in Keystore, we only use it via Signature API
val keyPairGenerator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
keyPairGenerator.initialize(
    KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(false) // No biometric required
        .build()
)
```

ACLs and provisioning metadata are stored in **EncryptedSharedPreferences** (AES-256-GCM, master key in Keystore).

### 2. Threading: GCD → Kotlin Coroutines

**iOS:** Uses `DispatchQueue` with serial queues and `@escaping` closures.

**Android:** Uses Kotlin coroutines (`suspend` functions) with `Dispatchers.IO` and `Mutex` for serialization.

```kotlin
// iOS: Completion handler
PhoneKeyFacade.shared.ensureProvisioned(userId: userId, deviceId: deviceId) { result in ... }

// Android: Suspend function in coroutine scope
viewModelScope.launch {
    val result = service.provisionPhoneKey(userId, deviceId)
    result.fold(
        onSuccess = { /* update UI */ },
        onFailure = { /* handle error */ }
    )
}
```

### 3. Error Handling: Swift throws/Error → Kotlin sealed class

**iOS:** `NokeMobileLibraryError` as a Swift `enum` conforming to `Error`.

**Android:** `NokeMobileLibraryError` as a Kotlin `sealed class` extending `Exception`.

```kotlin
// Android: Exhaustive when-expression on sealed class
when (error) {
    is NokeMobileLibraryError.NotInitialized -> { /* ... */ }
    is NokeMobileLibraryError.ProvisioningFailed -> { /* error.reason */ }
    is NokeMobileLibraryError.AclFetchFailed -> { /* error.lockMac, error.reason */ }
    is NokeMobileLibraryError.NetworkError -> { /* error.underlying */ }
    else -> { /* catch-all */ }
}
```

### 4. Device UDID: identifierForVendor → ANDROID_ID

**iOS:** `UIDevice.current.identifierForVendor?.uuidString`

**Android:** `Settings.Secure.ANDROID_ID` — unique per device and app signing key. Resets on factory reset but not on app reinstall.

```kotlin
val udid = PhoneKeyAccessService.getDeviceUdid(context)
// Equivalent to: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
```

### 5. Result Type: Swift Result<T, Error> → Kotlin Result<T>

**iOS:** `Result<T, Error>` with `.success` / `.failure` cases.

**Android:** `kotlin.Result<T>` with `.fold { onSuccess, onFailure }`. Errors are `NokeMobileLibraryError` instances.

### 6. Reactive Patterns: Combine → Kotlin Flow

**iOS:** Combine framework (`Publisher`, `@Published`).

**Android:** Kotlin Flow (`Flow<T>`, `StateFlow<T>`, `SharedFlow<T>`) with lifecycle-aware collection.

```kotlin
// Use viewModelScope + StateFlow for reactive UI
class ProvisionViewModel : ViewModel() {
    val provisioningState: StateFlow<ProvisioningState> =
        PhoneKeyStateMonitor(service, viewModelScope).provisioningState
}
```

---

## Core Components

### 1. PhoneKeyAccessService (Main Public API)

**Purpose:** High-level coroutine-based facade for all phone key operations.

**When to Use:**

- ✅ All provisioning operations
- ✅ All ACL generation and retrieval
- ✅ Cleanup on logout
- ✅ Checking provisioning status

**Setup (Required):** Must call `setSharedClient()` before use.

**Key Methods:**

```kotlin
class PhoneKeyAccessService {
    companion object {
        // Setup (required once in Application.onCreate)
        fun setSharedClient(client: PhoneKeyCoreClient, context: Context)

        // Access singleton
        fun getInstance(): PhoneKeyAccessService

        // Get device UDID
        fun getDeviceUdid(context: Context): String
    }

    // Core operations
    suspend fun initialize(userId: String, deviceId: String): Result<String>
    suspend fun provisionPhoneKey(userId: String, udid: String): Result<PhoneKeyInfoResponse>
    suspend fun generateBulkAcls(phoneKeyId: Int, userId: String, udid: String): Result<BulkAclResult>
    suspend fun generateAcl(userId: Int, lockMac: String, phoneKeyId: Int, udid: String): Result<Unit>

    // Local queries
    suspend fun isProvisioned(userId: String, deviceId: String): Result<Boolean>
    suspend fun getPhoneKeyId(userId: String, deviceId: String): Result<Int>
    suspend fun getAcl(userId: String, udid: String, lockMac: String): Result<BulkAclEnvelope?>
    suspend fun hasCachedAcl(userId: String, udid: String, lockMac: String): Result<Boolean>

    // Lifecycle
    suspend fun cleanupAclsForUser(userId: String, deviceId: String): Result<Unit>
    fun clearManagerInstance(userId: String, deviceId: String)
    val isInitialized: Boolean
}
```

---

### 2. PhoneKeyCoreClient (Interface — YOU IMPLEMENT)

**Purpose:** Abstraction layer connecting `PhoneKeyAccessService` to your backend API.

**Implementation Required:** ✅ **YES — You must implement this interface.**

This is the primary integration point. Implement it using your preferred networking library (OkHttp, Retrofit, Fuel, Ktor, etc.).

```kotlin
interface PhoneKeyCoreClient {

    // REQUIRED: Generate/restore keys, returns Base64 public key
    suspend fun initialize(userId: String, deviceId: String): Result<String>

    // REQUIRED: Whether initialize() succeeded
    val isInitialized: Boolean

    // REQUIRED: Register public key with backend, returns PhoneKeyInfoResponse with keyId
    suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse>

    // REQUIRED: Generate single ACL for a specific lock
    suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        udid: String
    ): Result<Unit>

    // REQUIRED: Generate bulk ACLs for all accessible locks
    suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        deviceId: String
    ): Result<BulkAclResult>

    // REQUIRED: Return current public key if initialized, null otherwise
    suspend fun validateCurrentKey(userId: String, deviceId: String): String?
}
```

---

### 3. PhoneKeyFacade (Local Storage Access)

**Purpose:** Direct access to cached ACLs and provisioning info stored on-device.

**When to Use:**

- ✅ Check provisioning status without a network call
- ✅ Retrieve cached ACLs before BLE connection
- ✅ Validate cached ACL expiration

```kotlin
val facade = PhoneKeyFacade.getInstance(context)

// Check provisioning
val info = facade.getPhoneKeyInfo(userId, deviceId)  // null if not provisioned

// Get cached ACL for a lock
val acl = facade.getACL(userId, lockMac)  // null if not cached

// List all valid ACLs
val validAcls = facade.listValidACLs()

// Check if a specific lock has a valid cached ACL
val hasCached = facade.hasCachedAcl(userId, deviceId, lockMac)

// Clear all data (logout)
facade.clearAll(userId)
```

---

### 4. PhoneKeyPersistence (Interface — Optional Custom Implementation)

**Purpose:** Storage abstraction for phone key data and ACLs.

**Default Implementation:** `DefaultPhoneKeyPersistence` using `EncryptedSharedPreferences`.

**Custom Implementation:** Only needed if you require a different storage backend (Room, Realm, etc.).

```kotlin
interface PhoneKeyPersistence {
    fun getPhoneKeyInfo(userId: String, deviceId: String): PhoneKeyInfoResponse?
    fun savePhoneKeyInfo(userId: String, deviceId: String, info: PhoneKeyInfoResponse)
    fun deletePhoneKeyInfo(userId: String, deviceId: String)

    fun getACL(userId: String, lockMac: String): BulkAclEnvelope?
    fun listACLs(): List<BulkAclEnvelope>
    fun saveACL(userId: String, lockMac: String, acl: BulkAclEnvelope)
    fun deleteACL(userId: String, lockMac: String)
    fun deleteAllACLs()
}
```

---

### 5. PhoneKeyStateMonitor (Reactive State)

**Purpose:** Observable state for provisioning and ACL operations via Kotlin Flow.

**When to Use:**

- ✅ Building reactive UI that reacts to provisioning steps
- ✅ ViewModel integration with `StateFlow`

```kotlin
val monitor = PhoneKeyStateMonitor(service, coroutineScope)

// Observe provisioning state
monitor.provisioningState.collect { state ->
    when (state) {
        is ProvisioningState.Idle -> showIdle()
        is ProvisioningState.Checking -> showProgress("Checking...")
        is ProvisioningState.Provisioning -> showProgress("Setting up...")
        is ProvisioningState.Provisioned -> navigateToLockList()
        is ProvisioningState.AlreadyProvisioned -> navigateToLockList()
        is ProvisioningState.Error -> showError(state.error)
    }
}

// Start provisioning
monitor.provisionDevice(userId, deviceId)
```

---

## Required Implementation

### Step 1: Implement PhoneKeyCoreClient

This is the **only required implementation**. Create a class that implements `PhoneKeyCoreClient` using your app's networking layer and backend integration.

See `TEMPLATE_PhoneKeyCoreClient.kt` at the root of the repository for a full annotated example.

```kotlin
import android.content.Context
import android.util.Log
import com.noke.nokemobilelibrary.phonekey.PhoneKeyCoreClient
import com.noke.nokemobilelibrary.phonekey.internal.PhoneKeyManager
import com.noke.nokemobilelibrary.phonekey.internal.SecurityService
import com.noke.nokemobilelibrary.phonekey.internal.SecurityServiceImpl
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MyPhoneKeyCoreClient(
    private val context: Context,
    private val baseUrl: String,
    private val authTokenProvider: () -> String,
    private val userUuidProvider: () -> String
) : PhoneKeyCoreClient {

    private val mutex = Mutex()
    private var _isInitialized = false

    // NOTE: Manager instances are per (userId, deviceId) via getInstance()
    private fun getManager(userId: String, deviceId: String): PhoneKeyManager {
        val securityService: SecurityService = SecurityServiceImpl(
            context = context,
            baseUrl = baseUrl,
            authTokenProvider = authTokenProvider,
            userUuidProvider = userUuidProvider
        )
        return PhoneKeyManager.getInstance(context, userId, deviceId, securityService)
    }

    override val isInitialized: Boolean
        get() = _isInitialized

    override suspend fun initialize(userId: String, deviceId: String): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (_isInitialized) {
                    val manager = getManager(userId, deviceId)
                    return@withContext Result.success(manager.getPublicKeyBase64())
                }

                runCatching {
                    val manager = getManager(userId, deviceId)
                    manager.ensureKeys()
                    _isInitialized = true
                    manager.getPublicKeyBase64()
                }
            }
        }

    override suspend fun provisionPhoneKey(
        userId: String,
        deviceId: String,
        publicKey: String
    ): Result<PhoneKeyInfoResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = getManager(userId, deviceId)
            // Coroutine wrapper provided by PhoneKeyManagerExtensions.kt
            manager.provisionPhoneKeySuspend(userId, deviceId)
                .getOrThrow()
        }
    }

    override suspend fun generateAcl(
        userId: Int,
        lockMac: String,
        phoneKeyId: Int,
        udid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = getManager(userId.toString(), udid)
            manager.getAclSuspend(userId, lockMac, phoneKeyId, udid)
                .getOrThrow()
        }
    }

    override suspend fun generateBulkAcls(
        phoneKeyId: Int,
        userId: String,
        deviceId: String
    ): Result<BulkAclResult> = withContext(Dispatchers.IO) {
        val manager = getManager(userId, deviceId)
        manager.getBulkAclsSuspend(phoneKeyId)
    }

    override suspend fun validateCurrentKey(userId: String, deviceId: String): String? {
        return runCatching {
            getManager(userId, deviceId).getPublicKeyBase64()
        }.getOrNull()
    }
}
```

### Step 2: Initialize in Application.onCreate()

```kotlin
import android.app.Application
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Phone Key system
        val client = MyPhoneKeyCoreClient(
            context = this,
            baseUrl = "https://router.smartentry.noke.com/",
            authTokenProvider = { sessionManager.getAuthToken() },
            userUuidProvider = { sessionManager.getUserUuid() }
        )
        PhoneKeyAccessService.setSharedClient(client, this)
    }
}
```

---

## Optional Customization

### Custom Persistence

If you require a custom storage backend (Room, Realm, encrypted DB), implement `PhoneKeyPersistence`:

```kotlin
class MyRoomPersistence(
    private val database: AppDatabase
) : PhoneKeyPersistence {

    override fun getPhoneKeyInfo(userId: String, deviceId: String): PhoneKeyInfoResponse? {
        return database.phoneKeyDao().get(userId, deviceId)?.toResponse()
    }

    override fun savePhoneKeyInfo(userId: String, deviceId: String, info: PhoneKeyInfoResponse) {
        database.phoneKeyDao().upsert(info.toEntity(userId, deviceId))
    }

    // ... implement all interface methods
}

// Use custom persistence with PhoneKeyFacade
val facade = PhoneKeyFacade.getInstance(context, MyRoomPersistence(database))
```

---

## View Model / State Management

### MVVM with Coroutines

```kotlin
class ProvisioningViewModel(
    private val service: PhoneKeyAccessService = PhoneKeyAccessService.getInstance()
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setup(userId: String, context: Context) {
        val deviceId = PhoneKeyAccessService.getDeviceUdid(context)

        viewModelScope.launch {
            _uiState.value = UiState.Loading("Checking status...")

            // Step 1: Initialize keys
            service.initialize(userId, deviceId).onFailure {
                _uiState.value = UiState.Error(it.message ?: "Init failed")
                return@launch
            }

            // Step 2: Provision if needed
            val isProvisioned = service.isProvisioned(userId, deviceId).getOrDefault(false)
            if (!isProvisioned) {
                _uiState.value = UiState.Loading("Provisioning device...")

                val provisionResult = service.provisionPhoneKey(userId, deviceId)
                provisionResult.onFailure {
                    _uiState.value = UiState.Error(it.message ?: "Provision failed")
                    return@launch
                }
            }

            // Step 3: Fetch ACLs
            _uiState.value = UiState.Loading("Loading lock access...")
            val phoneKeyId = service.getPhoneKeyId(userId, deviceId).getOrNull()
                ?: run {
                    _uiState.value = UiState.Error("Phone key ID not found")
                    return@launch
                }

            service.generateBulkAcls(phoneKeyId, userId, deviceId).fold(
                onSuccess = { result ->
                    _uiState.value = UiState.Success(result.successCount)
                },
                onFailure = {
                    _uiState.value = UiState.Error(it.message ?: "ACL fetch failed")
                }
            )
        }
    }

    sealed class UiState {
        object Idle : UiState()
        data class Loading(val message: String) : UiState()
        data class Success(val lockCount: Int) : UiState()
        data class Error(val message: String) : UiState()
    }
}
```

### Reactive with PhoneKeyStateMonitor

```kotlin
class ProvisionViewModel(
    private val service: PhoneKeyAccessService = PhoneKeyAccessService.getInstance()
) : ViewModel() {

    private val monitor = PhoneKeyStateMonitor(service, viewModelScope)

    // Expose state for UI
    val provisioningState: StateFlow<ProvisioningState> = monitor.provisioningState
    val aclState: StateFlow<AclState> = monitor.aclState
    val isSetupComplete: Flow<Boolean> = monitor.isSetupComplete

    fun provision(userId: String, deviceId: String) {
        viewModelScope.launch {
            monitor.provisionDevice(userId, deviceId)
        }
    }
}

// In Activity/Fragment
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.provisioningState.collect { state ->
            when (state) {
                is ProvisioningState.Idle -> binding.progressGroup.isVisible = false
                is ProvisioningState.Checking -> binding.statusText.text = "Checking..."
                is ProvisioningState.Provisioning -> binding.statusText.text = "Setting up..."
                is ProvisioningState.Provisioned -> navigateToLocks()
                is ProvisioningState.AlreadyProvisioned -> navigateToLocks()
                is ProvisioningState.Error -> showError(state.error.message)
            }
        }
    }
}
```

### Reactive with Flow Extensions

```kotlin
// In ViewModel
fun setupWithFlow(userId: String, deviceId: String) {
    viewModelScope.launch {
        service.completeProvisioningWorkflowFlow(userId, deviceId)
            .collect { event ->
                when (event) {
                    is ProvisioningEvent.CheckingStatus ->
                        _status.value = "Checking provisioning status..."
                    is ProvisioningEvent.AlreadyProvisioned ->
                        _status.value = "Already set up"
                    is ProvisioningEvent.Provisioning ->
                        _status.value = "Setting up phone key..."
                    is ProvisioningEvent.Provisioned ->
                        _status.value = "Phone key ready (ID: ${event.phoneKeyId})"
                    is ProvisioningEvent.FetchingAcls ->
                        _status.value = "Loading lock access..."
                    is ProvisioningEvent.AclsFetched ->
                        _status.value = "${event.result.successCount} locks ready"
                    is ProvisioningEvent.Error ->
                        _status.value = "Error: ${event.error.message}"
                }
            }
    }
}
```

---

## Complete Integration Example

```kotlin
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import kotlinx.coroutines.launch

class LockActivity : AppCompatActivity() {

    private val service = PhoneKeyAccessService.getInstance()
    private lateinit var userId: String
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        userId = sessionManager.getUserId()
        deviceId = PhoneKeyAccessService.getDeviceUdid(this)

        setupPhoneKey()
    }

    private fun setupPhoneKey() {
        lifecycleScope.launch {
            // Step 1: Initialize keys
            val initResult = service.initialize(userId, deviceId)
            if (initResult.isFailure) {
                showError("Key initialization failed: ${initResult.exceptionOrNull()?.message}")
                return@launch
            }

            // Step 2: Check if already provisioned
            val provisioned = service.isProvisioned(userId, deviceId).getOrDefault(false)

            if (!provisioned) {
                showProgress("Provisioning device...")

                // Step 3: Provision with backend
                val provisionResult = service.provisionPhoneKey(userId, deviceId)
                provisionResult.fold(
                    onSuccess = { response ->
                        if (response.isSuccess) {
                            showProgress("Fetching lock access...")
                            fetchAcls(response.keyId!!)
                        } else {
                            showError("Provisioning error: ${response.statusMessage}")
                        }
                    },
                    onFailure = { showError("Provisioning failed: ${it.message}") }
                )
            } else {
                // Step 4: Already provisioned — refresh ACLs
                val phoneKeyId = service.getPhoneKeyId(userId, deviceId).getOrNull()
                if (phoneKeyId != null) {
                    showProgress("Refreshing lock access...")
                    fetchAcls(phoneKeyId)
                } else {
                    showError("Phone key ID not found — re-provisioning required")
                }
            }
        }
    }

    private suspend fun fetchAcls(phoneKeyId: Int) {
        service.generateBulkAcls(phoneKeyId, userId, deviceId).fold(
            onSuccess = { result ->
                hideProgress()
                showSuccess("${result.successCount} locks ready")
                startLockDiscovery()
            },
            onFailure = { showError("ACL fetch failed: ${it.message}") }
        )
    }

    // Called when ION-2 lock is connected over BLE
    private fun onIon2LockConnected(lockMac: String, gatt: BluetoothGatt) {
        lifecycleScope.launch {
            // Check for valid cached ACL
            val hasCachedAcl = service.hasCachedAcl(userId, deviceId, lockMac)
                .getOrDefault(false)

            if (!hasCachedAcl) {
                // Fetch ACL for this specific lock
                val phoneKeyId = service.getPhoneKeyId(userId, deviceId).getOrNull()
                    ?: return@launch

                service.generateAcl(userId.toInt(), lockMac, phoneKeyId, deviceId)
            }

            // Get ACL envelope for BLE transmission
            val aclEnvelope = service.getAcl(userId, deviceId, lockMac).getOrNull()
                ?: return@launch

            // Decode ACL bytes and write to lock over BLE
            val aclBytes = android.util.Base64.decode(aclEnvelope.acl, android.util.Base64.DEFAULT)
            writeAclToLock(gatt, aclBytes)
        }
    }

    fun logout() {
        lifecycleScope.launch {
            // 1. Clean up ACLs and provisioning data
            service.cleanupAclsForUser(userId, deviceId)

            // 2. Clear in-memory singleton cache
            service.clearManagerInstance(userId, deviceId)
        }
    }
}
```

---

## API Reference

### Data Models

#### PhoneKeyInfoResponse

```kotlin
data class PhoneKeyInfoResponse(
    val keyId: Int?,           // Phone key ID assigned by backend (null on failure)
    val status: String,        // "success", "error", etc.
    val error: String?,        // Error message if failed
    val detail: String?        // Additional detail
) {
    val isSuccess: Boolean     // true if keyId != null && error == null
    val statusMessage: String  // First non-null of: error, detail, status
}
```

#### BulkAclResult

```kotlin
data class BulkAclResult(
    val successCount: Int,     // ACLs stored successfully
    val totalCount: Int        // Total ACLs returned from backend
) {
    val isFullSuccess: Boolean    // successCount == totalCount && totalCount > 0
    val hasPartialSuccess: Boolean // 0 < successCount < totalCount
    val isFailure: Boolean        // successCount == 0 && totalCount > 0
    val isEmpty: Boolean          // totalCount == 0 (no locks accessible)
}
```

#### BulkAclEnvelope

```kotlin
data class BulkAclEnvelope(
    val acl: String,           // Base64-encoded ACL bytes for BLE transmission
    val signature: String,     // Server signature for verification
    val lockMac: String,       // Target lock MAC address
    val phoneKeyId: Int,       // Associated phone key ID
    val expiresAt: Long,       // Unix timestamp (seconds) of expiration
    val issuedAt: Long         // Unix timestamp (seconds) when issued
) {
    val isValid: Boolean       // expiresAt > now
    val isExpired: Boolean     // expiresAt <= now
}
```

### Flow Extensions (PhoneKeyFlowExtensions.kt)

```kotlin
// Provision phone key as Flow (single result)
PhoneKeyAccessService.provisionPhoneKeyFlow(userId, udid): Flow<Result<PhoneKeyInfoResponse>>

// Check provisioning as Flow
PhoneKeyAccessService.isProvisionedFlow(userId, udid): Flow<Result<Boolean>>

// Get phone key ID as Flow
PhoneKeyAccessService.getPhoneKeyIdFlow(userId, udid): Flow<Result<Int>>

// Generate bulk ACLs as Flow
PhoneKeyAccessService.generateBulkAclsFlow(phoneKeyId, userId, udid): Flow<Result<BulkAclResult>>

// Full provisioning workflow with multiple events
PhoneKeyAccessService.completeProvisioningWorkflowFlow(userId, udid): Flow<ProvisioningEvent>

// Continuous ACL polling
PhoneKeyAccessService.pollAclUpdatesFlow(userId, udid, intervalMillis): Flow<Result<BulkAclResult>>
```

### ProvisioningEvent (sealed class)

```kotlin
sealed class ProvisioningEvent {
    object CheckingStatus : ProvisioningEvent()       // Checking if provisioned
    object AlreadyProvisioned : ProvisioningEvent()   // Already provisioned (skipping)
    object Provisioning : ProvisioningEvent()          // Provisioning now
    data class Provisioned(val phoneKeyId: Int) : ProvisioningEvent()  // Provisioned successfully
    object FetchingAcls : ProvisioningEvent()          // Fetching ACLs
    data class AclsFetched(val result: BulkAclResult) : ProvisioningEvent()
    data class Error(val error: Throwable) : ProvisioningEvent()
}
```

---

## Error Handling

### NokeMobileLibraryError Sealed Class

```kotlin
sealed class NokeMobileLibraryError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object NotInitialized : NokeMobileLibraryError(...)
    data class InvalidInput(val reason: String) : NokeMobileLibraryError(...)
    data class InvalidConfiguration(val reason: String) : NokeMobileLibraryError(...)
    data class ProvisioningFailed(val reason: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class AclFetchFailed(val lockMac: String, val reason: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class BulkAclFetchFailed(val phoneKeyId: Int, val reason: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class AclStorageFailed(val lockMac: String, val reason: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class StorageError(val reason: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class CryptographicError(val reason: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class NetworkError(val statusCode: Int? = null, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
    data class UnknownError(val operation: String, val underlying: Throwable? = null) : NokeMobileLibraryError(...)
}
```

### Error Handling Best Practices

```kotlin
service.provisionPhoneKey(userId, deviceId).fold(
    onSuccess = { response ->
        if (response.isSuccess) {
            proceed(response.keyId!!)
        } else {
            showError("Server error: ${response.statusMessage}")
        }
    },
    onFailure = { error ->
        when (error) {
            is NokeMobileLibraryError.NotInitialized ->
                // Call setSharedClient() in Application.onCreate()
                Log.e(TAG, "Library not initialized")

            is NokeMobileLibraryError.ProvisioningFailed ->
                // Retry with exponential backoff
                Log.e(TAG, "Provisioning failed: ${error.reason}", error.underlying)

            is NokeMobileLibraryError.NetworkError ->
                // Show "Check internet connection"
                showError("Network error (${error.statusCode}): check internet connection")

            is NokeMobileLibraryError.CryptographicError ->
                // Keystore issue — may need to clear and re-provision
                Log.e(TAG, "Keystore error: ${error.reason}")

            else ->
                Log.e(TAG, "Unexpected error: ${error.message}")
        }
    }
)
```

---

## Edge Cases / Considerations

### 1. Per-Device Storage Isolation

Each `(userId, deviceId)` pair gets completely isolated:

- A separate Android Keystore key alias: `noke_phone_key_pair_{userId}_{udid}`
- A separate MasterKey alias: `noke_master_key_{userId}_{udid}`
- A separate EncryptedSharedPreferences file: `phone_keys_{userId}_{udid}`

**Implication:** A user signing in on a different device must re-provision.

### 2. Singleton PhoneKeyManager Cache

`PhoneKeyManager.getInstance()` uses a per-`(userId, udid)` singleton cache. This is critical because `EncryptedSharedPreferences` has in-memory caching — two separate instances pointing to the same file will not share cache updates.

**Always use `getInstance()` — never create `PhoneKeyManager` directly in production.**

```kotlin
// ✅ Correct
val manager = PhoneKeyManager.getInstance(context, userId, udid, securityService)

// ❌ Wrong — breaks EncryptedSharedPreferences cache coherency
val manager = PhoneKeyManager(context, userId, udid, securityService)
```

### 3. Logout Cleanup Order

Always clean up in this specific order to prevent stale cache:

```kotlin
// 1. First cleanup ACLs and provisioning data from EncryptedSharedPreferences
service.cleanupAclsForUser(userId, deviceId)

// 2. Then clear the singleton instance cache
service.clearManagerInstance(userId, deviceId)
```

### 4. ACL Expiration

ACLs have a server-assigned `expiresAt` timestamp. Before attempting BLE unlock:

```kotlin
val hasCached = service.hasCachedAcl(userId, deviceId, lockMac).getOrDefault(false)
if (!hasCached) {
    // Fetch fresh ACL before connecting
    service.generateAcl(userId.toInt(), lockMac, phoneKeyId, deviceId)
}
```

### 5. BLE ACL Transmission

Retrieve the cached ACL envelope and pass the decoded bytes directly to the lock over BLE:

```kotlin
// Get ACL envelope from cache
val envelope = service.getAcl(userId, deviceId, lockMac).getOrNull() ?: return

// Decode base64 ACL bytes and write to the BLE GATT characteristic
val aclBytes = Base64.decode(envelope.acl, Base64.DEFAULT)
writeToGattCharacteristic(gatt, ION_PROVISION_CHAR_UUID, aclBytes)
```

The lock handles signing verification on its end using the ACL signature already embedded in the envelope returned by the backend.

### 6. API Parameter Naming

Android uses **snake_case** in JSON request bodies:

| Field           | Android JSON   | Notes                                     |
| --------------- | -------------- | ----------------------------------------- |
| User ID         | `"user_id"`    | Backend contract — confirm with your team |
| Phone UDID      | `"phone_udid"` | —                                         |
| Public Key      | `"public_key"` | —                                         |
| Response Key ID | `"key_id"`     | Returned from backend                     |

### 7. Thread Safety

All `suspend` functions in `PhoneKeyAccessService` are thread-safe. However:

- Do NOT access `PhoneKeyFacade` from multiple threads without `Mutex`
- `DefaultPhoneKeyPersistence` uses synchronized blocks internally
- Implement proper thread safety in your `PhoneKeyCoreClient` implementation

---

## Testing Guide

### Unit Testing Your Client Implementation

```kotlin
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertTrue

class PhoneKeyCoreClientTest {

    private val mockApiClient = mockk<MyApiClient>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = MyPhoneKeyCoreClient(context, "https://test.api.com/", { "token" }, { "uuid" })

    @Test
    fun `initialize returns public key`() = runTest {
        val result = client.initialize("userId", "deviceId")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.isNotEmpty() == true)
    }

    @Test
    fun `provisionPhoneKey returns keyId on success`() = runTest {
        coEvery { mockApiClient.provision(any()) } returns PhoneKeyInfoResponse(keyId = 42, status = "success")

        val result = client.provisionPhoneKey("userId", "deviceId", "publicKey")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.isSuccess == true)
        assert(result.getOrNull()?.keyId == 42)
    }
}
```

### Testing PhoneKeyAccessService with Mock Client

```kotlin
class PhoneKeyAccessServiceTest {

    private val mockClient = object : PhoneKeyCoreClient {
        override val isInitialized = true
        override suspend fun initialize(userId: String, deviceId: String) =
            Result.success("mockPublicKey")
        override suspend fun provisionPhoneKey(userId: String, deviceId: String, publicKey: String) =
            Result.success(PhoneKeyInfoResponse(keyId = 1, status = "success"))
        override suspend fun generateBulkAcls(phoneKeyId: Int, userId: String, deviceId: String) =
            Result.success(BulkAclResult(successCount = 5, totalCount = 5))
        override suspend fun generateAcl(userId: Int, lockMac: String, phoneKeyId: Int, udid: String) =
            Result.success(Unit)
        override suspend fun validateCurrentKey(userId: String, deviceId: String) = "mockPublicKey"
    }

    @Before
    fun setup() {
        PhoneKeyAccessService.setSharedClient(mockClient, context)
    }

    @Test
    fun `full provisioning flow succeeds`() = runTest {
        val service = PhoneKeyAccessService.getInstance()
        val udid = "testDevice"

        val initResult = service.initialize("user1", udid)
        assertTrue(initResult.isSuccess)

        val provisionResult = service.provisionPhoneKey("user1", udid)
        assertTrue(provisionResult.isSuccess)
        assert(provisionResult.getOrNull()?.keyId == 1)

        val bulkResult = service.generateBulkAcls(1, "user1", udid)
        assertTrue(bulkResult.isSuccess)
        assert(bulkResult.getOrNull()?.isFullSuccess == true)
    }
}
```

---

## Best Practices

### 1. Initialization

✅ **DO:**

- Call `setSharedClient()` in `Application.onCreate()`
- Use a single instance of your `PhoneKeyCoreClient` implementation
- Call `initialize(userId, deviceId)` before any provisioning or ACL operations

❌ **DON'T:**

- Create multiple instances of `PhoneKeyCoreClient`
- Call provisioning methods before setting the shared client
- Create `PhoneKeyManager` directly (always use `getInstance()`)

### 2. Data Management

✅ **DO:**

- Use `hasCachedAcl()` before BLE connection to check if ACL fetch is needed
- Call `cleanupAclsForUser()` + `clearManagerInstance()` on logout (in that order)
- Use `generateBulkAcls()` at app startup to pre-fetch all ACLs

❌ **DON'T:**

- Fetch individual ACLs per lock when bulk fetch covers all accessible locks
- Use expired ACLs for BLE transmission
- Skip logout cleanup (causes stale data across user sessions)

### 3. Error Handling

✅ **DO:**

- Handle all error cases in `.fold()` or `try/catch`
- Use exhaustive `when` on `NokeMobileLibraryError` subtypes
- Implement retry logic with exponential backoff for network errors
- Log `NokeMobileLibraryError.CryptographicError` as high-priority (may require re-provisioning)

❌ **DON'T:**

- Ignore errors silently
- Log private keys or raw ACL bytes in production
- Retry indefinitely without backoff

### 4. Coroutine Scopes

✅ **DO:**

- Use `viewModelScope` for UI-tied operations
- Use `lifecycleScope.launch { repeatOnLifecycle(STARTED) }` for Flow collection
- Cancel scopes appropriately on `onDestroy()`

❌ **DON'T:**

- Use `GlobalScope` — this leaks if the user navigates away
- Block the main thread with `runBlocking`
- Collect Flows without lifecycle awareness

### 5. Security

✅ **DO:**

- Trust Android Keystore — private keys never leave hardware
- Use `EncryptedSharedPreferences` (default) — do not store ACLs in plain `SharedPreferences`
- Use HTTPS for all API calls in your `PhoneKeyCoreClient`
- Call `cleanupAclsForUser()` on logout

❌ **DON'T:**

- Store private keys in plain `SharedPreferences`
- Log ACL binary data or signatures in production
- Share ACL envelopes between users

---

## FAQ

### Q: Do I need to implement `PhoneKeyPersistence`?

**A:** No. The default `DefaultPhoneKeyPersistence` uses `EncryptedSharedPreferences` with AES-256-GCM encryption backed by Android Keystore. Only implement a custom persistence layer if you need a different storage backend (Room, Realm, encrypted database, etc.).

### Q: What is the difference between `PhoneKeyFacade` and `PhoneKeyAccessService`?

**A:**

- **`PhoneKeyFacade`**: Low-level, local-only access to cached phone key info and ACLs stored on the device. No network calls. Use it to read cached ACLs or provisioning status directly.
- **`PhoneKeyAccessService`**: High-level coroutine API that orchestrates the full provisioning workflow — initializing keys, calling your backend (via `PhoneKeyCoreClient`), and persisting results. This is the primary entry point for most operations.

### Q: How do I handle ACL expiration?

**A:** Check `BulkAclEnvelope.isExpired` or use `hasCachedAcl()` (which only returns `true` for non-expired ACLs). If expired, call `generateAcl()` for a specific lock or `generateBulkAcls()` to refresh all locks at once:

```kotlin
val hasCached = service.hasCachedAcl(userId, udid, lockMac).getOrDefault(false)
if (!hasCached) {
    val phoneKeyId = service.getPhoneKeyId(userId, udid).getOrNull() ?: return
    service.generateAcl(userId.toInt(), lockMac, phoneKeyId, udid)
}
```

### Q: Can I use Java instead of Kotlin?

**A:** Yes. All public APIs in `PhoneKeyAccessService`, `PhoneKeyFacade`, and `PhoneKeyCoreClient` are fully Java-compatible. For `suspend` functions, use the `BuildersKt.launch` or `CoroutineScope` utilities from the Kotlin coroutines Java interop layer, or wrap calls using `ListenableFuture` / `CompletableFuture` adapters.

### Q: Where are keys and ACLs stored on Android?

**A:**

- **Private keys** — Generated and permanently stored inside **Android Keystore** hardware (or software equivalent). They physically never leave the secure enclave and cannot be extracted.
- **ACLs and provisioning metadata** — Stored in **EncryptedSharedPreferences** using AES-256-GCM, with the master key itself stored in Android Keystore. Each `(userId, deviceId)` pair gets its own isolated file.

### Q: Does re-installing the app lose provisioned keys?

**A:** Yes. Android Keystore keys are tied to the app installation. On uninstall (and reinstall), the keys are destroyed and `EncryptedSharedPreferences` is cleared. The user must re-provision. Design your backend to allow re-provisioning gracefully.

### Q: How do I debug provisioning issues?

**A:** Enable Logcat with tags `PhoneKeyAccessService`, `PhoneKeyManager`, and `PhoneKeyCoreClient`. The library logs all major operations:

- Key generation / restoration results
- Backend API request/response status
- ACL storage success or failure

Check for:

- `NokeMobileLibraryError.NotInitialized` — `setSharedClient()` was not called before use
- `NokeMobileLibraryError.CryptographicError` — Keystore issue; may require clearing and re-provisioning
- `NokeMobileLibraryError.NetworkError` — Backend unreachable or returning non-2xx status

### Q: Can multiple users share the same device?

**A:** Yes. Each `(userId, deviceId)` pair is fully isolated — separate Keystore key alias, separate `EncryptedSharedPreferences` file, and separate `PhoneKeyManager` singleton instance. When a user logs out, call:

```kotlin
service.cleanupAclsForUser(userId, deviceId) // clears persisted data
service.clearManagerInstance(userId, deviceId) // clears in-memory cache
```

---

## Support

For questions or issues:

1. Check the [Example App](../app/) for a reference implementation
2. Review `TEMPLATE_PhoneKeyCoreClient.kt` at the repository root for a full annotated implementation example
3. Contact Noke support at support@noke.com for backend API contracts and provisioning endpoint details

---

**Version:** 1.0.0  
**Last Updated:** April 22, 2026  
**License:** Apache 2.0
