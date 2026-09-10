package com.example.urlshortener.adapter.in.web.dto;

import com.example.urlshortener.domain.model.ShortLink;

import java.time.Instant;
import java.util.Map;

/** Representation of a link returned by the API. */
public record ShortLinkResponse(
        String code,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt,
        boolean active,
        Map<String, Object> metadata) {

    public static ShortLinkResponse from(ShortLink link, String baseUrl) {
        return new ShortLinkResponse(
                link.code(),
                baseUrl + "/" + link.code(),
                link.longUrl(),
                link.createdAt(),
                link.expiresAt().orElse(null),
                link.active(),
                link.metadata().isEmpty() ? null : link.metadata());
    }
}
