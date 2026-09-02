package com.forgeops.incidents.application;

/**
 * The caller is authenticated and generally allowed to assign, but not allowed to perform this
 * specific assignment — e.g. an ENGINEER attempting to assign someone other than themselves
 * (API_CONTRACTS.md §5/§12: ENGINEER may self-assign only). Mapped by the API layer to
 * {@code 403 Forbidden}. This is a content-dependent authorization rule (it depends on the
 * request body), so it is enforced in the application layer rather than by the URL security
 * rules alone (INV-SEC-005).
 */
public class ForbiddenAssignmentException extends RuntimeException {

    public ForbiddenAssignmentException(String message) {
        super(message);
    }
}
