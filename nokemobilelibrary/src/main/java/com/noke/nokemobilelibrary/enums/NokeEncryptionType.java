package com.noke.nokemobilelibrary.enums;

/**
 * Represents the encryption/signing type used by a Noke device.
 * Legacy devices use symmetric ENCRYPTION; ION-2 devices use ECDSA SIGNING.
 */
public enum NokeEncryptionType {
    ENCRYPTION,
    SIGNING;
}
