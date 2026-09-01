package com.forgeops.common.web;

import com.forgeops.common.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global error-handling foundation producing RFC 9457 Problem Details
 * ({@code application/problem+json}) per API_CONTRACTS.md §18 / ADR-0029.
 *
 * <p>Phase 3 scope: this establishes consistent, safe error responses for the framework
 * only. It handles transport/validation failures and a generic fallback. It does
 * <strong>not</strong> invent business exceptions — those are added by their owning
 * modules in later phases. It never exposes stack traces or infrastructure details, and
 * it attaches the diagnostic correlation id to every problem response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean-validation failures on request bodies → 400 with a structured field list. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("errors", toFieldErrors(ex));
        decorate(problem, request);
        return problem;
    }

    /**
     * Generic fallback → 500 with a safe, generic message. The actual exception is logged
     * server-side (with the correlation id via MDC); it is never leaked to the client.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request {}", request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("An unexpected error occurred.");
        decorate(problem, request);
        return problem;
    }

    private List<Map<String, String>> toFieldErrors(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of(
                    "field", fe.getField(),
                    "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()));
        }
        return errors;
    }

    private void decorate(ProblemDetail problem, HttpServletRequest request) {
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
