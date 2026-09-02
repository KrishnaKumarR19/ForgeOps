package com.forgeops.events.infrastructure;

import com.forgeops.events.application.RateLimitDecision;
import com.forgeops.events.application.RateLimitProperties;
import com.forgeops.events.application.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process, thread-safe {@link RateLimiter} for ingestion (Phase 8 Slice 1, FR-RL-6,
 * API_CONTRACTS §22). This is the v1 adapter behind the framework-free port; no Redis (ADR-0004
 * — Redis is non-authoritative and out of this slice). Losing this state on restart is
 * acceptable because rate limiting is protective, not authoritative.
 *
 * <p><strong>Algorithm — token bucket with continuous refill.</strong> Each principal owns a
 * bucket of capacity {@code limit} that refills at {@code limit / window} tokens per unit time.
 * A request consumes one token; if fewer than one token is available the request is limited and
 * the {@code Retry-After} is the deterministic time for the bucket to accrue the missing
 * fraction of a token ({@code ceil((1 - tokens) / refillPerSecond)}). Tokens are held as a
 * fixed-point value scaled by {@link #SCALE} so refill is exact integer arithmetic (no
 * floating-point drift), and are never allowed to exceed the scaled capacity. Time comes from the
 * injected {@link Clock}, so behavior is fully deterministic and unit-testable without sleeps.
 *
 * <p><strong>Thread safety.</strong> Per-principal state lives in a {@link ConcurrentHashMap} and
 * every check-and-consume runs inside {@link ConcurrentHashMap#compute} on that key, so the
 * refill + consume decision is atomic per principal: two concurrent requests for the same key
 * cannot both consume the last token. Locking is confined to a single map bucket; unrelated
 * principals never contend and no application work is synchronized.
 *
 * <p><strong>Bounded memory.</strong> The map is not allowed to grow without bound. An entry that
 * has been idle longer than the idle-TTL (a small multiple of the window) is evicted lazily
 * (on next access to any key, a bounded opportunistic sweep runs) and defensively when the map
 * exceeds {@link #MAX_ENTRIES}. A restart clears all state (acceptable — protective only).
 */
public class InProcessTokenBucketRateLimiter implements RateLimiter {

    /** Fixed-point scale for fractional tokens (1 token == SCALE units). */
    private static final long SCALE = 1_000_000L;
    /** Hard cap on tracked principals; defends against unbounded key accumulation. */
    private static final int MAX_ENTRIES = 100_000;
    /** Idle time (as a multiple of the window) after which a bucket is evictable. */
    private static final long IDLE_TTL_WINDOWS = 2;
    /** Max keys examined per opportunistic sweep so a call stays O(1)-ish amortized. */
    private static final int SWEEP_BUDGET = 64;

    private final RateLimitProperties properties;
    private final Clock clock;

    /** Scaled bucket capacity (== limit tokens). */
    private final long capacityUnits;
    /** Window length in milliseconds; refill accrues {@code capacityUnits} over this span. */
    private final long windowMillis;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    /** Rotating cursor for the bounded eviction sweep. */
    private final AtomicInteger sweepCursor = new AtomicInteger();

    InProcessTokenBucketRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.capacityUnits = (long) properties.limit() * SCALE;
        this.windowMillis = Math.max(1L, properties.window().toMillis());
    }

    @Override
    public RateLimitDecision tryConsume(String key) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.permit();
        }
        if (key == null || key.isBlank()) {
            // Defensive: a missing principal key must never silently share one global allowance.
            // Callers derive the key from the authenticated principal, so this should not happen.
            throw new IllegalArgumentException("rate-limit key is required");
        }

        long nowMillis = clock.millis();
        maybeEvict(nowMillis);

        boolean[] allowed = new boolean[1];
        long[] tokensAfter = new long[1];
        buckets.compute(key, (k, existing) -> {
            Bucket bucket = existing == null ? new Bucket(capacityUnits, nowMillis) : existing;
            refill(bucket, nowMillis);
            if (bucket.tokens >= SCALE) {
                bucket.tokens -= SCALE;
                allowed[0] = true;
            } else {
                allowed[0] = false;
            }
            tokensAfter[0] = bucket.tokens;
            bucket.lastAccessMillis = nowMillis;
            return bucket;
        });

        if (allowed[0]) {
            return RateLimitDecision.permit();
        }
        return RateLimitDecision.reject(retryAfter(tokensAfter[0]));
    }

    /** Adds the tokens accrued since {@code bucket.lastRefillMillis}, capped at capacity. */
    private void refill(Bucket bucket, long nowMillis) {
        long elapsedMillis = Math.max(0L, nowMillis - bucket.lastRefillMillis);
        if (elapsedMillis == 0L) {
            return;
        }
        // Tokens accrued over `elapsedMillis` = capacityUnits * elapsedMillis / windowMillis
        // (exact ratio: a full window accrues exactly capacityUnits — no per-second truncation).
        // Saturate at capacity for long idle gaps or on multiplication overflow.
        long added;
        if (elapsedMillis >= windowMillis) {
            added = capacityUnits; // at least a full window elapsed → full refill
        } else {
            try {
                added = Math.multiplyExact(capacityUnits, elapsedMillis) / windowMillis;
            } catch (ArithmeticException overflow) {
                added = capacityUnits;
            }
        }
        long candidate = bucket.tokens + added;
        boolean overflowed = candidate < bucket.tokens; // long wrap
        bucket.tokens = (overflowed || candidate > capacityUnits) ? capacityUnits : candidate;
        bucket.lastRefillMillis = nowMillis;
    }

    /** Deterministic Retry-After: time to accrue the missing fraction of one token, rounded up. */
    private Duration retryAfter(long tokensAvailable) {
        long deficit = SCALE - Math.max(0L, tokensAvailable);
        if (deficit <= 0L) {
            return Duration.ZERO;
        }
        // Time to accrue `deficit` units = deficit * windowMillis / capacityUnits (ms), then
        // ceil to whole seconds; at least 1 second so Retry-After is a useful hint.
        long neededMillis;
        try {
            neededMillis = (Math.multiplyExact(deficit, windowMillis) + capacityUnits - 1) / capacityUnits;
        } catch (ArithmeticException overflow) {
            neededMillis = windowMillis; // saturate: never longer than a full window per token
        }
        long seconds = (neededMillis + 999L) / 1000L; // ceil ms → s
        return Duration.ofSeconds(Math.max(1L, seconds));
    }

    /**
     * Bounded, deterministic eviction. On most calls this examines at most {@link #SWEEP_BUDGET}
     * keys and removes those idle beyond the TTL. When the map is over capacity it sweeps the
     * whole map once to shed idle entries (still bounded work relative to map size).
     */
    private void maybeEvict(long nowMillis) {
        long idleTtlMillis = Math.max(1L, properties.window().getSeconds() * 1000L) * IDLE_TTL_WINDOWS;
        long cutoff = nowMillis - idleTtlMillis;

        if (buckets.size() > MAX_ENTRIES) {
            buckets.entrySet().removeIf(e -> e.getValue().lastAccessMillis < cutoff);
            return;
        }
        if (buckets.isEmpty()) {
            return;
        }
        // Opportunistic bounded sweep: rotate through a slice of the keys each call.
        int budget = SWEEP_BUDGET;
        var iterator = buckets.entrySet().iterator();
        // Advance the cursor modulo size so we don't always inspect the same head entries.
        int skip = Math.floorMod(sweepCursor.getAndAdd(SWEEP_BUDGET), Math.max(1, buckets.size()));
        while (skip-- > 0 && iterator.hasNext()) {
            iterator.next();
        }
        while (budget-- > 0 && iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().lastAccessMillis < cutoff) {
                buckets.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Mutable per-principal state; only ever mutated inside {@code buckets.compute(key, ...)}. */
    private static final class Bucket {
        private long tokens;
        private long lastRefillMillis;
        private long lastAccessMillis;

        Bucket(long tokens, long nowMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = nowMillis;
            this.lastAccessMillis = nowMillis;
        }
    }
}
