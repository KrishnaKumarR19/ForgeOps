package com.forgeops.events.application;

import com.forgeops.events.domain.OperationalEvent;

/**
 * Result of a successful ingestion. {@code replay} is {@code false} when the event was newly
 * created (Case A) and {@code true} when an existing event was returned for a same-key,
 * same-payload retry (Cases B/D/E). Both outcomes map to a successful {@code 202} at the API;
 * the flag lets the API distinguish the two if needed without changing the representation.
 *
 * @param event  the accepted (or previously accepted) event
 * @param replay whether this was an idempotent replay of an existing event
 */
public record AcceptedEvent(OperationalEvent event, boolean replay) {
}
