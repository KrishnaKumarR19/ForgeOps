package com.forgeops.identity.domain;

/**
 * Explicit account lifecycle status (SECURITY_DESIGN.md §2). Modeled as an enum rather
 * than a boolean flag so the meaning is explicit and extensible. Only the two states
 * required now are defined; account-management workflows are not implemented in this slice.
 */
public enum AccountStatus {
    ACTIVE,
    DEACTIVATED
}
