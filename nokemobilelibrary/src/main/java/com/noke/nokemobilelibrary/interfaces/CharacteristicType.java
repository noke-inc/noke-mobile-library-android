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

    /** Optional: factory to create ad-hoc instances outside of enums if needed. */
    static CharacteristicType of(String tag, UUID uuid) {
        return new CharacteristicType() {
            @Override public String getTag() { return tag; }
            @Override public UUID getCharacteristicUuid() { return uuid; }
        };
    }
}
