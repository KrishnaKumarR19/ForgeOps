package com.forgeops.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides an injectable {@link Clock} so that time-dependent behavior (correlation
 * windows, state-machine timestamps, reliability tests) can be made deterministic in
 * tests by supplying a fixed clock.
 *
 * <p>Justified now (not premature): the domain will need timestamps for events, incidents,
 * and audit, and deterministic tests around the correlation time window and lifecycle
 * transitions require controllable time. Domain/application code must depend on an
 * injected {@link Clock} rather than calling {@code Instant.now()}/{@code System} directly.
 */
@Configuration
public class TimeConfiguration {

    /** UTC system clock by default; overridable with a fixed clock in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
