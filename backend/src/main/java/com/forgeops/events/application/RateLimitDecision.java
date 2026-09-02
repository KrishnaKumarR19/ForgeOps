package com.forgeops.events.application;

import java.time.Duration;

/**
 * Outcome of a single {@link RateLimiter#tryConsume(String)} call (Phase 8 Slice 1, FR-RL-6).
 * A framework-free value: no servlet, Spring, HTTP, or Redis types (ADR-0030).
 *
 * <p>When {@link #allowed()} is {@code true} the caller consumed one unit of its allowance and
 * the request may proceed. When {@code false} the allowance is exhausted and {@link #retryAfter()}
 * is a non-negative, deterministic estimate of how long until at least one unit is available
 * again — surfaced to clients as the HTTP {@code Retry-After} header (API_CONTRACTS §18/§22).
 * For an allowed decision {@code retryAfter} is {@link Duration#ZERO} and carries no meaning.
 *
 * @param allowed    whether the request may proceed (one unit consumed) or is rate limited
 * @param retryAfter when not allowed, the estimated wait until retry is sensible (>= 0); zero
 *                   when allowed
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    public RateLimitDecision {
        if (retryAfter == null || retryAfter.isNegative()) {
            retryAfter = Duration.ZERO;
        }
    }

    /** An allowed decision (request proceeds; no retry hint). */
    public static RateLimitDecision permit() {
        return new RateLimitDecision(true, Duration.ZERO);
    }

    /** A rejected decision carrying the deterministic {@code Retry-After} estimate. */
    public static RateLimitDecision reject(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
