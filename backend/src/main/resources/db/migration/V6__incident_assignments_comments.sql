-- ForgeOps — Phase 7 Slice 3: incident assignment history + comments.
-- Creates incident_assignments (append-only assignment history; current pointer stays on
-- incidents.current_assignee_id — ADR-0021, PERSISTENCE_MODEL §10) and incident_comments
-- (append-only investigation notes — PERSISTENCE_MODEL §11, DOMAIN_MODEL §12, INV-INC-008).
-- Both are insert-only from the domain path; there is no edit/delete. V1–V5 unchanged.

CREATE TABLE incident_assignments (
    id            UUID        NOT NULL,                 -- UUID v7 (ADR-0023), app-generated
    incident_id   UUID        NOT NULL,                 -- FK -> incidents.id
    assignee_id   UUID        NOT NULL,                 -- assigned user (FK -> users.id)
    assigned_by   UUID        NOT NULL,                 -- actor performing the assignment (FK -> users.id)
    assigned_at   TIMESTAMPTZ NOT NULL,                 -- when assigned
    unassigned_at TIMESTAMPTZ NULL,                     -- set when superseded/ended (reassignment/unassign)
    team          TEXT        NULL,                     -- optional team ownership (no teams entity)
    CONSTRAINT pk_incident_assignments PRIMARY KEY (id),
    CONSTRAINT fk_incident_assignments_incident
        FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_incident_assignments_assignee
        FOREIGN KEY (assignee_id) REFERENCES users (id),
    CONSTRAINT fk_incident_assignments_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES users (id)
);

-- List/read an incident's assignment history over time (the documented read pattern).
CREATE INDEX ix_incident_assignments_incident
    ON incident_assignments (incident_id, assigned_at);

CREATE TABLE incident_comments (
    id          UUID        NOT NULL,                   -- UUID v7 (ADR-0023), app-generated
    incident_id UUID        NOT NULL,                   -- FK -> incidents.id
    author_id   UUID        NOT NULL,                   -- FK -> users.id
    category    TEXT        NULL,                       -- optional NOTE | INVESTIGATION | RESOLUTION
    body        TEXT        NOT NULL,                   -- content
    created_at  TIMESTAMPTZ NOT NULL,                   -- authorship time
    CONSTRAINT pk_incident_comments PRIMARY KEY (id),
    CONSTRAINT fk_incident_comments_incident
        FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_incident_comments_author
        FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT ck_incident_comments_category
        CHECK (category IS NULL OR category IN ('NOTE', 'INVESTIGATION', 'RESOLUTION'))
);

-- List an incident's comments in authorship order (GET /incidents/{id}/comments).
CREATE INDEX ix_incident_comments_incident
    ON incident_comments (incident_id, created_at);
