package com.noke.nokemobilelibrary.phonekey.models

import android.util.Base64
import org.threeten.bp.Instant
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phone Key Access Control List (ACL) - Cryptographically signed permission token for ION-2 locks.
 *
 * An ACL is a binary-encoded, signed token that grants specific permissions to a phone key
 * for accessing a particular lock during a defined time window.
 *
 * ## Structure
 * The ACL contains:
 * - **trackingId**: Unique identifier for this ACL instance
 * - **lockMac**: Target lock's MAC address (e.g., "AA:BB:CC:DD:EE:FF")
 * - **issuedAt/expiresAt**: Validity time window (Unix epoch seconds)
 * - **schedule**: Lock-specific schedule string (format defined by lock firmware)
 * - **phonePubKey**: X9.62 uncompressed EC public key (65 bytes, Base64-encoded)
 * - **permissions**: Set of granted permissions (unlock, override, manage)
 *
 * ## Binary Encoding
 * The [toBytes] method encodes the ACL into a binary format for transmission to the lock:
 * ```
 * | trackingId (4 bytes) | lockMac length (1 byte) | lockMac (variable) |
 * | issuedAt (8 bytes) | expiresAt (8 bytes) | schedule length (2 bytes) |
 * | schedule (variable) | phonePubKey (65 bytes) | permissions (4 bytes) |
 * ```
 * All multi-byte fields use big-endian byte order.
 *
 * ## Signature Verification
 * The binary ACL is signed by the backend using the lock's private key.
 * The lock verifies the signature before granting access, ensuring the ACL hasn't been tampered with.
 *
 * ## Usage
 * ```kotlin
 * val acl = PhoneKeyAcl(
 *     trackingId = 12345,
 *     lockMac = "AA:BB:CC:DD:EE:FF",
 *     issuedAt = Instant.now().epochSecond,
 *     expiresAt = Instant.now().plusDays(30).epochSecond,
 *     schedule = "RRULE:FREQ=DAILY;INTERVAL=1",
 *     phonePubKey = "base64EncodedPublicKey...",
 *     permissions = setOf(AclPermission.unlock)
 * )
 *
 * val binaryAcl = acl.toBytes()  // Ready for lock transmission
 * val pubKeyBytes = acl.phonePublicKeyBytes()  // Extract raw public key
 * val issuedInstant = acl.issuedAtInstant()  // Convert to Instant
 * ```
 *
 * @property trackingId Unique identifier for this ACL (for logging/tracking)
 * @property lockMac MAC address of the lock this ACL grants access to
 * @property issuedAt Unix epoch seconds when this ACL was issued
 * @property expiresAt Unix epoch seconds when this ACL expires
 * @property schedule Raw schedule string as provided by lock firmware
 * @property phonePubKey X9.62 uncompressed EC public key (65 bytes), Base64-encoded
 * @property permissions Set of permissions granted by this ACL
 *
 * @see AclPermission
 * @see AclEnvelope
 * @see BulkAclEnvelope
 */
data class PhoneKeyAcl(
    val trackingId: Int,
    val lockMac: String,

    /** Unix epoch seconds */
    val issuedAt: Long,
    val expiresAt: Long,

    /** Raw schedule string as provided by lock */
    val schedule: String,

    /** X9.62 uncompressed EC public key (65 bytes), Base64 on wire */
    val phonePubKey: String,

    val permissions: Set<AclPermission>
) {
    companion object {
        /** Size of X9.62 uncompressed EC public key in bytes */
        private const val X962_UNCOMPRESSED_KEY_SIZE = 65
    }
    /**
     * Encodes this ACL into its binary representation for lock transmission.
     *
     * The binary format is:
     * ```
     * | trackingId (4) | lockMacLen (1) | lockMac (var) | issuedAt (8) |
     * | expiresAt (8) | scheduleLen (2) | schedule (var) | pubKey (65) | perms (4) |
     * ```
     *
     * All multi-byte integers are big-endian.
     *
     * @return ByteArray containing the binary-encoded ACL
     * @throws IllegalArgumentException if phonePubKey doesn't decode to exactly 65 bytes
     */
    fun toBytes(): ByteArray {
        val lockMacBytes = lockMac.toByteArray(Charsets.UTF_8)
        val scheduleBytes = schedule.toByteArray(Charsets.UTF_8)
        val pubKeyBytes = Base64.decode(phonePubKey, Base64.NO_WRAP)

        require(pubKeyBytes.size == X962_UNCOMPRESSED_KEY_SIZE) {
            "phonePubKey must decode to $X962_UNCOMPRESSED_KEY_SIZE bytes (X9.62 uncompressed format), got ${pubKeyBytes.size}"
        }

        val permissionsMask = permissionsToBitmask(permissions)

        val totalSize =
            4 +                       // trackingId
            1 + lockMacBytes.size +   // lockMac with length prefix
            8 +                       // issuedAt
            8 +                       // expiresAt
            2 + scheduleBytes.size +  // schedule with length prefix
            X962_UNCOMPRESSED_KEY_SIZE +  // phonePubKey (X9.62 uncompressed)
            4                         // permissions bitmask

        val buffer = ByteBuffer
            .allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(trackingId)
        buffer.put(lockMacBytes.size.toByte())
        buffer.put(lockMacBytes)
        buffer.putLong(issuedAt)
        buffer.putLong(expiresAt)
        buffer.putShort(scheduleBytes.size.toShort())
        buffer.put(scheduleBytes)
        buffer.put(pubKeyBytes)
        buffer.putInt(permissionsMask)

        return buffer.array()
    }

    /**
     * Converts the permissions set to a bitmask for binary encoding.
     *
     * Bit positions:
     * - Bit 0: unlock
     * - Bit 1: overrideOverlock
     * - Bit 2: manage_locks
     *
     * @param perms Set of permissions to encode
     * @return Integer bitmask representing the permissions
     */
    private fun permissionsToBitmask(perms: Set<AclPermission>): Int {
        var mask = 0
        perms.forEach { perm ->
            mask = when (perm) {
                AclPermission.unlock ->
                    mask or (1 shl 0)

                AclPermission.overrideOverlock ->
                    mask or (1 shl 1)

                AclPermission.manage_locks ->
                    mask or (1 shl 2)
            }
        }
        return mask
    }
}

/**
 * Extension function to extract the raw public key bytes from a PhoneKeyAcl.
 *
 * The public key is stored Base64-encoded in the ACL. This function decodes it
 * to the raw 65-byte X9.62 uncompressed format.
 *
 * @return ByteArray containing the 65-byte X9.62 uncompressed public key
 */
fun PhoneKeyAcl.phonePublicKeyBytes(): ByteArray =
    Base64.decode(phonePubKey, Base64.NO_WRAP)

/**
 * Extension function to convert the issuedAt timestamp to a ThreeTen Instant.
 *
 * @return Instant representing when this ACL was issued
 */
fun PhoneKeyAcl.issuedAtInstant(): Instant =
    Instant.ofEpochSecond(issuedAt)

/**
 * Extension function to convert the expiresAt timestamp to a ThreeTen Instant.
 *
 * @return Instant representing when this ACL expires
 */
fun PhoneKeyAcl.expiresAtInstant(): Instant =
    Instant.ofEpochSecond(expiresAt)
