package com.forgeops.incidents.domain;

/**
 * Signals that a requested lifecycle transition (or severity change) is not permitted from the
 * incident's current state (INV-INC-002, DOMAIN_MODEL.md §10). The state machine is
 * authoritative: any transition not explicitly defined is rejected.
 *
 * <p>This is a framework-free domain exception (ADR-0030) — it carries the current and
 * requested states/commands as data and exposes no HTTP status. The API layer maps it to
 * {@code 409 Conflict} (the request is well-formed but conflicts with resource state —
 * ADR-0027).
 */
public class IllegalIncidentTransitionException extends RuntimeException {

    private final IncidentState currentState;
    private final String command;

    public IllegalIncidentTransitionException(IncidentState currentState, String command) {
        super("Cannot " + command + " an incident in state " + currentState);
        this.currentState = currentState;
        this.command = command;
    }

    public IncidentState currentState() {
        return currentState;
    }

    public String command() {
        return command;
    }
}
