package com.example.urlshortener.domain.exception;

/** The destination URL failed a syntax, scheme, size or SSRF-safety check. */
public class InvalidUrlException extends DomainException {

    public InvalidUrlException(String message) {
        super("INVALID_URL", message);
    }
}
