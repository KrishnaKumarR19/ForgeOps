package com.forgeops.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayloadCanonicalizer} (ADR-0025): canonicalization must be
 * deterministic and hash equality must reflect semantic payload equality, not raw JSON text.
 */
class PayloadCanonicalizerTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadCanonicalizer canonicalizer = new PayloadCanonicalizer();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void keyOrderDoesNotAffectHash() throws Exception {
        String a = canonicalizer.canonicalize(json("{\"b\":2,\"a\":1}"));
        String b = canonicalizer.canonicalize(json("{\"a\":1,\"b\":2}"));

        assertThat(a).isEqualTo(b);
        assertThat(canonicalizer.hash(a)).isEqualTo(canonicalizer.hash(b));
    }

    @Test
    void whitespaceDoesNotAffectHash() throws Exception {
        String a = canonicalizer.canonicalize(json("{\"a\":1,\"b\":2}"));
        String b = canonicalizer.canonicalize(json("{  \"a\" : 1 ,  \"b\" : 2  }"));

        assertThat(canonicalizer.hash(a)).isEqualTo(canonicalizer.hash(b));
    }

    @Test
    void nestedKeyOrderDoesNotAffectHash() throws Exception {
        String a = canonicalizer.canonicalize(json("{\"outer\":{\"y\":1,\"x\":2}}"));
        String b = canonicalizer.canonicalize(json("{\"outer\":{\"x\":2,\"y\":1}}"));

        assertThat(canonicalizer.hash(a)).isEqualTo(canonicalizer.hash(b));
    }

    @Test
    void differentValuesProduceDifferentHash() throws Exception {
        String a = canonicalizer.canonicalize(json("{\"a\":1}"));
        String b = canonicalizer.canonicalize(json("{\"a\":2}"));

        assertThat(canonicalizer.hash(a)).isNotEqualTo(canonicalizer.hash(b));
    }

    @Test
    void hashIsStableSha256HexLength() throws Exception {
        String canonical = canonicalizer.canonicalize(json("{\"a\":1}"));

        assertThat(canonicalizer.hash(canonical)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsNonObjectPayload() throws Exception {
        assertThatThrownBy(() -> canonicalizer.canonicalize(json("[1,2,3]")))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> canonicalizer.canonicalize(null))
                .isInstanceOf(InvalidPayloadException.class);
    }
}
