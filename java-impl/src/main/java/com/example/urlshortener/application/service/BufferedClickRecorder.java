package com.example.urlshortener.application.service;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.ClickEventRepository;
import com.example.urlshortener.application.port.out.ClickRecorder;
import com.example.urlshortener.domain.model.ClickEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking, batched click writer.
 *
 * <p>The redirect hot path calls {@link #record}, which only offers onto a
 * bounded in-memory queue (O(1), no I/O). A single scheduled worker drains the
 * queue in batched transactions. Consequences:
 * <ul>
 *   <li>redirect latency is independent of analytics write latency;</li>
 *   <li>a slow or briefly unavailable DB cannot stall a redirect;</li>
 *   <li>under extreme click spikes we shed <em>analytics</em> load (drop + count),
 *       never redirect traffic.</li>
 * </ul>
 *
 * <p><b>Durability trade-off:</b> events sitting in the queue are lost on a hard
 * crash (at-most-once). Approximate click counts are acceptable for this domain;
 * a durable log (Kafka/Kinesis/SQS) is the production upgrade and slots in behind
 * this same port.
 */
@Component
public class BufferedClickRecorder implements ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(BufferedClickRecorder.class);

    private final ClickEventRepository repository;
    private final AppProperties.Analytics config;
    private final BlockingQueue<ClickEvent> queue;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicLong flushed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public BufferedClickRecorder(ClickEventRepository repository, AppProperties props) {
        this.repository = repository;
        this.config = props.analytics();
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "click-flusher");
            t.setDaemon(true);
            return t;
        });
        this.worker.scheduleWithFixedDelay(
                this::drainQuietly, config.flushIntervalMs(), config.flushIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void record(ClickEvent event) {
        if (!queue.offer(event)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 500 == 0) {
                log.warn("analytics queue full (capacity {}), dropped {} click events so far",
                        config.queueCapacity(), n);
            }
            return;
        }
        if (queue.size() >= config.batchSize()) {
            worker.execute(this::drainQuietly);
        }
    }

    /**
     * Drain the whole queue in batch-sized transactions. Re-entrancy guarded.
     * Public so operational tooling and tests can force a synchronous flush.
     */
    public void flush() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ClickEvent> batch = new ArrayList<>(config.batchSize());
            while (!queue.isEmpty()) {
                batch.clear();
                queue.drainTo(batch, config.batchSize());
                if (batch.isEmpty()) {
                    break;
                }
                try {
                    repository.saveBatch(batch);
                    flushed.addAndGet(batch.size());
                } catch (RuntimeException ex) {
                    // Don't lose the batch on a transient DB error: requeue at the
                    // tail if there's room, otherwise drop-and-count. Retry next tick.
                    log.error("analytics flush failed for {} events, requeueing", batch.size(), ex);
                    for (ClickEvent e : batch) {
                        if (!queue.offer(e)) {
                            dropped.incrementAndGet();
                        }
                    }
                    break;
                }
            }
        } finally {
            draining.set(false);
        }
    }

    private void drainQuietly() {
        try {
            flush();
        } catch (RuntimeException ex) {
            log.error("unexpected error in click flusher", ex);
        }
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(queue.size(), flushed.get(), dropped.get());
    }

    /** Flush what we can before the context closes. */
    @PreDestroy
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        flush();
        Snapshot s = snapshot();
        if (s.queued() > 0 || s.dropped() > 0) {
            log.warn("shutdown with {} click events still queued and {} dropped over the run",
                    s.queued(), s.dropped());
        }
    }
}
