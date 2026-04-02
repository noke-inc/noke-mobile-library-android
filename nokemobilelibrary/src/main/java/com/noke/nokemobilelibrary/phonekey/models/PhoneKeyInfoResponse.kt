package com.noke.nokemobilelibrary.phonekey.models

/**
 * Response from phone key provisioning operation.
 *
 * This model represents the backend response when provisioning a new phone key.
 * It contains the assigned phone key ID and status information.
 *
 * ## Success Response
 * When provisioning succeeds:
 * - [keyId] is populated with the assigned phone key ID
 * - [status] is typically "success"
 * - [error] is null
 *
 * ## Failure Response
 * When provisioning fails:
 * - [keyId] is null
 * - [status] may indicate failure reason
 * - [error] contains error details
 *
 * @property keyId The unique phone key identifier assigned by backend (null on failure)
 * @property status Status string from backend ("success", "error", etc.)
 * @property error Optional error message if provisioning failed
 * @property detail Optional additional detail about the response
 */
data class PhoneKeyInfoResponse(
    val keyId: Int?,
    val status: String = "unknown",
    val error: String? = null,
    val detail: String? = null
) {
    /**
     * Whether the provisioning was successful.
     * Success requires non-null keyId and no error.
     */
    val isSuccess: Boolean
        get() = keyId != null && error == null

    /**
     * User-friendly status message combining error and detail fields.
     * Prioritizes error message, then detail, then status.
     */
    val statusMessage: String
        get() = error ?: detail ?: status

    companion object {
        /**
         * Create a success response with phone key ID.
         */
        fun success(keyId: Int): PhoneKeyInfoResponse {
            return PhoneKeyInfoResponse(
                keyId = keyId,
                status = "success",
                error = null,
                detail = null
            )
        }

        /**
         * Create a failure response with error message.
         */
        fun failure(error: String, detail: String? = null): PhoneKeyInfoResponse {
            return PhoneKeyInfoResponse(
                keyId = null,
                status = "error",
                error = error,
                detail = detail
            )
        }
    }
}
