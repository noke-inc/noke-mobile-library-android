package com.noke.nokemobilelibrary.helpers;

import com.noke.nokemobilelibrary.interfaces.NokeErrorCode;

public final class NokeErrorMapper {
    private NokeErrorMapper() {}

    public static @NokeErrorCode int fromNokeMobileError(int legacyCode) {
        // sanity checks or direct return; they’re currently identical
        return legacyCode;
    }
}

