package com.forgeops.identity.api;

import com.forgeops.common.correlation.CorrelationIdFilter;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.InvalidCredentialsException;
import com.forgeops.identity.application.IssuedAccessToken;
import com.forgeops.identity.application.LoginService;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.application.UsernameAlreadyExistsException;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import jakarta.validation.Valid;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication API (API_CONTRACTS.md §4). Slice 3 implements only {@code POST
 * /api/v1/auth/login}, which is a public endpoint that issues a short-lived RS256 access
 * token on valid credentials.
 *
 * <p>Slice 4 adds {@code GET /api/v1/auth/me}, a protected endpoint that reports the
 * authenticated principal established by the Bearer-JWT filter chain.
 *
 * <p>Slice 5 adds {@code POST /api/v1/auth/register}, an ADMIN-gated provisioning endpoint
 * (ADR-0033). Its ADMIN role requirement is enforced by the security filter chain, so an
 * authenticated non-ADMIN caller is rejected with {@code 403} before this method runs.
 *
 * <p>Validation failures map to {@code 400} via the global handler; authentication failures
 * map to a generic {@code 401} (RFC 9457 Problem Details) that never reveals whether the
 * user exists or which factor failed; a duplicate username maps to {@code 409}.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final LoginService loginService;
    private final UserProvisioningService provisioningService;

    AuthController(LoginService loginService, UserProvisioningService provisioningService) {
        this.loginService = loginService;
        this.provisioningService = provisioningService;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        IssuedAccessToken token = loginService.login(request.username(), request.password());
        return ResponseEntity.ok(LoginResponse.bearer(token.token(), token.expiresInSeconds()));
    }

    /**
     * Returns the authenticated principal (API_CONTRACTS.md §4). Protected: reachable only
     * with a valid Bearer JWT — an absent/invalid token yields 401 from the security entry
     * point before this method runs. The principal identity is the server-established
     * {@code sub}; it is never taken from the request.
     */
    @GetMapping("/me")
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        List<String> roles = principal.roles().stream().map(Role::name).sorted().toList();
        return ResponseEntity.ok(new MeResponse(principal.userId().toString(), roles));
    }

    /**
     * Provisions a new user (API_CONTRACTS.md §4, ADR-0033). ADMIN-only: the required role is
     * enforced by the security filter chain, so reaching this method already implies an
     * authenticated ADMIN. The server generates the id and sets the account status; the
     * client supplies only username, password, and roles. The password is never echoed;
     * only the created user's non-secret representation is returned with {@code 201}.
     */
    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        Set<Role> roles = toRoles(request.roles());
        User created = provisioningService.provision(request.username(), request.password(), roles);
        List<String> roleNames = created.roles().stream().map(Role::name).sorted().toList();
        RegisterResponse body = new RegisterResponse(
                created.id().toString(), created.username(), roleNames, created.status().name());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Maps role name strings to the domain enum; an unknown role is a 400 (bad request). */
    private static Set<Role> toRoles(Set<String> roleNames) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String name : roleNames) {
            try {
                roles.add(Role.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown role: " + name);
            }
        }
        return roles;
    }

    /** Duplicate username → 409 Conflict (RFC 9457), with the correlation id attached. */
    @ExceptionHandler(UsernameAlreadyExistsException.class)
    ProblemDetail handleUsernameExists() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Username already exists");
        problem.setDetail("A user with that username already exists.");
        attachCorrelationId(problem);
        return problem;
    }

    /** Invalid request data (e.g. unknown role) → 400 Bad Request (RFC 9457). */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail("One or more fields are invalid.");
        attachCorrelationId(problem);
        return problem;
    }

    /**
     * Maps authentication failure to a generic 401 Problem Details. The detail is
     * intentionally generic (no enumeration, no factor disclosure) and carries the
     * diagnostic correlation id.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Authentication failed");
        problem.setDetail("Invalid credentials");
        attachCorrelationId(problem);
        return problem;
    }

    private static void attachCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
