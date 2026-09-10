package com.example.urlshortener.application.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All tunables, bound from {@code app.*} and validated at startup. An invalid
 * value fails the context refresh — the process refuses to boot rather than
 * misbehave later.
 *
 * @param baseUrl            public origin used to build {@code shortUrl} in responses (no trailing slash)
 * @param codeLength         generated short-code length (62^7 ≈ 3.5e12 at the default 7)
 * @param codeMaxAttempts    bounded retry budget when a generated code collides
 * @param redirectStatus     301/302/307/308 — 302 keeps analytics flowing and takedowns fast
 * @param dedupeByDefault    whether identical destinations reuse an existing code unless opted out
 * @param maxUrlLength        reject destinations longer than this (header-smuggling / storage abuse)
 * @param blockPrivateAddresses SSRF guard on private/loopback/link-local/reserved IP literals
 * @param validateDns        additionally resolve the host and re-check the IPs (adds latency)
 * @param ipHashSalt         salt for the one-way hash applied to client IPs before storage
 * @param metadataMaxBytes   cap on the serialized size of the free-form metadata bag
 * @param trustForwardedFor  honour X-Forwarded-For for client-IP (only true behind a trusted proxy)
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank @Pattern(regexp = "^https?://.+[^/]$", message = "must be an http(s) origin without a trailing slash")
        String baseUrl,

        @Min(4) @Max(16) int codeLength,
        @Min(1) @Max(20) int codeMaxAttempts,
        @Pattern(regexp = "30[1278]", message = "must be one of 301, 302, 307, 308") String redirectStatus,
        boolean dedupeByDefault,

        @Min(16) @Max(8192) int maxUrlLength,
        boolean blockPrivateAddresses,
        boolean validateDns,
        @NotBlank String ipHashSalt,
        @Min(64) @Max(65536) int metadataMaxBytes,
        boolean trustForwardedFor,

        RateLimit rateLimit,
        Analytics analytics,
        Cache cache) {

    public int redirectStatusCode() {
        return Integer.parseInt(redirectStatus);
    }

    public record RateLimit(
            @Min(1) int writeCapacity,
            @Min(1) int writeRefillPerSecond,
            @Min(1) int redirectCapacity,
            @Min(1) int redirectRefillPerSecond) {
    }

    public record Analytics(
            @Min(50) long flushIntervalMs,
            @Min(1) int batchSize,
            @Min(100) int queueCapacity,
            @Min(1) @Max(2048) int referrerMaxLength,
            @Min(1) @Max(2048) int userAgentMaxLength) {
    }

    public record Cache(
            @Min(0) int maxEntries,
            @Min(0) long ttlMs) {
    }
}
