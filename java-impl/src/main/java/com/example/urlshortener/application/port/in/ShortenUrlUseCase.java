package com.example.urlshortener.application.port.in;

import com.example.urlshortener.domain.model.ShortLink;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Primary port: create a short link. */
public interface ShortenUrlUseCase {

    Result shorten(Command command);

    /**
     * @param longUrl     raw destination (validated/normalized downstream)
     * @param customAlias optional caller-chosen code; bypasses dedupe
     * @param expiresAt   optional absolute expiry (already resolved from expiresAt|ttl by the web layer)
     * @param dedupe      when true (default) an identical active destination returns the existing code
     * @param createdBy   optional owner/attribution handle
     * @param metadata    optional opaque key/value bag (size-capped by the web layer)
     */
    record Command(
            String longUrl,
            String customAlias,
            Instant expiresAt,
            boolean dedupe,
            String createdBy,
            Map<String, Object> metadata) {

        public Optional<String> optionalAlias() {
            return Optional.ofNullable(customAlias);
        }

        public Optional<Instant> optionalExpiresAt() {
            return Optional.ofNullable(expiresAt);
        }
    }

    /** @param reused true when an existing link was returned instead of creating a new one */
    record Result(ShortLink link, boolean reused) {
    }
}
