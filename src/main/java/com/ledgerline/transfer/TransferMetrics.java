package com.ledgerline.transfer;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business-level counters, exposed at /actuator/prometheus. */
@Component
public class TransferMetrics {

    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Called after the transaction has committed, so "completed" never over-counts. */
    public void record(String type, boolean replayed) {
        if (replayed) {
            registry
                .counter("ledgerline.idempotency.replays", "operation", type)
                .increment();
        } else {
            registry
                .counter("ledgerline.transfers.completed", "type", type)
                .increment();
        }
    }
}
