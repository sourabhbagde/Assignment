package com.example.urlshortener.application.port.out;

/**
 * Output port for short-code generation. Implementations must draw from a
 * cryptographically strong source and must not encode any monotonic counter
 * (no enumeration / creation-order leak).
 */
public interface ShortCodeGenerator {

    /** @return a fresh candidate code of the configured length (base62). */
    String newCode();
}
