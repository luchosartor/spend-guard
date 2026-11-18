package com.spendguard.exception;

/** Thrown when a referenced entity doesn't exist. Mapped to HTTP 404 by the global exception handler. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
