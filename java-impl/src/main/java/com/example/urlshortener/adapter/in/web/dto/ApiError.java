package com.example.urlshortener.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error envelope for every failed request:
 * <pre>
 * { "error": { "code", "message", "details"?, "requestId", "timestamp", "path" } }
 * </pre>
 * Messages are curated and safe to expose — no stack traces, SQL, class names or
 * internal paths ever reach the client (see {@code GlobalExceptionHandler}).
 */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(
            String code,
            String message,
            List<FieldIssue> details,
            String requestId,
            Instant timestamp,
            String path) {
    }

    public record FieldIssue(String field, String message) {
    }

    public static ApiError of(String code, String message, String requestId, String path, List<FieldIssue> details) {
        return new ApiError(new Body(code, message, details, requestId, Instant.now(), path));
    }
}
