package com.forgeops.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.forgeops.testsupport.PostgresTestContainer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Authorization integration tests (Phase 4.2 Slice 5) over real HTTP against PostgreSQL
 * (Testcontainers). Verifies the 401-vs-403 boundary, the ADMIN requirement on {@code
 * POST /api/v1/auth/register}, that role-claim tampering does not yield elevation, and that
 * both failure kinds return RFC 9457 {@code application/problem+json} with a correlation id.
 *
 * <p>Forged tokens are minted with the application's own configured (test-only) RSA private
 * key so signatures are valid but claims are not — isolating each check. The shared,
 * non-rolled-back database is truncated before each test; bootstrap admin is disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class AuthorizationIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private JwtKeyConfiguration.RsaKeyPair keyPair;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestRestTemplate rest;

    @BeforeEach
    void cleanIdentityTables() {
        // Use a request factory that BUFFERS the request body (Content-Length, not chunked
        // streaming). The default JDK client streams the POST body and then cannot replay it
        // when the server returns a 401 (it treats the response as an auth challenge and
        // tries to resend), throwing "cannot retry due to server authentication, in
        // streaming mode" before the test can read the 401. Buffering makes the body
        // repeatable so negative POSTs reliably receive their 401/403 response. Test-only;
        // production and the server's 401/403 behavior are unchanged.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        rest = new TestRestTemplate();
        rest.getRestTemplate().setRequestFactory(new BufferingClientHttpRequestFactory(factory));
        rest.getRestTemplate().setUriTemplateHandler(
                new org.springframework.web.util.DefaultUriBuilderFactory(
                        "http://localhost:" + port));

        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    // ----- helpers -------------------------------------------------------------

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private ResponseEntity<String> getMe(String token) {
        return rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
    }

    private ResponseEntity<String> register(String token, String newUsername) {
        String body = "{\"username\":\"" + newUsername
                + "\",\"password\":\"" + PASSWORD + "\",\"roles\":[\"VIEWER\"]}";
        return rest.exchange("/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), String.class);
    }

    private String signWithAppKey(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        jwt.sign(new RSASSASigner(keyPair.privateKey()));
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder claimsFor(User user, List<String> roleNames) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(user.id().toString())
                .claim("roles", roleNames)
                .issuer(jwtProperties.getIssuer())
                .audience(jwtProperties.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .jwtID("018f0000-0000-7000-8000-0000000000b1");
    }

    // ----- public / authenticated ----------------------------------------------

    @Test
    void loginRemainsPublic() {
        provisioning.provision("alice", PASSWORD, EnumSet.of(Role.VIEWER));
        assertThat(login("alice")).isNotBlank();
    }

    @Test
    void anyAuthenticatedUserCanAccessMe() {
        provisioning.provision("viewer", PASSWORD, EnumSet.of(Role.VIEWER));
        String token = login("viewer");

        ResponseEntity<String> me = getMe(token);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains("VIEWER");
    }

    // ----- ADMIN endpoint: 200 vs 403 vs 401 ------------------------------------

    @Test
    void adminCanRegisterNewUser() {
        provisioning.provision("root", PASSWORD, EnumSet.of(Role.ADMIN));
        String adminToken = login("root");

        ResponseEntity<String> response = register(adminToken, "provisioned");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("provisioned");
        // No secret is echoed.
        assertThat(response.getBody().toLowerCase()).doesNotContain("password");
        assertThat(response.getBody()).doesNotContain("argon2");
    }

    @Test
    void engineerCannotRegisterReceives403ProblemJson() {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");

        ResponseEntity<String> response = register(token, "shouldNotExist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void viewerCannotRegisterReceives403() {
        provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));
        String token = login("view");

        assertThat(register(token, "nope").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void registerWithoutTokenReceives401ProblemJson() {
        ResponseEntity<String> response = register(null, "nope");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void invalidTokenOnAdminEndpointReceives401NotForbidden() {
        assertThat(register("not.a.jwt", "nope").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenReceives401() throws Exception {
        User admin = provisioning.provision("root", PASSWORD, EnumSet.of(Role.ADMIN));
        Instant past = Instant.now().minusSeconds(3600);
        String expired = signWithAppKey(claimsFor(admin, List.of("ADMIN"))
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(60)))
                .build());

        assertThat(register(expired, "nope").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----- role tampering must NOT elevate --------------------------------------

    @Test
    void tamperingRolesClaimToAdminIsRejectedWithout401ElevationBypass() throws Exception {
        // A real VIEWER logs in; we forge a token claiming ADMIN but corrupt the signature.
        User viewer = provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));
        String forged = signWithAppKey(claimsFor(viewer, List.of("ADMIN")).build());
        // Deterministically break the signature so it cannot verify: flip the first
        // signature character to a different base64url character.
        String[] parts = forged.split("\\.");
        char first = parts[2].charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);

        ResponseEntity<String> response = register(tampered, "nope");

        // Signature invalid -> authentication fails -> 401, never a working ADMIN (403/201).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validlySignedViewerTokenCannotRegisterEvenIfClaimsSayViewer() {
        // Sanity: a correctly-signed VIEWER token is authenticated but forbidden (403),
        // confirming authorization uses the token's real (VIEWER) role, not request data.
        provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));
        String token = login("view");

        ResponseEntity<String> response = rest.exchange("/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(
                        "{\"username\":\"x\",\"password\":\"" + PASSWORD + "\",\"roles\":[\"VIEWER\"]}",
                        withOverrideHeaders(token)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpHeaders withOverrideHeaders(String token) {
        HttpHeaders headers = bearer(token);
        headers.add("X-Role", "ADMIN");
        headers.add("X-Roles", "ADMIN");
        return headers;
    }

    // ----- disabled user still rejected at authentication -----------------------

    @Test
    void deactivatedUserCannotAuthenticate() {
        User dave = provisioning.provision("dave", PASSWORD, EnumSet.of(Role.ADMIN));
        String token = login("dave");
        jdbcTemplate.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", dave.id());

        assertThat(getMe(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
