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
 *   <li>{@code POST /api/v1/auth/login} and the health endpoint are public; everything else
 *       requires authentication;</li>
 *   <li>the {@link JwtAuthenticationFilter} runs before the username/password filter and
 *       populates the security context from a valid token;</li>
 *   <li>authentication failures are rendered as RFC 9457 Problem Details by
 *       {@link ProblemDetailAuthenticationEntryPoint}.</li>
 * </ul>
 *
 * <p>No role-based authorization rules are configured here — {@code 403} authorization is
 * the next slice (SECURITY_DESIGN.md §11). The correlation-id filter is registered at
 * highest precedence as its own servlet filter, so it already runs before this chain and
 * 401 responses retain their correlation id.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            ProblemDetailAuthenticationEntryPoint entryPoint)
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
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
