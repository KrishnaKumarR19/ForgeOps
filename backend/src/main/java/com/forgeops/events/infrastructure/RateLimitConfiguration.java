package com.forgeops.events.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.events.application.RateLimitProperties;
import com.forgeops.events.application.RateLimiter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires ingestion rate limiting (Phase 8 Slice 1, FR-RL-6). Binds {@link RateLimitProperties}
 * ({@code forgeops.rate-limit.ingestion.*}) and declares the in-process limiter and the MVC
 * interceptor registration as explicit beans.
 *
 * <p>Declaring the limiter and the {@link RateLimitWebConfig} here (rather than component-scanning
 * them) means a sliced {@code @WebMvcTest} that does not import this configuration is entirely
 * unaffected — it neither sees the {@link WebMvcConfigurer} nor requires a {@link RateLimiter}
 * bean. In the full application this {@code @Configuration} is component-scanned, so both beans are
 * present.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, Clock clock) {
        return new InProcessTokenBucketRateLimiter(properties, clock);
    }

    @Bean
    RateLimitWebConfig rateLimitWebConfig(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        return new RateLimitWebConfig(rateLimiter, objectMapper);
    }
}
