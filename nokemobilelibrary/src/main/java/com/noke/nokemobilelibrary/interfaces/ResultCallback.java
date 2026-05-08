package com.noke.nokemobilelibrary.interfaces;

public interface ResultCallback<T> {
    void onSuccess(T value);

    void onFailure(Throwable error);
}
