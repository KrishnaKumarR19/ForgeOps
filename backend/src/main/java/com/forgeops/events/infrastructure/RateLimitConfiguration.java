package com.forgeops.events.infrastructure;

import com.forgeops.events.application.RateLimitProperties;
import com.forgeops.events.application.RateLimiter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires ingestion rate limiting (Phase 8 Slice 1, FR-RL-6). Binds {@link RateLimitProperties}
 * ({@code forgeops.rate-limit.ingestion.*}) and declares the in-process {@link RateLimiter} bean.
 *
 * <p>The MVC interceptor registration lives in the component-scanned {@link RateLimitWebConfig}
 * (a {@code @Component} {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer})
 * so Spring Boot reliably applies it — see the note there. Sliced {@code @WebMvcTest} suites that
 * do not import this configuration get no {@link RateLimiter} bean; the configurer then registers
 * nothing and is a no-op.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, Clock clock) {
        return new InProcessTokenBucketRateLimiter(properties, clock);
    }
}
