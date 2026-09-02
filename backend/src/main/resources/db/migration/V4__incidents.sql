-- ForgeOps — Phase 7 Slice 1: incident persistence foundation.
-- Creates the incidents aggregate-root table (PERSISTENCE_MODEL.md §8/§9/§16/§19,
-- DOMAIN_MODEL.md §2/§10, INV-INC-001/004/005, ADR-0021/0028) and adds the FK from
-- operational_events.incident_id -> incidents(id) that was intentionally deferred from
-- V2__events.sql until this table existed.
--
-- Scope: persistence + aggregate foundation ONLY. The DB enforces that `state` and `severity`
-- are valid values (CHECK) and that `version` is non-negative. The lifecycle TRANSITION rules
-- (which state may move to which) are an application-enforced invariant (INV-INC-002),
-- implemented in a later slice — not here. Incidents are never hard-deleted; closure is a
-- state (PERSISTENCE_MODEL.md §19). No assignments/comments/audit tables in this slice.

CREATE TABLE incidents (
    id                  UUID        NOT NULL,               -- UUID v7 (ADR-0023), app-generated
    title               TEXT        NULL,                   -- human-readable summary (optional)
    service_id          UUID        NOT NULL,               -- service context (FK -> services.id)
    environment_id      UUID        NOT NULL,               -- environment context (FK -> environments.id)
    failure_signature   TEXT        NULL,                   -- correlation signature shared by its events
    severity            TEXT        NOT NULL,               -- always present (INV-INC-004)
    state               TEXT        NOT NULL,               -- lifecycle state (§9)
    current_assignee_id UUID        NULL,                   -- denormalized current owner (FK -> users.id)
    version             BIGINT      NOT NULL,               -- optimistic-lock token (INV-INC-005)
    created_at          TIMESTAMPTZ NOT NULL,               -- creation time
    resolved_at         TIMESTAMPTZ NULL,                   -- set when RESOLVED
    closed_at           TIMESTAMPTZ NULL,                   -- set when CLOSED
    CONSTRAINT pk_incidents PRIMARY KEY (id),
    CONSTRAINT fk_incidents_service
        FOREIGN KEY (service_id) REFERENCES services (id),
    CONSTRAINT fk_incidents_environment
        FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT fk_incidents_assignee
        FOREIGN KEY (current_assignee_id) REFERENCES users (id),
    CONSTRAINT ck_incidents_severity
        CHECK (severity IN ('INFO', 'WARNING', 'MINOR', 'MAJOR', 'CRITICAL')),
    CONSTRAINT ck_incidents_state
        CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_incidents_version_non_negative
        CHECK (version >= 0)
);

-- Index design (PERSISTENCE_MODEL.md §16). Each index maps to an expected access pattern.
CREATE INDEX ix_incidents_state
    ON incidents (state);                                   -- dashboards: open/active incidents

CREATE INDEX ix_incidents_service_env_created
    ON incidents (service_id, environment_id, created_at);  -- incidents by context over time

CREATE INDEX ix_incidents_severity_state
    ON incidents (severity, state);                         -- prioritized active views

CREATE INDEX ix_incidents_current_assignee
    ON incidents (current_assignee_id)
    WHERE current_assignee_id IS NOT NULL;                  -- "my incidents" (assigned only)

-- Deferred FK from V2__events.sql: operational_events.incident_id -> incidents(id). The column
-- already exists (nullable) and remains nullable — an event is uncorrelated (NULL) until later
-- detection assigns it (Option A, ADR-0020). No ON DELETE cascade: incidents are never hard-
-- deleted (§19), and destroying incident history via cascade must never happen.
ALTER TABLE operational_events
    ADD CONSTRAINT fk_operational_events_incident
        FOREIGN KEY (incident_id) REFERENCES incidents (id);
