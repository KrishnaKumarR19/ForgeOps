package com.forgeops.identity.infrastructure.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Parses the PEM-encoded RSA key material from {@link JwtProperties} into JCA key objects
 * used for RS256 signing (private key) and later verification (public key).
 *
 * <p>Keys come from configuration/environment (never committed). This configuration only
 * runs when JWT keys are provided; when they are absent (e.g. some tests), the beans are
 * not created and login token issuance is simply unavailable.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtKeyConfiguration {

    @Bean
    RsaKeyPair rsaKeyPair(JwtProperties properties) {
        if (properties.getPrivateKey() == null || properties.getPrivateKey().isBlank()
                || properties.getPublicKey() == null || properties.getPublicKey().isBlank()) {
            throw new IllegalStateException(
                    "JWT RSA keys are not configured (forgeops.security.jwt.private-key / public-key). "
                            + "Supply them via the environment/secret store.");
        }
        RSAPrivateKey privateKey = parsePrivateKey(properties.getPrivateKey());
        RSAPublicKey publicKey = parsePublicKey(properties.getPublicKey());
        return new RsaKeyPair(privateKey, publicKey);
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            // Do not include key material in the message.
            throw new IllegalStateException("Failed to parse RSA private key from configuration");
        }
    }

    private static RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RSA public key from configuration");
        }
    }

    private static String stripPem(String pem) {
        return pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    /** Holder for the parsed RSA key pair used by the token issuer. */
    record RsaKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
    }
}
