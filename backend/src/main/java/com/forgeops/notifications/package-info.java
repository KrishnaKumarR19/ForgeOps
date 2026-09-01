/**
 * notifications module — supporting capability (non-authoritative).
 *
 * <p>Delivers best-effort real-time notifications (SSE) derived from authoritative state
 * changes (DOMAIN_MODEL.md §1.2, PRD FR-RT). Notifications are not business state. No
 * business behavior is implemented in Phase 2; this package establishes the module
 * boundary only.
 */
package com.forgeops.notifications;
