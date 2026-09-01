/**
 * identity infrastructure layer (ADR-0030).
 *
 * <p>Holds the JPA persistence entity ({@code UserEntity}), the Spring Data repository, and
 * the {@code JpaUserRepository} adapter implementing the domain {@code UserRepository}
 * port. These types are internal to the identity module and must not be referenced by other
 * modules or by the identity domain. Schema is owned by Flyway migrations; the mapping is
 * validated against it ({@code ddl-auto=validate}).
 */
package com.forgeops.identity.infrastructure;
