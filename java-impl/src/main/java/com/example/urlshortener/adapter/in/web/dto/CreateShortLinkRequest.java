package com.example.urlshortener.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request body for {@code POST /api/v1/urls}.
 *
 * <p>Unknown properties are rejected (see {@code spring.jackson.deserialization.
 * fail-on-unknown-properties=true} and the explicit annotation) so a client
 * cannot smuggle fields past validation. Field-level constraints are the first
 * gate; semantic checks (expiry in the past, {@code expiresAt} XOR
 * {@code ttlSeconds}, SSRF) happen in the controller/service.
 *
 * @param url         destination to shorten (required)
 * @param customAlias optional caller-chosen code; when present, dedupe is bypassed
 * @param expiresAt   optional ISO-8601 instant/date after which the link 410s
 * @param ttlSeconds  optional relative expiry; mutually exclusive with {@code expiresAt}
 * @param dedupe      when null/true, an identical active destination reuses its code
 * @param metadata    optional opaque key/value bag (size-capped server-side)
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateShortLinkRequest(
        @NotBlank(message = "url is required")
        @Size(max = 8192, message = "url must be at most 8192 characters")
        String url,

        @Size(min = 3, max = 64, message = "customAlias must be 3-64 characters")
        @Pattern(regexp = "^[0-9A-Za-z_-]+$", message = "customAlias may only contain letters, digits, '-' and '_'")
        String customAlias,

        @Size(max = 40, message = "expiresAt must be an ISO-8601 timestamp")
        String expiresAt,

        @Positive(message = "ttlSeconds must be positive")
        @Max(value = 157_680_000L, message = "ttlSeconds must be at most 5 years")
        Long ttlSeconds,

        Boolean dedupe,

        Map<String, Object> metadata) {

    public boolean dedupeOrDefault(boolean fallback) {
        return dedupe == null ? fallback : dedupe;
    }
}
