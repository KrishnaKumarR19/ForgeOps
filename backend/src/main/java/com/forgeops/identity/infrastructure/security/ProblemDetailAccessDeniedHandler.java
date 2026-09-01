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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders authorization failures — a request that <em>is</em> authenticated but whose
 * principal lacks the required role — as an RFC 9457 {@code application/problem+json}
 * {@code 403} response (Phase 4.2 Slice 5, SECURITY_DESIGN.md §15, ADR-0029).
 *
 * <p>This is deliberately distinct from the {@code 401}
 * {@link ProblemDetailAuthenticationEntryPoint}: an authenticated-but-forbidden caller must
 * receive {@code 403}, never {@code 401}. The correlation id is attached for diagnostics.
 * The response never reveals which role/permission was required, nor any token contents,
 * user secret, security configuration, or stack trace (SECURITY_DESIGN.md §15).
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access denied");
        problem.setDetail("You do not have permission to access this resource.");
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
