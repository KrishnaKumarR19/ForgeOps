package com.forgeops.events.infrastructure.messaging;

import com.forgeops.events.application.OutboxCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-delay trigger for outbox retention cleanup (Phase 6 Slice 4). It contains no business
 * logic — it only invokes the application {@link OutboxCleanupService} and isolates failures so
 * a single bad cycle never kills future cleanup (a cycle exception is caught and logged; the
 * next scheduled cycle still runs). The delay is config-driven
 * ({@code forgeops.outbox.cleanup.fixed-delay}, default hourly); scheduling is enabled by
 * {@link SchedulingConfig}. Mirrors {@link OutboxPublisherScheduler}.
 *
 * <p>Enabled by default; {@code forgeops.outbox.cleanup.enabled=false} disables the timer so
 * tests can drive {@link OutboxCleanupService#cleanupOnce()} deterministically without a
 * background cycle racing their assertions. The cleanup logic itself is unaffected.
 */
@Component
@ConditionalOnProperty(name = "forgeops.outbox.cleanup.enabled", havingValue = "true",
        matchIfMissing = true)
class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    private final OutboxCleanupService cleanupService;

    OutboxCleanupScheduler(OutboxCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${forgeops.outbox.cleanup.fixed-delay:PT1H}")
    void pollAndCleanup() {
        try {
            cleanupService.cleanupOnce();
        } catch (RuntimeException e) {
            // Isolate the cycle: log and let the next scheduled run proceed. Do not rethrow
            // (that would stop the fixed-delay schedule). Not a silent swallow — it is logged.
            log.error("Outbox retention cleanup cycle failed; will retry on the next cycle", e);
        }
    }
}
