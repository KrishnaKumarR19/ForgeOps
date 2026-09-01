/**
 * ai module — supporting capability (optional, non-authoritative).
 *
 * <p>Provides optional, evidence-grounded, advisory incident-investigation assistance
 * (DOMAIN_MODEL.md §1.2, §17, PRD FR-AI). It never owns or mutates authoritative state
 * and the core platform is fully correct without it. No business behavior is implemented
 * in Phase 2; this package establishes the module boundary only.
 */
package com.forgeops.ai;
