package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.events.application.OutboxPublishService;
import com.forgeops.events.application.OutboxPublisherProperties;
import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxMessageRepository;
import com.forgeops.testsupport.PostgresTestContainer;
import com.forgeops.testsupport.RabbitMqTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox publisher integration tests against real PostgreSQL <strong>and</strong> real
 * RabbitMQ (Testcontainers). Verifies the confirmed-publish path (PENDING → PUBLISHED with a
 * real broker-accepted message on the queue), message identity/routing/persistence, claim
 * eligibility by {@code next_attempt_at}, and the conditional PUBLISHED guard. Scheduling is
 * effectively disabled by a very long poll delay so the test drives {@code publishBatch()}
 * deterministically. DB isolated via TRUNCATE; bootstrap admin disabled.
 */
@SpringBootTest(properties = {
        "forgeops.security.bootstrap-admin.enabled=false",
        "forgeops.outbox.publisher.poll-delay=PT1H" // scheduler disabled in tests anyway
})
@Import({PostgresTestContainer.class, RabbitMqTestContainer.class})
class OutboxPublisherIntegrationTests {

    @Autowired
    private OutboxPublishService publishService;
    @Autowired
    private OutboxMessageRepository outbox;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private OutboxPublisherProperties properties;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String QUEUE = "forgeops.events.processing";

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_messages CASCADE");
        // Drain any residue from a prior test so receive() sees only this test's message.
        while (rabbitTemplate.receive(QUEUE, 200) != null) {
            // drain
        }
    }

    private OutboxMessage savePending(UUID id, Instant createdAt, Instant nextAttemptAt, int attempts) {
        OutboxMessage m = new OutboxMessage(id, "OPERATIONAL_EVENT_RECEIVED", "OPERATIONAL_EVENT",
                UUID.fromString("018f0000-0000-7000-8000-0000000000e1"),
                "{\"event_id\":\"" + id + "\"}",
                com.forgeops.events.domain.OutboxStatus.PENDING, attempts,
                createdAt, null, nextAttemptAt, null);
        return outbox.save(m);
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_messages WHERE id = ?::uuid", String.class, id.toString());
    }

    @Test
    void publishesPendingMessageToRabbitAndMarksPublished() {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-000000000001");
        savePending(id, Instant.now().minusSeconds(5), null, 0);

        int published = publishService.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_messages WHERE id = ?::uuid", Instant.class, id.toString()))
                .isNotNull();

        // The broker actually received a persistent JSON message with the outbox id as messageId.
        Message received = rabbitTemplate.receive(QUEUE, 5_000);
        assertThat(received).as("message on the queue").isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(received.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
        assertThat(received.getMessageProperties().getHeaders().get("aggregate_id"))
                .isEqualTo("018f0000-0000-7000-8000-0000000000e1");
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).contains(id.toString());
    }

    @Test
    void doesNotClaimMessageWhoseNextAttemptIsInTheFuture() {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-000000000002");
        savePending(id, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600), 1);

        int published = publishService.publishBatch();

        assertThat(published).isZero();
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(rabbitTemplate.receive(QUEUE, 500)).isNull();
    }

    @Test
    void claimsMessageWhoseRetryIsDue() {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-000000000003");
        savePending(id, Instant.now().minusSeconds(60), Instant.now().minusSeconds(5), 1);

        int published = publishService.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(status(id)).isEqualTo("PUBLISHED");
    }

    @Test
    void alreadyPublishedMessageIsNotClaimedAgain() {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-000000000004");
        savePending(id, Instant.now().minusSeconds(5), null, 0);
        publishService.publishBatch();
        assertThat(status(id)).isEqualTo("PUBLISHED");
        rabbitTemplate.receive(QUEUE, 5_000); // drain

        // A second cycle finds nothing to publish (status guard + claim filter).
        int publishedAgain = publishService.publishBatch();

        assertThat(publishedAgain).isZero();
        assertThat(rabbitTemplate.receive(QUEUE, 500)).isNull();
    }

    @Test
    void conditionalMarkPublishedOnlyAffectsPendingRows() {
        UUID id = UUID.fromString("018f2000-0000-7000-8000-000000000005");
        savePending(id, Instant.now().minusSeconds(5), null, 0);
        // Pre-mark it PUBLISHED out of band.
        jdbcTemplate.update("UPDATE outbox_messages SET status='PUBLISHED', published_at=now() "
                + "WHERE id = ?::uuid", id.toString());

        // A subsequent markPublished with a new time must NOT change the already-published row.
        // markPublished is a @Modifying JPA update; production runs it inside the publisher's
        // TransactionTemplate, so exercise it the same way here (a bare call has no active
        // transaction and JPA would reject the update).
        Instant staleTime = Instant.parse("2000-01-01T00:00:00Z").truncatedTo(ChronoUnit.MICROS);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> outbox.markPublished(id, staleTime));

        Instant publishedAt = jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_messages WHERE id = ?::uuid", Instant.class, id.toString());
        assertThat(publishedAt).isAfter(Instant.parse("2020-01-01T00:00:00Z")); // not the stale value
    }
}
