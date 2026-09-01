/**
 * identity application layer (ADR-0030) — use-case orchestration and the transaction
 * boundary for identity operations.
 *
 * <p>Phase 4.2 Slice 2 adds {@link com.forgeops.identity.application.UserProvisioningService}
 * (admin-created account provisioning) and the bootstrap-administrator mechanism. This
 * layer depends on the domain (User, roles, ports) and on {@code common} services
 * (IdGenerator, Clock); it must not depend on the {@code api} layer or on JPA/persistence
 * implementation details directly.
 */
package com.forgeops.identity.application;
