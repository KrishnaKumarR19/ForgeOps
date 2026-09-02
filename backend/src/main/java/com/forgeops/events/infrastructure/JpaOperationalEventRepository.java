package com.forgeops.events.infrastructure;

import com.forgeops.events.domain.DuplicateIdempotencyKeyException;
import com.forgeops.events.domain.OperationalEvent;
import com.forgeops.events.domain.OperationalEventRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter implementing the domain {@link OperationalEventRepository} port
 * (ADR-0030). Maps between the framework-free {@link OperationalEvent} aggregate and
 * {@link OperationalEventEntity}, keeping JPA out of the domain.
 *
 * <p>{@code saveAndFlush} forces the INSERT to reach PostgreSQL within this call so the
 * authoritative {@code uq_operational_events_client_idempotency} constraint is enforced at
 * save time. A concurrent duplicate that loses the race surfaces as a
 * {@link DataIntegrityViolationException}, which is translated to the framework-free
 * {@link DuplicateIdempotencyKeyException} so the application can resolve replay vs conflict
 * without depending on Spring data-access types.
 */
@Repository
class JpaOperationalEventRepository implements OperationalEventRepository {

    private final SpringDataOperationalEventJpaRepository jpa;
    private final SpringDataServiceJpaRepository services;
    private final SpringDataEnvironmentJpaRepository environments;

    JpaOperationalEventRepository(SpringDataOperationalEventJpaRepository jpa,
                                  SpringDataServiceJpaRepository services,
                                  SpringDataEnvironmentJpaRepository environments) {
        this.jpa = jpa;
        this.services = services;
        this.environments = environments;
    }

    @Override
    public OperationalEvent save(OperationalEvent event) {
        try {
            jpa.saveAndFlush(toEntity(event));
            // The just-built domain event already carries the resolved keys; return it
            // directly rather than re-reading (avoids an id->key lookup on the hot path).
            return event;
        } catch (DataIntegrityViolationException e) {
            // The only uniqueness constraint on this table is (client_id, idempotency_key).
            throw new DuplicateIdempotencyKeyException(
                    "Idempotency key already exists for this client", e);
        }
    }

    @Override
    public Optional<OperationalEvent> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<OperationalEvent> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return jpa.findByClientIdAndIdempotencyKey(clientId, idempotencyKey).map(this::toDomain);
    }

    private static OperationalEventEntity toEntity(OperationalEvent e) {
        return new OperationalEventEntity(
                e.id(),
                e.clientId(),
                e.producerEventId().orElse(null),
                e.idempotencyKey().orElse(null),
                e.serviceId(),
                e.environmentId(),
                e.eventType(),
                e.severity().orElse(null),
                e.failureSignature().orElse(null),
                e.occurredAt(),
                e.receivedAt(),
                e.payload(),
                e.payloadHash(),
                e.status(),
                e.incidentId().orElse(null));
    }

    private OperationalEvent toDomain(OperationalEventEntity e) {
        // Resolve the reference ids back to their keys for the domain representation. Rows are
        // controlled reference data and always present for a persisted event (FK-enforced).
        String serviceKey = services.findById(e.getServiceId())
                .map(ServiceEntity::getKey).orElse(null);
        String environmentKey = environments.findById(e.getEnvironmentId())
                .map(EnvironmentEntity::getKey).orElse(null);
        return new OperationalEvent(
                e.getId(),
                e.getClientId(),
                e.getProducerEventId(),
                e.getIdempotencyKey(),
                e.getServiceId(),
                serviceKey,
                e.getEnvironmentId(),
                environmentKey,
                e.getEventType(),
                e.getSeverity(),
                e.getFailureSignature(),
                e.getOccurredAt(),
                e.getReceivedAt(),
                e.getPayload(),
                e.getPayloadHash(),
                e.getStatus(),
                e.getIncidentId());
    }
}
