package com.example.urlshortener.domain.exception;

/**
 * A semantic validation failure not tied to a single field annotation
 * (e.g. {@code expiresAt} in the past, {@code from} after {@code to}, both
 * {@code expiresAt} and {@code ttlSeconds} supplied). Maps to HTTP 400.
 */
public class RequestValidationException extends DomainException {

    public RequestValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
