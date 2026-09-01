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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for the Bearer-JWT filter chain against real PostgreSQL
 * (Testcontainers) over HTTP. Provisions users through the real path, logs in to obtain a
 * genuine RS256 token, then exercises the protected {@code GET /api/v1/auth/me} endpoint and
 * a range of authentication failures.
 *
 * <p>Forged/expired/wrong-issuer tokens are minted with the application's own configured
 * RSA private key (test-only keys from {@code src/test/resources}) so the signature is valid
 * but the claims/contract are not — isolating each failure to the intended check.
 *
 * <p>The shared, non-rolled-back {@code @SpringBootTest} database is truncated before each
 * test so integration classes stay order-independent. Bootstrap admin is disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class JwtAuthenticationIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JwtKeyConfiguration.RsaKeyPair keyPair;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanIdentityTables() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    // ----- helpers -------------------------------------------------------------

    private String login(String username) {
        var body = java.util.Map.of("username", username, "password", PASSWORD);
        ResponseEntity<java.util.Map> response =
                rest.postForEntity("/api/v1/auth/login", body, java.util.Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private ResponseEntity<String> getMe(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    /** Signs a claim set with the application's configured (test) private key. */
    private String signWithAppKey(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        jwt.sign(new RSASSASigner(keyPair.privateKey()));
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder claimsFor(User user) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(user.id().toString())
                .claim("roles", user.roles().stream().map(Role::name).toList())
                .issuer(jwtProperties.getIssuer())
                .audience(jwtProperties.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .jwtID("018f0000-0000-7000-8000-0000000000b1");
    }

    // ----- tests ---------------------------------------------------------------

    @Test
    void loginTokenAuthenticatesProtectedRequestAndReturnsPrincipal() {
        User alice = provisioning.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER, Role.VIEWER));
        String token = login("alice");

        ResponseEntity<String> me = getMe(token);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains(alice.id().toString());
        assertThat(me.getBody()).contains("ENGINEER").contains("VIEWER");
    }

    @Test
    void loginRemainsPublic() {
        provisioning.provision("carol", PASSWORD, EnumSet.of(Role.ADMIN));

        // No Authorization header on login; must still succeed.
        assertThat(login("carol")).isNotBlank();
    }

    @Test
    void missingTokenIsRejectedOnProtectedEndpoint() {
        ResponseEntity<String> me = getMe(null);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void malformedTokenIsRejected() {
        ResponseEntity<String> me = getMe("not.a.jwt");

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenWithInvalidSignatureIsRejected() throws Exception {
        User alice = provisioning.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER));
        String valid = login("alice");
        // Corrupt the signature segment.
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2) + "AB";

        ResponseEntity<String> me = getMe(tampered);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        User alice = provisioning.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER));
        Instant past = Instant.now().minusSeconds(3600);
        String expired = signWithAppKey(claimsFor(alice)
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(60)))
                .build());

        ResponseEntity<String> me = getMe(expired);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongIssuerTokenIsRejected() throws Exception {
        User alice = provisioning.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER));
        String wrongIssuer = signWithAppKey(claimsFor(alice).issuer("evil-issuer").build());

        ResponseEntity<String> me = getMe(wrongIssuer);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongAudienceTokenIsRejected() throws Exception {
        User alice = provisioning.provision("alice", PASSWORD, EnumSet.of(Role.ENGINEER));
        String wrongAudience = signWithAppKey(claimsFor(alice).audience("some-other-api").build());

        ResponseEntity<String> me = getMe(wrongAudience);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenForDeactivatedUserIsRejected() throws Exception {
        User dave = provisioning.provision("dave", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("dave");
        // Deactivate directly in the database; the (still-valid, unexpired) token must fail.
        jdbcTemplate.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", dave.id());

        ResponseEntity<String> me = getMe(token);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenForUnknownUserIsRejected() throws Exception {
        // A well-formed, correctly-signed token whose sub does not exist in the database.
        User ghost = new User(
                java.util.UUID.fromString("018f0000-0000-7000-8000-0000000000ee"),
                "ghost", null, com.forgeops.identity.domain.AccountStatus.ACTIVE,
                EnumSet.of(Role.ENGINEER), Instant.now());
        String token = signWithAppKey(claimsFor(ghost).build());

        ResponseEntity<String> me = getMe(token);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
