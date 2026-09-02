package com.forgeops.events.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the asynchronous event consumer (Phase 6 Slice 3). The consumer's retry
 * policy and concurrency are config-driven with safe, deterministic defaults (ADR-0014 fixes
 * the semantics — idempotent, explicit ack, bounded retry, dead-letter — while leaving the
 * concrete intervals/attempts an implementation choice, as ADR-0019 did for the publisher).
 * No secrets; overridable per environment.
 *
 * <p>{@code enabled} lets tests stop the listener from racing their assertions (mirrors the
 * publisher's {@code forgeops.outbox.publisher.enabled}); it defaults to {@code true} so the
 * consumer runs in production.
 *
 * <p>Retry is <strong>consumer-side</strong> and entirely separate from the Slice 2
 * publisher backoff: {@code maxAttempts} bounds in-listener redelivery of a failing message;
 * once exhausted the message is rejected without requeue and the broker dead-letters it
 * (INV-MSG-005/006, FR-RL-4/5).
 *
 * @param enabled     whether the {@code @RabbitListener} is active (true in production)
 * @param concurrency number of concurrent consumers on the processing queue
 * @param retry       bounded retry policy for transient processing failures
 */
@ConfigurationProperties(prefix = "forgeops.events.consumer")
public record EventConsumerProperties(
        Boolean enabled,
        Integer concurrency,
        Retry retry) {

    public EventConsumerProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        concurrency = (concurrency == null || concurrency <= 0) ? 1 : concurrency;
        retry = retry == null ? new Retry(null, null, null) : retry;
    }

    /**
     * Bounded exponential retry for transient failures. Total attempts includes the first try;
     * {@code maxAttempts=3} means the initial delivery plus two retries. After the last attempt
     * the message is dead-lettered rather than requeued forever.
     *
     * @param maxAttempts   total delivery attempts before dead-lettering (>= 1)
     * @param initialDelay  delay before the first retry
     * @param maxDelay      hard cap on the computed backoff delay
     */
    public record Retry(Integer maxAttempts, Duration initialDelay, Duration maxDelay) {
        public Retry {
            maxAttempts = (maxAttempts == null || maxAttempts < 1) ? 3 : maxAttempts;
            initialDelay = initialDelay == null ? Duration.ofSeconds(2) : initialDelay;
            maxDelay = maxDelay == null ? Duration.ofSeconds(30) : maxDelay;
        }
    }
}
