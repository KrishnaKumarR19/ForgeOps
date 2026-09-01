package com.forgeops.identity.infrastructure.security;

import com.forgeops.identity.application.AccessTokenValidator;
import com.forgeops.identity.application.InvalidAccessTokenException;
import com.forgeops.identity.application.ValidatedAccessToken;
import com.forgeops.identity.domain.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates ForgeOps RS256 access tokens using Nimbus JOSE + JWT and the configured RSA
 * <strong>public</strong> key (Phase 4.2 Slice 4, SECURITY_DESIGN.md §7/§12, ADR-0032).
 * Infrastructure adapter for the {@link AccessTokenValidator} application port — the JWT
 * library types never cross into the application/domain layers.
 *
 * <p>Security posture:
 * <ul>
 *   <li>The algorithm is explicitly constrained to {@code RS256}. Tokens whose header
 *       declares anything else — {@code none}, HS256, or any other algorithm — are rejected
 *       <em>before</em> signature verification, defeating algorithm-substitution attacks.</li>
 *   <li>The signature is verified with the public key only; the private signing key is never
 *       loaded into this validation path.</li>
 *   <li>Issuer, audience and expiration are checked against configuration; {@code sub},
 *       {@code roles} and {@code jti} are required and must be well-formed.</li>
 * </ul>
 *
 * <p>All failures surface as {@link InvalidAccessTokenException} with a generic message.
 * Token contents, claim values, signatures and key material are never logged or placed in
 * exception messages (SECURITY_DESIGN.md §17).
 */
@Component
class NimbusRs256AccessTokenValidator implements AccessTokenValidator {

    private final JwtProperties properties;
    private final JWSVerifier verifier;
    private final Clock clock;

    NimbusRs256AccessTokenValidator(JwtProperties properties,
                                    JwtKeyConfiguration.RsaKeyPair keyPair,
                                    Clock clock) {
        this.properties = properties;
        this.verifier = new RSASSAVerifier(keyPair.publicKey());
        this.clock = clock;
    }

    @Override
    public ValidatedAccessToken validate(String encodedToken) {
        if (encodedToken == null || encodedToken.isBlank()) {
            throw new InvalidAccessTokenException("Token is missing");
        }

        SignedJWT jwt = parse(encodedToken);
        requireRs256(jwt);
        verifySignature(jwt);

        JWTClaimsSet claims = claims(jwt);
        verifyIssuer(claims);
        verifyAudience(claims);
        verifyNotExpired(claims);
        verifyNotIssuedInFuture(claims);
        requireJwtId(claims);

        UUID userId = requireSubject(claims);
        Set<Role> roles = requireRoles(claims);
        return new ValidatedAccessToken(userId, roles, claims.getJWTID());
    }

    private static SignedJWT parse(String encodedToken) {
        try {
            return SignedJWT.parse(encodedToken);
        } catch (ParseException e) {
            // Malformed / not a JWS. Do not echo the token.
            throw new InvalidAccessTokenException("Malformed token");
        }
    }

    /**
     * Rejects any token whose declared algorithm is not exactly RS256. This runs before
     * verification, so {@code alg=none} and HS256 tokens never reach the RSA verifier.
     */
    private static void requireRs256(SignedJWT jwt) {
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidAccessTokenException("Unsupported token algorithm");
        }
    }

    private void verifySignature(SignedJWT jwt) {
        try {
            if (!jwt.verify(verifier)) {
                throw new InvalidAccessTokenException("Invalid token signature");
            }
        } catch (JOSEException e) {
            throw new InvalidAccessTokenException("Invalid token signature");
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidAccessTokenException("Malformed token claims");
        }
    }

    private void verifyIssuer(JWTClaimsSet claims) {
        if (!properties.getIssuer().equals(claims.getIssuer())) {
            throw new InvalidAccessTokenException("Invalid token issuer");
        }
    }

    private void verifyAudience(JWTClaimsSet claims) {
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(properties.getAudience())) {
            throw new InvalidAccessTokenException("Invalid token audience");
        }
    }

    private void verifyNotExpired(JWTClaimsSet claims) {
        Date expiration = claims.getExpirationTime();
        if (expiration == null) {
            throw new InvalidAccessTokenException("Token expiration is missing");
        }
        if (!expiration.toInstant().isAfter(clock.instant())) {
            throw new InvalidAccessTokenException("Token is expired");
        }
    }

    /**
     * Rejects tokens issued in the future beyond a small tolerance for clock skew. {@code
     * iat} is required by the ForgeOps token contract, so its absence is also a failure.
     */
    private void verifyNotIssuedInFuture(JWTClaimsSet claims) {
        Date issuedAt = claims.getIssueTime();
        if (issuedAt == null) {
            throw new InvalidAccessTokenException("Token issued-at is missing");
        }
        // 60s tolerance for small clock skew between issuer and validator.
        if (issuedAt.toInstant().isAfter(clock.instant().plusSeconds(60))) {
            throw new InvalidAccessTokenException("Token issued in the future");
        }
    }

    private static void requireJwtId(JWTClaimsSet claims) {
        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank()) {
            throw new InvalidAccessTokenException("Token id is missing");
        }
    }

    private static UUID requireSubject(JWTClaimsSet claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidAccessTokenException("Token subject is missing");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new InvalidAccessTokenException("Token subject is malformed");
        }
    }

    private static Set<Role> requireRoles(JWTClaimsSet claims) {
        List<String> roleNames;
        try {
            roleNames = claims.getStringListClaim("roles");
        } catch (ParseException e) {
            throw new InvalidAccessTokenException("Token roles are malformed");
        }
        if (roleNames == null) {
            throw new InvalidAccessTokenException("Token roles are missing");
        }
        Set<Role> roles = new LinkedHashSet<>();
        for (String name : roleNames) {
            try {
                roles.add(Role.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new InvalidAccessTokenException("Token roles are malformed");
            }
        }
        return roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }
}
