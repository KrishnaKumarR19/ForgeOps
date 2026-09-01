/**
 * audit module — authoritative domain.
 *
 * <p>Owns the append-only audit trail of significant changes (DOMAIN_MODEL.md §1.1,
 * PRD FR-IN-7). Audit entries are append-only and are written atomically with the change
 * they describe. No business behavior is implemented in Phase 2; this package establishes
 * the module boundary only.
 */
package com.forgeops.audit;
