package com.forgeops.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a correlation id for every HTTP request:
 * <ul>
 *   <li>reads the {@code X-Request-Id} header if the client supplied a valid one;</li>
 *   <li>otherwise generates one;</li>
 *   <li>puts it in the SLF4J {@link MDC} under {@value #MDC_KEY} so logs are correlated;</li>
 *   <li>echoes it back on the {@code X-Request-Id} response header.</li>
 * </ul>
 *
 * <p>Foundation only: propagation into asynchronous messages (RabbitMQ) is deferred to a
 * later phase. The id is diagnostic metadata, never identity/authorization
 * (API_CONTRACTS.md §21).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CorrelationId correlationId =
                CorrelationId.fromClientValueOrGenerate(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId.value());
        response.setHeader(HEADER, correlationId.value());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
