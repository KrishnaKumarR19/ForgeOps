package com.forgeops.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.common.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Renders authentication failures (missing/invalid/expired token on a protected endpoint)
 * as an RFC 9457 {@code application/problem+json} 401 response, integrating with the
 * existing Problem Details convention (API_CONTRACTS.md §18, ADR-0029) rather than
 * redesigning it.
 *
 * <p>The correlation id is attached for diagnostics. The response is deliberately generic:
 * it never reveals why the token failed, and it never echoes the token, header, or claim
 * values (SECURITY_DESIGN.md §17, §11 — this is a 401 concern, not 403).
 */
@Component
class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Authentication required");
        problem.setDetail("Authentication is required to access this resource.");
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
