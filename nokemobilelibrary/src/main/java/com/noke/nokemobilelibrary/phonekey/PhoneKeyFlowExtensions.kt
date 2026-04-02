package com.noke.nokemobilelibrary.phonekey

import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * Reactive Flow-based extensions for [PhoneKeyAccessService].
 * 
 * This file provides extension functions that wrap suspend-based operations into Kotlin Flows
 * for reactive programming patterns, enabling stream-based processing, UI reactivity, and
 * compositional operations.
 * 
 * ## Flow vs Suspend Functions
 * 
 * **Use Suspend Functions When:**
 * - You need a single result (one-shot operations)
 * - You're using callbacks/listeners already
 * - Simple request-response patterns
 * 
 * **Use Flow Extensions When:**
 * - You want reactive UI updates
 * - You need to chain multiple operations
 * - You want built-in backpressure and cancellation
 * - You're building a stream processing pipeline
 * - You want to combine multiple async operations reactively
 * 
 * ## Architecture
 * 
 * All flows are **cold** - they don't execute until collected:
 * ```kotlin
 * val flow = service.provisionPhoneKeyFlow(userId, udid)  // Does nothing yet
 * flow.collect { result -> /* Now it executes */ }
 * ```
 * 
 * ## Usage Examples
 * 
 * ### Basic Flow Collection
 * 
 * ```kotlin
 * // In ViewModel with lifecycle-aware scope
 * class SetupViewModel : ViewModel() {
 *     val service = PhoneKeyAccessService.getInstance()
 *     
 *     fun provisionDevice(userId: String, deviceId: String) {
 *         viewModelScope.launch {
 *             service.provisionPhoneKeyFlow(userId, deviceId)
 *                 .catch { error ->
 *                     _uiState.value = UiState.Error(error.message)
 *                 }
 *                 .collect { result ->
 *                     result.fold(
 *                         onSuccess = { phoneKeyId ->
 *                             _uiState.value = UiState.Success(phoneKeyId)
 *                         },
 *                         onFailure = { error ->
 *                             _uiState.value = UiState.Error(error.message)
 *                         }
 *                     )
 *                 }
 *         }
 *     }
 * }
 * ```
 * 
 * ### Chaining Operations
 * 
 * ```kotlin
 * // Provision device and then fetch ACLs
 * viewModelScope.launch {
 *     service.provisionPhoneKeyFlow(userId, deviceId)
 *         .map { result ->
 *             result.getOrThrow()  // Throw on error
 *         }
 *         .flatMapConcat { phoneKeyId ->
 *             service.generateBulkAclsFlow(phoneKeyId, userId, deviceId)
 *         }
 *         .catch { error ->
 *             Log.e(TAG, "Operation failed", error)
 *         }
 *         .collect { aclResult ->
 *             aclResult.onSuccess { bulkResult ->
 *                 Log.d(TAG, "${bulkResult.successCount} ACLs fetched")
 *             }
 *         }
 * }
 * ```
 * 
 * ### Advanced Workflow with Multi-Event Emission
 * 
 * ```kotlin
 * // Use completeProvisioningWorkflowFlow for step-by-step reactive updates
 * viewModelScope.launch {
 *     service.completeProvisioningWorkflowFlow(userId, deviceId)
 *         .collect { event ->
 *             when (event) {
 *                 is ProvisioningEvent.CheckingStatus -> {
 *                     showProgress("Checking status...")
 *                 }
 *                 is ProvisioningEvent.AlreadyProvisioned -> {
 *                     showSuccess("Already provisioned")
 *                 }
 *                 is ProvisioningEvent.Provisioning -> {
 *                     showProgress("Provisioning device...")
 *                 }
 *                 is ProvisioningEvent.Provisioned -> {
 *                     Log.d(TAG, "Phone Key ID: ${event.phoneKeyId}")
 *                 }
 *                 is ProvisioningEvent.FetchingAcls -> {
 *                     showProgress("Fetching lock access...")
 *                 }
 *                 is ProvisioningEvent.AclsFetched -> {
 *                     val count = event.result.successCount
 *                     showSuccess("$count locks ready")
 *                 }
 *                 is ProvisioningEvent.Error -> {
 *                     showError(event.error.message)
 *                 }
 *             }
 *         }
 * }
 * ```
 * 
 * ### Retry with Exponential Backoff
 * 
 * ```kotlin
 * // Automatic retry on failure
 * service.provisionPhoneKeyFlow(userId, deviceId)
 *     .retry(3) { error ->
 *         if (error is IOException) {
 *             delay(1000)  // Wait 1s before retry
 *             true
 *         } else {
 *             false  // Don't retry auth errors
 *         }
 *     }
 *     .collect { result -> /* Handle result */ }
 * ```
 * 
 * ### Combining Multiple Flows
 * 
 * ```kotlin
 * // Check provisioning status for multiple devices
 * val devices = listOf("device1", "device2", "device3")
 * 
 * devices.map { deviceId ->
 *     service.isProvisionedFlow(userId, deviceId)
 * }
 * .merge()  // Combine all flows
 * .collect { result ->
 *     result.onSuccess { isProvisioned ->
 *         Log.d(TAG, "Device provisioned: $isProvisioned")
 *     }
 * }
 * ```
 * 
 * ### Reactive UI Updates
 * 
 * ```kotlin
 * // In ViewModel
 * private val _setupState = MutableStateFlow<SetupState>(SetupState.Idle)
 * val setupState: StateFlow<SetupState> = _setupState.asStateFlow()
 * 
 * init {
 *     viewModelScope.launch {
 *         service.completeProvisioningWorkflowFlow(userId, deviceId)
 *             .collect { event ->
 *                 _setupState.value = when (event) {
 *                     is ProvisioningEvent.Provisioning -> 
 *                         SetupState.Provisioning
 *                     is ProvisioningEvent.FetchingAcls -> 
 *                         SetupState.FetchingLocks
 *                     is ProvisioningEvent.AclsFetched -> 
 *                         SetupState.Complete(event.result.successCount)
 *                     is ProvisioningEvent.Error -> 
 *                         SetupState.Error(event.error.message)
 *                     else -> _setupState.value
 *                 }
 *             }
 *     }
 * }
 * 
 * // In Activity/Fragment
 * lifecycleScope.launch {
 *     viewModel.setupState.collect { state ->
 *         when (state) {
 *             SetupState.Idle -> showIdleUI()
 *             SetupState.Provisioning -> showProgress("Setting up device...")
 *             SetupState.FetchingLocks -> showProgress("Loading locks...")
 *             is SetupState.Complete -> navigateToLockList()
 *             is SetupState.Error -> showError(state.message)
 *         }
 *     }
 * }
 * ```
 * 
 * ## Performance Considerations
 * 
 * - All flows are cold (lazy evaluation)
 * - Flows automatically cancel when scope is cancelled
 * - Use `flowOn(Dispatchers.IO)` for background work if needed
 * - Consider using `shareIn`/`stateIn` to share flow results
 * 
 * ## Thread Safety
 * 
 * All flow operations inherit thread safety from [PhoneKeyAccessService].
 * You can collect flows from any coroutine context.
 * 
 * @since 1.1.0 (Phase 4 - Reactive Extensions)
 */

/**
 * Provision a phone key as a Flow.
 * 
 * This creates a cold flow that provisions a phone key when collected.
 * The flow emits once with the result and then completes.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Flow emitting Result<Int> with phone key ID
 */
fun PhoneKeyAccessService.provisionPhoneKeyFlow(
    userId: String,
    udid: String
): Flow<Result<Int>> = flow {
    val result = provisionPhoneKey(userId, udid)
    emit(result)
}

/**
 * Check if device is provisioned as a Flow.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Flow emitting Result<Boolean>
 */
fun PhoneKeyAccessService.isProvisionedFlow(
    userId: String,
    udid: String
): Flow<Result<Boolean>> = flow {
    val result = isProvisioned(userId, udid)
    emit(result)
}

/**
 * Get phone key ID as a Flow.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Flow emitting Result<String> with phone key ID
 */
fun PhoneKeyAccessService.getPhoneKeyIdFlow(
    userId: String,
    udid: String
): Flow<Result<String>> = flow {
    val result = getPhoneKeyId(userId, udid)
    emit(result)
}

/**
 * Generate bulk ACLs as a Flow.
 * 
 * @param phoneKeyId Phone key numeric ID
 * @param userId User identifier
 * @param udid Device identifier
 * @return Flow emitting Result<BulkAclResult>
 */
fun PhoneKeyAccessService.generateBulkAclsFlow(
    phoneKeyId: Int,
    userId: String,
    udid: String
): Flow<Result<BulkAclResult>> = flow {
    val result = generateBulkAcls(phoneKeyId, userId, udid)
    emit(result)
}

/**
 * Generate single ACL as a Flow.
 * 
 * @param userId Numeric user ID
 * @param lockMac Lock MAC address
 * @param phoneKeyId Phone key numeric ID
 * @param udid Device identifier
 * @return Flow emitting Result<Unit>
 */
fun PhoneKeyAccessService.generateAclFlow(
    userId: Int,
    lockMac: String,
    phoneKeyId: Int,
    udid: String
): Flow<Result<Unit>> = flow {
    val result = generateAcl(userId, lockMac, phoneKeyId, udid)
    emit(result)
}

/**
 * Refresh all ACLs as a Flow.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Flow emitting Result<BulkAclResult>
 */
fun PhoneKeyAccessService.refreshAllAclsFlow(
    userId: String,
    udid: String
): Flow<Result<BulkAclResult>> = flow {
    val result = refreshAllAcls(userId, udid)
    emit(result)
}

/**
 * Complete provisioning workflow as a multi-event Flow.
 * 
 * This reactive flow emits multiple events throughout the provisioning workflow,
 * enabling fine-grained UI updates for each step.
 * 
 * **Events emitted in sequence:**
 * 1. [ProvisioningEvent.CheckingStatus] - Starting status check
 * 2. [ProvisioningEvent.AlreadyProvisioned] OR [ProvisioningEvent.Provisioning]
 * 3. [ProvisioningEvent.Provisioned] - Provisioning complete (includes phoneKeyId)
 * 4. [ProvisioningEvent.FetchingAcls] - Starting ACL fetch
 * 5. [ProvisioningEvent.AclsFetched] - ACLs fetched (includes result)
 * 6. [ProvisioningEvent.Error] - If any step fails
 * 
 * Example:
 * ```kotlin
 * service.completeProvisioningWorkflowFlow(userId, deviceId)
 *     .collect { event ->
 *         when (event) {
 *             is ProvisioningEvent.CheckingStatus -> showProgress("Checking...")
 *             is ProvisioningEvent.Provisioning -> showProgress("Setting up...")
 *             is ProvisioningEvent.FetchingAcls -> showProgress("Loading locks...")
 *             is ProvisioningEvent.AclsFetched -> navigateNext()
 *             is ProvisioningEvent.Error -> showError(event.error)
 *             else -> {}
 *         }
 *     }
 * ```
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Flow emitting [ProvisioningEvent] for each workflow step
 */
fun PhoneKeyAccessService.completeProvisioningWorkflowFlow(
    userId: String,
    udid: String
): Flow<ProvisioningEvent> = flow {
    try {
        // Step 1: Check provisioning status
        emit(ProvisioningEvent.CheckingStatus)
        
        val isProvisionedResult = isProvisioned(userId, udid)
        
        isProvisionedResult.fold(
            onSuccess = { isProvisioned ->
                var phoneKeyId: Int? = null
                
                if (isProvisioned) {
                    emit(ProvisioningEvent.AlreadyProvisioned)
                    
                    // Get existing phone key ID
                    val idResult = getPhoneKeyId(userId, udid)
                    idResult.fold(
                        onSuccess = { idString ->
                            val keyId = idString.toIntOrNull()
                                ?: throw IllegalStateException("Invalid phone key ID: $idString")
                            phoneKeyId = keyId
                            emit(ProvisioningEvent.Provisioned(keyId))
                        },
                        onFailure = { error ->
                            emit(ProvisioningEvent.Error(error))
                            return@flow
                        }
                    )
                } else {
                    // Step 2: Provision if needed
                    emit(ProvisioningEvent.Provisioning)
                    
                    val provisionResult = provisionPhoneKey(userId, udid)
                    provisionResult.fold(
                        onSuccess = { keyId ->
                            phoneKeyId = keyId
                            emit(ProvisioningEvent.Provisioned(keyId))
                        },
                        onFailure = { error ->
                            emit(ProvisioningEvent.Error(error))
                            return@flow
                        }
                    )
                }
                
                // Step 3: Fetch ACLs
                // phoneKeyId is guaranteed to be non-null here (both branches assign or return)
                emit(ProvisioningEvent.FetchingAcls)
                
                val finalPhoneKeyId = phoneKeyId ?: throw IllegalStateException("Phone key ID not set")
                val aclResult = generateBulkAcls(finalPhoneKeyId, userId, udid)
                aclResult.fold(
                    onSuccess = { bulkResult ->
                        emit(ProvisioningEvent.AclsFetched(bulkResult))
                    },
                    onFailure = { error ->
                        emit(ProvisioningEvent.Error(error))
                    }
                )
            },
            onFailure = { error ->
                emit(ProvisioningEvent.Error(error))
            }
        )
    } catch (e: Exception) {
        emit(ProvisioningEvent.Error(e))
    }
}

/**
 * Events emitted during provisioning workflow.
 * 
 * These events represent all possible states during the complete
 * provisioning + ACL fetch workflow.
 */
sealed class ProvisioningEvent {
    /** Checking if device is already provisioned */
    object CheckingStatus : ProvisioningEvent()
    
    /** Device is already provisioned (skipping provision step) */
    object AlreadyProvisioned : ProvisioningEvent()
    
    /** Provisioning device now */
    object Provisioning : ProvisioningEvent()
    
    /** Provisioning completed successfully */
    data class Provisioned(val phoneKeyId: Int) : ProvisioningEvent()
    
    /** Fetching ACLs from backend */
    object FetchingAcls : ProvisioningEvent()
    
    /** ACLs fetched successfully */
    data class AclsFetched(val result: BulkAclResult) : ProvisioningEvent()
    
    /** Error occurred at any step */
    data class Error(val error: Throwable) : ProvisioningEvent()
}

/**
 * Poll for ACL updates continuously.
 * 
 * This creates a never-ending flow that polls for ACL updates at regular intervals.
 * The flow continues emitting until it's cancelled.
 * 
 * **Use with caution:** This creates an infinite flow that must be cancelled manually.
 * 
 * Example:
 * ```kotlin
 * // Poll every 30 seconds in a ViewModel
 * val aclUpdates = service.pollAclUpdatesFlow(userId, deviceId, 30_000)
 *     .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)
 * 
 * // In Activity/Fragment
 * lifecycleScope.launch {
 *     viewModel.aclUpdates
 *         .collect { result ->
 *             result.onSuccess { bulkResult ->
 *                 updateLockList(bulkResult)
 *             }
 *         }
 * }
 * ```
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @param intervalMillis Polling interval in milliseconds (default: 60000 = 1 minute)
 * @return Flow emitting Result<BulkAclResult> at each interval
 */
fun PhoneKeyAccessService.pollAclUpdatesFlow(
    userId: String,
    udid: String,
    intervalMillis: Long = 60_000
): Flow<Result<BulkAclResult>> = flow {
    while (true) {
        val result = refreshAllAcls(userId, udid)
        emit(result)
        delay(intervalMillis)
    }
}

/**
 * Provision device and poll for ACL updates.
 * 
 * This combines provisioning workflow with continuous ACL polling.
 * After successful provisioning, it starts polling for ACL updates.
 * 
 * **Use with caution:** Creates an infinite flow after provisioning.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @param intervalMillis Polling interval in milliseconds
 * @return Flow emitting [ProvisioningEvent] for setup, then continuous ACL updates
 */
fun PhoneKeyAccessService.provisionAndPollFlow(
    userId: String,
    udid: String,
    intervalMillis: Long = 60_000
): Flow<ProvisioningEvent> = flow {
    // First complete provisioning workflow
    completeProvisioningWorkflowFlow(userId, udid)
        .collect { event ->
            emit(event)
            
            // If workflow completed successfully, start polling
            if (event is ProvisioningEvent.AclsFetched) {
                // Start continuous polling
                pollAclUpdatesFlow(userId, udid, intervalMillis)
                    .collect { result ->
                        result.fold(
                            onSuccess = { bulkResult ->
                                emit(ProvisioningEvent.AclsFetched(bulkResult))
                            },
                            onFailure = { error ->
                                emit(ProvisioningEvent.Error(error))
                            }
                        )
                    }
            }
        }
}
