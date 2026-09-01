package com.forgeops.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.identity.application.IssuedAccessToken;
import com.forgeops.identity.domain.AccountStatus;
import com.forgeops.identity.domain.PasswordHash;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the RS256 access-token issuer. Generates a throwaway RSA keypair in-test
 * (never a production key) and cryptographically verifies the issued token with the
 * corresponding public key. Synthetic data only.
 */
class NimbusRs256AccessTokenIssuerTests {

    private RSAPublicKey publicKey;
    private NimbusRs256AccessTokenIssuer issuer;
    private final Instant fixedNow = Instant.parse("2026-01-02T03:04:05Z");

    private final User user = new User(
            UUID.fromString("018f0000-0000-7000-8000-0000000000aa"),
            "alice",
            PasswordHash.ofEncoded("$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$c29tZWhhc2h2YWx1ZQ"),
            AccountStatus.ACTIVE,
            EnumSet.of(Role.ENGINEER, Role.INCIDENT_MANAGER),
            fixedNow);

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();

        JwtProperties props = new JwtProperties();
        props.setIssuer("forgeops-test");
        props.setAudience("forgeops-api");
        props.setAccessTokenTtl(Duration.ofMinutes(15));

        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        // Deterministic jti sequence for uniqueness assertions.
        var ids = List.of(
                UUID.fromString("018f0000-0000-7000-8000-0000000000b1"),
                UUID.fromString("018f0000-0000-7000-8000-0000000000b2"));
        var counter = new int[]{0};
        issuer = new NimbusRs256AccessTokenIssuer(
                props,
                new JwtKeyConfiguration.RsaKeyPair((RSAPrivateKey) keyPair.getPrivate(), publicKey),
                clock,
                () -> ids.get(counter[0]++ % ids.size()));
    }

    @Test
    void issuesRs256TokenVerifiableWithPublicKey() throws Exception {
        IssuedAccessToken issued = issuer.issueFor(user);
        SignedJWT jwt = SignedJWT.parse(issued.token());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
    }

    @Test
    void containsRequiredClaimsDerivedFromUserAndConfig() throws Exception {
        IssuedAccessToken issued = issuer.issueFor(user);
        SignedJWT jwt = SignedJWT.parse(issued.token());
        var claims = jwt.getJWTClaimsSet();

        assertThat(claims.getSubject()).isEqualTo(user.id().toString());
        assertThat(claims.getStringListClaim("roles"))
                .containsExactlyInAnyOrder("ENGINEER", "INCIDENT_MANAGER");
        assertThat(claims.getIssuer()).isEqualTo("forgeops-test");
        assertThat(claims.getAudience()).containsExactly("forgeops-api");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(fixedNow);
        assertThat(claims.getExpirationTime().toInstant())
                .isEqualTo(fixedNow.plus(Duration.ofMinutes(15)));
        assertThat(claims.getJWTID()).isNotBlank();
        // exp = iat + configured ttl
        assertThat(issued.expiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void doesNotIncludePasswordOrHash() throws Exception {
        IssuedAccessToken issued = issuer.issueFor(user);
        SignedJWT jwt = SignedJWT.parse(issued.token());
        String claimsJson = jwt.getJWTClaimsSet().toString();

        assertThat(claimsJson).doesNotContain("argon2id");
        assertThat(claimsJson.toLowerCase()).doesNotContain("password");
    }

    @Test
    void issuesUniqueJtiPerToken() throws Exception {
        String jti1 = SignedJWT.parse(issuer.issueFor(user).token()).getJWTClaimsSet().getJWTID();
        String jti2 = SignedJWT.parse(issuer.issueFor(user).token()).getJWTClaimsSet().getJWTID();

        assertThat(jti1).isNotEqualTo(jti2);
    }
}
