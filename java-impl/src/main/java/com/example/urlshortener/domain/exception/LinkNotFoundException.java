package com.example.urlshortener.domain.exception;

/** No link exists for the given code. Maps to HTTP 404. */
public class LinkNotFoundException extends DomainException {

    public LinkNotFoundException(String code) {
        super("LINK_NOT_FOUND", "no short link for \"" + code + "\"");
    }
}
