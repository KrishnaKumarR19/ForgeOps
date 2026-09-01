package com.forgeops.identity.infrastructure.security;

import com.forgeops.common.id.IdGenerator;
import com.forgeops.identity.application.AccessTokenIssuer;
import com.forgeops.identity.application.IssuedAccessToken;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived RS256 access tokens (SECURITY_DESIGN.md §7, ADR-0032) using Nimbus
 * JOSE + JWT. Infrastructure adapter for the {@link AccessTokenIssuer} application port.
 *
 * <p>All claims are derived server-side: {@code sub} from the persisted {@link User} id,
 * {@code roles} from the persisted user roles, {@code iss}/{@code aud} from configuration,
 * {@code iat}/{@code exp} from the injected {@link Clock} and configured TTL, and a unique
 * {@code jti} from the {@link IdGenerator}. No password, hash, or client-supplied value is
 * ever placed in the token.
 */
@Component
class NimbusRs256AccessTokenIssuer implements AccessTokenIssuer {

    private final JwtProperties properties;
    private final JwtKeyConfiguration.RsaKeyPair keyPair;
    private final Clock clock;
    private final IdGenerator idGenerator;

    NimbusRs256AccessTokenIssuer(JwtProperties properties,
                                 JwtKeyConfiguration.RsaKeyPair keyPair,
                                 Clock clock,
                                 IdGenerator idGenerator) {
        this.properties = properties;
        this.keyPair = keyPair;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    @Override
    public IssuedAccessToken issueFor(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
        List<String> roles = user.roles().stream().map(Role::name).toList();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.id().toString())
                .claim("roles", roles)
                .issuer(properties.getIssuer())
                .audience(properties.getAudience())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(idGenerator.newId().toString())
                .build();

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                claims);
        try {
            signedJwt.sign(new RSASSASigner(keyPair.privateKey()));
        } catch (JOSEException e) {
            // Never include key material or claim internals in the message.
            throw new IllegalStateException("Failed to sign access token");
        }

        long expiresInSeconds = properties.getAccessTokenTtl().toSeconds();
        return new IssuedAccessToken(signedJwt.serialize(), expiresInSeconds);
    }
}
