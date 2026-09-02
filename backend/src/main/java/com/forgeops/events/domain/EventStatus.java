package com.forgeops.events.domain;

/**
 * Processing state of an accepted operational event (DOMAIN_MODEL.md §8, API_CONTRACTS.md
 * §6/§8). An event is {@code RECEIVED} on acceptance; it becomes {@code PROCESSED} only after
 * asynchronous processing (Phase 6) has run. This slice only ever creates {@code RECEIVED}
 * events — it does not process them.
 */
public enum EventStatus {
    RECEIVED,
    PROCESSED
}
