package com.example.urlshortener.domain.exception;

/** The requested custom alias is already taken. Maps to HTTP 409. */
public class AliasAlreadyExistsException extends DomainException {

    public AliasAlreadyExistsException(String alias) {
        super("ALIAS_TAKEN", "alias \"" + alias + "\" is already taken");
    }
}
