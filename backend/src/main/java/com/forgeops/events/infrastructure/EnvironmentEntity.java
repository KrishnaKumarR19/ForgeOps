package com.forgeops.events.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * JPA entity for an environment reference row (PERSISTENCE_MODEL.md §4). Package-private.
 * Rows are provisioned by the Flyway migration (controlled reference data); there is no
 * management API in this slice.
 */
@Entity
@Table(name = "environments")
class EnvironmentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "key", nullable = false, unique = true, updatable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    protected EnvironmentEntity() {
        // Required by JPA.
    }

    UUID getId() {
        return id;
    }

    String getKey() {
        return key;
    }

    String getName() {
        return name;
    }
}
