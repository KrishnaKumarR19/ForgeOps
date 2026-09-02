package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.forgeops.testsupport.PostgresTestContainer;
import com.forgeops.testsupport.RabbitMqTestContainer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Consumer integration tests against real PostgreSQL <strong>and</strong> real RabbitMQ
 * (Testcontainers), with the {@code @RabbitListener} enabled. Verifies the Slice 3 delivery
 * semantics end to end at the messaging layer (ADR-0014, INV-MSG-001..006):
 *
 * <ul>
 *   <li>happy path — a RECEIVED event is marked PROCESSED and the message is acknowledged
 *       (drains from the processing queue);</li>
 *   <li>duplicate delivery — the same message twice yields exactly one effect;</li>
 *   <li>poison message — an unknown event id is dead-lettered (lands on the DLQ) rather than
 *       retried forever;</li>
 *   <li>ack-only-after-success — a dead-lettered message leaves the processing queue but is
 *       never lost (it is on the DLQ).</li>
 * </ul>
 *
 * <p>Messages are published straight to the exchange here to isolate the consumer; the full
 * REST → outbox → publisher → consumer path is covered by
 * {@link EndToEndEventProcessingIntegrationTests}. The consumer is enabled and its retry
 * backoff shortened so the dead-letter scenario runs quickly. DB isolated via TRUNCATE and the
 * queues drained before each test.
 */
@SpringBootTest(properties = {
        "forgeops.security.bootstrap-admin.enabled=false",
        "forgeops.events.consumer.enabled=true",
        "forgeops.events.consumer.retry.max-attempts=3",
        "forgeops.events.consumer.retry.initial-delay=PT0.05S",
        "forgeops.events.consumer.retry.max-delay=PT0.2S"
})
@Import({PostgresTestContainer.class, RabbitMqTestContainer.class})
class OperationalEventConsumerIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String EXCHANGE = "forgeops.events";
    private static final String ROUTING_KEY = "operational-event.received";
    private static final String QUEUE = "forgeops.events.processing";
    private static final String DLQ = "forgeops.events.processing.dlq";

    private static final String SERVICE_ID = "018f1000-0000-7000-8000-000000000001";
    private static final String ENV_ID = "018f1001-0000-7000-8000-000000000001";
    private static final UUID CLIENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_messages CASCADE");
        drain(QUEUE);
        drain(DLQ);
    }

    private void drain(String queue) {
        while (rabbitTemplate.receive(queue, 200) != null) {
            // discard residue from a prior test
        }
    }

    private void insertReceivedEvent(UUID id) {
        jdbcTemplate.update("""
                INSERT INTO operational_events
                  (id, client_id, service_id, environment_id, event_type, occurred_at,
                   received_at, payload, payload_hash, status)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'http_5xx', ?, ?, ?::jsonb, ?, 'RECEIVED')
                """,
                id.toString(), CLIENT_ID.toString(), SERVICE_ID, ENV_ID,
                Timestamp.from(Instant.parse("2026-03-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-03-01T00:00:01Z")),
                "{\"a\":1}", "hash-" + id);
    }

    /** Publishes a message shaped like the Slice 2 publisher's (event_id in the JSON body). */
    private void publish(UUID eventId, String outboxMessageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(outboxMessageId);
        props.setType("OPERATIONAL_EVENT_RECEIVED");
        props.setHeader("aggregate_id", eventId.toString());
        props.setHeader("message_type", "OPERATIONAL_EVENT_RECEIVED");
        String body = "{\"event_id\":\"" + eventId + "\",\"service\":\"checkout\","
                + "\"environment\":\"production\",\"event_type\":\"http_5xx\","
                + "\"severity\":null,\"occurred_at\":\"2026-03-01T00:00:00Z\","
                + "\"received_at\":\"2026-03-01T00:00:01Z\"}";
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM operational_events WHERE id = ?::uuid", String.class, id.toString());
    }

    @Test
    void happyPathMarksEventProcessedAndAcknowledges() {
        UUID id = UUID.fromString("018f3200-0000-7000-8000-000000000001");
        insertReceivedEvent(id);

        publish(id, UUID.randomUUID().toString());

        // The effect is applied...
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("PROCESSED"));
        // ...and the message is acknowledged (queue empty, nothing dead-lettered).
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(rabbitTemplate.receive(QUEUE, 200)).isNull());
        assertThat(rabbitTemplate.receive(DLQ, 200)).isNull();
    }

    @Test
    void duplicateDeliveryAppliesEffectExactlyOnce() {
        UUID id = UUID.fromString("018f3200-0000-7000-8000-000000000002");
        insertReceivedEvent(id);
        String outboxId = UUID.randomUUID().toString();

        // Same logical message (same outbox messageId / event_id) delivered twice.
        publish(id, outboxId);
        publish(id, outboxId);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("PROCESSED"));
        // Exactly one event row, and it is PROCESSED — the duplicate had no additional effect.
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operational_events WHERE id = ?::uuid", Long.class, id.toString());
        assertThat(count).isEqualTo(1L);
        // Both deliveries acknowledged; nothing dead-lettered.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(rabbitTemplate.receive(QUEUE, 200)).isNull());
        assertThat(rabbitTemplate.receive(DLQ, 200)).isNull();
    }

    @Test
    void poisonMessageForUnknownEventIsDeadLettered() {
        // No event row for this id → NOT_FOUND → non-retryable → dead-lettered immediately.
        UUID unknown = UUID.fromString("018f3200-0000-7000-8000-0000000000ff");

        publish(unknown, UUID.randomUUID().toString());

        // The message must leave the processing queue and arrive on the DLQ (never lost).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message dead = rabbitTemplate.receive(DLQ, 200);
            assertThat(dead).as("message on the dead-letter queue").isNotNull();
            assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(unknown.toString());
        });
        // And it is not sitting unacknowledged on the processing queue.
        assertThat(rabbitTemplate.receive(QUEUE, 200)).isNull();
    }
}
