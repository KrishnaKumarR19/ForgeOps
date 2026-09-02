-- ForgeOps — Phase 7 Slice 4: event-driven detection concurrency safeguard.
-- Enforces AT MOST ONE active incident per correlation key so two concurrent events that both
-- find no matching incident cannot create duplicate active incidents (INV-INC-005 spirit,
-- ADR-0017 deterministic detection). PostgreSQL is authoritative: the losing INSERT gets a
-- unique-violation and retries, then attaches to the winner. The correlation key is
-- (service_id, environment_id, failure_signature); only ACTIVE-state rows participate, so a new
-- incident can be created once a prior one is RESOLVED/CLOSED. Rows with a NULL
-- failure_signature (e.g. manual incidents) do not collide (NULLs are distinct in a unique
-- index), which is intended — detection always sets a normalized non-null signature.
--
-- No table/column change: incidents.failure_signature (TEXT) already holds the bounded (<=200
-- char) normalized detection signature. V1-V6 unchanged.

CREATE UNIQUE INDEX uq_incidents_active_correlation
    ON incidents (service_id, environment_id, failure_signature)
    WHERE state IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATED');
