package com.example.urlshortener.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A single redirect hit. The client IP is never stored here in the clear — only
 * {@code ipHash}, a salted one-way digest produced at the edge (see
 * {@code ClientIpResolver} / {@code BufferedClickRecorder}). Referrer and
 * user-agent are truncated by the caller before construction.
 */
public record ClickEvent(
        long linkId,
        String code,
        Instant occurredAt,
        String referrer,
        String userAgent,
        String ipHash) {

    public ClickEvent {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (linkId <= 0) {
            throw new IllegalArgumentException("linkId must be positive");
        }
    }

    public String day() {
        return occurredAt.toString().substring(0, 10); // YYYY-MM-DD (UTC)
    }
}
