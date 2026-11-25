# NokeMobileLibrary (Android) — ION 2 technical overview and quality review

Scope: only the code added for ION 2 (hardware `5E`) on branch `feature/ION2-Unlock-Integration` (`419112f`), compared against `master`. About 80 files changed (+13.9k / −1.1k lines, including the sample app). Static review: nothing was compiled or run.

> The local clone `noke-mobile-library-android` is on `firmware-update`, which does not contain the ION 2 work.

---

# Part 1 — Technical explanation of the changes

## 1.1 The model change
- **Legacy locks:** offline key + offline command, encrypted with an AES session negotiated over BLE.
- **ION 2 (`5E`):** no session, no offline key. The phone owns a P-256 key pair (Android Keystore). The backend issues a **signed ACL** per lock/user. The lock verifies the ACL signature, hands out an 8-byte nonce, and verifies the phone's signature over the command.
- The library picks the path per lock: `NokeDevice.getEncryptionType()` is `SIGNING` if `isNokeIon2()` (hardware string contains `5E`/`E5`), else encryption.

## 1.2 New components
| Area | Files | Responsibility |
|---|---|---|
| BLE queue | `BleOperationQueue.java`, `GattRequest.java` | Serialises GATT reads/writes; MTU (512) negotiation and service discovery for ION 2. |
| Device | `NokeDevice.java` | `signingOfflineUnlock`, `unlockForSigning` (validates unlock options, decodes ACL, writes time), signing retry counter, `isNokeIon2`, `getEncryptionType`, `NokeUnlockOption`. |
| Manager service | `NokeDeviceManagerService.java` | Discovers the ION 2 characteristics, state machine in `handleAclStatus`, `refetchAcl`, `unlockSigningDevice`, `hasAclForDevice`, `setPhoneKeyApiKey`, scan/ban/cooldown logic. |
| Signing enums | `enums/*`, `NokeDeviceSigningException` | `NokeDeviceSigningStatus` (lock status strings), `NokeDeviceSigningError` (user text, `shouldRefreshKeys`, `willUseFallback`), characteristic types. |
| PhoneKey layer | `phonekey/*` | `PhoneKeyAccessService` (coroutine API over an injected `PhoneKeyCoreClient`), `PhoneKeyFacade` (encrypted local cache), `PhoneKeyPersistence`, `PhoneKeyStateMonitor`, Flow extensions, `NokeMobileLibraryError`. |
| PhoneKey internals | `phonekey/internal/*` | `PhoneKeyManager` (keys, provisioning, ACL fetch, signing), `SecurityService` (OkHttp backend client), `SecurityServiceRetryInterceptor`, `PhoneKeyCredentialStore` (encrypted API key). |
| Models | `phonekey/models/*` | ACL envelope/permission/result, provisioning, phone key info. |
| Sample app | `app/` | Kotlin rewrite of the demo: `NokeDemoPhoneKeyCoreClient`, `NokeDemoApplication`, `DeviceDetailActivity`. |

## 1.3 ION 2 unlock sequence
```
connect → discover Infinity service → MTU negotiation → characteristics found → onNokeConnected
write time → TIME_SYNCED
write ACL → ACL_RECEIVED / ACL_VALIDATED
write ACL signature → ACLSIG_VERIFIED
read cmdId (8-byte nonce) → CID_READ
sign nonce with phone key (Keystore)
write command → CMD_RECEIVED
write command signature → UNLOCK_EXECUTED  (or an error status)
```

## 1.4 Lock status → library error
| Lock status | `NokeDeviceSigningError` | Meaning |
|---|---|---|
| `UNLOCK_DENIED` | `UNLOCK_DENIED` | Everything verified, but the ACL lacks unlock permission |
| `ACL_SCHEDULE_BLOCKED` | `OUT_OF_SCHEDULE` | Schedule forbids it now |
| `UNLOCK_OVERLOCKED` | `UNLOCK_OVERLOCKED` | Payment likely required |
| `CMDSIG_VERIFY_FAIL` | `CMDSIG_VERIFY_FAIL` | Phone signature/command wrong |
| `ACLSIG_VERIFY_FAIL` | `INVALID_ACL_SIGNATURE` | Triggers one ACL refetch |
| `ACL_REJECTED`, `ACL_SETUP_ERR`, `ACL_TIME_EXPIRED` | `ACL_REJECTED` | Trigger one ACL refetch |
| `ACL_TIME_INVALID` | `ACL_TIME_INVALID` | Triggers one ACL refetch |
| `LOCK_LOCKED`, `LOCK_ALREADY_UNLOCKED` | `LOCK_LOCKED` / `LOCK_ALREADY_UNLOCKED` | Lock-side result |

## 1.5 Public API surface added
- `NokeDevice`: `signingOfflineUnlock()`, `isNokeIon2()`, `getEncryptionType()`, `setUnlockOption/getUnlockOption`, `isEmergencyUnlockRequired/isOverrideUnlockRequired`, `isEligibleForSigningRetry`, `incrementSigningRetry`, `resetSigningRetry`.
- `NokeDeviceManagerService`: `unlockSigningDevice`, `hasAclForDevice`, `setPhoneKeyApiKey`, `safeDisconnectNoke`, `removeAllBanned`, `clearUploadQueue`, `addHandleSuccessClosure`, `addHandleFailureClosure`.
- `NokeServiceListener`: five new methods (`onNokeJammedLocking/Unlocking`, `shouldUploadData`, `nokeDeviceDidSendDiagnostics`, `successPacketReceived`); `NokeServiceListenerAdapter` provides empty defaults.
- Phone Key: `PhoneKeyAccessService`, `PhoneKeyCoreClient`, `PhoneKeyFacade`, `PhoneKeyPersistence`, `NokeMobileLibraryError`, `NokeUnlockOption`, `NokeEncryptionType`.
- Changed: `NokeMobileError.ERROR_CONNECTION_TIMEOUT` renumbered to `318`; new `ERROR_SIGNING` (320), `ERROR_UNSTABLE_BLUETOOTH_STACK` (317); minSdk 19 → 21, compileSdk 28 → 34, Java 17, Kotlin; Bintray publishing removed.
- Non-ION-2 additions bundled in the same release: jammed events, diagnostics, out-of-schedule and free-exit results, scan throttling/banning and post-unlock cooldowns.

---

# Part 2 — Quality review

**Verdict:** the layering is good and the Android-specific security (Keystore, encrypted storage) is solid, but the unlock entry point is fragile and client integration is heavier than necessary.

## 2.1 What is good
- **Coroutine/`Result` API** for provisioning and ACLs, with a sealed `NokeMobileLibraryError`.
- **Hardware-backed keys** and `EncryptedSharedPreferences` for ACLs; the API key moved to encrypted storage (`setPhoneKeyApiKey`).
- **Single BLE operation queue** (`BleOperationQueue`) instead of ad-hoc GATT calls.
- **Automatic ACL refetch + retry** is implemented inside the library (unlike iOS, where the client must wire it).
- **Adapter class** keeps the listener change non-breaking for most clients.

## 2.2 Problems / bugs

**High priority**
1. **No unlock queueing.** `signingOfflineUnlock()` starts writing immediately; if the characteristics are not yet discovered (MTU/discovery waits of up to ~1.7 s) the unlock can fail. There is no "ready for unlock" callback, so clients must guess (the demo calls it from `onNokeConnected`).
2. **Silent failure on bad ACL data.** `NokeDevice.startUnlock` catches `IllegalArgumentException` from Base64 decoding, logs it and returns with `// TODO: Handle Failure properly`. The listener never gets `onError`, so the client waits forever.
3. **Inconsistent retry counters.** `ACL_REJECTED`/`ACL_TIME_*` use the per-device `signingRetryCount`; `ACLSIG_VERIFY_FAIL`/`ACL_SCHEDULE_BLOCKED` use a service-wide `signingRetries` shared by all locks. The two budgets are independent, and the service-wide one can leak between devices.
4. **Hidden global contract through `SharedPreferences`.** The user (`user_uuid`), `email`, `companyId`, `siteId` and a legacy plaintext `apikey` are read from the file `nokeAPILibaryFile`, written by the host app. A missing or mismatched value fails only at unlock time as "No user logged in".
5. **Verbose logging with identifiers.** About 400 `Log.*` calls, no logging switch, and ACL operations log lock MACs and user IDs (`PhoneKeyFacade`, `PhoneKeyManager`). An SDK needs a configurable logger, off by default in release.

**Medium priority**
6. **Error mapping.** `UNLOCK_DENIED`, `OUT_OF_SCHEDULE`, `UNLOCK_OVERLOCKED` and `LOCK_LOCKED` have `shouldRefreshKeys = true`, but refreshing the ACL does not fix them. `isValid` is `false` for almost everything. `ACL_SCHEDULE_BLOCKED` triggers a refetch even though the schedule is the cause. Errors reach clients only as `(ERROR_SIGNING, message)`; the enum is not exposed through `onError`, so apps must match on text.
7. **Duplicated switch arms.** Five near-identical `case` blocks in `handleAclStatus` repeat the retry/disconnect/notify logic. `ACL_TIME_EXPIRED` reports `ACL_REJECTED`.
8. **Brittle ION 2 detection.** `isNokeIon2()` uses `contains("E5") || contains("5E")` on the hardware string and logs on every call.
9. **Huge types.** `NokeDeviceManagerService` grew by ~1.3k lines and mixes scanning, banning, BLE, signing and Phone Key management; `PhoneKeyManager.kt` is 1466 lines.
10. **Documentation drift.** The integration guide states minimum API 23, while the library declares `minSdkVersion 21`. The "Matches iOS" comments hide that `MAX_SIGNING_RETRIES = 1` means 2 attempts in total.
11. **Device ID.** `getDeviceUdid` uses `ANDROID_ID`, which can change (for example after a factory reset); the existing key then stops working without a clear error.

## 2.3 How to make it simpler for clients
Today the demo needs roughly 750 lines of glue (`NokeDemoPhoneKeyCoreClient`, `NokeDemoApplication`, `DeviceDetailActivity`) for provisioning, ACL caching, preference wiring and timeouts. Proposals, highest impact first:

1. **One high-level `unlock`** that waits for the characteristics, picks the path by `encryptionType`, applies a timeout and retries with a fresh ACL, and always ends in `onNokeUnlocked` or `onError`.
2. **Explicit configuration instead of SharedPreferences:** e.g. `NokeDeviceManagerService.configurePhoneKey(userId, apiKey, …)` rather than a file the app must populate.
3. **Typed errors in the listener**, e.g. `onSigningError(noke, NokeDeviceSigningError)` with a suggested action (`RETRY`, `REFRESH_ACL`, `CONTACT_ADMIN`, `FALLBACK_ONLINE`) instead of `isValid` / `shouldRefreshKeys` / `willUseFallback` plus message matching.
4. **Provisioning inside the library:** one `PhoneKey.prepare(userId)` that initializes, provisions and fetches ACLs, with a stored `deviceId` instead of `ANDROID_ID`.
5. **Default `PhoneKeyCoreClient`.** Done: `DefaultPhoneKeyCoreClient` needs only `baseUrl` and an auth provider, and removes the Java bridge and client glue from the sample app.
6. **Configurable logger** (`NokeLogger.level = OFF`) that never prints sensitive data.
7. **Backwards compatibility:** keep the old error-code values or document the renumbering.

## 2.4 Suggested order of work
1. Report Base64/ACL decode failures through `onError`.
2. Unify the retry counters (per device) and reduce the duplicated `handleAclStatus` arms.
3. Queue the unlock until characteristics are ready.
4. Align `shouldRefreshKeys` with what each error can actually fix and expose typed errors.
5. Replace the SharedPreferences contract with explicit configuration; add the logger.
6. Add the high-level `unlock` API.
