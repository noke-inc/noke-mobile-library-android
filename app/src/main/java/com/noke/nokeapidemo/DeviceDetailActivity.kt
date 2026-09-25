package com.noke.nokeapidemo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noke.nokemobilelibrary.NokeDefines
import com.noke.nokemobilelibrary.NokeDevice
import com.noke.nokemobilelibrary.NokeDeviceManagerService
import com.noke.nokemobilelibrary.NokeMobileError
import com.noke.nokemobilelibrary.NokeServiceListener
import com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService
import com.noke.nokeapidemo.databinding.ActivityDeviceDetailBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device detail screen for a connected Noke device.
 *
 * Demonstrates all core unlock flows supported by the library:
 *
 * 1. **Standard unlock** (online) — Session sent to your server; server responds with commands
 * 2. **Offline unlock** — Locally cached offline key + command, no network required
 * 3. **ION-2 Phone Key unlock** — ACL-based cryptographic unlock for ION-2 hardware
 *
 * ## Library Features Validated
 * - [NokeDevice.sendCommands] — Sending commands to the lock
 * - [NokeDevice.offlineUnlock] — Offline unlock with cached key
 * - [NokeDevice.getSession] — Session token used in server unlock requests
 * - [NokeDevice.getHardwareVersion] — Determine ION-2 vs classic devices
 * - [NokeDeviceManagerService.uploadData] — Uploading activity logs
 * - [NokeServiceListener] disconnect, unlock, and error callbacks
 */
class DeviceDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeviceDetailActivity"
        private const val EXTRA_MAC = "extra_mac"
        private const val EXTRA_NAME = "extra_name"

        fun newIntent(context: Context, mac: String, name: String): Intent {
            return Intent(context, DeviceDetailActivity::class.java).apply {
                putExtra(EXTRA_MAC, mac)
                putExtra(EXTRA_NAME, name)
            }
        }
    }

    private lateinit var binding: ActivityDeviceDetailBinding
    private var nokeService: NokeDeviceManagerService? = null
    private var connectedDevice: NokeDevice? = null

    private val deviceMac by lazy { intent.getStringExtra(EXTRA_MAC) ?: "" }
    private val deviceName by lazy { intent.getStringExtra(EXTRA_NAME) ?: deviceMac }

    // -------------------------------------------------------------------------
    // Service Reference (shared via application)
    // -------------------------------------------------------------------------

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            nokeService = (binder as NokeDeviceManagerService.LocalBinder)
                .getService(NokeDefines.NOKE_LIBRARY_SANDBOX)
            nokeService?.registerNokeListener(nokeServiceListener)
            // Retrieve device from service's managed device list (keyed by MAC)
            connectedDevice = nokeService?.nokeDevices?.get(deviceMac)
            updateDeviceInfo()

            // IMPORTANT: The device is already connected when DeviceDetailActivity opens,
            // because MainActivity.onNokeConnected() calls openDeviceDetail() which starts
            // this activity. By the time this listener is registered, onNokeConnected has
            // already fired in MainActivity — it will NOT fire again in nokeServiceListener.
            // Therefore we must trigger provisioning here for ION-2 devices.
            val noke = connectedDevice
            Log.d(TAG, "PROVISIONING_ISSUE_DEBUG onServiceConnected: connectedDevice=${noke?.mac}, isNokeIon2=${noke?.isNokeIon2}")
            if (noke != null && noke.isNokeIon2) {
                lifecycleScope.launch { ensurePhoneKeyProvisioned(noke) }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            nokeService = null
        }
    }

    // -------------------------------------------------------------------------
    // Noke Service Listener
    // -------------------------------------------------------------------------

    private val nokeServiceListener = object : NokeServiceListener {

        override fun onNokeDiscovered(noke: NokeDevice) {}

        override fun onNokeConnecting(noke: NokeDevice) {
            if (noke.mac == deviceMac) updateStatus(getString(R.string.status_connecting, noke.name))
        }

        override fun onNokeConnected(noke: NokeDevice) {
            if (noke.mac == deviceMac) {
                connectedDevice = noke
                updateStatus(getString(R.string.status_connected, noke.name))
                updateDeviceInfo()
                updateConnectionState(connected = true)

                // ION-2 devices require Phone Key provisioning before unlock.
                // Trigger the provisioning flow on every connect; it is idempotent
                // (completes immediately if already provisioned and ACLs are fresh).
                if (noke.isNokeIon2) {
                    lifecycleScope.launch { ensurePhoneKeyProvisioned(noke) }
                }
            }
        }

        override fun onNokeSyncing(noke: NokeDevice) {
            if (noke.mac == deviceMac) updateStatus(getString(R.string.status_syncing, noke.name))
        }

        override fun onNokeUnlocked(noke: NokeDevice) {
            if (noke.mac == deviceMac) {
                updateStatus(getString(R.string.status_unlocked, noke.name))
                updateLockStateIndicator(unlocked = true)
                Log.d(TAG, "Unlock success for ${noke.name}")
            }
        }

        override fun onNokeJammedLocking(noke: NokeDevice) {
            if (noke.mac == deviceMac) updateStatus(getString(R.string.status_jammed_locking))
        }

        override fun onNokeJammedUnlocking(noke: NokeDevice) {
            if (noke.mac == deviceMac) updateStatus(getString(R.string.status_jammed_unlocking))
        }

        override fun successPacketReceived(noke: NokeDevice) {
            if (noke.mac == deviceMac) Log.d(TAG, "Success packet received")
        }

        override fun shouldUploadData(data: JSONArray) {
            Log.d(TAG, "Uploading ${data.length()} activity records")
            nokeService?.uploadData()
        }

        override fun nokeDeviceDidSendDiagnostics(data: JSONObject, noke: NokeDevice) {
            Log.d(TAG, "Diagnostics from ${noke.name}: $data")
        }

        override fun onNokeShutdown(noke: NokeDevice, isLocked: Boolean, didTimeout: Boolean) {
            if (noke.mac == deviceMac) {
                val statusMsg = buildString {
                    append(getString(R.string.status_shutdown, noke.name))
                    if (isLocked) append(" — Locked")
                    if (didTimeout) append(" (Timeout)")
                }
                updateStatus(statusMsg)
                updateLockStateIndicator(unlocked = false)
            }
        }

        override fun onNokeDisconnected(noke: NokeDevice) {
            if (noke.mac == deviceMac) {
                updateStatus(getString(R.string.status_disconnected, noke.name))
                updateConnectionState(connected = false)
                // Upload accumulated activity data on disconnect
                nokeService?.uploadData()
                finish()
            }
        }

        override fun onDataUploaded(result: Int, message: String) {
            Log.d(TAG, "Data uploaded: result=$result message=$message")
        }

        override fun onBluetoothStatusChanged(bluetoothStatus: Int) {}

        override fun onError(noke: NokeDevice?, error: Int, message: String) {
            Log.e(TAG, "Error $error on ${noke?.name ?: "unknown"}: $message")
            handleError(error, message)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = deviceName
            setDisplayHomeAsUpEnabled(true)
        }

        setupButtons()

        // Re-bind to service to receive callbacks and get device reference
        val serviceIntent = Intent(this, NokeDeviceManagerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }

    // -------------------------------------------------------------------------
    // UI Setup
    // -------------------------------------------------------------------------

    private fun setupButtons() {
        // Standard online unlock
        binding.btnUnlock.setOnClickListener {
            val noke = connectedDevice ?: run {
                updateStatus(getString(R.string.error_no_device))
                return@setOnClickListener
            }
            performStandardUnlock(noke)
        }

        // Offline unlock (uses cached offline key — no network required)
        binding.btnOfflineUnlock.setOnClickListener {
            val noke = connectedDevice ?: run {
                updateStatus(getString(R.string.error_no_device))
                return@setOnClickListener
            }
            performOfflineUnlock(noke)
        }

        // Disconnect
        binding.btnDisconnect.setOnClickListener {
            connectedDevice?.let { nokeService?.disconnectNoke(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Unlock Flows
    // -------------------------------------------------------------------------

    /**
     * Standard online unlock flow.
     *
     * - **ION-2 (NOKE5E)**: delegates to [NokeDeviceManagerService.unlockSigningDevice] which
     *   performs a Phone Key ACL-based cryptographic unlock. No server round-trip is needed
     *   because the ACL is stored locally by [PhoneKeyManager].
     * - **Classic/legacy devices**: sends the device session to your server, which responds
     *   with unlock commands that are forwarded to the lock via [NokeDevice.sendCommands].
     *
     * Note: The demo server URL must be configured in [NokeDemoApiClient].
     * Requests should NOT be made to the Noke Core API directly from the mobile app.
     */
    private fun performStandardUnlock(noke: NokeDevice) {
        updateStatus(getString(R.string.status_requesting_unlock))
        setButtonsEnabled(false)

        if (noke.isNokeIon2) {
            // ION-2 (NOKE5E): Phone Key ACL signing unlock. The service uses the locally
            // cached ACL and reports ERROR_SIGNING via onError if provisioning is incomplete.
            val service = nokeService
            if (service == null) {
                Log.e(TAG, "nokeService is null — cannot perform ION-2 unlock")
                updateStatus(getString(R.string.error_no_device))
                setButtonsEnabled(true)
                return
            }
            Log.d(TAG, "ION-2 device — routing online unlock to unlockSigningDevice")
            service.unlockSigningDevice(noke)
        } else {
            // Classic/legacy device: send session to server and receive unlock commands.
            val apiClient = NokeDemoApiClient()
            apiClient.requestUnlock(
                noke = noke,
                onSuccess = { commandString, offlineKey, offlineUnlockCmd ->
                    Log.d(TAG, "Unlock commands received, sending to device")
                    // Cache offline credentials whenever the server provides them so that
                    // future offline unlocks are available without a network connection.
                    if (!offlineKey.isNullOrBlank() && !offlineUnlockCmd.isNullOrBlank()) {
                        noke.setOfflineKey(offlineKey)
                        noke.setOfflineUnlockCmd(offlineUnlockCmd)
                        Log.d(TAG, "Offline key cached for ${noke.mac}")
                        updateDeviceInfo()
                    }
                    noke.sendCommands(commandString)
                },
                onError = { errorMessage ->
                    Log.e(TAG, "Unlock request failed: $errorMessage")
                    updateStatus(getString(R.string.error_unlock_failed, errorMessage))
                    setButtonsEnabled(true)
                }
            )
        }
    }

    /**
     * Offline unlock flow.
     *
     * - **ION-2 (NOKE5E)**: uses the locally cached Phone Key ACL via
     *   [NokeDeviceManagerService.unlockSigningDevice]. No network required — the ACL
     *   stored by [PhoneKeyManager] is the offline credential.
     * - **Classic/legacy devices**: uses the offline key + command cached via
     *   [NokeDevice.setOfflineKey] / [NokeDevice.setOfflineUnlockCmd] (obtained from a
     *   prior server response). No network required.
     */
    private fun performOfflineUnlock(noke: NokeDevice) {
        if (noke.isNokeIon2) {
            // ION-2 (NOKE5E): the Phone Key ACL IS the offline credential.
            // unlockSigningDevice reads it from local storage — no network call needed.
            val service = nokeService
            if (service == null) {
                Log.e(TAG, "nokeService is null — cannot perform ION-2 offline unlock")
                updateStatus(getString(R.string.error_no_device))
                return
            }
            Log.d(TAG, "ION-2 device — routing offline unlock to unlockSigningDevice (Phone Key ACL)")
            updateStatus(getString(R.string.status_offline_unlock))
            setButtonsEnabled(false)
            // service.unlockSigningDevice(noke);
            noke.signingOfflineUnlock();
        } else {
            // Classic/legacy device: use pre-cached offline key + unlock command.
            if (noke.offlineKey.isNullOrBlank()) {
                updateStatus(getString(R.string.error_no_offline_key))
                return
            }
            updateStatus(getString(R.string.status_offline_unlock))
            val timestamp = noke.scheduledOfflineUnlock()
            if (timestamp.isNotEmpty()) {
                Log.d(TAG, "Offline unlock sent at $timestamp")
            } else {
                updateStatus(getString(R.string.error_offline_unlock_failed))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Phone Key Provisioning
    // -------------------------------------------------------------------------

    /**
     * Ensures the Phone Key is provisioned and ACLs are cached for an ION-2 device.
     *
     * This is idempotent — if the phone is already provisioned and ACLs are available,
     * the check resolves immediately without any network calls.
     *
     * Full provisioning flow (first time per user/device):
     * 1. [PhoneKeyAccessService.initialize] — generate ECDSA P-256 key pair in Keystore
     * 2. [PhoneKeyAccessService.provisionPhoneKey] — register public key with backend
     *    (idempotent: returns stored keyId if already provisioned)
     * 3. [PhoneKeyAccessService.generateBulkAcls] — fetch all ACL envelopes from backend
     *
     * The stored ACLs are then available to [NokeDeviceManagerService.unlockSigningDevice]
     * for both online and offline ION-2 unlock, sharing the same [PhoneKeyManager] singleton.
     *
     * Note: Avoids [PhoneKeyAccessService.isProvisioned] and [PhoneKeyAccessService.getPhoneKeyId]
     * because those go through [PhoneKeyFacade] which uses an unsupported constructor in the
     * standalone library. The keyId is obtained directly from the provisioning response instead.
     */
    private suspend fun ensurePhoneKeyProvisioned(noke: NokeDevice) {
        val service = PhoneKeyAccessService.getInstance()
        val userId = getPhoneKeyUserId()
        val udid = PhoneKeyAccessService.getDeviceUdid(applicationContext)

        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG ensurePhoneKeyProvisioned: userId='$userId', udid='$udid', lockMac=${noke.mac}")

        if (userId.isEmpty()) {
            Log.w(TAG, "PROVISIONING_ISSUE_DEBUG userId is empty — phoneKeyUserId not set in local.properties")
            Log.w(TAG, "ION-2 provisioning skipped — phoneKeyUserId not configured in local.properties")
            updateStatus(getString(R.string.error_phone_key_not_configured))
            return
        }

        // Fast-path: ACL already cached locally — nothing to do.
        // Note: hasAclForDevice checks manager.isProvisioned() + getAclEnvelope() — no PhoneKeyFacade.
        val hasAcl = nokeService?.hasAclForDevice(noke.mac) ?: false
        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG hasAclForDevice(${noke.mac})=$hasAcl")
        if (hasAcl) {
            Log.d(TAG, "ION-2 — ACL already cached for ${noke.mac}")
            updateDeviceInfo()
            return
        }

        // ---- Step 1: Initialize (generate/verify ECDSA keys — idempotent) ----
        updateStatus(getString(R.string.status_phone_key_initializing))
        Log.d(TAG, "ION-2 — Initializing Phone Key for user=$userId, device=$udid")

        val initResult = service.initialize(userId, udid)
        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG initialize result: isSuccess=${initResult.isSuccess}, error=${initResult.exceptionOrNull()?.message}")
        if (initResult.isFailure) {
            val error = initResult.exceptionOrNull()
            Log.e(TAG, "ION-2 — Phone Key initialization failed", error)
            updateStatus(getString(R.string.error_phone_key_init_failed, error?.message ?: "unknown"))
            return
        }

        // ---- Step 2: Provision (idempotent — returns stored keyId if already provisioned) ----
        // NokeDemoPhoneKeyCoreClient.provisionPhoneKey checks manager.isProvisioned() first and
        // returns the locally stored keyId without a backend call if already provisioned.
        // This avoids 409 Conflict errors on re-connect.
        updateStatus(getString(R.string.status_phone_key_provisioning))
        Log.d(TAG, "ION-2 — Provisioning Phone Key for user=$userId")

        val provisionResult = service.provisionPhoneKey(userId, udid)
        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG provisionPhoneKey result: isSuccess=${provisionResult.isSuccess}, error=${provisionResult.exceptionOrNull()?.message}")
        if (provisionResult.isFailure) {
            val error = provisionResult.exceptionOrNull()
            Log.e(TAG, "ION-2 — Phone Key provisioning failed", error)
            updateStatus(getString(R.string.error_phone_key_provision_failed, error?.message ?: "unknown"))
            return
        }

        val provisionResponse = provisionResult.getOrNull()
        if (provisionResponse == null || !provisionResponse.isSuccess || provisionResponse.keyId == null) {
            val msg = provisionResponse?.statusMessage ?: "Backend rejected provisioning"
            Log.e(TAG, "ION-2 — Provisioning response indicates failure: $msg")
            updateStatus(getString(R.string.error_phone_key_provision_failed, msg))
            return
        }

        val phoneKeyId: Int = provisionResponse.keyId!!
        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG phoneKeyId=$phoneKeyId, userId=$userId, lockMac=${noke.mac}")
        Log.d(TAG, "ION-2 — Using phoneKeyId=$phoneKeyId for user=$userId")

        // ---- Step 3: Fetch bulk ACLs ----
        // initialize() already put the manager in cache, so generateBulkAcls can find it.
        updateStatus(getString(R.string.status_phone_key_fetching_acls))
        Log.d(TAG, "ION-2 — Fetching bulk ACLs for ${noke.mac}")

        val bulkResult = service.generateBulkAcls(phoneKeyId, userId, udid)
        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG generateBulkAcls result: isSuccess=${bulkResult.isSuccess}, successCount=${bulkResult.getOrNull()?.successCount}, totalCount=${bulkResult.getOrNull()?.totalCount}, error=${bulkResult.exceptionOrNull()?.message}")
        if (bulkResult.isFailure) {
            Log.w(TAG, "ION-2 — Bulk ACL fetch failed (${bulkResult.exceptionOrNull()?.message}) — falling back to single ACL fetch for ${noke.mac}")
        } else {
            val acls = bulkResult.getOrNull()
            Log.d(TAG, "ION-2 — Bulk ACL fetch complete: ${acls?.successCount}/${acls?.totalCount} stored")
        }

        val hasAclAfterBulk = nokeService?.hasAclForDevice(noke.mac) ?: false
        Log.d(TAG, "PROVISIONING_ISSUE_DEBUG hasAclForDevice(${noke.mac}) after bulk=$hasAclAfterBulk")

        // ---- Step 3b: Single-ACL fallback ----
        // Bulk ACL requires the user to have a unit/smartentry assignment on the backend.
        // If bulk fails (e.g. 404 unit-not-found) or returns no ACL for this specific lock,
        // fall back to the single-lock endpoint which looks up by userId + lockMac + phoneKeyId.
        if (!hasAclAfterBulk) {
            Log.d(TAG, "ION-2 — No ACL for ${noke.mac} after bulk — trying single ACL fetch")
            val userId_int = userId.toIntOrNull()
            if (userId_int == null) {
                Log.e(TAG, "ION-2 — Cannot fetch single ACL: phoneKeyUserId '$userId' is not a valid integer")
                updateStatus(getString(R.string.error_phone_key_acl_failed, "invalid userId"))
                return
            }
            val singleResult = service.generateAcl(userId_int, noke.mac, phoneKeyId, udid)
            Log.d(TAG, "PROVISIONING_ISSUE_DEBUG generateAcl(userId=$userId_int, lockMac=${noke.mac}, phoneKeyId=$phoneKeyId) result: isSuccess=${singleResult.isSuccess}, error=${singleResult.exceptionOrNull()?.message}")
            if (singleResult.isFailure) {
                val error = singleResult.exceptionOrNull()
                Log.e(TAG, "ION-2 — Single ACL fetch failed for ${noke.mac}: ${error?.message}", error)
                // Surface the most useful error — bulk error if available, otherwise single error
                val bulkError = bulkResult.exceptionOrNull()?.message
                val reason = bulkError ?: error?.message ?: "unknown"
                updateStatus(getString(R.string.error_phone_key_acl_failed, reason))
                return
            }
            Log.d(TAG, "ION-2 — Single ACL fetched and stored for ${noke.mac}")
        }

        // Provisioning complete — ACL cached and ready for offline unlock
        updateStatus(getString(R.string.status_phone_key_ready, noke.name))
        updateDeviceInfo()
    }

    /**
     * Returns the configured Phone Key user ID from SharedPreferences.
     * This is populated by [NokeDemoApplication] from [BuildConfig.PHONE_KEY_USER_ID].
     */
    private fun getPhoneKeyUserId(): String {
        val prefs = getSharedPreferences("nokeAPILibaryFile", Context.MODE_PRIVATE)
        return prefs.getString("user_uuid", "") ?: ""
    }

    // -------------------------------------------------------------------------
    // Device Info Display
    // -------------------------------------------------------------------------

    private fun updateDeviceInfo() {
        val noke = connectedDevice ?: return
        runOnUiThread {
            binding.tvDeviceName.text = noke.name.ifBlank { noke.mac }
            binding.tvDeviceMac.text = noke.mac
            binding.tvHardwareVersion.text = noke.hardwareVersion ?: getString(R.string.value_unknown)
            binding.tvSoftwareVersion.text = runCatching { noke.softwareVersion }.getOrElse { getString(R.string.value_unknown) }

            val lockStateText = when (noke.lockState) {
                NokeDefines.NOKE_LOCK_STATE_LOCKED -> getString(R.string.lock_state_locked)
                NokeDefines.NOKE_LOCK_STATE_UNLOCKED -> getString(R.string.lock_state_unlocked)
                NokeDefines.NOKE_LOCK_STATE_UNSHACKLED -> getString(R.string.lock_state_unshackled)
                else -> getString(R.string.lock_state_unknown)
            }
            binding.tvLockState.text = lockStateText

            // Show offline unlock option based on device type:
            // - ION-2 (NOKE5E): offline unlock uses the locally cached Phone Key ACL.
            // - Classic: offline unlock uses the cached offlineKey + offlineUnlockCmd pair.
            val hasOfflineAccess: Boolean
            val offlineStatusText: String
            if (noke.isNokeIon2) {
                val hasAcl = nokeService?.hasAclForDevice(noke.mac) ?: false
                hasOfflineAccess = hasAcl
                offlineStatusText = if (hasAcl)
                    getString(R.string.offline_key_available_ion2)
                else
                    getString(R.string.offline_key_unavailable_ion2)
            } else {
                val hasLegacyKey = !noke.offlineKey.isNullOrBlank() && !noke.offlineUnlockCmd.isNullOrBlank()
                hasOfflineAccess = hasLegacyKey
                offlineStatusText = if (hasLegacyKey)
                    getString(R.string.offline_key_available)
                else
                    getString(R.string.offline_key_unavailable)
            }
            binding.btnOfflineUnlock.visibility = if (hasOfflineAccess) View.VISIBLE else View.GONE
            binding.tvOfflineKeyStatus.text = offlineStatusText

            updateLockStateIndicator(unlocked = noke.lockState == NokeDefines.NOKE_LOCK_STATE_UNLOCKED)
        }
    }

    // -------------------------------------------------------------------------
    // UI Helpers
    // -------------------------------------------------------------------------

    private fun updateStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    private fun updateConnectionState(connected: Boolean) {
        runOnUiThread {
            binding.tvConnectionState.text = if (connected)
                getString(R.string.connection_state_connected)
            else
                getString(R.string.connection_state_disconnected)
            binding.connectionIndicator.setBackgroundColor(
                if (connected) getColor(R.color.nokeBlue) else getColor(R.color.disconnectGray)
            )
            setButtonsEnabled(connected)
        }
    }

    private fun updateLockStateIndicator(unlocked: Boolean) {
        runOnUiThread {
            binding.lockStateIndicator.setBackgroundColor(
                if (unlocked) getColor(R.color.unlockGreen) else getColor(R.color.disconnectGray)
            )
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        runOnUiThread {
            binding.btnUnlock.isEnabled = enabled
            binding.btnOfflineUnlock.isEnabled = enabled
        }
    }

    private fun handleError(error: Int, message: String) {
        val errorMsg = when (error) {
            NokeMobileError.DEVICE_ERROR_INVALID_KEY -> getString(R.string.error_invalid_key)
            NokeMobileError.ERROR_BLUETOOTH_GATT -> getString(R.string.error_gatt)
            NokeMobileError.ERROR_INVALID_OFFLINE_KEY -> getString(R.string.error_no_offline_key)
            NokeMobileError.DEVICE_ERROR_INVALID_PERMISSION -> getString(R.string.error_invalid_permission)
            NokeMobileError.DEVICE_OUT_OF_SCHEDULE_UNLOCK -> getString(R.string.error_out_of_schedule)
            NokeMobileError.ERROR_SIGNING -> getString(R.string.error_signing)
            else -> getString(R.string.error_generic, error, message)
        }
        updateStatus(errorMsg)
        setButtonsEnabled(true)
    }
}
