package com.forgeops.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.forgeops.identity.application.AccessTokenValidator;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.AuthenticationService;
import com.forgeops.identity.application.InvalidAccessTokenException;
import com.forgeops.identity.application.LoginService;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.application.UsernameAlreadyExistsException;
import com.forgeops.identity.application.ValidatedAccessToken;
import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.identity.infrastructure.security.JwtAuthenticationFilter;
import com.forgeops.identity.infrastructure.security.ProblemDetailAccessDeniedHandler;
import com.forgeops.identity.infrastructure.security.ProblemDetailAuthenticationEntryPoint;
import com.forgeops.identity.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorization boundary tests for the real security filter chain, driven through MockMvc
 * without a database or Testcontainers. The {@link SecurityConfig}, {@link
 * JwtAuthenticationFilter} and the 401/403 problem handlers are imported and exercised for
 * real; only the application ports (token validation, principal resolution, login,
 * provisioning) are mocked.
 *
 * <p>These tests establish the 401-vs-403 distinction, the ADMIN requirement on {@code
 * /register}, and that authorization comes only from the validated principal — never from
 * client-supplied headers, body, or query parameters. Synthetic data.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class})
class AuthorizationWebMvcTests {

    private static final UUID USER_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccessTokenValidator accessTokenValidator;
    @MockBean
    private AuthenticationService authenticationService;
    @MockBean
    private LoginService loginService;
    @MockBean
    private UserProvisioningService provisioningService;

    /** Wires the mocks so a given bearer string resolves to a principal with the given roles. */
    private void principalWithRoles(String token, Set<Role> roles) {
        var validated = new ValidatedAccessToken(USER_ID, roles, "jti-1");
        when(accessTokenValidator.validate(eq(token))).thenReturn(validated);
        when(authenticationService.authenticate(validated))
                .thenReturn(new AuthenticatedUser(USER_ID, roles));
    }

    private String registerBody() {
        return "{\"username\":\"newuser\",\"password\":\"CorrectHorseBatteryStaple\",\"roles\":[\"VIEWER\"]}";
    }

    // ----- public endpoint ------------------------------------------------------

    @Test
    void loginIsPublic() throws Exception {
        when(loginService.login(any(), any()))
                .thenReturn(new com.forgeops.identity.application.IssuedAccessToken("tok", 900));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isOk());
    }

    // ----- /me: any authenticated user -----------------------------------------

    @Test
    void meRequiresAuthenticationAndReturns401WithProblemJsonWhenMissing() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void meAllowsAnyAuthenticatedRole() throws Exception {
        principalWithRoles("viewer-token", Set.of(Role.VIEWER));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer viewer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));
    }

    // ----- /register: ADMIN only (401 vs 403) -----------------------------------

    @Test
    void registerWithoutTokenIsUnauthorized401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void registerAsNonAdminIsForbidden403() throws Exception {
        principalWithRoles("engineer-token", Set.of(Role.ENGINEER));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer engineer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void registerAsViewerIsForbidden403() throws Exception {
        principalWithRoles("viewer-token", Set.of(Role.VIEWER));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerAsAdminIsAllowed() throws Exception {
        principalWithRoles("admin-token", Set.of(Role.ADMIN));
        User created = new User(UUID.fromString("018f0000-0000-7000-8000-0000000000bb"),
                "newuser", PasswordHash.ofEncoded("$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA"),
                AccountStatus.ACTIVE, java.util.EnumSet.of(Role.VIEWER),
                Instant.parse("2026-01-01T00:00:00Z"));
        when(provisioningService.provision(eq("newuser"), any(), any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ----- invalid token -> 401 (not 403) --------------------------------------

    @Test
    void invalidTokenOnAdminEndpointIsUnauthorized401NotForbidden() throws Exception {
        when(accessTokenValidator.validate(eq("bad-token")))
                .thenThrow(new InvalidAccessTokenException("invalid"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnauthorized());
    }

    // ----- authorization cannot be overridden by client-supplied data -----------

    @Test
    void clientCannotElevateToAdminViaHeadersBodyOrQuery() throws Exception {
        // Authenticated as ENGINEER only; the request tries every override vector.
        principalWithRoles("engineer-token", Set.of(Role.ENGINEER));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer engineer-token")
                        .header("X-Role", "ADMIN")
                        .header("X-Roles", "ADMIN")
                        .header("X-User-Roles", "ROLE_ADMIN")
                        .param("role", "ADMIN")
                        .param("roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"pw12345678\",\"roles\":[\"VIEWER\"],\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }
}
