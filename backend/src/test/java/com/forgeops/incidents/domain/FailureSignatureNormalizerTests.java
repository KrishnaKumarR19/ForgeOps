package com.forgeops.incidents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FailureSignatureNormalizer} (Phase 7 Slice 4, ratified v1 rules):
 * trim, lowercase (ROOT), collapse whitespace, strip one trailing period, bound to 200 chars,
 * fallback to event type, and deterministic output. No fuzzy/semantic behavior.
 */
class FailureSignatureNormalizerTests {

    @Test
    void trimsLowercasesAndCollapsesWhitespace() {
        assertThat(FailureSignatureNormalizer.normalize("  HTTP   5xx  Error  ", "type"))
                .contains("http 5xx error");
    }

    @Test
    void stripsOneTrailingPeriod() {
        assertThat(FailureSignatureNormalizer.normalize("connection refused.", "type"))
                .contains("connection refused");
    }

    @Test
    void lowercasesUsingRootLocale() {
        assertThat(FailureSignatureNormalizer.normalize("TIMEOUT", "type")).contains("timeout");
    }

    @Test
    void fallsBackToEventTypeWhenSignatureBlank() {
        assertThat(FailureSignatureNormalizer.normalize(null, "Http_5xx")).contains("http_5xx");
        assertThat(FailureSignatureNormalizer.normalize("   ", "Http_5xx")).contains("http_5xx");
    }

    @Test
    void emptyWhenBothBlank() {
        assertThat(FailureSignatureNormalizer.normalize(null, null)).isEmpty();
        assertThat(FailureSignatureNormalizer.normalize("  ", "  ")).isEmpty();
        assertThat(FailureSignatureNormalizer.normalize(".", null)).isEmpty(); // "." -> "" after strip
    }

    @Test
    void boundsToMaxLength() {
        String longSig = "x".repeat(500);
        String result = FailureSignatureNormalizer.normalize(longSig, "type").orElseThrow();
        assertThat(result).hasSize(FailureSignatureNormalizer.MAX_LENGTH);
    }

    @Test
    void isDeterministic() {
        String a = FailureSignatureNormalizer.normalize("  Foo  Bar. ", "t").orElseThrow();
        String b = FailureSignatureNormalizer.normalize("  Foo  Bar. ", "t").orElseThrow();
        assertThat(a).isEqualTo(b).isEqualTo("foo bar");
    }
}
