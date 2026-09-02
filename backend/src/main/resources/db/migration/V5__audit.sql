-- ForgeOps — Phase 7 Slice 2: audit trail persistence.
-- Creates the append-only audit_entries table (PERSISTENCE_MODEL.md §12/§16/§17/§19,
-- DOMAIN_MODEL.md §14, INV-INC-003/007/008, ADR-0018/0024). Every significant incident change
-- (creation, state transition, severity change) writes an audit entry ATOMICALLY with the
-- change (same transaction). Audit entries are insert-only: no UPDATE/DELETE from the domain
-- path.
--
-- actor_id is a NULLABLE FK to users (SYSTEM actions have no user actor; this slice writes only
-- USER actions). resource_id is a POLYMORPHIC SOFT REFERENCE (§17): it may point at different
-- resource kinds (resource_type), so it is intentionally NOT a foreign key — referential
-- soundness is an application responsibility.

CREATE TABLE audit_entries (
    id             UUID        NOT NULL,               -- UUID v7 (ADR-0023), app-generated
    actor_id       UUID        NULL,                   -- acting user (NULL for SYSTEM); FK -> users.id
    actor_type     TEXT        NOT NULL,               -- USER | SYSTEM
    action         TEXT        NOT NULL,               -- e.g. INCIDENT_STATE_CHANGED
    resource_type  TEXT        NOT NULL,               -- e.g. INCIDENT
    resource_id    UUID        NOT NULL,               -- changed resource id (polymorphic soft ref, no FK)
    occurred_at    TIMESTAMPTZ NOT NULL,               -- when the change happened (server clock)
    old_value      JSONB       NULL,                   -- previous state where meaningful (ADR-0024)
    new_value      JSONB       NULL,                   -- new state where meaningful (ADR-0024)
    correlation_id TEXT        NULL,                   -- request/correlation id where useful
    CONSTRAINT pk_audit_entries PRIMARY KEY (id),
    CONSTRAINT fk_audit_entries_actor
        FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT ck_audit_entries_actor_type
        CHECK (actor_type IN ('USER', 'SYSTEM'))
);

-- Index design (PERSISTENCE_MODEL.md §16).
CREATE INDEX ix_audit_entries_resource
    ON audit_entries (resource_type, resource_id, occurred_at);   -- full history of a resource

CREATE INDEX ix_audit_entries_actor
    ON audit_entries (actor_id, occurred_at);                     -- actions by an actor over time
