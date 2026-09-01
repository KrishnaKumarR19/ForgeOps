package com.forgeops.identity.api;

import com.forgeops.common.correlation.CorrelationIdFilter;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.InvalidCredentialsException;
import com.forgeops.identity.application.IssuedAccessToken;
import com.forgeops.identity.application.LoginService;
import com.forgeops.identity.domain.Role;
import jakarta.validation.Valid;
import java.util.List;
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
 * <p>Validation failures map to {@code 400} via the global handler; authentication failures
 * map to a generic {@code 401} (RFC 9457 Problem Details) that never reveals whether the
 * user exists or which factor failed. No role-based authorization is implemented here —
 * that is a later slice.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final LoginService loginService;

    AuthController(LoginService loginService) {
        this.loginService = loginService;
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
     * Maps authentication failure to a generic 401 Problem Details. The detail is
     * intentionally generic (no enumeration, no factor disclosure) and carries the
     * diagnostic correlation id.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Authentication failed");
        problem.setDetail("Invalid credentials");
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
