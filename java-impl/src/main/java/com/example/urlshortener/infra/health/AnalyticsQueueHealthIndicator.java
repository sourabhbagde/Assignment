package com.example.urlshortener.infra.health;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.ClickRecorder;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes the analytics buffer's state to {@code /health} (and the readiness
 * group). Reports {@code OUT_OF_SERVICE} when the queue is saturated so a load
 * balancer can drain this instance rather than keep piling on click traffic that
 * will be dropped.
 */
@Component("analyticsQueue")
public class AnalyticsQueueHealthIndicator implements HealthIndicator {

    private final ClickRecorder clickRecorder;
    private final int capacity;

    public AnalyticsQueueHealthIndicator(ClickRecorder clickRecorder, AppProperties props) {
        this.clickRecorder = clickRecorder;
        this.capacity = props.analytics().queueCapacity();
    }

    @Override
    public Health health() {
        ClickRecorder.Snapshot s = clickRecorder.snapshot();
        double fill = capacity == 0 ? 0 : (double) s.queued() / capacity;
        Health.Builder builder = fill >= 0.9 ? Health.outOfService() : Health.up();
        return builder
                .withDetail("queued", s.queued())
                .withDetail("capacity", capacity)
                .withDetail("flushed", s.flushed())
                .withDetail("dropped", s.dropped())
                .build();
    }
}
