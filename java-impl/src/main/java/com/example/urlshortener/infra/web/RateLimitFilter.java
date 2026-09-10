package com.example.urlshortener.infra.web;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.adapter.in.web.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-client token-bucket rate limiter.
 *
 * <p>Two independent budgets so a burst of writes can never starve redirects:
 * <ul>
 *   <li><b>write</b> — mutating {@code /api/**} calls (POST/PUT/PATCH/DELETE);</li>
 *   <li><b>redirect</b> — everything else (the {@code GET /{code}} hot path and
 *       {@code GET /api/**} reads).</li>
 * </ul>
 * Token bucket (not fixed window) so short bursts up to {@code capacity} are
 * allowed while the sustained rate is bounded by {@code refillPerSecond}.
 *
 * <p>Limitation: state is per-instance, so behind N nodes the effective limit is
 * N×. The production version is the same algorithm in Redis behind this same
 * filter. Health/monitoring endpoints ({@code /health**}, {@code /actuator/**})
 * are never limited.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties.RateLimit config;
    private final ClientIpResolver clientIp;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    public RateLimitFilter(AppProperties props, ClientIpResolver clientIp, ObjectMapper objectMapper) {
        this.config = props.rateLimit();
        this.clientIp = clientIp;
        this.objectMapper = objectMapper;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ratelimit-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/health") || p.startsWith("/actuator") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        boolean write = isMutating(request.getMethod()) && request.getRequestURI().startsWith("/api/");
        int capacity = write ? config.writeCapacity() : config.redirectCapacity();
        double refill = write ? config.writeRefillPerSecond() : config.redirectRefillPerSecond();
        String key = (write ? "w:" : "r:") + clientIp.resolve(request);

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        Decision d = bucket.tryAcquire(capacity, refill);

        response.setHeader("X-RateLimit-Limit", Integer.toString(capacity));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0, d.remaining())));

        if (!d.allowed()) {
            writeTooMany(request, response, d.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeTooMany(HttpServletRequest request, HttpServletResponse response, long retryAfter)
            throws IOException {
        Object rid = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        ApiError body = ApiError.of("RATE_LIMITED", "too many requests, slow down",
                rid == null ? null : rid.toString(), request.getRequestURI(), null);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private void sweep() {
        long cutoff = System.nanoTime() - TimeUnit.MINUTES.toNanos(10);
        buckets.entrySet().removeIf(e -> e.getValue().lastSeenNanos() < cutoff);
    }

    @Override
    public void destroy() {
        sweeper.shutdownNow();
    }

    private record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    /** Lazily-refilled token bucket; guarded by its own monitor. */
    private static final class Bucket {
        private double tokens;
        private long updatedNanos = System.nanoTime();
        private volatile long lastSeenNanos = System.nanoTime();

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        synchronized Decision tryAcquire(int capacity, double refillPerSecond) {
            long now = System.nanoTime();
            lastSeenNanos = now;
            double elapsedSec = (now - updatedNanos) / 1_000_000_000d;
            updatedNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSecond);

            if (tokens < 1d) {
                long retry = (long) Math.ceil((1d - tokens) / refillPerSecond);
                return new Decision(false, 0, Math.max(1, retry));
            }
            tokens -= 1d;
            return new Decision(true, (long) Math.floor(tokens), 0);
        }

        long lastSeenNanos() {
            return lastSeenNanos;
        }
    }
}
