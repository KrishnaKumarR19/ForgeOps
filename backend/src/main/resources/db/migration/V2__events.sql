-- ForgeOps — Phase 5 Slice 1: event ingestion persistence.
-- Creates the operational_events table plus the services/environments reference tables it
-- references (PERSISTENCE_MODEL.md §4/§5/§6/§16/§17; DOMAIN_MODEL.md §1.1 — Service/Environment
-- reference data is owned by the events module). No outbox table (Phase 6), no incidents
-- table yet: incident_id is a nullable column whose FK to incidents(id) is added with the
-- incidents table (kept nullable/uncorrelated now).
--
-- Service and Environment are a small CONTROLLED SET managed as reference data
-- (PERSISTENCE_MODEL.md §4). There is no management API/UI in this slice; the controlled set
-- is provisioned via this migration (Flyway, ADR-0034) — the approved reference-data
-- provisioning mechanism, not a new product capability.

CREATE TABLE services (
    id           UUID NOT NULL,                            -- UUID v7 (ADR-0023)
    key          TEXT NOT NULL,                            -- stable unique service key
    display_name TEXT NOT NULL,
    CONSTRAINT pk_services PRIMARY KEY (id),
    CONSTRAINT uq_services_key UNIQUE (key)
);

CREATE TABLE environments (
    id   UUID NOT NULL,                                    -- UUID v7 (ADR-0023)
    key  TEXT NOT NULL,                                    -- e.g. production, staging
    name TEXT NOT NULL,
    CONSTRAINT pk_environments PRIMARY KEY (id),
    CONSTRAINT uq_environments_key UNIQUE (key)
);

-- Seed the initial controlled set of reference data. Fixed UUID v7 values so the rows are
-- stable across environments. Managing (adding/removing) services/environments is a separate
-- ADMIN capability (API_CONTRACTS.md §5) deferred to a later slice; this seed provides the
-- known references event ingestion validates against now.
INSERT INTO services (id, key, display_name) VALUES
    ('018f1000-0000-7000-8000-000000000001', 'checkout',    'Checkout Service'),
    ('018f1000-0000-7000-8000-000000000002', 'payments',    'Payments Service'),
    ('018f1000-0000-7000-8000-000000000003', 'inventory',   'Inventory Service'),
    ('018f1000-0000-7000-8000-000000000004', 'notifications','Notifications Service');

INSERT INTO environments (id, key, name) VALUES
    ('018f1001-0000-7000-8000-000000000001', 'production', 'Production'),
    ('018f1001-0000-7000-8000-000000000002', 'staging',    'Staging'),
    ('018f1001-0000-7000-8000-000000000003', 'development','Development');

CREATE TABLE operational_events (
    id                UUID        NOT NULL,               -- UUID v7 (ADR-0023), server-generated resource identity
    client_id         UUID        NOT NULL,               -- authenticated submitting principal (JWT sub); scopes idempotency
    producer_event_id TEXT        NULL,                   -- source system's own id (optional, traceability)
    idempotency_key   TEXT        NULL,                   -- request idempotency token (optional; required for reliable retry)
    service_id        UUID        NOT NULL,               -- emitting service (FK -> services.id)
    environment_id    UUID        NOT NULL,               -- scoping environment (FK -> environments.id)
    event_type        TEXT        NOT NULL,               -- producer-supplied event type
    severity          TEXT        NULL,                   -- severity hint (optional)
    failure_signature TEXT        NULL,                   -- normalized failure signature (optional; correlation input)
    occurred_at       TIMESTAMPTZ NOT NULL,               -- when the event happened (producer clock)
    received_at       TIMESTAMPTZ NOT NULL,               -- when ForgeOps accepted it (server clock)
    payload           JSONB       NOT NULL,               -- structured content (ADR-0024)
    payload_hash      TEXT        NOT NULL,               -- deterministic hash of canonicalized payload (ADR-0025)
    status            TEXT        NOT NULL,               -- processing state: RECEIVED | PROCESSED
    incident_id       UUID        NULL,                   -- 0..1 owning incident (NULL = uncorrelated); FK added with incidents table
    CONSTRAINT pk_operational_events PRIMARY KEY (id),
    -- Idempotency is scoped to the authenticated client (ADR-0025): the same key from two
    -- different clients never collides. This DB constraint is authoritative for duplicate
    -- detection; the application distinguishes replay from conflict via payload_hash.
    CONSTRAINT uq_operational_events_client_idempotency UNIQUE (client_id, idempotency_key),
    CONSTRAINT fk_operational_events_service
        FOREIGN KEY (service_id) REFERENCES services (id),
    CONSTRAINT fk_operational_events_environment
        FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT ck_operational_events_severity
        CHECK (severity IS NULL OR severity IN ('INFO', 'WARNING', 'MINOR', 'MAJOR', 'CRITICAL')),
    CONSTRAINT ck_operational_events_status
        CHECK (status IN ('RECEIVED', 'PROCESSED'))
);

-- Query/index support (PERSISTENCE_MODEL.md §16). Retrieval/filtering by service/environment
-- over time, and lookup of an incident's events.
CREATE INDEX ix_operational_events_service_env_received
    ON operational_events (service_id, environment_id, received_at);

CREATE INDEX ix_operational_events_incident
    ON operational_events (incident_id);
