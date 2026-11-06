package com.ledgerline.idempotency;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(
        IdempotencyCleanupJob.class
    );

    private final IdempotencyService service;
    private final Duration retention;

    IdempotencyCleanupJob(
        IdempotencyService service,
        @Value("${ledgerline.idempotency.retention:PT24H}") Duration retention
    ) {
        this.service = service;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purge() {
        int removed = service.purgeExpired(retention);
        if (removed > 0) {
            log.info("Purged {} expired idempotency keys", removed);
        }
    }
}
