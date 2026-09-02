package com.forgeops.events.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for ingestion rate limiting (Phase 8 Slice 1, FR-RL-6, API_CONTRACTS §22).
 * Protects {@code POST /api/v1/events} against abuse; the limit and window are config-driven so
 * the contract fixes the behavior, not the numbers (no fabricated scalability — NFR-8).
 *
 * <p>ForgeOps v1 production defaults: {@code enabled=true}, {@code limit=60} requests per
 * {@code window=PT1M} (one minute) per authenticated principal. Validation clamps to safe
 * values: a non-positive {@code limit} falls back to the default, and a non-positive
 * {@code window} falls back to the default (a misconfiguration must never disable protection by
 * yielding a zero/negative window or an unlimited allowance).
 *
 * @param enabled whether ingestion rate limiting is active (default {@code true})
 * @param limit   maximum requests permitted per principal within {@code window} (>= 1)
 * @param window  the rolling time window over which {@code limit} applies (> 0; default 1 minute)
 */
@ConfigurationProperties(prefix = "forgeops.rate-limit.ingestion")
public record RateLimitProperties(Boolean enabled, Integer limit, Duration window) {

    /** ForgeOps v1 production defaults (config-overridable). */
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_LIMIT = 60;
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    public RateLimitProperties {
        enabled = enabled == null ? DEFAULT_ENABLED : enabled;
        limit = (limit == null || limit < 1) ? DEFAULT_LIMIT : limit;
        window = (window == null || window.isZero() || window.isNegative())
                ? DEFAULT_WINDOW : window;
    }

    /** Convenience accessor: {@code true} when protection is active. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
