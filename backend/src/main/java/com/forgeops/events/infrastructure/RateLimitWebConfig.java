package com.forgeops.events.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.events.application.RateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the ingestion rate-limit interceptor for {@code POST /api/v1/events} only (Phase 8
 * Slice 1, FR-RL-6). No other endpoint (GET events, incidents, health, auth, actuator) is
 * intercepted. The interceptor runs inside the MVC dispatcher, after the security filter chain,
 * so it sees the authenticated principal and never affects the {@code 401} path.
 *
 * <p><strong>Why a {@code @Component} {@link WebMvcConfigurer} (not a plain {@code @Bean}).</strong>
 * Spring Boot's {@code WebMvcAutoConfiguration}/{@code DelegatingWebMvcConfiguration} reliably
 * collects {@code WebMvcConfigurer} beans discovered by component scanning; a configurer declared
 * only as a {@code @Bean} inside another {@code @Configuration} was found NOT to be applied in the
 * full application context (the bean existed but its {@code addInterceptors} never ran), so the
 * interceptor silently did nothing at runtime. Component-scanning this configurer fixes that.
 *
 * <p>To keep sliced {@code @WebMvcTest} suites healthy (they pull in {@code WebMvcConfigurer}
 * components but do not provide a {@link RateLimiter}), the limiter is injected as an
 * {@link ObjectProvider}: when no limiter bean is present the configurer registers nothing and is
 * a harmless no-op. In the full application the limiter bean is present, so the interceptor is
 * registered.
 */
@Component
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<RateLimiter> rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitWebConfig(ObjectProvider<RateLimiter> rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter == null) {
            return; // no limiter configured (e.g. a sliced test) — register nothing
        }
        registry.addInterceptor(new IngestionRateLimitInterceptor(limiter, objectMapper))
                .addPathPatterns("/api/v1/events");
    }
}
