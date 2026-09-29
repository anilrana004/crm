-- =============================================================================
-- Phase 6, Module 1 — workflow automation: definition model.
--
-- Two tables:
--   workflows         the stable, addressable rule (slug, name). A workflow is
--                     NOT configurable — the configurable thing is a version,
--                     so identity and behaviour are separate:
--   workflow_versions the immutable definition snapshot. DRAFT until it is
--                     activated; editing an ACTIVE workflow creates a new
--                     DRAFT version that must be validated and activated
--                     again. Exactly one version per workflow may be ACTIVE
--                     (partial unique index below), so the engine's "what
--                     runs" lookup is a filter, not a coordination problem.
--
-- The definition is a self-contained JSON document (trigger, entry
-- conditions, ordered steps + branch graph) persisted as jsonb. It is
-- validated by WorkflowValidator before a version may be active; the DB
-- backstop is deliberately loose (jsonb) because the validation lives in the
-- application, which is where the closed action/vocabulary lives. Only the
-- state machine (DRAFT/ACTIVE/PAUSED/ARCHIVED) and the single-ACTIVE rule are
-- enforced here.
--
-- Version numbering continues from V13/V14/V15/V16 as usual. In-flight runs
-- (V18, Module 2) pin the workflow_version they started from, which is why a
-- version is append-only data and editing = new row.
-- =============================================================================

CREATE TABLE workflows (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug          varchar(100) NOT NULL,
    name          varchar(200) NOT NULL,
    description   text,
    created_by    uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_workflows_slug ON workflows (slug);

CREATE TABLE workflow_versions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id      uuid NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    version_number   int NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    definition       jsonb NOT NULL,
    published_by     uuid REFERENCES users(id) ON DELETE SET NULL,
    published_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

-- Version numbers restart per workflow.
CREATE UNIQUE INDEX uq_workflow_versions_number ON workflow_versions (workflow_id, version_number);

-- The engine's contract: at most one ACTIVE version per workflow. Activation
-- (WorkflowService) relies on this index to make a second activation a
-- constraint violation rather than a race.
CREATE UNIQUE INDEX uq_workflow_versions_active ON workflow_versions (workflow_id) WHERE status = 'ACTIVE';

CREATE INDEX idx_workflow_versions_active ON workflow_versions (status) WHERE status IN ('ACTIVE', 'PAUSED');