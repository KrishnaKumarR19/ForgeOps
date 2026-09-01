-- ForgeOps — Phase 4.1 identity persistence foundation.
-- Creates ONLY the identity tables (users, user_roles). No other business tables.
-- Aligns with PERSISTENCE_MODEL.md §3 and SECURITY_DESIGN.md.

-- Users: authoritative principal records.
CREATE TABLE users (
    id            UUID        NOT NULL,                 -- UUID v7 (ADR-0023), app-generated
    username      TEXT        NOT NULL,                 -- unique login identifier
    password_hash TEXT        NULL,                     -- encoded Argon2id hash (Phase 4.2); never plaintext
    status        TEXT        NOT NULL,                 -- AccountStatus: ACTIVE | DEACTIVATED
    created_at    TIMESTAMPTZ NOT NULL,                 -- timezone-aware creation time
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DEACTIVATED'))
);

-- User roles: many roles per user; each (user, role) pair at most once.
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role    TEXT NOT NULL,                              -- Role: ADMIN | ENGINEER | INCIDENT_MANAGER | VIEWER
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('ADMIN', 'ENGINEER', 'INCIDENT_MANAGER', 'VIEWER'))
);
