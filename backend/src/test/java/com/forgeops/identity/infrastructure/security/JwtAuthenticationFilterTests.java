package com.forgeops.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.forgeops.identity.application.AccessTokenValidator;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.AuthenticationService;
import com.forgeops.identity.application.InvalidAccessTokenException;
import com.forgeops.identity.application.ValidatedAccessToken;
import com.forgeops.identity.domain.Role;
import jakarta.servlet.FilterChain;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link JwtAuthenticationFilter}: Bearer extraction rules and the fact that
 * the authenticated identity comes only from the validated token, never from client-supplied
 * request data. Collaborators are mocked; no HTTP server, database, or crypto. Synthetic data.
 */
class JwtAuthenticationFilterTests {

    private static final UUID TOKEN_SUBJECT = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");

    private final AccessTokenValidator validator = Mockito.mock(AccessTokenValidator.class);
    private final AuthenticationService authenticationService =
            Mockito.mock(AuthenticationService.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(validator, authenticationService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication runFilter(MockHttpServletRequest request) throws Exception {
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any()); // request always proceeds down the chain
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void authenticatesWithValidBearerTokenAndSetsPrincipalFromToken() throws Exception {
        var validated = new ValidatedAccessToken(TOKEN_SUBJECT, Set.of(Role.ENGINEER), "jti-1");
        when(validator.validate("good-token")).thenReturn(validated);
        when(authenticationService.authenticate(validated))
                .thenReturn(new AuthenticatedUser(TOKEN_SUBJECT, Set.of(Role.ENGINEER)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");

        Authentication auth = runFilter(request);

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) auth.getPrincipal()).userId()).isEqualTo(TOKEN_SUBJECT);
    }

    @Test
    void identityComesFromTokenNotFromClientSuppliedHeadersOrParams() throws Exception {
        UUID attackerClaimedId = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
        var validated = new ValidatedAccessToken(TOKEN_SUBJECT, Set.of(Role.VIEWER), "jti-1");
        when(validator.validate("good-token")).thenReturn(validated);
        when(authenticationService.authenticate(validated))
                .thenReturn(new AuthenticatedUser(TOKEN_SUBJECT, Set.of(Role.VIEWER)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");
        // Client attempts to override identity/roles via headers and params — must be ignored.
        request.addHeader("X-User-Id", attackerClaimedId.toString());
        request.addHeader("X-Roles", "ADMIN");
        request.setParameter("userId", attackerClaimedId.toString());
        request.setParameter("roles", "ADMIN");

        Authentication auth = runFilter(request);

        AuthenticatedUser principal = (AuthenticatedUser) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(TOKEN_SUBJECT);
        assertThat(principal.userId()).isNotEqualTo(attackerClaimedId);
        assertThat(principal.roles()).containsExactly(Role.VIEWER);
    }

    @Test
    void missingAuthorizationHeaderLeavesRequestUnauthenticated() throws Exception {
        Authentication auth = runFilter(new MockHttpServletRequest());

        assertThat(auth).isNull();
        verify(validator, never()).validate(any());
    }

    @Test
    void nonBearerAuthorizationHeaderIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        Authentication auth = runFilter(request);

        assertThat(auth).isNull();
        verify(validator, never()).validate(any());
    }

    @Test
    void emptyBearerTokenIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer ");

        Authentication auth = runFilter(request);

        assertThat(auth).isNull();
        verify(validator, never()).validate(any());
    }

    @Test
    void queryParameterTokenIsNotAccepted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("access_token", "good-token");

        Authentication auth = runFilter(request);

        assertThat(auth).isNull();
        verify(validator, never()).validate(any());
    }

    @Test
    void invalidTokenLeavesRequestUnauthenticated() throws Exception {
        when(validator.validate(eq("bad-token")))
                .thenThrow(new InvalidAccessTokenException("Invalid token signature"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-token");

        Authentication auth = runFilter(request);

        assertThat(auth).isNull();
    }
}
