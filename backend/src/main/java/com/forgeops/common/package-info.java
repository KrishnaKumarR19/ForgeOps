/**
 * common — shared primitives that genuinely cross module boundaries.
 *
 * <p>This package is intentionally minimal and is NOT a general "utils"/"helpers" dump.
 * Only primitives with a real cross-module need belong here — for example, in later
 * phases: a shared correlation-id concept, a common error/Problem-Details representation
 * (API_CONTRACTS.md §18), or base value types used by multiple modules.
 *
 * <p>What does NOT belong here: module-specific domain types, business logic,
 * repositories, controllers, module-specific DTOs, or generic "Utils" grab-bags. Those
 * live in their owning module.
 *
 * <p>Phase 3 populated {@code common} with genuinely cross-cutting foundations only:
 * <ul>
 *   <li>{@code common.correlation} — diagnostic request/correlation id (all modules);</li>
 *   <li>{@code common.web} — shared RFC 9457 Problem Details error handling;</li>
 *   <li>{@code common.time} — injectable {@link java.time.Clock} for deterministic time;</li>
 *   <li>{@code common.id} — {@code IdGenerator} (UUID v7, ADR-0023) used by every module.</li>
 * </ul>
 * Anything added here in future must clear the same bar: a real cross-module need.
 */
package com.forgeops.common;
