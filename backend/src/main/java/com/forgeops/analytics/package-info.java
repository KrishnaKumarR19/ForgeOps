/**
 * analytics module — supporting capability (non-authoritative).
 *
 * <p>Provides read-oriented aggregates and operational visibility derived from
 * authoritative state (DOMAIN_MODEL.md §1.2, PRD FR-OB). It never becomes a second source
 * of truth. No business behavior is implemented in Phase 2; this package establishes the
 * module boundary only.
 */
package com.forgeops.analytics;
