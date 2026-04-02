package com.noke.nokemobilelibrary;

import com.noke.nokemobilelibrary.enums.NokeDeviceSigningError;

public class NokeDeviceSigningException extends Exception {
    private final NokeDeviceSigningError error;

    public NokeDeviceSigningException(NokeDeviceSigningError error) {
        super(error.getDescription()); // use your error message
        this.error = error;
    }

    public NokeDeviceSigningError getError() {
        return error;
    }
}
