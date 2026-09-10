package com.example.urlshortener.domain.exception;

/**
 * The service could not allocate a unique random short code within the retry
 * budget. Extremely unlikely (keyspace is 62^7); indicates the table is
 * saturated or something is wrong. Maps to HTTP 503 so the client retries.
 */
public class CodeAllocationException extends DomainException {

    public CodeAllocationException() {
        super("CODE_ALLOCATION_FAILED", "could not allocate a unique short code, please retry");
    }
}
