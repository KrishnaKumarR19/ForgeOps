package com.forgeops.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgeops.identity.application.InvalidAccessTokenException;
import com.forgeops.identity.application.ValidatedAccessToken;
import com.forgeops.identity.domain.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit and attack tests for {@link NimbusRs256AccessTokenValidator}. A throwaway RSA
 * keypair is generated in-test (never a production key) and used as the configured
 * verification key. Each case builds a token with Nimbus and asserts accept/reject
 * behavior. Rejection is asserted only by exception <em>type</em>
 * ({@link InvalidAccessTokenException}) — never by message text.
 */
class NimbusRs256AccessTokenValidatorTests {

    private static final String ISSUER = "forgeops-test";
    private static final String AUDIENCE = "forgeops-api";
    private static final UUID SUBJECT = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private final Instant fixedNow = Instant.parse("2026-01-02T03:04:05Z");

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private NimbusRs256AccessTokenValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();

        JwtProperties props = new JwtProperties();
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);

        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        validator = new NimbusRs256AccessTokenValidator(
                props,
                new JwtKeyConfiguration.RsaKeyPair(privateKey, publicKey),
                clock);
    }

    // ----- helpers -------------------------------------------------------------

    /** A well-formed claim set for the configured contract. */
    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .subject(SUBJECT.toString())
                .claim("roles", List.of("ENGINEER", "INCIDENT_MANAGER"))
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(fixedNow.minusSeconds(10)))
                .expirationTime(Date.from(fixedNow.plusSeconds(900)))
                .jwtID("018f0000-0000-7000-8000-0000000000b1");
    }

    /** Signs a claim set as RS256 with the given private key. */
    private static String signRs256(RSAPrivateKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private String validToken() throws JOSEException {
        return signRs256(privateKey, validClaims().build());
    }

    // ----- happy path ----------------------------------------------------------

    @Test
    void acceptsValidRs256TokenAndExtractsClaims() throws Exception {
        ValidatedAccessToken result = validator.validate(validToken());

        assertThat(result.userId()).isEqualTo(SUBJECT);
        assertThat(result.roles()).containsExactlyInAnyOrder(Role.ENGINEER, Role.INCIDENT_MANAGER);
        assertThat(result.jwtId()).isEqualTo("018f0000-0000-7000-8000-0000000000b1");
    }

    // ----- signature / algorithm attacks ---------------------------------------

    @Test
    void rejectsTokenSignedWithDifferentRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey attackerKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();

        String forged = signRs256(attackerKey, validClaims().build());

        assertThatThrownBy(() -> validator.validate(forged))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsHs256Token() throws Exception {
        // Algorithm-confusion: sign an HMAC token whose secret is the RSA public key bytes.
        byte[] secret = new byte[64];
        System.arraycopy(publicKey.getEncoded(), 0,
                secret, 0, Math.min(publicKey.getEncoded().length, secret.length));
        SignedJWT hs = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), validClaims().build());
        hs.sign(new MACSigner(secret));

        assertThatThrownBy(() -> validator.validate(hs.serialize()))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsUnsignedAlgNoneToken() {
        PlainJWT plain = new PlainJWT(validClaims().build());

        assertThatThrownBy(() -> validator.validate(plain.serialize()))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsTokenWithTamperedPayload() throws Exception {
        String token = validToken();
        // Flip the payload segment for a different, unsigned claim set.
        String[] parts = token.split("\\.");
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                validClaims().subject("018f0000-0000-7000-8000-0000000000ff").build()
                        .toJSONObject().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> validator.validate(tampered))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    // ----- claim validation -----------------------------------------------------

    @Test
    void rejectsMissingSubject() throws Exception {
        JWTClaimsSet claims = validClaims().subject(null).build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsNonUuidSubject() throws Exception {
        JWTClaimsSet claims = validClaims().subject("not-a-uuid").build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsMissingRoles() throws Exception {
        JWTClaimsSet claims = validClaims().claim("roles", null).build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsUnknownRoleValue() throws Exception {
        JWTClaimsSet claims = validClaims().claim("roles", List.of("SUPERADMIN")).build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        JWTClaimsSet claims = validClaims().issuer("evil-issuer").build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        JWTClaimsSet claims = validClaims().audience("some-other-api").build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        JWTClaimsSet claims = validClaims()
                .issueTime(Date.from(fixedNow.minusSeconds(2000)))
                .expirationTime(Date.from(fixedNow.minusSeconds(1)))
                .build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsMissingExpiration() throws Exception {
        JWTClaimsSet claims = validClaims().expirationTime(null).build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsMissingJwtId() throws Exception {
        JWTClaimsSet claims = validClaims().jwtID(null).build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsTokenIssuedInFutureBeyondSkew() throws Exception {
        JWTClaimsSet claims = validClaims()
                .issueTime(Date.from(fixedNow.plusSeconds(3600)))
                .expirationTime(Date.from(fixedNow.plusSeconds(7200)))
                .build();
        String token = signRs256(privateKey, claims);

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    // ----- structural / input --------------------------------------------------

    @Test
    void rejectsMalformedJwt() {
        assertThatThrownBy(() -> validator.validate("not.a.jwt"))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsNullOrBlankToken() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> validator.validate("   "))
                .isInstanceOf(InvalidAccessTokenException.class);
    }
}
