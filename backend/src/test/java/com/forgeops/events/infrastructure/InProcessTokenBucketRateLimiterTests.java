package com.forgeops.events.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.events.application.RateLimitDecision;
import com.forgeops.events.application.RateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the in-process token-bucket rate limiter (Phase 8 Slice 1,
 * FR-RL-6). Time is driven by a mutable test {@link Clock} — no real sleeps — so refill,
 * exhaustion, and {@code Retry-After} are exact and reproducible.
 */
class InProcessTokenBucketRateLimiterTests {

    private static final String KEY = "principal-a";

    /** A hand-advanced clock so refill is exercised without wall-clock timing. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public long millis() { return now.toEpochMilli(); }
    }

    private static RateLimitProperties props(boolean enabled, int limit, Duration window) {
        return new RateLimitProperties(enabled, limit, window);
    }

    private InProcessTokenBucketRateLimiter limiter(RateLimitProperties p, Clock clock) {
        return new InProcessTokenBucketRateLimiter(p, clock);
    }

    @Test
    void underLimitAllowsEachRequest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 5, Duration.ofMinutes(1)), clock);
        for (int i = 0; i < 5; i++) {
            assertThat(rl.tryConsume(KEY).allowed()).as("request %d", i).isTrue();
        }
    }

    @Test
    void atLimitThenNextRequestIsRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 3, Duration.ofMinutes(1)), clock);
        assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        assertThat(rl.tryConsume(KEY).allowed()).isTrue();

        RateLimitDecision rejected = rl.tryConsume(KEY);
        assertThat(rejected.allowed()).isFalse();
    }

    @Test
    void rejectedDecisionCarriesPositiveRetryAfter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 2, Duration.ofMinutes(1)), clock);
        rl.tryConsume(KEY);
        rl.tryConsume(KEY);

        RateLimitDecision rejected = rl.tryConsume(KEY);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isGreaterThan(Duration.ZERO);
        // 2 per 60s => 1 token per 30s; a full deficit needs at most ~30s.
        assertThat(rejected.retryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void refillAfterWindowAllowsAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 2, Duration.ofMinutes(1)), clock);
        rl.tryConsume(KEY);
        rl.tryConsume(KEY);
        assertThat(rl.tryConsume(KEY).allowed()).isFalse();

        // Advance a full window: the bucket refills to capacity.
        clock.advance(Duration.ofMinutes(1));
        assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        assertThat(rl.tryConsume(KEY).allowed()).isFalse();
    }

    @Test
    void partialRefillGrantsExactlyTheAccruedTokens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        // 6 per 60s => 1 token every 10s.
        var rl = limiter(props(true, 6, Duration.ofMinutes(1)), clock);
        for (int i = 0; i < 6; i++) {
            assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        }
        assertThat(rl.tryConsume(KEY).allowed()).isFalse();

        // After 10s exactly one token has accrued: one allowed, next rejected.
        clock.advance(Duration.ofSeconds(10));
        assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        assertThat(rl.tryConsume(KEY).allowed()).isFalse();
    }

    @Test
    void separatePrincipalsHaveIndependentAllowances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 1, Duration.ofMinutes(1)), clock);
        assertThat(rl.tryConsume("alice").allowed()).isTrue();
        assertThat(rl.tryConsume("alice").allowed()).isFalse();
        // Bob is unaffected by Alice's exhaustion.
        assertThat(rl.tryConsume("bob").allowed()).isTrue();
        assertThat(rl.tryConsume("bob").allowed()).isFalse();
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(false, 1, Duration.ofMinutes(1)), clock);
        for (int i = 0; i < 100; i++) {
            assertThat(rl.tryConsume(KEY).allowed()).isTrue();
        }
    }

    @Test
    void blankKeyIsRejectedDefensively() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 5, Duration.ofMinutes(1)), clock);
        assertThatThrownBy(() -> rl.tryConsume("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rl.tryConsume(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idleEntriesAreEvictedAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        var rl = limiter(props(true, 1, Duration.ofSeconds(1)), clock);
        // Exhaust alice, then let her entry go idle well beyond the TTL (2 * window).
        assertThat(rl.tryConsume("alice").allowed()).isTrue();
        assertThat(rl.tryConsume("alice").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(60)); // >> 2 * 1s TTL
        // A different key's access triggers the opportunistic sweep, evicting alice's idle entry.
        rl.tryConsume("trigger");
        // Alice starts fresh (full bucket) — proving her stale state was removed/refilled, not
        // retained-and-exhausted. (Either eviction or full refill yields "allowed" here; the
        // point is unbounded stale state is not retained as exhausted.)
        assertThat(rl.tryConsume("alice").allowed()).isTrue();
    }

    @Test
    void concurrentRequestsCannotExceedTheLimit() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-20T12:00:00Z"));
        int limit = 50;
        var rl = limiter(props(true, limit, Duration.ofMinutes(1)), clock);

        int threads = 16;
        int attemptsPerThread = 20; // 320 attempts >> 50 allowance, clock frozen (no refill)
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Callable<Void> task = () -> {
                go.await();
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (rl.tryConsume(KEY).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        go.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        // The frozen clock means no refill; exactly `limit` requests may be admitted, never more.
        assertThat(allowed.get()).isEqualTo(limit);
    }
}
