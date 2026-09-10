package com.example.urlshortener.domain.exception;

/**
 * Base type for every <em>expected</em> business failure. Carries a stable,
 * machine-readable {@code errorCode} that the web layer maps to an HTTP status.
 * Messages are safe to return to clients (no internal detail, no PII).
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
