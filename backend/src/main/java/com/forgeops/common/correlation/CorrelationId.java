package com.forgeops.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A correlation (request) identifier used purely for diagnostics and tracing.
 *
 * <p>Per API_CONTRACTS.md §21 this is <strong>diagnostic metadata only</strong>. It is
 * NOT user identity, authorization data, an idempotency key, or any business identity,
 * and it must never be treated as trusted for those purposes.
 *
 * <p>This is a small value type in {@code common} because correlation genuinely crosses
 * every module boundary (HTTP, application, and — later — asynchronous messages).
 */
public final class CorrelationId {

    /** Bounded, safe character set to prevent header abuse (client-supplied values). */
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    private final String value;

    private CorrelationId(String value) {
        this.value = value;
    }

    /** Generates a fresh correlation id. */
    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    /**
     * Accepts a client-supplied value if it is within the safe character set; otherwise
     * returns a freshly generated id. Never throws — an invalid client header simply
     * yields a generated id (diagnostics must not fail a request).
     */
    public static CorrelationId fromClientValueOrGenerate(String candidate) {
        if (candidate != null && SAFE.matcher(candidate).matches()) {
            return new CorrelationId(candidate);
        }
        return generate();
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
