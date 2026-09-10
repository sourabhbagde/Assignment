package com.example.urlshortener.adapter.out.cache;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.ResolutionCache;
import com.example.urlshortener.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessLruResolutionCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private InProcessLruResolutionCache cache(int maxEntries, long ttlMs) {
        AppProperties props = new AppProperties(
                "http://localhost:3000", 7, 6, "302", true, 2048, true, false, "s", 4096, false,
                new AppProperties.RateLimit(20, 2, 100, 50),
                new AppProperties.Analytics(1000, 200, 10_000, 512, 512),
                new AppProperties.Cache(maxEntries, ttlMs));
        return new InProcessLruResolutionCache(props, clock);
    }

    private static ResolutionCache.Entry entry(String code) {
        return new ResolutionCache.Entry(1L, code, "https://example.com/" + code, null);
    }

    @Test
    void storesAndReturnsEntries() {
        var c = cache(10, 0);
        c.put("a", entry("a"));
        assertThat(c.get("a")).contains(entry("a"));
        assertThat(c.get("missing")).isEmpty();
    }

    @Test
    void evictsLeastRecentlyUsedBeyondCapacity() {
        var c = cache(2, 0);
        c.put("a", entry("a"));
        c.put("b", entry("b"));
        assertThat(c.get("a")).isPresent();   // touch 'a' -> 'b' is now LRU
        c.put("c", entry("c"));               // evicts 'b'
        assertThat(c.get("b")).isEmpty();
        assertThat(c.get("a")).isPresent();
        assertThat(c.get("c")).isPresent();
    }

    @Test
    void expiresEntriesAfterTtl() {
        var c = cache(10, 1000);
        c.put("k", entry("k"));
        clock.advance(Duration.ofMillis(999));
        assertThat(c.get("k")).isPresent();
        clock.advance(Duration.ofMillis(2));
        assertThat(c.get("k")).isEmpty();
    }

    @Test
    void evictAndStatsWork() {
        var c = cache(10, 0);
        c.put("a", entry("a"));
        c.get("a");
        c.get("b");
        c.evict("a");
        assertThat(c.get("a")).isEmpty();
        ResolutionCache.Stats s = c.stats();
        assertThat(s.hits()).isEqualTo(1);
        assertThat(s.misses()).isEqualTo(2);
    }

    @Test
    void zeroCapacityDisablesCaching() {
        var c = cache(0, 0);
        c.put("a", entry("a"));
        assertThat(c.get("a")).isEmpty();
    }
}
