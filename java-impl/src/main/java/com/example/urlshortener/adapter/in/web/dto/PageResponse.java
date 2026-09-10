package com.example.urlshortener.adapter.in.web.dto;

import java.util.List;

/** Paginated list envelope. */
public record PageResponse<T>(List<T> items, long total, int limit, int offset) {
}
