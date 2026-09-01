/**
 * identity domain layer — framework-independent (ADR-0030).
 *
 * <p>Holds the {@link com.forgeops.identity.domain.User} aggregate, {@link
 * com.forgeops.identity.domain.Role}, {@link com.forgeops.identity.domain.AccountStatus},
 * the {@link com.forgeops.identity.domain.PasswordHash} security value object (no plaintext
 * path), and the {@link com.forgeops.identity.domain.UserRepository} port. No Spring,
 * JPA/Hibernate, or persistence-framework types appear here.
 */
package com.forgeops.identity.domain;
