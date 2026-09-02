package com.forgeops.incidents.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic, rule-based normalization of an event's failure signature for detection
 * grouping (Phase 7 Slice 4, ratified v1 contract; DOMAIN_MODEL.md §6, ADR-0017). No ML, no
 * fuzzy/semantic matching — the same inputs always produce the same bounded output, suitable for
 * PostgreSQL equality/indexing.
 *
 * <p>The source is the event's {@code failureSignature} when non-null/non-blank, otherwise the
 * {@code eventType} fallback. Normalization steps (in order): trim; lowercase using
 * {@link Locale#ROOT}; collapse runs of whitespace to a single ASCII space; remove one trailing
 * period; trim again; bound to {@value #MAX_LENGTH} characters (defends against unbounded
 * user-controlled strings). If both sources are blank the input is invalid detection data
 * (empty result) and the caller treats it as a poison event.
 *
 * <p>Framework-free (ADR-0030).
 */
public final class FailureSignatureNormalizer {

    /** Upper bound on the normalized signature length. */
    public static final int MAX_LENGTH = 200;

    private FailureSignatureNormalizer() {
    }

    /**
     * Produces the normalized signature from the failure signature (preferred) or event type
     * (fallback). Returns empty if neither yields a non-blank value.
     */
    public static Optional<String> normalize(String failureSignature, String eventType) {
        String source = (failureSignature != null && !failureSignature.isBlank())
                ? failureSignature : eventType;
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.length() > MAX_LENGTH) {
            normalized = normalized.substring(0, MAX_LENGTH);
        }
        return Optional.of(normalized);
    }
}
