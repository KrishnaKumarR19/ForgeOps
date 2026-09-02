package com.forgeops.identity.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Minimal stateless Spring Security configuration for Bearer-JWT authentication
 * (Phase 4.2 Slice 4, SECURITY_DESIGN.md §7/§12).
 *
 * <p>Scope is deliberately narrow — this slice distinguishes authenticated from
 * unauthenticated requests only:
 * <ul>
 *   <li>the API is stateless: no HTTP session is created or used;</li>
 *   <li>CSRF is disabled — there are no cookies/sessions to protect and clients
 *       authenticate with a Bearer token per request;</li>
 *   <li>form login, HTTP Basic and OAuth2/OIDC are all disabled;</li>
 *   <li>{@code POST /api/v1/auth/login} and the health endpoint are public; {@code POST
 *       /api/v1/auth/register} requires the {@code ADMIN} role (ADR-0033); everything else
 *       (including {@code GET /api/v1/auth/me}) requires authentication;</li>
 *   <li>the {@link JwtAuthenticationFilter} runs before the username/password filter and
 *       populates the security context (with {@code ROLE_*} authorities) from a valid
 *       token;</li>
 *   <li>authentication failures are rendered as RFC 9457 Problem Details ({@code 401}) by
 *       {@link ProblemDetailAuthenticationEntryPoint}, and authorization failures
 *       ({@code 403}) by {@link ProblemDetailAccessDeniedHandler} — the two are kept
 *       distinct (SECURITY_DESIGN.md §15).</li>
 * </ul>
 *
 * <p>Authorization is expressed as URL-level request-matcher rules on this single filter
 * chain (no method-level annotations, no second chain), consistent with the Slice 4 setup.
 * Roles map to {@code ROLE_<name>} authorities exactly once (see
 * {@link AuthenticatedUserAuthentication}), so {@code hasRole("ADMIN")} matches without a
 * doubled prefix. The correlation-id filter is registered at highest precedence as its own
 * servlet filter, so it already runs before this chain and both 401 and 403 responses retain
 * their correlation id.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            ProblemDetailAuthenticationEntryPoint entryPoint,
                                            ProblemDetailAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").hasRole("ADMIN")
                        // Event submission: ADMIN/ENGINEER/INCIDENT_MANAGER allowed, VIEWER
                        // denied (403) per API_CONTRACTS.md §5 authorization matrix.
                        .requestMatchers(HttpMethod.POST, "/api/v1/events")
                            .hasAnyRole("ADMIN", "ENGINEER", "INCIDENT_MANAGER")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
