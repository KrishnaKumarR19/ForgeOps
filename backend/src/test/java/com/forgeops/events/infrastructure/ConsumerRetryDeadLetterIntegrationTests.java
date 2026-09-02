package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import com.forgeops.events.domain.ProcessingOutcome;
import com.forgeops.testsupport.PostgresTestContainer;
import com.forgeops.testsupport.RabbitMqTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
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

/**
 * Consumer retry + dead-letter integration tests against real PostgreSQL and RabbitMQ
 * (Testcontainers). A {@code @Primary} repository whose {@code markProcessed} always throws a
 * <em>transient</em> exception exercises the bounded-retry path (FR-RL-4, INV-MSG-005): the
 * listener retries up to {@code max-attempts} and then, once exhausted, the message is rejected
 * without requeue and the broker dead-letters it (FR-RL-5, INV-MSG-006). This also proves the
 * ack contract on the failure side: a message that never processes successfully is never
 * acknowledged off the processing queue as "done" — it ends up on the DLQ, not lost and not
 * infinitely reprocessed (INV-MSG-004).
 *
 * <p>The retry backoff is shortened via test properties so the scenario completes quickly. The
 * consumer is enabled; queues are drained before each test.
 */
@SpringBootTest(properties = {
        "forgeops.security.bootstrap-admin.enabled=false",
        "forgeops.events.consumer.enabled=true",
        "forgeops.events.consumer.retry.max-attempts=3",
        "forgeops.events.consumer.retry.initial-delay=PT0.05S",
        "forgeops.events.consumer.retry.max-delay=PT0.2S"
})
@Import({PostgresTestContainer.class, RabbitMqTestContainer.class,
        ConsumerRetryDeadLetterIntegrationTests.TransientlyFailingRepositoryConfig.class})
class ConsumerRetryDeadLetterIntegrationTests {

    /** Counts processing attempts and always fails transiently (retryable, not poison). */
    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    @TestConfiguration
    static class TransientlyFailingRepositoryConfig {
        @Bean
        @Primary
        OperationalEventRepository transientlyFailingRepository() {
            return new OperationalEventRepository() {
                @Override
                public OperationalEvent save(OperationalEvent e) {
                    throw new UnsupportedOperationException();
                }
                @Override
                public Optional<OperationalEvent> findById(UUID id) {
                    return Optional.empty();
                }
                @Override
                public Optional<OperationalEvent> findByClientIdAndIdempotencyKey(
                        UUID clientId, String idempotencyKey) {
                    return Optional.empty();
                }
                @Override
                public ProcessingOutcome markProcessed(UUID id) {
                    ATTEMPTS.incrementAndGet();
                    throw new RuntimeException("transient failure (attempt "
                            + ATTEMPTS.get() + ") for " + id);
                }
            };
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String EXCHANGE = "forgeops.events";
    private static final String ROUTING_KEY = "operational-event.received";
    private static final String QUEUE = "forgeops.events.processing";
    private static final String DLQ = "forgeops.events.processing.dlq";

    @BeforeEach
    void setUp() {
        ATTEMPTS.set(0);
        while (rabbitTemplate.receive(QUEUE, 200) != null) {
            // drain
        }
        while (rabbitTemplate.receive(DLQ, 200) != null) {
            // drain
        }
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

        publish(id);

        // Exhausted retries route the message to the DLQ (never lost, never looping forever).
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message dead = rabbitTemplate.receive(DLQ, 200);
            assertThat(dead).as("message dead-lettered after retry exhaustion").isNotNull();
            assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(id.toString());
        });

        // It was retried the bounded number of times (initial try + retries = max-attempts),
        // not once and not forever.
        assertThat(ATTEMPTS.get()).isEqualTo(3);
        // And it is not still sitting on the processing queue.
        assertThat(rabbitTemplate.receive(QUEUE, 200)).isNull();
    }
}
