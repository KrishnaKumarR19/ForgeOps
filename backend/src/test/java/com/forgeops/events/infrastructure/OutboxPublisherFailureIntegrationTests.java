package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.events.application.OutboxPublishService;
import com.forgeops.events.domain.MessageBroker;
import com.forgeops.events.domain.MessagePublishException;
import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.events.domain.OutboxStatus;
import com.forgeops.testsupport.PostgresTestContainer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox publisher failure + concurrency integration tests against real PostgreSQL. The broker
 * is replaced with a controllable {@code @Primary} fake (no RabbitMQ container needed here):
 * one variant always throws {@link MessagePublishException} to exercise the retryable-failure
 * path against the real database; the SKIP LOCKED concurrency test drives the repository
 * directly. DB isolated via TRUNCATE.
 */
@SpringBootTest(properties = {
        "forgeops.security.bootstrap-admin.enabled=false",
        "forgeops.outbox.publisher.poll-delay=1h"
})
@Import({PostgresTestContainer.class, OutboxPublisherFailureIntegrationTests.FailingBrokerConfig.class})
class OutboxPublisherFailureIntegrationTests {

    @TestConfiguration
    static class FailingBrokerConfig {
        @Bean
        @Primary
        MessageBroker failingBroker() {
            return message -> {
                throw new MessagePublishException("Simulated broker unavailable for " + message.id());
            };
        }
    }

    @Autowired
    private OutboxPublishService publishService;
    @Autowired
    private OutboxMessageRepository outbox;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_messages CASCADE");
    }

    private void savePending(UUID id, int attempts) {
        outbox.save(new OutboxMessage(id, "OPERATIONAL_EVENT_RECEIVED", "OPERATIONAL_EVENT",
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                "{\"event_id\":\"" + id + "\"}", OutboxStatus.PENDING, attempts,
                Instant.now().minusSeconds(5), null, null, null));
    }

    @Test
    void failedPublishLeavesMessageRetryableWithMetadataAndLosesNothing() {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-0000000000f1");
        savePending(id, 0);

        int published = publishService.publishBatch();

        assertThat(published).isZero();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, attempts, next_attempt_at, last_error "
                        + "FROM outbox_messages WHERE id = ?::uuid", id.toString());
        assertThat(row.get("status")).isEqualTo("PENDING"); // still retryable, not lost/dropped
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("next_attempt_at")).isNotNull();
        assertThat((String) row.get("last_error")).isNotBlank().contains(id.toString());
        // Nothing was silently discarded: the row is still present and PENDING.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages", Long.class)).isEqualTo(1);
    }

    @Test
    void skipLockedPreventsTwoConcurrentTransactionsFromClaimingTheSameRows() throws Exception {
        // 4 due PENDING rows; two concurrent claim transactions each claim a batch of 4.
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            UUID id = UUID.fromString("018f2000-0000-7000-8000-00000000000" + i);
            ids.add(id);
            savePending(id, 0);
        }

        // Each worker claims within its own transaction and holds the locks until a latch, so
        // the two claims genuinely overlap and SKIP LOCKED must partition the rows.
        var startLatch = new java.util.concurrent.CountDownLatch(2);
        var releaseLatch = new java.util.concurrent.CountDownLatch(1);
        Callable<List<UUID>> worker = () -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            return tt.execute(txStatus -> {
                List<UUID> claimed = outbox.claimPending(4, Instant.now()).stream()
                        .map(OutboxMessage::id).toList();
                startLatch.countDown();
                try {
                    releaseLatch.await(); // hold row locks while the peer also claims
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return claimed;
            });
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<List<UUID>> f1 = pool.submit(worker);
        Future<List<UUID>> f2 = pool.submit(worker);
        startLatch.await();       // both inside their transaction with locks held
        releaseLatch.countDown(); // let both commit
        List<UUID> claimed1 = f1.get();
        List<UUID> claimed2 = f2.get();
        pool.shutdown();

        // No row is claimed by both workers simultaneously (SKIP LOCKED partitions the set).
        List<UUID> overlap = new ArrayList<>(claimed1);
        overlap.retainAll(claimed2);
        assertThat(overlap).as("no row claimed by both concurrent transactions").isEmpty();
        // Together they saw at most the 4 available rows; neither double-counted.
        assertThat(claimed1.size() + claimed2.size()).isLessThanOrEqualTo(4);
    }
}
