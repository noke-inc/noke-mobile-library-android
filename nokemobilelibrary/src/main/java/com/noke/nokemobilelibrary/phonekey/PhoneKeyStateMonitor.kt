package com.noke.nokemobilelibrary.phonekey

import android.content.Context
import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reactive state monitor for Phone Key provisioning and ACL status.
 * 
 * This class provides StateFlow-based observables for monitoring phone key
 * state changes in real-time, enabling reactive UI updates.
 * 
 * ## Features
 * 
 * - **StateFlow**: Hot streams that always have current state
 * - **Thread-safe**: All state updates protected by mutex
 * - **Lifecycle-aware**: StateFlows can be collected safely in lifecyclescope
 * - **Distinct updates**: Only emits when state actually changes
 * - **Multiple observers**: Many collectors can observe the same state
 * 
 * ## Architecture
 * 
 * This monitor wraps [PhoneKeyAccessService] and provides Observable state:
 * - Tracks provisioning status
 * - Monitors ACL fetch results
 * - Provides error state
 * - Exposes loading indicators
 * 
 * ## Usage Example
 * 
 * ### Basic State Observation
 * 
 * ```kotlin
 * class SetupViewModel(
 *     private val monitor: PhoneKeyStateMonitor
 * ) : ViewModel() {
 *     
 *     val provisioningState: StateFlow<ProvisioningState> = 
 *         monitor.provisioningState
 *     
 *     val aclState: StateFlow<AclState> = 
 *         monitor.aclState
 *     
 *     fun provision(userId: String, deviceId: String) {
 *         viewModelScope.launch {
 *             monitor.provisionDevice(userId, deviceId)
 *         }
 *     }
 * }
 * 
 * // In Activity/Fragment
 * lifecycleScope.launch {
 *     viewModel.provisioningState.collect { state ->
 *         when (state) {
 *             is ProvisioningState.Idle -> { /* Show provision button */ }
 *             is ProvisioningState.Checking -> { /* Show progress */ }
 *             is ProvisioningState.Provisioning -> { /* Show provisioning progress */ }
 *             is ProvisioningState.Provisioned -> { /* Navigate to next screen */ }
 *             is ProvisioningState.Error -> { /* Show error */ }
 *         }
 *     }
 * }
 * ```
 * 
 * ### Derived State Flows
 * 
 * ```kotlin
 * val isLoading: StateFlow<Boolean> = monitor.provisioningState
 *     .map { it is ProvisioningState.Checking || it is ProvisioningState.Provisioning }
 *     .stateIn(viewModelScope, SharingStarted.Eagerly, false)
 * 
 * val lockCount: StateFlow<Int> = monitor.aclState
 *     .map { state ->
 *         when (state) {
 *             is AclState.Fetched -> state.result.successCount
 *             else -> 0
 *         }
 *     }
 *     .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
 * ```
 * 
 * ### Combine Multiple States
 * 
 * ```kotlin
 * val setupComplete: StateFlow<Boolean> = combine(
 *     monitor.provisioningState,
 *     monitor.aclState
 * ) { provisionState, aclState ->
 *     provisionState is ProvisioningState.Provisioned && 
 *     aclState is AclState.Fetched && 
 *     aclState.result.successCount > 0
 * }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
 * ```
 * 
 * @param context Application context
 * @param phoneKeyService PhoneKeyAccessService instance
 * 
 * @since 1.1.0 (Phase 4 - Reactive Extensions)
 */
class PhoneKeyStateMonitor(
    private val context: Context,
    private val phoneKeyService: PhoneKeyAccessService
) {
    
    private val mutex = Mutex()
    
    // Internal mutable state flows
    private val _provisioningState = MutableStateFlow<ProvisioningState>(ProvisioningState.Idle)
    private val _aclState = MutableStateFlow<AclState>(AclState.Idle)
    
    /**
     * Current provisioning state.
     * 
     * Emits state changes during provisioning workflow:
     * - Idle → Checking → Provisioning → Provisioned
     * - Or: Idle → Checking → AlreadyProvisioned
     * - Or: Any state → Error
     */
    val provisioningState: StateFlow<ProvisioningState> = _provisioningState.asStateFlow()
    
    /**
     * Current ACL fetch state.
     * 
     * Emits state changes during ACL operations:
     * - Idle → Fetching → Fetched
     * - Or: Idle → Fetching → Error
     */
    val aclState: StateFlow<AclState> = _aclState.asStateFlow()
    
    /**
     * Derived flow indicating if any operation is in progress.
     */
    val isLoading: Flow<Boolean> = combine(
        provisioningState,
        aclState
    ) { provState, aclState ->
        provState is ProvisioningState.Checking ||
        provState is ProvisioningState.Provisioning ||
        aclState is AclState.Fetching
    }.distinctUntilChanged()
    
    /**
     * Derived flow indicating if setup is complete.
     * 
     * True when:
     * - Device is provisioned
     * - At least one ACL has been fetched successfully
     */
    val isSetupComplete: Flow<Boolean> = combine(
        provisioningState,
        aclState
    ) { provState, aclState ->
        (provState is ProvisioningState.Provisioned || 
         provState is ProvisioningState.AlreadyProvisioned) &&
        aclState is AclState.Fetched &&
        aclState.result.successCount > 0
    }.distinctUntilChanged()
    
    /**
     * Provision a device and update state reactively.
     * 
     * This method:
     * 1. Checks if already provisioned
     * 2. Provisions if needed
     * 3. Updates [provisioningState] throughout
     * 4. Automatically fetches ACLs after provisioning
     * 
     * @param userId User identifier
     * @param deviceId Device identifier
     */
    suspend fun provisionDevice(userId: String, deviceId: String) {
        mutex.withLock {
            try {
                // Step 1: Check provisioning status
                _provisioningState.value = ProvisioningState.Checking
                
                val isProvisionedResult = phoneKeyService.isProvisioned(userId, deviceId)
                
                isProvisionedResult.fold(
                    onSuccess = { isProvisioned ->
                        var phoneKeyId: Int? = null
                        
                        if (isProvisioned) {
                            // Already provisioned
                            _provisioningState.value = ProvisioningState.AlreadyProvisioned
                            
                            // Get existing phone key ID
                            val idResult = phoneKeyService.getPhoneKeyId(userId, deviceId)
                            idResult.fold(
                                onSuccess = { keyId ->
                                    phoneKeyId = keyId
                                    _provisioningState.value = ProvisioningState.Provisioned(keyId)
                                },
                                onFailure = { error ->
                                    _provisioningState.value = ProvisioningState.Error(error)
                                    return
                                }
                            )
                        } else {
                            // Need to provision
                            _provisioningState.value = ProvisioningState.Provisioning
                            
                            val provisionResult = phoneKeyService.provisionPhoneKey(userId, deviceId)
                            provisionResult.fold(
                                onSuccess = { response ->
                                    val keyId = response.keyId
                                        ?: throw IllegalStateException("Provisioning succeeded but keyId is null")
                                    phoneKeyId = keyId
                                    _provisioningState.value = ProvisioningState.Provisioned(keyId)
                                },
                                onFailure = { error ->
                                    _provisioningState.value = ProvisioningState.Error(error)
                                    return
                                }
                            )
                        }
                        
                        // Automatically fetch ACLs after provisioning
                        // phoneKeyId is guaranteed to be non-null here (both branches assign or return)
                        val finalPhoneKeyId = phoneKeyId ?: throw IllegalStateException("Phone key ID not set")
                        fetchAcls(finalPhoneKeyId, userId, deviceId)
                    },
                    onFailure = { error ->
                        _provisioningState.value = ProvisioningState.Error(error)
                    }
                )
            } catch (e: Exception) {
                _provisioningState.value = ProvisioningState.Error(e)
            }
        }
    }
    
    /**
     * Fetch ACLs and update state reactively.
     * 
     * @param phoneKeyId Phone key integer ID
     * @param userId User identifier
     * @param deviceId Device identifier
     */
    suspend fun fetchAcls(phoneKeyId: Int, userId: String, deviceId: String) {
        mutex.withLock {
            try {
                _aclState.value = AclState.Fetching
                
                val result = phoneKeyService.generateBulkAcls(phoneKeyId, userId, deviceId)
                
                result.fold(
                    onSuccess = { bulkResult ->
                        _aclState.value = AclState.Fetched(bulkResult)
                    },
                    onFailure = { error ->
                        _aclState.value = AclState.Error(error)
                    }
                )
            } catch (e: Exception) {
                _aclState.value = AclState.Error(e)
            }
        }
    }
    
    /**
     * Refresh ACLs and update state reactively.
     * 
     * @param userId User identifier
     * @param deviceId Device identifier
     */
    suspend fun refreshAcls(userId: String, deviceId: String) {
        mutex.withLock {
            try {
                _aclState.value = AclState.Fetching
                
                val result = phoneKeyService.refreshAllAcls(userId, deviceId)
                
                result.fold(
                    onSuccess = { bulkResult ->
                        _aclState.value = AclState.Fetched(bulkResult)
                    },
                    onFailure = { error ->
                        _aclState.value = AclState.Error(error)
                    }
                )
            } catch (e: Exception) {
                _aclState.value = AclState.Error(e)
            }
        }
    }
    
    /**
     * Reset monitor to idle state.
     * 
     * Call this when user logs out or when you want to clear state.
     */
    fun reset() {
        _provisioningState.value = ProvisioningState.Idle
        _aclState.value = AclState.Idle
    }
    
    // Helper to combine flows
    private fun <T1, T2, R> combine(
        flow1: Flow<T1>,
        flow2: Flow<T2>,
        transform: (T1, T2) -> R
    ): Flow<R> = kotlinx.coroutines.flow.combine(flow1, flow2, transform)
}

/**
 * Provisioning state sealed class.
 * 
 * Represents all possible states during phone key provisioning.
 */
sealed class ProvisioningState {
    /** Initial state, no operation started */
    object Idle : ProvisioningState()
    
    /** Checking if device is already provisioned */
    object Checking : ProvisioningState()
    
    /** Device is already provisioned (no action needed) */
    object AlreadyProvisioned : ProvisioningState()
    
    /** Provisioning in progress */
    object Provisioning : ProvisioningState()
    
    /** Provisioning completed successfully */
    data class Provisioned(val phoneKeyId: Int) : ProvisioningState()
    
    /** Error occurred during provisioning */
    data class Error(val error: Throwable) : ProvisioningState()
}

/**
 * ACL fetch state sealed class.
 * 
 * Represents all possible states during ACL fetching.
 */
sealed class AclState {
    /** Initial state, no fetch started */
    object Idle : AclState()
    
    /** Fetching ACLs from backend */
    object Fetching : AclState()
    
    /** ACLs fetched successfully */
    data class Fetched(val result: BulkAclResult) : AclState()
    
    /** Error occurred during ACL fetch */
    data class Error(val error: Throwable) : AclState()
}

/**
 * Factory method to create PhoneKeyStateMonitor.
 * 
 * Usage:
 * ```kotlin
 * val monitor = createPhoneKeyStateMonitor(context)
 * ```
 * 
 * @param context Application context
 * @return Configured PhoneKeyStateMonitor instance
 */
fun createPhoneKeyStateMonitor(context: Context): PhoneKeyStateMonitor {
    val service = PhoneKeyAccessService.getInstance()
    return PhoneKeyStateMonitor(context, service)
}
