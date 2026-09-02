package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.forgeops.incidents.application.DetectionResult;
import com.forgeops.incidents.application.IncidentDetectionPort;
import com.forgeops.testsupport.PostgresTestContainer;
import com.forgeops.testsupport.RabbitMqTestContainer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consumer retry + dead-letter integration tests against real PostgreSQL and RabbitMQ
 * (Testcontainers). A {@code @Primary} detection port whose {@code correlateOrCreate} always
 * throws a <em>transient</em> exception exercises the bounded-retry path (FR-RL-4, INV-MSG-005):
 * the listener retries up to {@code max-attempts} and then, once exhausted, the message is
 * rejected without requeue and the broker dead-letters it (FR-RL-5, INV-MSG-006). This also
 * proves the ack contract on the failure side: a message that never processes successfully is
 * never acknowledged off the processing queue as "done" — it ends up on the DLQ (INV-MSG-004).
 *
 * <p>A real RECEIVED event is inserted so processing reaches the detection step. The retry
 * backoff is shortened via test properties; the consumer is enabled; queues drained per test.
 */
@SpringBootTest(properties = {
        "forgeops.security.bootstrap-admin.enabled=false",
        "forgeops.events.consumer.enabled=true",
        "forgeops.events.consumer.retry.max-attempts=3",
        "forgeops.events.consumer.retry.initial-delay=PT0.05S",
        "forgeops.events.consumer.retry.max-delay=PT0.2S"
})
@Import({PostgresTestContainer.class, RabbitMqTestContainer.class,
        ConsumerRetryDeadLetterIntegrationTests.TransientlyFailingDetectionConfig.class})
class ConsumerRetryDeadLetterIntegrationTests {

    /** Counts detection attempts and always fails transiently (retryable, not poison). */
    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    @TestConfiguration
    static class TransientlyFailingDetectionConfig {
        @Bean
        @Primary
        IncidentDetectionPort transientlyFailingDetection() {
            return context -> {
                ATTEMPTS.incrementAndGet();
                throw new RuntimeException("transient detection failure (attempt "
                        + ATTEMPTS.get() + ") for " + context.eventId());
            };
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String EXCHANGE = "forgeops.events";
    private static final String ROUTING_KEY = "operational-event.received";
    private static final String QUEUE = "forgeops.events.processing";
    private static final String DLQ = "forgeops.events.processing.dlq";
    private static final String SERVICE_ID = "018f1000-0000-7000-8000-000000000001";
    private static final String ENV_ID = "018f1001-0000-7000-8000-000000000001";

    @BeforeEach
    void setUp() {
        ATTEMPTS.set(0);
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        while (rabbitTemplate.receive(QUEUE, 200) != null) {
            // drain
        }
        while (rabbitTemplate.receive(DLQ, 200) != null) {
            // drain
        }
    }

    private void insertReceivedEvent(UUID id) {
        jdbcTemplate.update("""
                INSERT INTO operational_events
                  (id, client_id, service_id, environment_id, event_type, occurred_at,
                   received_at, payload, payload_hash, status)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'http_5xx', ?, ?, ?::jsonb, ?, 'RECEIVED')
                """,
                id.toString(), "018f0000-0000-7000-8000-0000000000a1", SERVICE_ID, ENV_ID,
                Timestamp.from(Instant.parse("2026-03-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-03-01T00:00:01Z")), "{\"a\":1}", "hash-" + id);
    }

    private void publish(UUID eventId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(UUID.randomUUID().toString());
        props.setType("OPERATIONAL_EVENT_RECEIVED");
        props.setHeader("aggregate_id", eventId.toString());
        String body = "{\"event_id\":\"" + eventId + "\"}";
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }

    @Test
    void transientFailureIsRetriedThenDeadLettered() {
        UUID id = UUID.fromString("018f3300-0000-7000-8000-000000000001");
        insertReceivedEvent(id);

        publish(id);

        // Exhausted retries route the message to the DLQ (never lost, never looping forever).
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message dead = rabbitTemplate.receive(DLQ, 200);
            assertThat(dead).as("message dead-lettered after retry exhaustion").isNotNull();
            assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(id.toString());
        });

        // It was retried the bounded number of times (initial try + retries = max-attempts).
        assertThat(ATTEMPTS.get()).isEqualTo(3);
        // And it is not still sitting on the processing queue.
        assertThat(rabbitTemplate.receive(QUEUE, 200)).isNull();
    }
}
