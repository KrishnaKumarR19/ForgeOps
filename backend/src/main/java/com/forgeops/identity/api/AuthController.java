package com.forgeops.identity.api;

import com.forgeops.common.correlation.CorrelationIdFilter;
import com.forgeops.identity.application.InvalidCredentialsException;
import com.forgeops.identity.application.IssuedAccessToken;
import com.forgeops.identity.application.LoginService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication API (API_CONTRACTS.md §4). Slice 3 implements only {@code POST
 * /api/v1/auth/login}, which is a public endpoint that issues a short-lived RS256 access
 * token on valid credentials.
 *
 * <p>Validation failures map to {@code 400} via the global handler; authentication failures
 * map to a generic {@code 401} (RFC 9457 Problem Details) that never reveals whether the
 * user exists or which factor failed. No JWT validation, principal extraction, or
 * authorization is implemented here — those are later slices.
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
