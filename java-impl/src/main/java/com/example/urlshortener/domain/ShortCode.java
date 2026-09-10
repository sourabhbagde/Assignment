package com.example.urlshortener.domain;

import com.example.urlshortener.domain.exception.InvalidAliasException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Value object for a short code / custom alias. Centralises the character rules
 * and the reserved-word list so routes can never be shadowed and aliases can
 * never carry a path separator, dot-segment or encoded traversal.
 */
public final class ShortCode {

    /** base62 — unreserved in URLs, no case-folding ambiguity, no lookalike-prone symbols. */
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Generated codes: strictly base62. Custom aliases additionally allow '-' and '_'. */
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[0-9A-Za-z_-]{3,64}$");
    private static final Pattern GENERATED_PATTERN = Pattern.compile("^[0-9A-Za-z]{3,64}$");

    /**
     * Path prefixes owned by the application. Compared case-insensitively. Any
     * alias that equals one of these (or that could collide with a static asset)
     * is rejected.
     */
    public static final Set<String> RESERVED = Set.of(
            "api", "actuator", "health", "healthz", "readyz", "livez", "metrics",
            "admin", "login", "logout", "static", "assets", "public", "docs",
            "favicon.ico", "robots.txt", "sitemap.xml", "openapi.yaml", "openapi.json",
            "null", "undefined", "index", "console");

    private final String value;

    private ShortCode(String value) {
        this.value = value;
    }

    /** Validate a user-supplied alias. */
    public static ShortCode ofAlias(String raw) {
        if (raw == null) {
            throw new InvalidAliasException("alias must not be null");
        }
        String trimmed = raw.strip();
        if (!ALIAS_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidAliasException(
                    "alias must be 3-64 characters of letters, digits, hyphen or underscore");
        }
        if (RESERVED.contains(trimmed.toLowerCase())) {
            throw new InvalidAliasException("alias \"" + trimmed + "\" is reserved");
        }
        return new ShortCode(trimmed);
    }

    /** Wrap a code the generator produced (defensive: still validated). */
    public static ShortCode ofGenerated(String raw) {
        if (raw == null || !GENERATED_PATTERN.matcher(raw).matches() || RESERVED.contains(raw.toLowerCase())) {
            throw new IllegalStateException("generator produced an invalid code");
        }
        return new ShortCode(raw);
    }

    /**
     * Cheap structural check for the redirect hot path: is this path segment even
     * shaped like a code? Lets scanners / junk paths 404 without a DB lookup.
     */
    public static boolean looksLikeCode(String candidate) {
        return candidate != null && ALIAS_PATTERN.matcher(candidate).matches()
                && !RESERVED.contains(candidate.toLowerCase());
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
