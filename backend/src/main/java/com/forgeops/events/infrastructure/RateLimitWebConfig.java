package com.forgeops.events.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.events.application.RateLimiter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the ingestion rate-limit interceptor for {@code /api/v1/events} only (Phase 8
 * Slice 1, FR-RL-6). No other endpoint (GET events, incidents, health, auth, actuator) is
 * intercepted. The interceptor runs inside the MVC dispatcher, after the security filter chain,
 * so it sees the authenticated principal and never affects the {@code 401} path.
 *
 * <p>This is a plain {@link WebMvcConfigurer} instantiated as a bean by {@link
 * RateLimitConfiguration} — not a component-scanned {@code @Configuration}. That keeps sliced
 * {@code @WebMvcTest} suites that do not import {@link RateLimitConfiguration} completely
 * unaffected (they never see this configurer or the limiter).
 */
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitWebConfig(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IngestionRateLimitInterceptor(rateLimiter, objectMapper))
                .addPathPatterns("/api/v1/events");
    }
}
