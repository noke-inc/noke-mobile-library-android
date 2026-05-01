package com.noke.nokemobilelibrary.phonekey

import com.noke.nokemobilelibrary.phonekey.models.BulkAclResult
import com.noke.nokemobilelibrary.phonekey.models.PhoneKeyInfoResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch

/**
 * Flow-based reactive extensions for PhoneKeyAccessService.
 * 
 * This module provides Kotlin Flow wrappers around Phone Key operations,
 * enabling reactive programming patterns and stream-based processing.
 * 
 * ## Benefits of Flow-based APIs
 * 
 * - **Reactive**: Observe operations as streams of events
 * - **Backpressure**: Built-in flow control for async operations
 * - **Composable**: Chain multiple operations with flow operators
 * - **Cancellable**: Automatically cancelled when coroutine scope is cancelled
 * - **Cold streams**: Operations only execute when collected
 * 
 * ## Usage Examples
 * 
 * ### Basic Flow Collection
 * 
 * ```kotlin
 * lifecycleScope.launch {
 *     phoneKeyService.provisionPhoneKeyFlow(userId, deviceId)
 *         .catch { error ->
 *             Log.e(TAG, "Provisioning error", error)
 *             emit(Result.failure(error))
 *         }
 *         .collect { result ->
 *             result.fold(
 *                 onSuccess = { phoneKeyId -> 
 *                     updateUI("Provisioned: $phoneKeyId")
 *                 },
 *                 onFailure = { error ->
 *                     showError(error.message)
 *                 }
 *             )
 *         }
 * }
 * ```
 * 
 * ### Chaining Flow Operations
 * 
 * ```kotlin
 * lifecycleScope.launch {
 *     phoneKeyService.provisionPhoneKeyFlow(userId, deviceId)
 *         .mapNotNull { it.getOrNull() }  // Extract successful results
 *         .flatMapConcat { phoneKeyId ->
 *             phoneKeyService.generateBulkAclsFlow(phoneKeyId, userId, deviceId)
 *         }
 *         .collect { aclResult ->
 *             updateLockList(aclResult)
 *         }
 * }
 * ```
 * 
 * ### Retry with Flow
 * 
 * ```kotlin
 * phoneKeyService.provisionPhoneKeyFlow(userId, deviceId)
 *     .retry(3) { error ->
 *         error is IOException  // Retry only network errors
 *     }
 *     .collect { result ->
 *         handleResult(result)
 *     }
 * ```
 * 
 * ### Combine Multiple Flows
 * 
 * ```kotlin
 * combine(
 *     phoneKeyService.isProvisionedFlow(userId, deviceId),
 *     phoneKeyService.getPhoneKeyIdFlow(userId, deviceId)
 * ) { isProvisioned, phoneKeyId ->
 *     SetupState(isProvisioned, phoneKeyId.getOrNull())
 * }.collect { state ->
 *     updateSetupUI(state)
 * }
 * ```
 * 
 * @since 1.1.0 (Phase 4 - Reactive Extensions)
 */

/**
 * Provision a phone key as a Flow.
 * 
 * Emits a single [Result] containing [PhoneKeyInfoResponse] on success,
 * or a failure with [NokeMobileLibraryError] on error.
 * 
 * @param userId Unique user identifier
 * @param udid Unique device identifier
 * @return Cold Flow emitting Result<PhoneKeyInfoResponse>
 */
fun PhoneKeyAccessService.provisionPhoneKeyFlow(
    userId: String,
    udid: String
): Flow<Result<PhoneKeyInfoResponse>> = flow {
    val result = provisionPhoneKey(userId, udid)
    emit(result)
}

/**
 * Check if device is provisioned as a Flow.
 * 
 * Emits a single [Result] containing true if provisioned, false otherwise.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Cold Flow emitting Result<Boolean>
 */
fun PhoneKeyAccessService.isProvisionedFlow(
    userId: String,
    udid: String
): Flow<Result<Boolean>> = flow {
    val result = runCatching { isProvisioned(userId, udid) }.getOrElse { Result.failure(it) }
    emit(result)
}

/**
 * Get phone key ID as a Flow.
 * 
 * Emits a single [Result] containing the phone key ID string.
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Cold Flow emitting Result<Int>
 */
fun PhoneKeyAccessService.getPhoneKeyIdFlow(
    userId: String,
    udid: String
): Flow<Result<Int>> = flow {
    val result = runCatching { getPhoneKeyId(userId, udid) }.getOrElse { Result.failure(it) }
    emit(result)
}

/**
 * Generate bulk ACLs as a Flow.
 * 
 * Emits a single [Result] containing [BulkAclResult] with fetch statistics.
 * 
 * Use this for reactive UI updates during bulk ACL fetching:
 * ```kotlin
 * generateBulkAclsFlow(phoneKeyId, userId, deviceId)
 *     .onEach { result ->
 *         result.onSuccess { bulkResult ->
 *             progressBar.visibility = View.GONE
 *             showLockCount(bulkResult.successCount)
 *         }
 *     }
 *     .launchIn(lifecycleScope)
 * ```
 * 
 * @param phoneKeyId Phone key integer ID
 * @param userId User identifier
 * @param udid Device identifier
 * @return Cold Flow emitting Result<BulkAclResult>
 */
fun PhoneKeyAccessService.generateBulkAclsFlow(
    phoneKeyId: Int,
    userId: String,
    udid: String
): Flow<Result<BulkAclResult>> = flow {
    val result = runCatching { generateBulkAcls(phoneKeyId, userId, udid) }.getOrElse { Result.failure(it) }
    emit(result)
}

/**
 * Generate single ACL as a Flow.
 * 
 * Emits a single [Result] indicating success or failure.
 * 
 * @param userId User ID as integer
 * @param lockMac Lock MAC address
 * @param phoneKeyId Phone key integer ID
 * @param udid Device identifier
 * @return Cold Flow emitting Result<Unit>
 */
fun PhoneKeyAccessService.generateAclFlow(
    userId: Int,
    lockMac: String,
    phoneKeyId: Int,
    udid: String
): Flow<Result<Unit>> = flow {
    val result = runCatching { generateAcl(userId, lockMac, phoneKeyId, udid) }.getOrElse { Result.failure(it) }
    emit(result)
}

/**
 * Refresh all ACLs as a Flow.
 * 
 * Emits a single [Result] containing [BulkAclResult] after refresh.
 * 
 * Perfect for pull-to-refresh with Flow:
 * ```kotlin
 * swipeRefresh.setOnRefreshListener {
 *     lifecycleScope.launch {
 *         refreshAllAclsFlow(userId, deviceId)
 *             .onEach { result ->
 *                 swipeRefresh.isRefreshing = false
 *                 result.onSuccess { handleRefreshSuccess(it) }
 *             }
 *             .launchIn(this)
 *     }
 * }
 * ```
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Cold Flow emitting Result<BulkAclResult>
 */
fun PhoneKeyAccessService.refreshAllAclsFlow(
    userId: String,
    udid: String
): Flow<Result<BulkAclResult>> = flow {
    val result = runCatching { refreshAllAcls(userId, udid) }.getOrElse { Result.failure(it) }
    emit(result)
}

/**
 * Complete provisioning workflow as a Flow.
 * 
 * This Flow emits multiple events representing the provisioning workflow:
 * 1. Check provisioning status
 * 2. Provision if needed (or get existing phone key ID)
 * 3. Fetch bulk ACLs
 * 
 * Events are emitted as [ProvisioningEvent] sealed class instances.
 * 
 * Example usage:
 * ```kotlin
 * completeProvisioningWorkflowFlow(userId, deviceId)
 *     .onEach { event ->
 *         when (event) {
 *             is ProvisioningEvent.CheckingStatus -> 
 *                 showProgress("Checking status...")
 *             is ProvisioningEvent.AlreadyProvisioned -> 
 *                 showInfo("Device already provisioned")
 *             is ProvisioningEvent.Provisioning -> 
 *                 showProgress("Provisioning device...")
 *             is ProvisioningEvent.Provisioned -> 
 *                 showSuccess("Provisioned: ${event.phoneKeyId}")
 *             is ProvisioningEvent.FetchingAcls -> 
 *                 showProgress("Fetching locks...")
 *             is ProvisioningEvent.AclsFetched -> 
 *                 showSuccess("${event.result.successCount} locks ready")
 *             is ProvisioningEvent.Error -> 
 *                 showError(event.error.message)
 *         }
 *     }
 *     .launchIn(lifecycleScope)
 * ```
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @return Cold Flow emitting [ProvisioningEvent] instances
 */
fun PhoneKeyAccessService.completeProvisioningWorkflowFlow(
    userId: String,
    udid: String
): Flow<ProvisioningEvent> = flow {
    // Step 1: Check provisioning status
    emit(ProvisioningEvent.CheckingStatus)
    
    val isProvisionedResult = isProvisioned(userId, udid)
    
    isProvisionedResult.fold(
        onSuccess = { isProvisioned ->
            var phoneKeyId: Int? = null
            
            if (isProvisioned) {
                // Already provisioned - get existing ID
                emit(ProvisioningEvent.AlreadyProvisioned)
                
                val idResult = getPhoneKeyId(userId, udid)
                idResult.fold(
                    onSuccess = { keyId ->
                        phoneKeyId = keyId
                        emit(ProvisioningEvent.Provisioned(keyId))
                    },
                    onFailure = { error ->
                        emit(ProvisioningEvent.Error(error))
                        return@flow
                    }
                )
            } else {
                // Need to provision
                emit(ProvisioningEvent.Provisioning)
                
                val provisionResult = provisionPhoneKey(userId, udid)
                provisionResult.fold(
                    onSuccess = { response ->
                        val keyId = response.keyId
                            ?: throw IllegalStateException("Provisioning succeeded but keyId is null")
                        phoneKeyId = keyId
                        emit(ProvisioningEvent.Provisioned(keyId))
                    },
                    onFailure = { error ->
                        emit(ProvisioningEvent.Error(error))
                        return@flow
                    }
                )
            }
            
            // Step 2: Fetch ACLs
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
}

/**
 * Events emitted during the complete provisioning workflow.
 * 
 * Use these events to update UI state reactively during the provisioning process.
 */
sealed class ProvisioningEvent {
    /** Checking if device is already provisioned */
    object CheckingStatus : ProvisioningEvent()
    
    /** Device is already provisioned */
    object AlreadyProvisioned : ProvisioningEvent()
    
    /** Starting provisioning process */
    object Provisioning : ProvisioningEvent()
    
    /** Provisioning completed successfully */
    data class Provisioned(val phoneKeyId: Int) : ProvisioningEvent()
    
    /** Fetching ACLs from backend */
    object FetchingAcls : ProvisioningEvent()
    
    /** ACLs fetched successfully */
    data class AclsFetched(val result: BulkAclResult) : ProvisioningEvent()
    
    /** Error occurred during workflow */
    data class Error(val error: Throwable) : ProvisioningEvent()
}

/**
 * Advanced: Create a continuous polling Flow for ACL refresh.
 * 
 * This Flow repeatedly fetches ACLs at the specified interval,
 * useful for keeping ACLs fresh in always-on applications.
 * 
 * **Use with caution**: This creates a continuous polling loop.
 * Only use when you need real-time ACL updates.
 * 
 * Example usage:
 * ```kotlin
 * class LockService : Service() {
 *     private val pollingJob = serviceScope.launch {
 *         phoneKeyService.pollAclUpdatesFlow(
 *             userId = userId,
 *             deviceId = deviceId,
 *             intervalMillis = 5.minutes.inWholeMilliseconds
 *         )
 *         .catch { error ->
 *             Log.e(TAG, "Polling error", error)
 *         }
 *         .collect { result ->
 *             result.onSuccess { bulkResult ->
 *                 notifyAclUpdate(bulkResult)
 *             }
 *         }
 *     }
 *     
 *     override fun onDestroy() {
 *         pollingJob.cancel()
 *         super.onDestroy()
 *     }
 * }
 * ```
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @param intervalMillis Polling interval in milliseconds (default: 5 minutes)
 * @return Hot Flow emitting Result<BulkAclResult> at regular intervals
 */
fun PhoneKeyAccessService.pollAclUpdatesFlow(
    userId: String,
    udid: String,
    intervalMillis: Long = 5 * 60 * 1000L  // 5 minutes default
): Flow<Result<BulkAclResult>> = flow {
    while (true) {
        val result = refreshAllAcls(userId, udid)
        emit(result)
        kotlinx.coroutines.delay(intervalMillis)
    }
}

/**
 * Example: Combined provisioning and polling Flow.
 * 
 * This advanced pattern provisions a device (if needed) and then
 * starts continuous ACL polling.
 * 
 * ```kotlin
 * provisionAndPollFlow(userId, deviceId, intervalMillis = 5.minutes.inWholeMilliseconds)
 *     .onEach { event ->
 *         when (event) {
 *             is ProvisioningEvent.Provisioned -> 
 *                 Log.d(TAG, "Setup complete, starting polling")
 *             is ProvisioningEvent.AclsFetched -> 
 *                 updateLockCache(event.result)
 *             is ProvisioningEvent.Error -> 
 *                 Log.e(TAG, "Error", event.error)
 *             else -> { /* other events */ }
 *         }
 *     }
 *     .launchIn(serviceScope)
 * ```
 * 
 * @param userId User identifier
 * @param udid Device identifier
 * @param intervalMillis Polling interval in milliseconds
 * @return Flow emitting [ProvisioningEvent] instances
 */
fun PhoneKeyAccessService.provisionAndPollFlow(
    userId: String,
    udid: String,
    intervalMillis: Long = 5 * 60 * 1000L
): Flow<ProvisioningEvent> = flow {
    // First, complete provisioning workflow
    completeProvisioningWorkflowFlow(userId, udid)
        .collect { event ->
            emit(event)
            
            // If provisioning completed successfully, start polling
            if (event is ProvisioningEvent.AclsFetched) {
                // Wait for interval, then start continuous polling
                kotlinx.coroutines.delay(intervalMillis)
                
                // Continuous polling
                while (true) {
                    emit(ProvisioningEvent.FetchingAcls)
                    
                    val result = refreshAllAcls(userId, udid)
                    result.fold(
                        onSuccess = { bulkResult ->
                            emit(ProvisioningEvent.AclsFetched(bulkResult))
                        },
                        onFailure = { error ->
                            emit(ProvisioningEvent.Error(error))
                        }
                    )
                    
                    kotlinx.coroutines.delay(intervalMillis)
                }
            }
        }
}
