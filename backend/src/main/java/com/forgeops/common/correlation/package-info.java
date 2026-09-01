/**
 * Correlation / request-context foundation (cross-cutting, belongs in {@code common}).
 *
 * <p>Provides a diagnostic correlation id per request, propagated to logs via MDC and
 * echoed on the response header (API_CONTRACTS.md §21). The id is metadata only — never
 * identity, authorization, an idempotency key, or business identity. Asynchronous
 * propagation is a later-phase concern.
 */
package com.forgeops.common.correlation;
