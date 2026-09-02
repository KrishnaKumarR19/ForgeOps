package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.common.id.IdGenerator;
import com.forgeops.events.domain.EventSeverity;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OutboxMessage;
import com.forgeops.events.domain.OutboxStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxMessageFactory}: the outbox message built for an accepted event
 * has deterministic, correct fields (UUID v7 id, message/aggregate type, aggregate_id = event
 * id, PENDING, zero attempts, created_at = event received_at, null publisher fields) and a
 * deterministic payload that identifies the event. Synthetic data.
 */
class OutboxMessageFactoryTests {

    private static final UUID EVENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000e1");
    private static final UUID OUTBOX_ID = UUID.fromString("018f2000-0000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("018f1001-0000-7000-8000-000000000001");

    private final IdGenerator idGenerator = () -> OUTBOX_ID;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OutboxMessageFactory factory = new OutboxMessageFactory(idGenerator, mapper);

    private OperationalEvent event() {
        return OperationalEvent.accepted(
                EVENT_ID,
                UUID.fromString("018f0000-0000-7000-8000-0000000000a1"), // clientId
                null, "key-1",
                SERVICE_ID, "checkout",
                ENV_ID, "production",
                "http_5xx", EventSeverity.MAJOR, null,
                Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:01Z"),
                "{\"a\":1}", "hash-abc");
    }

    @Test
    void buildsDeterministicPendingMessageForAcceptedEvent() {
        OutboxMessage message = factory.forAcceptedEvent(event());

        assertThat(message.id()).isEqualTo(OUTBOX_ID);
        assertThat(message.messageType()).isEqualTo("OPERATIONAL_EVENT_RECEIVED");
        assertThat(message.aggregateType()).isEqualTo("OPERATIONAL_EVENT");
        assertThat(message.aggregateId()).isEqualTo(EVENT_ID);
        assertThat(message.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.attempts()).isZero();
        assertThat(message.createdAt()).isEqualTo(Instant.parse("2026-02-01T00:00:01Z"));
        assertThat(message.publishedAt()).isNull();
        assertThat(message.nextAttemptAt()).isNull();
        assertThat(message.lastError()).isNull();
    }

    @Test
    void payloadIdentifiesTheEventAndItsContext() throws Exception {
        OutboxMessage message = factory.forAcceptedEvent(event());

        JsonNode payload = mapper.readTree(message.payload());
        assertThat(payload.get("event_id").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(payload.get("service").asText()).isEqualTo("checkout");
        assertThat(payload.get("environment").asText()).isEqualTo("production");
        assertThat(payload.get("event_type").asText()).isEqualTo("http_5xx");
        assertThat(payload.get("severity").asText()).isEqualTo("MAJOR");
        assertThat(payload.get("occurred_at").asText()).isEqualTo("2026-02-01T00:00:00Z");
        assertThat(payload.get("received_at").asText()).isEqualTo("2026-02-01T00:00:01Z");
    }

    @Test
    void payloadIsDeterministicForTheSameEvent() {
        String first = factory.forAcceptedEvent(event()).payload();
        String second = factory.forAcceptedEvent(event()).payload();

        assertThat(first).isEqualTo(second);
    }
}
