package com.forgeops.events.application;

import com.forgeops.events.domain.OutboxMessageRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox retention cleanup use case (Phase 6 Slice 4, PERSISTENCE_MODEL §15, ADR-0019,
 * INV-OUTBOX-006): periodically prunes old {@code PUBLISHED} outbox rows so the append-heavy
 * table does not grow without bound, while never touching work that is still needed.
 *
 * <p>Each invocation computes {@code cutoff = now - retention} from the injected {@link Clock}
 * and deletes eligible rows in bounded batches until a batch removes nothing. Every batch runs
 * in its own {@link TransactionTemplate} transaction and commits independently, so a large
 * backlog is pruned in small steps rather than one long, table-locking statement; if a batch
 * fails, that batch rolls back and the remaining rows stay available for the next run.
 *
 * <p>Eligibility is enforced by the repository: only {@code status = 'PUBLISHED'} rows with a
 * non-null {@code published_at} strictly before {@code cutoff} are deleted. {@code PENDING}
 * rows — including failed-but-retryable ones — and rows with a {@code NULL published_at} are
 * never deleted (INV-OUTBOX-003). Deleting old {@code PUBLISHED} rows cannot affect delivery:
 * the publisher and recovery paths read only {@code PENDING} rows (ADR-0022), and the outbox is
 * never the authoritative business record (INV-OUTBOX-007). Cleanup is safe under repeated
 * execution — a re-run simply finds fewer (or no) eligible rows.
 *
 * <p>Time comes from the injected {@link Clock} (deterministic in tests). Logs carry counts and
 * the cutoff only — never payloads, credentials, or secrets. This service owns the transaction
 * boundary and the batching policy; scheduling lives in the infrastructure layer.
 */
@Service
public class OutboxCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupService.class);
    /** Guards against a pathological non-terminating loop (e.g. a misconfigured batch size). */
    private static final int MAX_BATCHES_PER_RUN = 10_000;

    private final OutboxMessageRepository outbox;
    private final OutboxCleanupProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OutboxCleanupService(OutboxMessageRepository outbox,
                                OutboxCleanupProperties properties,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs one cleanup pass: deletes eligible {@code PUBLISHED} rows older than the retention
     * cutoff in bounded batches until none remain. Returns the total number of rows deleted.
     */
    public int cleanupOnce() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int batchSize = properties.batchSize();
        int totalDeleted = 0;
        int batches = 0;

        while (batches < MAX_BATCHES_PER_RUN) {
            final Instant batchCutoff = cutoff;
            Integer deleted = transactionTemplate.execute(status ->
                    outbox.deletePublishedOlderThan(batchCutoff, batchSize));
            int deletedInBatch = deleted == null ? 0 : deleted;
            totalDeleted += deletedInBatch;
            batches++;
            if (deletedInBatch < batchSize) {
                // A short (or empty) batch means no more eligible rows remain.
                break;
            }
        }

        if (totalDeleted > 0) {
            log.info("Outbox retention cleanup deleted {} published row(s) older than {} in {} batch(es)",
                    totalDeleted, cutoff, batches);
        } else {
            log.debug("Outbox retention cleanup found no rows older than {}", cutoff);
        }
        return totalDeleted;
    }
}
