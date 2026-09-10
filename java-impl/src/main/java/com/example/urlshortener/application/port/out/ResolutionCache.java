package com.example.urlshortener.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * Output port for the redirect-path cache. The in-process LRU adapter implements
 * it now; a Redis adapter with the same contract is the multi-instance upgrade.
 */
public interface ResolutionCache {

    Optional<Entry> get(String code);

    void put(String code, Entry entry);

    void evict(String code);

    void clear();

    Stats stats();

    /** Minimal data the redirect path needs: destination + identity + expiry. */
    record Entry(long linkId, String code, String longUrl, Instant expiresAt) {
    }

    record Stats(long size, long hits, long misses, double hitRate) {
    }
}
