package com.noke.nokeapidemo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.noke.nokemobilelibrary.NokeDefines
import com.noke.nokemobilelibrary.NokeDevice
import com.noke.nokemobilelibrary.NokeDeviceManagerService
import com.noke.nokemobilelibrary.NokeMobileError
import com.noke.nokemobilelibrary.NokeServiceListener
import com.noke.nokeapidemo.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main screen of the Nokē Mobile Library Demo.
 *
 * Demonstrates core third-party library usage:
 * - BLE scanning for Noke devices
 * - Device discovery and connection lifecycle
 * - Navigating to per-device unlock operations
 * - Permission handling for BLE and location
 *
 * ## Library Features Validated
 * - [NokeDeviceManagerService] BLE scanning and service binding
 * - [NokeServiceListener] device lifecycle callbacks
 * - [NokeMobileError] error handling
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        /** Matches the library's default BLE scan window (BLUETOOTH_DEFAULT_SCAN_DURATION) */
        private const val SCAN_DURATION_MS = 8_000L
        /** Minimum time between processed re-discoveries of the same MAC, to avoid flooding the UI thread. */
        private const val DISCOVERY_THROTTLE_MS = 500L
        /** Minimum time between list reorders — prevents RSSI jitter from constantly shuffling rows. */
        private const val REORDER_THROTTLE_MS = 3_000L
        /** Weight given to each new RSSI sample when smoothing (0-1, higher = less smoothing). */
        private const val RSSI_SMOOTHING_ALPHA = 0.3
        /** Delay before a typed search query is applied, so fast typing doesn't re-filter on every keystroke. */
        private const val FILTER_DEBOUNCE_MS = 200L
    }

    private lateinit var binding: ActivityMainBinding
    private var nokeService: NokeDeviceManagerService? = null
    /** All devices discovered this session, keyed by MAC — unaffected by the current search filter. */
    private val allDevices = LinkedHashMap<String, NokeDevice>()
    /** Subset of [allDevices] currently matching the search filter, sorted and bound to the adapter. */
    private val displayedDevices = mutableListOf<NokeDevice>()
    private lateinit var deviceAdapter: DiscoveredDeviceAdapter
    /** Last displayed (name, lockState) per MAC — lets us skip no-op rebinds on repeat BLE re-discovery. */
    private val lastRenderedSnapshots = mutableMapOf<String, Pair<String, Int>>()
    /** O(1) index lookup by MAC into [displayedDevices], avoiding an O(n) list scan on every raw BLE advertisement. */
    private val deviceIndexByMac = mutableMapOf<String, Int>()
    /** Timestamp of the last processed (non-throttled) discovery per MAC. */
    private val lastDiscoveryProcessedAt = mutableMapOf<String, Long>()
    /** Exponentially-smoothed RSSI per MAC, used for sort order so noisy readings don't thrash it. */
    private val smoothedRssi = mutableMapOf<String, Double>()
    /** Timestamp of the last time the list order was actually changed. */
    private var lastReorderAt = 0L
    /** True while the user has a finger down on the list — reordering is paused so taps land reliably. */
    private var isUserTouchingList = false
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { updateScanState(scanning = false) }
    /** Prevents duplicate scan starts from concurrent callbacks (e.g. onServiceConnected + onBluetoothStatusChanged). */
    private var isScanning = false
    /** Prevents stacking multiple "enable location services" dialogs when error 301 fires repeatedly. */
    private var isLocationDialogShowing = false
    /** Current search filter text (trimmed), applied against device names. Empty = show all. */
    private var filterQuery = ""
    private val filterHandler = Handler(Looper.getMainLooper())
    private var pendingFilterRunnable: Runnable? = null

    // -------------------------------------------------------------------------
    // Permission Launchers
    // -------------------------------------------------------------------------

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startScanning()
        } else {
            showPermissionDeniedDialog(getString(R.string.no_location_message))
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startScanning()
        } else {
            showPermissionDeniedDialog(getString(R.string.no_bluetooth_message))
        }
    }

    // -------------------------------------------------------------------------
    // Service Connection
    // -------------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "NokeDeviceManagerService connected")
            nokeService = (binder as NokeDeviceManagerService.LocalBinder)
                .getService(NokeDefines.NOKE_LIBRARY_SANDBOX)

            nokeService?.registerNokeListener(nokeServiceListener)
            nokeService?.initialize()

            // Allow all discovered devices (no pre-registration needed for demo)
            nokeService?.setAllowAllDevices(true)

            checkPermissionsAndScan()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "NokeDeviceManagerService disconnected")
            nokeService = null
            updateScanState(scanning = false)
        }
    }

    // -------------------------------------------------------------------------
    // Noke Service Listener
    // -------------------------------------------------------------------------

    private val nokeServiceListener = object : NokeServiceListener {

        override fun onNokeDiscovered(noke: NokeDevice) {
            runOnUiThread {
                // Raw BLE advertisements can repeat many times per second per device; without
                // throttling, every packet floods the UI thread and scrolling stutters/sticks.
                val now = SystemClock.elapsedRealtime()
                val wasAlreadyKnown = allDevices.containsKey(noke.mac)
                if (wasAlreadyKnown) {
                    val lastProcessed = lastDiscoveryProcessedAt[noke.mac]
                    if (lastProcessed != null && now - lastProcessed < DISCOVERY_THROTTLE_MS) return@runOnUiThread
                }
                lastDiscoveryProcessedAt[noke.mac] = now
                Log.d(TAG, "Discovered: ${noke.name} [${noke.mac}] rssi=${noke.rssi}")

                // Raw RSSI swings wildly between readings; smooth it before using it for sort
                // order so the list doesn't reshuffle (and dodge taps) on every noisy sample.
                val previousSmoothed = smoothedRssi[noke.mac]
                val smoothed = if (previousSmoothed == null) {
                    noke.rssi.toDouble()
                } else {
                    previousSmoothed + (noke.rssi - previousSmoothed) * RSSI_SMOOTHING_ALPHA
                }
                smoothedRssi[noke.mac] = smoothed
                allDevices[noke.mac] = noke

                val snapshot = noke.name to noke.lockState
                val contentChanged = lastRenderedSnapshots[noke.mac] != snapshot
                lastRenderedSnapshots[noke.mac] = snapshot

                if (!matchesFilter(noke)) {
                    // Filtered out of the visible list; allDevices above is already up to date so
                    // it reappears instantly if the user broadens/clears the search later.
                    return@runOnUiThread
                }

                val existingIndex = deviceIndexByMac[noke.mac]
                if (existingIndex != null) {
                    displayedDevices[existingIndex] = noke
                    if (contentChanged) {
                        deviceAdapter.notifyItemChanged(existingIndex)
                    }
                    // Only reposition occasionally, and never while the user is touching the
                    // list — otherwise rows keep sliding around and taps never land.
                    if (!isUserTouchingList && now - lastReorderAt >= REORDER_THROTTLE_MS) {
                        repositionIfNeeded(existingIndex, smoothed)
                    }
                } else {
                    val insertIndex = sortedInsertionIndex(smoothed)
                    displayedDevices.add(insertIndex, noke)
                    deviceAdapter.notifyItemInserted(insertIndex)
                    rebuildDeviceIndex()
                }
                updateEmptyState()
            }
        }

        override fun onNokeConnecting(noke: NokeDevice) {
            Log.d(TAG, "Connecting: ${noke.name}")
            updateStatusText(getString(R.string.status_connecting, noke.name))
        }

        override fun onNokeConnected(noke: NokeDevice) {
            Log.d(TAG, "Connected: ${noke.name}")
            // Stop scanning once connected so BLE resources are freed
            nokeService?.stopScanning()
            scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
            updateScanState(scanning = false)
            // Navigate to device detail screen
            openDeviceDetail(noke)
        }

        override fun onNokeSyncing(noke: NokeDevice) {
            Log.d(TAG, "Syncing: ${noke.name}")
            updateStatusText(getString(R.string.status_syncing, noke.name))
        }

        override fun onNokeUnlocked(noke: NokeDevice) {
            Log.d(TAG, "Unlocked: ${noke.name}")
        }

        override fun onNokeJammedLocking(noke: NokeDevice) {
            Log.w(TAG, "Jammed (locking): ${noke.name}")
        }

        override fun onNokeJammedUnlocking(noke: NokeDevice) {
            Log.w(TAG, "Jammed (unlocking): ${noke.name}")
        }

        override fun successPacketReceived(noke: NokeDevice) {
            Log.d(TAG, "Success packet: ${noke.name}")
        }

        override fun shouldUploadData(data: JSONArray) {
            Log.d(TAG, "Uploading activity data (${data.length()} records)")
            nokeService?.uploadData()
        }

        override fun nokeDeviceDidSendDiagnostics(data: JSONObject, noke: NokeDevice) {
            Log.d(TAG, "Diagnostics from ${noke.name}: $data")
        }

        override fun onNokeShutdown(noke: NokeDevice, isLocked: Boolean, didTimeout: Boolean) {
            Log.d(TAG, "Shutdown: ${noke.name} locked=$isLocked timeout=$didTimeout")
        }

        override fun onNokeDisconnected(noke: NokeDevice) {
            Log.d(TAG, "Disconnected: ${noke.name}")
            updateStatusText(getString(R.string.status_scanning))
            // Resume scanning after disconnect
            nokeService?.startScanningForNokeDevices()
            nokeService?.setBluetoothScanDuration(8000)
        }

        override fun onDataUploaded(result: Int, message: String) {
            Log.d(TAG, "Data uploaded: result=$result message=$message")
        }

        override fun onBluetoothStatusChanged(bluetoothStatus: Int) {
            Log.d(TAG, "Bluetooth status changed: $bluetoothStatus")
            when (bluetoothStatus) {
                BluetoothAdapter.STATE_OFF -> updateStatusText(getString(R.string.status_bluetooth_off))
                BluetoothAdapter.STATE_ON -> checkPermissionsAndScan()
            }
        }

        override fun onError(noke: NokeDevice?, error: Int, message: String) {
            Log.e(TAG, "Error $error: $message")
            handleLibraryError(error, message)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupScanButton()
        setupSearchFilter()

        // Bind to NokeDeviceManagerService
        val serviceIntent = Intent(this, NokeDeviceManagerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        // Reclaim the service's single listener slot — DeviceDetailActivity registers its own
        // listener while open and never restores ours, so discoveries would otherwise stop
        // reaching this screen after the first connect.
        nokeService?.registerNokeListener(nokeServiceListener)
        clearDiscoveredDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        nokeService?.stopScanning()
        unbindService(serviceConnection)
        scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
        filterHandler.removeCallbacksAndMessages(null)
    }

    // -------------------------------------------------------------------------
    // UI Setup
    // -------------------------------------------------------------------------

    private fun setupRecyclerView() {
        deviceAdapter = DiscoveredDeviceAdapter(displayedDevices) { noke ->
            Log.d(TAG, "Device tapped: ${noke.name}")
            nokeService?.connectToNoke(noke)
            updateStatusText(getString(R.string.status_connecting, noke.name))
        }
        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
            // Repeat BLE re-discoveries otherwise trigger a fade cross-animation per update.
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            setOnTouchListener { _, event ->
                isUserTouchingList = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
                    else -> false
                }
                false
            }
        }
    }

    private fun setupScanButton() {
        binding.btnScan.setOnClickListener {
            if (nokeService != null) {
                clearDiscoveredDevices()
                checkPermissionsAndScan()
            }
        }
    }

    private fun clearDiscoveredDevices() {
        allDevices.clear()
        displayedDevices.clear()
        deviceIndexByMac.clear()
        lastDiscoveryProcessedAt.clear()
        lastRenderedSnapshots.clear()
        smoothedRssi.clear()
        lastReorderAt = 0L
        deviceAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    /** Index where [smoothedValue] belongs to keep [displayedDevices] sorted strongest-signal-first. */
    private fun sortedInsertionIndex(smoothedValue: Double): Int {
        val index = displayedDevices.indexOfFirst { (smoothedRssi[it.mac] ?: it.rssi.toDouble()) < smoothedValue }
        return if (index >= 0) index else displayedDevices.size
    }

    /** Moves the device at [currentIndex] to match its current smoothed RSSI, if its rank changed. */
    private fun repositionIfNeeded(currentIndex: Int, smoothedValue: Double) {
        val device = displayedDevices.removeAt(currentIndex)
        val newIndex = sortedInsertionIndex(smoothedValue)
        displayedDevices.add(newIndex, device)
        if (newIndex != currentIndex) {
            deviceAdapter.notifyItemMoved(currentIndex, newIndex)
            rebuildDeviceIndex()
            lastReorderAt = SystemClock.elapsedRealtime()
        }
    }

    private fun rebuildDeviceIndex() {
        deviceIndexByMac.clear()
        displayedDevices.forEachIndexed { index, device -> deviceIndexByMac[device.mac] = index }
    }

    // -------------------------------------------------------------------------
    // Search / Filter
    // -------------------------------------------------------------------------

    private fun setupSearchFilter() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                scheduleFilterUpdate(query)
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text?.clear() }
    }

    /** Debounces filter application so fast typing doesn't re-filter the list on every keystroke. */
    private fun scheduleFilterUpdate(query: String) {
        pendingFilterRunnable?.let { filterHandler.removeCallbacks(it) }
        val runnable = Runnable { applyFilter(query) }
        pendingFilterRunnable = runnable
        filterHandler.postDelayed(runnable, FILTER_DEBOUNCE_MS)
    }

    private fun applyFilter(query: String) {
        filterQuery = query.trim()
        displayedDevices.clear()
        displayedDevices.addAll(
            allDevices.values
                .filter { matchesFilter(it) }
                .sortedByDescending { smoothedRssi[it.mac] ?: it.rssi.toDouble() }
        )
        rebuildDeviceIndex()
        deviceAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun matchesFilter(device: NokeDevice): Boolean {
        return filterQuery.isEmpty() || device.name.contains(filterQuery, ignoreCase = true)
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    private fun checkPermissionsAndScan() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val bluetoothScanGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
                val bluetoothConnectGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (bluetoothScanGranted && bluetoothConnectGranted) {
                    // The Noke library also checks ACCESS_COARSE_LOCATION internally on all
                    // API levels, so we must ensure it is granted before starting a scan.
                    checkLocationPermissionAndScan()
                } else {
                    bluetoothPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                }
            }
            else -> {
                checkLocationPermissionAndScan()
            }
        }
    }

    /**
     * Checks whether ACCESS_COARSE_LOCATION is granted before calling [startScanning].
     * Required because [NokeDeviceManagerService] checks this permission on every API level.
     */
    private fun checkLocationPermissionAndScan() {
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (locationGranted) {
            startScanning()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    private fun startScanning() {
        if (isScanning) {
            Log.d(TAG, "BLE scan already in progress — skipping redundant start")
            return
        }
        nokeService?.startScanningForNokeDevices()
        updateScanState(scanning = true)
        updateStatusText(getString(R.string.status_scanning))
        Log.d(TAG, "BLE scan started")
        // Reset UI after the scan window so the button re-enables even if no device is tapped
        scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable, SCAN_DURATION_MS)
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    private fun openDeviceDetail(noke: NokeDevice) {
        val intent = DeviceDetailActivity.newIntent(this, noke.mac, noke.name)
        startActivity(intent)
    }

    // -------------------------------------------------------------------------
    // UI Helpers
    // -------------------------------------------------------------------------

    private fun updateStatusText(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    private fun updateScanState(scanning: Boolean) {
        isScanning = scanning
        runOnUiThread {
            binding.progressScanning.visibility = if (scanning) View.VISIBLE else View.GONE
            binding.btnScan.isEnabled = !scanning
            binding.btnScan.text = if (scanning) getString(R.string.btn_scanning) else getString(R.string.btn_scan)
        }
    }

    private fun updateEmptyState() {
        val noResults = displayedDevices.isEmpty()
        binding.tvEmpty.visibility = if (noResults) View.VISIBLE else View.GONE
        binding.recyclerDevices.visibility = if (noResults) View.GONE else View.VISIBLE
        if (noResults) {
            binding.tvEmpty.text = if (filterQuery.isNotEmpty() && allDevices.isNotEmpty()) {
                getString(R.string.empty_no_matching_devices, filterQuery)
            } else {
                getString(R.string.empty_no_devices)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Error Handling
    // -------------------------------------------------------------------------

    private fun handleLibraryError(error: Int, message: String) {
        when (error) {
            NokeMobileError.ERROR_LOCATION_SERVICES_DISABLED -> {
                updateScanState(scanning = false)
                // The library fires this for two distinct reasons:
                // 1. ACCESS_COARSE_LOCATION permission is not granted—request it directly.
                // 2. Permission is granted but device location services (GPS/network) are disabled—direct to Settings.
                val locationGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!locationGranted) {
                    showPermissionRationaleDialog(
                        title = getString(R.string.location_access_required),
                        message = getString(R.string.location_permission_request_message),
                        onOk = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }
                    )
                } else {
                    // Permission is already granted; the device's location services are turned off.
                    updateStatusText(getString(R.string.status_location_disabled))
                    showLocationSettingsDialog()
                }
            }
            NokeMobileError.ERROR_LOCATION_PERMISSIONS_NEEDED -> {
                // Only relevant on API < 31; on API 31+ the library fires ERROR_BLUETOOTH_SCAN_PERMISSION instead.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    showPermissionRationaleDialog(
                        title = getString(R.string.location_access_required),
                        message = getString(R.string.location_permission_request_message),
                        onOk = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }
                    )
                }
            }
            NokeMobileError.ERROR_BLUETOOTH_SCAN_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    showPermissionRationaleDialog(
                        title = getString(R.string.bluetooth_access_required),
                        message = getString(R.string.bluetooth_permission_request_message),
                        onOk = {
                            bluetoothPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            )
                        }
                    )
                }
            }
            NokeMobileError.ERROR_BLUETOOTH_DISABLED -> {
                updateStatusText(getString(R.string.status_bluetooth_off))
            }
            NokeMobileError.ERROR_BLUETOOTH_GATT -> {
                updateStatusText(getString(R.string.error_gatt))
                Log.e(TAG, "GATT error: $message")
            }
            NokeMobileError.ERROR_INVALID_NOKE_DEVICE -> {
                // GATT service discovery completed but the expected Noke characteristics were
                // not found (e.g. firmware mismatch or unsupported hardware revision).
                // Reset scan state so the button re-enables and the user can retry.
                updateScanState(scanning = false)
                updateStatusText(getString(R.string.error_device_incompatible))
                Log.e(TAG, "Invalid noke device (304): $message")
            }
            NokeMobileError.ERROR_CONNECTION_TIMEOUT -> {
                // Connection attempt exceeded the library's internal timeout.
                updateScanState(scanning = false)
                updateStatusText(getString(R.string.error_connection_timeout))
                Log.e(TAG, "Connection timeout (318): $message")
            }
            NokeMobileError.DEVICE_ERROR_INVALID_KEY -> {
                updateStatusText(getString(R.string.error_invalid_key))
            }
            else -> {
                Log.e(TAG, "Unhandled error $error: $message")
            }
        }
    }

    private fun showLocationSettingsDialog() {
        if (isLocationDialogShowing) return
        isLocationDialogShowing = true
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.location_services_required_title))
                .setMessage(getString(R.string.location_services_required_message))
                .setPositiveButton(getString(R.string.btn_open_settings)) { dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .setOnDismissListener {
                    isLocationDialogShowing = false
                }
                .show()
        }
    }

    private fun showPermissionRationaleDialog(title: String, message: String, onOk: () -> Unit) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    onOk()
                }
                .show()
        }
    }

    private fun showPermissionDeniedDialog(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.functionality_limited))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
