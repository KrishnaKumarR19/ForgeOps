-- ForgeOps — Phase 6 Slice 1: transactional outbox persistence.
-- Creates the outbox_messages table (PERSISTENCE_MODEL.md §13/§16, ADR-0013/0019/0022/0023/0024).
-- The outbox record is written in the SAME transaction as its operational event
-- (INV-OUTBOX-001, INV-EVENT-006). This slice persists outbox rows only; the publisher
-- (polling, FOR UPDATE SKIP LOCKED claiming, PUBLISHED transitions, retry/backoff) is a
-- later Phase 6 slice. The publisher/retry columns (attempts, published_at, next_attempt_at,
-- last_error) exist now so that later slice needs no schema change.
--
-- aggregate_id is a GENERIC UUID reference (aggregate_type names the resource kind); the
-- authoritative persistence model does not mandate a foreign key to operational_events(id),
-- so none is added. Event<->outbox pairing is guaranteed by the atomic write transaction.

CREATE TABLE outbox_messages (
    id              UUID        NOT NULL,               -- UUID v7 (ADR-0023), server-generated
    message_type    TEXT        NOT NULL,               -- routing/type of the message
    aggregate_type  TEXT        NOT NULL,               -- resource kind, e.g. OPERATIONAL_EVENT
    aggregate_id    UUID        NOT NULL,               -- source resource id (e.g. the event id)
    payload         JSONB       NOT NULL,               -- message body to publish (ADR-0024)
    status          TEXT        NOT NULL,               -- PENDING | PUBLISHED (ADR-0019)
    attempts        INTEGER     NOT NULL,               -- retry counter (0 on creation)
    created_at      TIMESTAMPTZ NOT NULL,               -- creation = event acceptance time
    published_at    TIMESTAMPTZ NULL,                   -- set on successful publication (publisher)
    next_attempt_at TIMESTAMPTZ NULL,                   -- earliest next try / backoff (publisher)
    last_error      TEXT        NULL,                   -- last failure detail (publisher diagnostics)
    CONSTRAINT pk_outbox_messages PRIMARY KEY (id),
    CONSTRAINT ck_outbox_messages_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_outbox_messages_attempts CHECK (attempts >= 0)
);

-- Index design (PERSISTENCE_MODEL.md §16). The hot "claim due pending rows" query leads with
-- the most selective equality column (status) before time (used by the later publisher).
CREATE INDEX ix_outbox_messages_pending
    ON outbox_messages (status, next_attempt_at)
    WHERE status = 'PENDING';

-- Retention cleanup of published rows (later publisher/cleanup slice).
CREATE INDEX ix_outbox_messages_published
    ON outbox_messages (published_at)
    WHERE status = 'PUBLISHED';
