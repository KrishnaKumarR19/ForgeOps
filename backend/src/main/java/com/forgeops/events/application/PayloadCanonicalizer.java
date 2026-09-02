package com.forgeops.events.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Canonicalizes an event payload and computes a deterministic hash of it (ADR-0025). "Same
 * payload" is defined by this hash — stable key ordering and formatting — not by raw JSON
 * string comparison, so two semantically-identical submissions that differ only in key order
 * or whitespace produce the same hash (and thus replay), while any semantic difference
 * produces a different hash (and thus a conflict).
 *
 * <p>Canonical form: the payload is parsed to a JSON tree and re-serialized with object keys
 * sorted recursively (Jackson {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}) and no
 * insignificant whitespace. The hash is SHA-256 of the UTF-8 canonical text, hex-encoded.
 * The canonical text is what is persisted in the {@code payload} column, so retrieval returns
 * a stable representation.
 */
@Component
public class PayloadCanonicalizer {

    private final ObjectMapper canonicalMapper;

    public PayloadCanonicalizer() {
        // ORDER_MAP_ENTRIES_BY_KEYS sorts java.util.Map; JsonNodeFeature.WRITE_PROPERTIES_SORTED
        // additionally sorts ObjectNode fields recursively when serializing a parsed JsonNode.
        // Both are needed so a parsed payload tree canonicalizes with stable key ordering.
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.canonicalMapper.configure(JsonNodeFeature.WRITE_PROPERTIES_SORTED, true);
    }

    /**
     * Produces the canonical JSON text for a parsed payload tree.
     *
     * @throws InvalidPayloadException if the tree cannot be serialized
     */
    public String canonicalize(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new InvalidPayloadException("payload is required");
        }
        if (!payload.isObject()) {
            throw new InvalidPayloadException("payload must be a JSON object");
        }
        try {
            return canonicalMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new InvalidPayloadException("payload is not serializable JSON");
        }
    }

    /** SHA-256 (hex) of the canonical JSON text. */
    public String hash(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
