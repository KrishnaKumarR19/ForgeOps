/**
 * identity api layer (ADR-0030) — the HTTP boundary for identity.
 *
 * <p>Phase 4.2 Slice 3 adds the public {@code POST /api/v1/auth/login} endpoint and its
 * request/response DTOs. Controllers depend on the application layer (use cases) and map
 * results/errors to HTTP; they do not touch JPA entities or domain internals directly.
 * JWT validation, authenticated-principal extraction, and authorization are later slices.
 */
package com.forgeops.identity.api;
