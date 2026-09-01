package com.forgeops.identity.infrastructure.security;

import com.forgeops.identity.application.AccessTokenValidator;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.AuthenticationService;
import com.forgeops.identity.application.InvalidAccessTokenException;
import com.forgeops.identity.application.ValidatedAccessToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates HTTP requests that carry a Bearer JWT (Phase 4.2 Slice 4).
 *
 * <p>Behavior:
 * <ul>
 *   <li>Extracts a token only from {@code Authorization: Bearer <jwt>}. Query-parameter,
 *       cookie and custom-header tokens are never consulted (SECURITY_DESIGN.md §17).</li>
 *   <li>When no Bearer header is present, the request continues unauthenticated — the
 *       security filter chain then rejects protected endpoints with 401 and lets public
 *       endpoints (login, health) through.</li>
 *   <li>When a Bearer token is present it is validated ({@link AccessTokenValidator}) and
 *       resolved to an active persisted user ({@link AuthenticationService}); on success the
 *       {@link AuthenticatedUser} is placed in the {@link SecurityContextHolder}. On any
 *       failure the context is left unauthenticated and the entry point produces 401.</li>
 * </ul>
 *
 * <p>The raw token and Authorization header are never logged (SECURITY_DESIGN.md §17). This
 * filter runs after the correlation-id filter so 401 responses still carry a correlation id.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessTokenValidator validator;
    private final AuthenticationService authenticationService;

    JwtAuthenticationFilter(AccessTokenValidator validator,
                            AuthenticationService authenticationService) {
        this.validator = validator;
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                ValidatedAccessToken validated = validator.validate(token);
                AuthenticatedUser principal = authenticationService.authenticate(validated);
                SecurityContextHolder.getContext().setAuthentication(
                        new AuthenticatedUserAuthentication(principal));
            } catch (InvalidAccessTokenException e) {
                // Leave the context unauthenticated; the entry point emits 401. Do not log
                // the token or the reason detail at request scope.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Returns the JWT from a well-formed {@code Authorization: Bearer <jwt>} header, or
     * {@code null} if the header is absent or not a non-empty Bearer credential.
     */
    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
