package com.forgeops.incidents.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for event-driven incident detection (Phase 7 Slice 4, ratified v1 contract).
 * The correlation window is the sliding time span (on the event's {@code received_at}) within
 * which a new event correlates to an existing active incident. Configurable with a safe default
 * ({@code PT30M}); a non-positive value falls back to the default.
 *
 * @param correlationWindow sliding correlation window (default 30 minutes)
 */
@ConfigurationProperties(prefix = "forgeops.incidents.detection")
public record DetectionProperties(Duration correlationWindow) {

    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(30);

    public DetectionProperties {
        correlationWindow = (correlationWindow == null || correlationWindow.isZero()
                || correlationWindow.isNegative()) ? DEFAULT_WINDOW : correlationWindow;
    }
}
