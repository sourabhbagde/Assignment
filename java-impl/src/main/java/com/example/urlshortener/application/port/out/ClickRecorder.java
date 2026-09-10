package com.example.urlshortener.application.port.out;

import com.example.urlshortener.domain.model.ClickEvent;

/**
 * Output port for recording a redirect hit. The contract for the redirect hot
 * path: {@link #record} must return immediately and must never perform blocking
 * I/O (implementation buffers and flushes asynchronously).
 */
public interface ClickRecorder {

    void record(ClickEvent event);

    /** Snapshot for health/metrics. */
    Snapshot snapshot();

    record Snapshot(int queued, long flushed, long dropped) {
    }
}
