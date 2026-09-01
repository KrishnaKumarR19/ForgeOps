/**
 * incidents module — authoritative domain.
 *
 * <p>Owns incidents and their lifecycle state machine, severity, assignments,
 * comments/investigation notes, resolution, and deterministic event correlation
 * (DOMAIN_MODEL.md §1.1, PRD FR-IN). No business behavior is implemented in Phase 2;
 * this package establishes the module boundary only.
 */
package com.forgeops.incidents;
