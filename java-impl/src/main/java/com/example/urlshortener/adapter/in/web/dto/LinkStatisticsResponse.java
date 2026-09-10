package com.example.urlshortener.adapter.in.web.dto;

import com.example.urlshortener.domain.model.LinkStatistics;

import java.time.Instant;
import java.util.List;

/** Analytics representation for {@code GET /api/v1/urls/{code}/stats}. */
public record LinkStatisticsResponse(
        String code,
        String longUrl,
        Instant createdAt,
        long totalClicks,
        long windowClicks,
        Window window,
        List<DailyPoint> daily,
        List<Breakdown> topReferrers,
        List<Breakdown> topUserAgents,
        Instant lastClickedAt) {

    public record Window(Instant from, Instant to) {
    }

    public record DailyPoint(String day, long count) {
    }

    public record Breakdown(String value, long count) {
    }

    public static LinkStatisticsResponse from(LinkStatistics s) {
        return new LinkStatisticsResponse(
                s.code(),
                s.longUrl(),
                s.createdAt(),
                s.totalClicks(),
                s.windowClicks(),
                new Window(s.windowFrom(), s.windowTo()),
                s.daily().stream().map(d -> new DailyPoint(d.day(), d.count())).toList(),
                s.topReferrers().stream().map(v -> new Breakdown(v.value(), v.count())).toList(),
                s.topUserAgents().stream().map(v -> new Breakdown(v.value(), v.count())).toList(),
                s.lastClickedAt());
    }
}
