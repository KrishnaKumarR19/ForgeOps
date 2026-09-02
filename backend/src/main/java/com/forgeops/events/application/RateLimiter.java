package com.forgeops.events.application;

/**
 * Application-facing port for protective, per-principal request rate limiting (Phase 8 Slice 1,
 * FR-RL-6, API_CONTRACTS §22). Framework-free (ADR-0030): no servlet, Spring Security, HTTP, or
 * Redis types — the key is an opaque string the caller derives from the authoritative
 * authenticated principal (never a client-supplied value; INV-SEC-005).
 *
 * <p>Rate limiting is <strong>protective, not authoritative</strong> (ADR-0004): no business
 * correctness depends on it. It never participates in event persistence, idempotency, the
 * outbox, incidents, or messaging. The v1 implementation is in-process; the port is defined so a
 * Redis-backed adapter can replace it later without touching callers.
 *
 * <p>The single operation is an <em>atomic</em> check-and-consume: two concurrent calls for the
 * same key must not both succeed beyond the configured allowance (no check-then-increment race).
 */
public interface RateLimiter {

    /**
     * Atomically attempts to consume one unit of {@code key}'s allowance.
     *
     * @param key the rate-limit key — the authenticated principal id (opaque, non-null)
     * @return an allowed decision if a unit was consumed, otherwise a limited decision carrying
     *         a deterministic {@code Retry-After} estimate
     */
    RateLimitDecision tryConsume(String key);
}
