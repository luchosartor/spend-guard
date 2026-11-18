package com.spendguard.exception;

/** Thrown when a requested state transition isn't valid given the entity's current state. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
