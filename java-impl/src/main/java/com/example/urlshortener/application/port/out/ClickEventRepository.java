package com.example.urlshortener.application.port.out;

import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.model.LinkStatistics;

import java.time.Instant;
import java.util.List;

/**
 * Output port for the analytics store. Writes are always batched (one
 * transaction per batch) by {@code BufferedClickRecorder}; reads back the
 * aggregates for the stats endpoint.
 */
public interface ClickEventRepository {

    /**
     * Persist a batch of click events and roll them into the per-day counters
     * atomically. Implementations must be safe to call with an empty list.
     */
    void saveBatch(List<ClickEvent> events);

    long totalClicks(long linkId);

    long clicksBetween(long linkId, Instant from, Instant to);

    Instant lastClickAt(long linkId);

    List<LinkStatistics.DailyCount> dailyCounts(long linkId, String fromDay, String toDay);

    List<LinkStatistics.ValueCount> topReferrers(long linkId, Instant from, Instant to, int limit);

    List<LinkStatistics.ValueCount> topUserAgents(long linkId, Instant from, Instant to, int limit);
}
