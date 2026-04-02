package com.noke.nokemobilelibrary.interfaces;

import java.util.UUID;

/**
 * Minimal contract for a write characteristic type:
 * - a log/metric tag
 * - the target characteristic UUID
 *
 * Keep this in a small shared module (or duplicate in both libs) to avoid cross-deps.
 */
public interface CharacteristicType {
    String getTag();
    UUID getCharacteristicUuid();

    /** Optional convenience for logging; override if you want a different format. */
    default String logTag() {
        return getTag();
    }
}
