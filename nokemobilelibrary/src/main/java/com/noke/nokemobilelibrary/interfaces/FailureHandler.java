package com.noke.nokemobilelibrary.interfaces;

/**
 * Callback interface for failure events.
 */
public interface FailureHandler {
    void onFailure(Throwable error);
}
