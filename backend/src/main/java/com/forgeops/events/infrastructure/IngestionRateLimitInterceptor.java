package com.forgeops.events.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.common.correlation.CorrelationIdFilter;
import com.forgeops.events.application.RateLimitDecision;
import com.forgeops.events.application.RateLimiter;
import com.forgeops.identity.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies protective ingestion rate limiting to {@code POST /api/v1/events} (Phase 8 Slice 1,
 * FR-RL-6, API_CONTRACTS §22). Registered only for that path by {@link RateLimitWebConfig}, so
 * no other endpoint is affected.
 *
 * <p>Placement: as a Spring MVC {@link HandlerInterceptor}, {@code preHandle} runs after the
 * security filter chain, so the authenticated principal is already in the
 * {@link SecurityContextHolder} and an unauthenticated request has already been rejected with
 * {@code 401} before reaching here (the 401 path is unchanged). The correlation-id filter runs
 * even earlier (highest precedence), so a {@code 429} response still carries its correlation id.
 *
 * <p>The rate-limit key is the authoritative authenticated principal's user id — never a
 * client-supplied header, body field, or {@code client_id} (INV-SEC-005). If, defensively, no
 * authenticated principal is present, the request is allowed through unchanged (authorization,
 * not rate limiting, is responsible for rejecting unauthenticated access).
 *
 * <p>On rejection this writes an RFC 9457 {@code application/problem+json} body with
 * {@code status=429}, a {@code Retry-After} header (whole seconds, from the limiter's
 * deterministic estimate), and the correlation id — reusing the repository's existing
 * ProblemDetail conventions. It never exposes bucket/counter internals. Rate limiting is
 * protective only: it does not touch persistence, idempotency, the outbox, incidents, or
 * messaging.
 */
class IngestionRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IngestionRateLimitInterceptor.class);
    private static final String CATEGORY = "event-ingestion";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    IngestionRateLimitInterceptor(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        // Scope strictly to event submission (POST). The path mapping already limits this to
        // /api/v1/events; this guard ensures only the submit verb is rate limited even if other
        // verbs are added on the same path later.
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String principalId = authenticatedPrincipalId();
        if (principalId == null) {
            // No authenticated principal to key on. Do not invent a shared key; let the request
            // proceed — the security layer already governs authenticated access.
            return true;
        }

        RateLimitDecision decision = rateLimiter.tryConsume(principalId);
        if (decision.allowed()) {
            return true;
        }

        long retryAfterSeconds = Math.max(1L, decision.retryAfter().toSeconds());
        writeTooManyRequests(request, response, retryAfterSeconds);
        log.warn("Ingestion rate limit exceeded: principalId={} category={} retryAfterSeconds={}",
                principalId, CATEGORY, retryAfterSeconds);
        return false;
    }

    /** The authenticated principal's user id from the security context, or {@code null}. */
    private static String authenticatedPrincipalId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            return user.userId().toString();
        }
        return null;
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many requests");
        problem.setDetail("Rate limit exceeded for event ingestion. Retry after the indicated delay.");
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
