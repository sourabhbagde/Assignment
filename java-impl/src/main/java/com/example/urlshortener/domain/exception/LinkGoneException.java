package com.example.urlshortener.domain.exception;

/**
 * The link exists but is no longer usable. Maps to HTTP 410 Gone. The specific
 * {@code errorCode} ({@code LINK_DEACTIVATED} / {@code LINK_EXPIRED}) tells the
 * caller which without leaking anything sensitive.
 */
public class LinkGoneException extends DomainException {

    private LinkGoneException(String errorCode, String message) {
        super(errorCode, message);
    }

    public static LinkGoneException deactivated(String code) {
        return new LinkGoneException("LINK_DEACTIVATED", "short link \"" + code + "\" has been deactivated");
    }

    public static LinkGoneException expired(String code) {
        return new LinkGoneException("LINK_EXPIRED", "short link \"" + code + "\" has expired");
    }
}
