/**
 * events module — authoritative domain.
 *
 * <p>Owns operational events, the transactional outbox record, event idempotency, and
 * Service/Environment reference data (DOMAIN_MODEL.md §1.1, PRD FR-EV). No business
 * behavior is implemented in Phase 2; this package establishes the module boundary only.
 *
 * <p>Boundary rule: the outbox is an internal concern of this module and is never exposed
 * externally.
 */
package com.forgeops.events;
