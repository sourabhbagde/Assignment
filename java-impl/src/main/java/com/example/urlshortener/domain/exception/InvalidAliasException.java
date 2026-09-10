package com.example.urlshortener.domain.exception;

/** A custom alias violated the character/length/reserved-word rules. */
public class InvalidAliasException extends DomainException {

    public InvalidAliasException(String message) {
        super("INVALID_ALIAS", message);
    }
}
