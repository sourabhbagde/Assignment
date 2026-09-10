package com.example.urlshortener.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A shortened link. Enterprise entity: pure data + invariants, no framework or
 * persistence concerns (Clean Architecture inner circle).
 *
 * <p>Immutable. Instances are only created through {@link #newLink} (creation) or
 * {@link #rehydrate} (loading a persisted row), which keeps the invariants in one
 * place.
 */
public final class ShortLink {

    private final long id;
    private final String code;
    private final String longUrl;
    private final String normalizedHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final boolean active;
    private final String createdBy;
    private final Map<String, Object> metadata;

    private ShortLink(long id, String code, String longUrl, String normalizedHash,
                      Instant createdAt, Instant expiresAt, boolean active,
                      String createdBy, Map<String, Object> metadata) {
        this.id = id;
        this.code = requireText(code, "code");
        this.longUrl = requireText(longUrl, "longUrl");
        this.normalizedHash = requireText(normalizedHash, "normalizedHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        this.active = active;
        this.createdBy = createdBy;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Factory for a brand-new link (id assigned by the repository on insert). */
    public static ShortLink newLink(String code, String longUrl, String normalizedHash,
                                    Instant createdAt, Instant expiresAt,
                                    String createdBy, Map<String, Object> metadata) {
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            // Only enforced at creation time; a persisted link may legitimately
            // have had its expiry moved earlier later on.
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        return new ShortLink(0L, code, longUrl, normalizedHash, createdAt, expiresAt, true, createdBy, metadata);
    }

    /** Factory for reconstructing a link loaded from storage. */
    public static ShortLink rehydrate(long id, String code, String longUrl, String normalizedHash,
                                      Instant createdAt, Instant expiresAt, boolean active,
                                      String createdBy, Map<String, Object> metadata) {
        return new ShortLink(id, code, longUrl, normalizedHash, createdAt, expiresAt, active, createdBy, metadata);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** A link is usable for redirecting iff it is active and not past its expiry. */
    public boolean isResolvableAt(Instant now) {
        return active && !isExpiredAt(now);
    }

    public long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String longUrl() {
        return longUrl;
    }

    public String normalizedHash() {
        return normalizedHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public boolean active() {
        return active;
    }

    public Optional<String> createdBy() {
        return Optional.ofNullable(createdBy);
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShortLink other)) {
            return false;
        }
        return code.equals(other.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        // Deliberately does not include longUrl to avoid destinations leaking into logs.
        return "ShortLink{code=" + code + ", active=" + active + ", expiresAt=" + expiresAt + '}';
    }
}
