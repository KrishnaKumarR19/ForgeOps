package com.forgeops.identity.domain;

/**
 * The fixed set of ForgeOps roles (SECURITY_DESIGN.md §10, DOMAIN_MODEL.md §2). Persisted
 * as a stable string value in the {@code user_roles} join table (PERSISTENCE_MODEL.md §3).
 *
 * <p>No fine-grained permissions/ACLs are modeled — authorization maps roles to operations
 * at the application/API layer.
 */
public enum Role {
    ADMIN,
    ENGINEER,
    INCIDENT_MANAGER,
    VIEWER
}
