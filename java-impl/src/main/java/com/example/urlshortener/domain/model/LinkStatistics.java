package com.example.urlshortener.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated analytics for one link over an optional time window.
 *
 * @param totalClicks  all-time click count (window-independent)
 * @param windowClicks clicks within [from, to]
 * @param daily        per-day counts within the window, ascending
 * @param topReferrers most frequent referrers within the window
 * @param topUserAgents most frequent user-agents within the window
 */
public record LinkStatistics(
        String code,
        String longUrl,
        Instant createdAt,
        long totalClicks,
        long windowClicks,
        Instant windowFrom,
        Instant windowTo,
        List<DailyCount> daily,
        List<ValueCount> topReferrers,
        List<ValueCount> topUserAgents,
        Instant lastClickedAt) {

    public LinkStatistics {
        daily = daily == null ? List.of() : List.copyOf(daily);
        topReferrers = topReferrers == null ? List.of() : List.copyOf(topReferrers);
        topUserAgents = topUserAgents == null ? List.of() : List.copyOf(topUserAgents);
    }

    public record DailyCount(String day, long count) {
    }

    public record ValueCount(String value, long count) {
    }
}
