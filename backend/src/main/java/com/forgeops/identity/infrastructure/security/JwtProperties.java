package com.forgeops.identity.infrastructure.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT access-token configuration (SECURITY_DESIGN.md §7, ADR-0032).
 *
 * <p>Bound from {@code forgeops.security.jwt.*}. RSA key material is supplied from the
 * environment/secret store as PEM text and is <strong>never committed</strong>. The private
 * key signs tokens (RS256); the public key is retained for later verification slices.
 */
@ConfigurationProperties(prefix = "forgeops.security.jwt")
public class JwtProperties {

    /** Token issuer claim ({@code iss}). */
    private String issuer = "forgeops";

    /** Token audience claim ({@code aud}). */
    private String audience = "forgeops-api";

    /** Access-token lifetime; ~15 minutes per SECURITY_DESIGN.md §7. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** PEM-encoded RSA private key (PKCS#8). From environment/secret store; never committed. */
    private String privateKey;

    /** PEM-encoded RSA public key (X.509 SubjectPublicKeyInfo). From environment; never committed. */
    private String publicKey;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }
}
