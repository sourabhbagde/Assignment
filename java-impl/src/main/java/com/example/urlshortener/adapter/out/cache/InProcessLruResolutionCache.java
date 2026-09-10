package com.example.urlshortener.adapter.out.cache;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.ResolutionCache;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process LRU + TTL cache for the redirect hot path, so popular links skip the
 * database. Backed by an access-ordered {@link LinkedHashMap} guarded by an
 * intrinsic lock — simple and correct; the redirect path's critical section is a
 * single map operation.
 *
 * <p>Multi-instance deployments replace this with a shared Redis implementation
 * of {@link ResolutionCache} (same contract) — see ARCHITECTURE.md.
 */
@Component
public class InProcessLruResolutionCache implements ResolutionCache {

    private final int maxEntries;
    private final long ttlMs;
    private final Clock clock;
    private final Map<String, Timed> map;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public InProcessLruResolutionCache(AppProperties props, Clock clock) {
        this.maxEntries = props.cache().maxEntries();
        this.ttlMs = props.cache().ttlMs();
        this.clock = clock;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Timed> eldest) {
                return maxEntries > 0 && size() > maxEntries;
            }
        };
    }

    @Override
    public Optional<Entry> get(String code) {
        if (maxEntries == 0) {
            return Optional.empty();
        }
        synchronized (map) {
            Timed t = map.get(code);
            if (t == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (isStale(t)) {
                map.remove(code);
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(t.entry);
        }
    }

    @Override
    public void put(String code, Entry entry) {
        if (maxEntries == 0) {
            return;
        }
        long expiresAt = ttlMs == 0 ? Long.MAX_VALUE : clock.millis() + ttlMs;
        synchronized (map) {
            map.put(code, new Timed(entry, expiresAt));
        }
    }

    @Override
    public void evict(String code) {
        synchronized (map) {
            map.remove(code);
        }
    }

    @Override
    public void clear() {
        synchronized (map) {
            map.clear();
        }
    }

    @Override
    public Stats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double rate = total == 0 ? 0.0 : Math.round((double) h / total * 10_000d) / 10_000d;
        synchronized (map) {
            return new Stats(map.size(), h, m, rate);
        }
    }

    private boolean isStale(Timed t) {
        return t.expiresAtMillis != Long.MAX_VALUE && t.expiresAtMillis <= clock.millis();
    }

    private record Timed(Entry entry, long expiresAtMillis) {
    }
}
