package com.forgeops.events.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * JPA entity for a service reference row (PERSISTENCE_MODEL.md §4). Package-private: the
 * events module's persistence internals are not exposed. Rows are provisioned by the Flyway
 * migration (controlled reference data); there is no management API in this slice.
 */
@Entity
@Table(name = "services")
class ServiceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "key", nullable = false, unique = true, updatable = false)
    private String key;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    protected ServiceEntity() {
        // Required by JPA.
    }

    UUID getId() {
        return id;
    }

    String getKey() {
        return key;
    }

    String getDisplayName() {
        return displayName;
    }
}
