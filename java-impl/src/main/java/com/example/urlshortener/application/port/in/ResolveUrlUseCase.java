package com.example.urlshortener.application.port.in;

/** Primary port: resolve a code to its destination for redirecting. */
public interface ResolveUrlUseCase {

    /**
     * @throws com.example.urlshortener.domain.exception.LinkNotFoundException if unknown
     * @throws com.example.urlshortener.domain.exception.LinkGoneException     if deactivated or expired
     */
    Resolution resolve(String code);

    record Resolution(long linkId, String code, String longUrl) {
    }
}
