# NokeMobileLibrary 0.10.1 — ION 2 (5E) upgrade guide

This guide is for apps upgrading `noke-mobile-library-android` and adding support for **ION 2 locks (hardware type `5E`/`E5`)**. It covers only what is new for ION 2. Locks that are not ION 2 keep working through the existing offline-key flow.

For the full Phone Key reference (all APIs, flows, testing) see [ANDROID_PHONEKEY_INTEGRATION_GUIDE.md](./ANDROID_PHONEKEY_INTEGRATION_GUIDE.md).

## 1. How ION 2 differs
Legacy locks unlock with an **offline key + command**. ION 2 locks have **no offline key**. Instead:
- the phone has its own ECDSA P-256 key pair (stored in the Android Keystore);
- your backend issues a signed **ACL** (access control list) for each user and lock;
- the library sends the ACL and a signed command to the lock over BLE.

The library chooses the path per lock: `noke.getEncryptionType()` is `NokeEncryptionType.SIGNING` for ION 2 locks (`noke.isNokeIon2()`) and encryption for the rest.

## 2. Upgrade steps

### 2.1 Requirements
- **`minSdkVersion 21`**, `compileSdkVersion`/`targetSdkVersion 34`, **Java 17**, Kotlin 1.8.x. (The previous release was minSdk 19 / compileSdk 28.)
- New dependencies, resolved automatically by Gradle: Kotlin stdlib and coroutines, OkHttp, `androidx.security:security-crypto`, Tink, ThreeTenABP, AndroidX AppCompat.
- Library coordinates: `com.noke.nokemobilelibrary:noke-mobile-library:0.10.1`.
- Bluetooth permissions as before (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` on Android 12+).

### 2.2 Update your `NokeServiceListener`
Five methods were added to `NokeServiceListener`. If you extend **`NokeServiceListenerAdapter`** they have empty defaults and nothing breaks; if you implement the interface directly you must add them:

```kotlin
override fun onNokeJammedLocking(noke: NokeDevice) { }
override fun onNokeJammedUnlocking(noke: NokeDevice) { }
override fun shouldUploadData(data: JSONArray) { }
override fun nokeDeviceDidSendDiagnostics(data: JSONObject, noke: NokeDevice) { }
override fun successPacketReceived(noke: NokeDevice) { }
```
`onNokeUnlocked` / `onError` / `onNokeConnected` keep their meaning and also report ION 2 results.

### 2.3 Changed API you may notice
| Changed | What to do |
|---|---|
| `NokeMobileError.ERROR_CONNECTION_TIMEOUT` is now `318` (was `317`) | Compare by constant, never by literal |
| New `ERROR_UNSTABLE_BLUETOOTH_STACK` (`317`) and `ERROR_SIGNING` (`320`) | Handle `ERROR_SIGNING` in `onError` for ION 2 failures |
| New `DEVICE_OUT_OF_SCHEDULE_UNLOCK` (`271`) and `DEVICE_FREE_EXIT_UNLOCK` (`311`) | Optional; reported when the lock returns these results |
| Library `AndroidManifest.xml` no longer declares `package` | Uses `namespace 'com.noke.nokemobilelibrary'` in Gradle |
| Bintray / `jcenter` publishing removed | Use Maven Central or your own repository |

## 3. Adding ION 2 support

### 3.1 Register a `PhoneKeyCoreClient`
The library ships `DefaultPhoneKeyCoreClient`, which manages the Keystore key pair, provisioning and ACL storage for the Noke phone key backend. You only provide the base URL and how to authenticate each request, then register it once at startup:

```kotlin
// Application.onCreate()
val client = DefaultPhoneKeyCoreClient(
    context = applicationContext,
    baseUrl = baseUrl,
    tokenScheme = "ApiKey",                       // header becomes "Authorization: ApiKey <token>"
    authToken = { apiKey },
    extraHeaders = { mapOf("Email" to email, "CompanyId" to companyId, "SiteId" to siteId) },
)
client.prewarm(userId)   // once the user id is known; before any unlock, see 3.3
PhoneKeyAccessService.setSharedClient(client, applicationContext)
```
`checkCachedAcl` / `discardCachedAcl` on the client let you reject a cached ACL that is expired or was issued for a different key before sending it to a lock.

If your backend is entirely different, implement the `PhoneKeyCoreClient` interface yourself (`initialize`, `isInitialized`, `provisionPhoneKey`, `generateAcl`, `generateBulkAcls`, `validateCurrentKey`); see `TEMPLATE_PhoneKeyCoreClient.kt` (repo root).

### 3.2 Choose a stable `deviceId`
Use **one** identifier for the device and the **same value** for provisioning and for every unlock. `PhoneKeyAccessService.getDeviceUdid(context)` returns `Settings.Secure.ANDROID_ID`. It is part of what the phone signs; if it changes, the existing key stops working.

### 3.3 Store the signed-in user and API key for the library
`NokeDeviceManagerService` reads the user from the `SharedPreferences` file `nokeAPILibaryFile` (`MODE_PRIVATE`), key **`user_uuid`**. It must equal the `userId` used when provisioning, otherwise the unlock fails with `ERROR_SIGNING` ("No user logged in"). The `email`, `companyId` and `siteId` keys in the same file are used only for the automatic ACL refetch.

Store the Phone Key gateway API key in encrypted storage:
```kotlin
nokeService.setPhoneKeyApiKey(apiKey)   // encrypted; removes any plaintext "apikey" pref
```

### 3.4 Provision the phone key and cache ACLs (once online)
```kotlin
val service = PhoneKeyAccessService.getInstance()
val udid = PhoneKeyAccessService.getDeviceUdid(context)

service.initialize(userId, udid).getOrThrow()                     // key pair in Android Keystore
if (!service.isProvisioned(userId, udid).getOrDefault(false)) {
    val info = service.provisionPhoneKey(userId, udid).getOrThrow()
    service.generateBulkAcls(info.keyId!!, userId, udid)           // all the user's locks, cached encrypted
}

// One lock (fallback when no valid cached ACL exists)
service.generateAcl(numericUserId, lockMac, phoneKeyId, udid)

// Read from cache
val hasAcl = service.hasCachedAcl(userId, udid, lockMac).getOrDefault(false)
```
Refresh ACLs on login / pull-to-refresh. On logout (or when the signed-in user changes):
```kotlin
service.cleanupAclsForUser(userId, udid)
service.clearManagerInstance(userId, udid)
```

### 3.5 Unlock an ION 2 lock
1. Scan and connect as usual (`NokeDeviceManagerService`, `NokeServiceListener`).
2. Detect ION 2 with `noke.isNokeIon2()`.
3. When `onNokeConnected(noke)` fires, unlock:

```kotlin
if (noke.isNokeIon2) {
    noke.signingOfflineUnlock()          // uses the cached ACL; success -> onNokeUnlocked
} else {
    noke.scheduledOfflineUnlock()        // legacy offline key + command
}
```
Show or hide an "offline unlock" button with `noke.isNokeIon2 && nokeService.hasAclForDevice(noke.mac)` (this does not check ACL expiry).

Unlock options: call `noke.setUnlockOption(NokeUnlockOption.NONE | OVERRIDE | EMERGENCY)` before unlocking. If the user's access requires `OVERRIDE` or `EMERGENCY`, the ACL-based unlock is rejected with `ERROR_SIGNING`.

Add your own timeout (the demo uses 30 s): a lost BLE connection can leave the request without a callback.

## 4. Handling errors
ION 2 failures arrive in `NokeServiceListener.onError(noke, NokeMobileError.ERROR_SIGNING, message)`. `message` is the user-facing description of a `NokeDeviceSigningError`:

| Error (message) | Typical cause | Suggested client action |
|---|---|---|
| `UNLOCK_DENIED` | The ACL does not grant unlock permission | Show the message; verify the user's permissions on the backend; do not retry blindly |
| `ACL_REJECTED`, `INVALID_ACL_SIGNATURE`, `ACL_TIME_INVALID` | ACL expired, invalid or rejected | The library already refetches once and retries; if the error still arrives, call `generateAcl` and retry |
| `OUT_OF_SCHEDULE` | Schedule forbids access now | Show the message |
| `UNLOCK_OVERLOCKED` | Unit in overlock, payment required | Show the message |
| `COMMAND_SIGNATURE_FAILED` / `CMDSIG_VERIFY_FAIL` | Phone signature rejected (often a `deviceId` or key mismatch) | Re-provision the key; check the `deviceId` |
| `TIME_OFFSET_ERROR` | Clock mismatch | Ask the user to check date/time settings |
| `REQUIRES_EMERGENCY_UNLOCK` / `REQUIRES_OVERRIDE_UNLOCK` | Unlock options require a fallback | Use your fallback flow |
| `CHARACTERISTIC_NOT_FOUND`, `PERIPHERAL_NOT_FOUND` | BLE problem | Retry the connection |
| "No user logged in", "Phone not provisioned", "No ACL available for device" | Missing `user_uuid`, provisioning or cached ACL | Fix the setup in §3.3–3.4 |

Library-level (Phone Key) errors are the sealed class `NokeMobileLibraryError`; handle them with an exhaustive `when`.

## 5. Checklist
- [ ] `minSdkVersion` ≥ 21, Java 17, `compileSdk` 34; dependencies resolve.
- [ ] Listener extends `NokeServiceListenerAdapter` (or implements the new methods).
- [ ] Error-code comparisons use the `NokeMobileError` constants (`ERROR_CONNECTION_TIMEOUT` value changed).
- [ ] `DefaultPhoneKeyCoreClient` (or your own `PhoneKeyCoreClient`) registered with `PhoneKeyAccessService.setSharedClient`.
- [ ] Stable `deviceId` used for provisioning and every unlock.
- [ ] `user_uuid` stored in `nokeAPILibaryFile`; API key set with `setPhoneKeyApiKey`.
- [ ] Phone key provisioned and ACLs fetched; cache cleared on logout / user change.
- [ ] ION 2 locks unlocked with `signingOfflineUnlock()` after `onNokeConnected`.
- [ ] `ERROR_SIGNING` mapped to user messages and ACL refresh logic.
- [ ] A timeout wraps each unlock attempt.
