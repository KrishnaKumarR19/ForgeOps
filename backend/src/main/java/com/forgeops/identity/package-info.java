/**
 * identity module — authoritative domain.
 *
 * <p>Owns users and roles: registration, authentication, tokens, and role-based
 * authorization (DOMAIN_MODEL.md §1.1, PRD FR-ID). Phase 4.1 implements the persistence
 * foundation only — the {@code domain} model (User, Role, AccountStatus, PasswordHash,
 * UserRepository port) and the {@code infrastructure} JPA adapter. Authentication, JWT,
 * authorization, password hashing, and HTTP endpoints are later Phase 4 slices.
 *
 * <p>Boundary rule: other modules interact with identity through its public interfaces,
 * never through its persistence internals.
 */
package com.forgeops.identity;
