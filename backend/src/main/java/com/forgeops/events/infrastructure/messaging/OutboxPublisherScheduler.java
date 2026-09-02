package com.forgeops.events.infrastructure.messaging;

import com.forgeops.events.application.OutboxPublishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-delay trigger that drives the outbox publisher (Phase 6 Slice 2). It contains no
 * business logic — it only invokes the application {@link OutboxPublishService} and isolates
 * failures so a single bad cycle never kills future polling (a poll-cycle exception is caught
 * and logged; the next scheduled cycle still runs). The delay is config-driven
 * ({@code forgeops.outbox.publisher.poll-delay}); scheduling is enabled by
 * {@link SchedulingConfig}.
 *
 * <p>Enabled by default; {@code forgeops.outbox.publisher.enabled=false} disables the timer so
 * tests can drive {@link OutboxPublishService#publishBatch()} deterministically without a
 * background poll racing their assertions. The publish logic itself is unaffected.
 */
@Component
@ConditionalOnProperty(name = "forgeops.outbox.publisher.enabled", havingValue = "true",
        matchIfMissing = true)
class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final OutboxPublishService publishService;

    OutboxPublisherScheduler(OutboxPublishService publishService) {
        this.publishService = publishService;
    }

    @Scheduled(fixedDelayString = "${forgeops.outbox.publisher.poll-delay:PT5S}")
    void pollAndPublish() {
        try {
            publishService.publishBatch();
        } catch (RuntimeException e) {
            // Isolate the cycle: log and let the next scheduled run proceed. Do not rethrow
            // (that would stop the fixed-delay schedule). Not a silent swallow — it is logged.
            log.error("Outbox publisher cycle failed; will retry on the next cycle", e);
        }
    }
}
