-- =============================================================================
-- Phase 6, Module 2 — workflow automation: the run layer.
--
-- Every table here is append-mostly or claims-based; the engine never mutates
-- a definition. Five concerns, one migration:
--
--   automation_events        the transactional outbox. A domain service that
--                            wants automation work to happen calls
--                            EventPublisher.publish(...); the record is written
--                            IN THE SAME tx as the domain change, and a relay
--                            drains it afterwards. event_key is unique so a
--                            replayed publish (already in the outbox) is a
--                            no-op and exactly-once.
--
--   workflow_runs           one row per triggered execution. Pins the
--                           workflow_version it started from (append-only data,
--                           so in-flight runs keep their exact snapshot), the
--                           subject, the trigger, and an event_key that makes a
--                           duplicate trigger for the same run a no-op. At most
--                           one RUNNING/WAITING run per (workflow, subject) is
--                           enforced by a partial unique index -- the coalescing
--                           rule of the engine, not a service-layer decision.
--                           position is the next step the run is parked on (or
--                           was last working on); the recovery sweep re-enters
--                           a stuck run at its position idempotently.
--
--   workflow_run_steps      the per-step ledger: PENDING -> SUCCEEDED/SKIPPED/
--                           FAILED with attempts and last_error. A SUCCEEDED or
--                           SKIPPED step is never re-run (exactly-once bookkeeping
--                           even when the run is re-entered by the poller or
--                           recovery).
--
--   workflow_scheduled_steps  the durable wait queue. WAIT parks a run for N
--                           minutes, RETRY parks it for backoff before the next
--                           attempt, APPROVAL parks it until an operator
--                           approves/rejects. The poller claims rows with
--                           FOR UPDATE SKIP LOCKED, so concurrent pollers each
--                           take a disjoint slice. One row per (run, step).
--
--   workflow_step_effects   the effect outbox. The executor inserts one row keyed
--                           (run_id, step_id, attempt_group) IN THE SAME tx as
--                           the side effect; a re-entered execution of the same
--                           attempt hits the unique key, sees the effect already
--                           applied, and does not apply it twice. This is what
--                           makes "exactly-once replay" hold even for idempotency
--                           that the domain features cannot claim themselves.
--
--   workflow_run_failures   the failure inbox: where a run that exhausted its
--                           retries lands, so it is a queryable fact (Manager
--                           inbox, Module 5) rather than a log line.
-- =============================================================================

-- ------------------------------------------------------------------ outbox --
CREATE TABLE automation_events (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity       varchar(30) NOT NULL,
    action       varchar(50) NOT NULL,
    subject_id   uuid NOT NULL,
    occurred_at  timestamptz NOT NULL,
    event_key    varchar(255) NOT NULL,
    status       varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    attempts     int NOT NULL DEFAULT 0,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz
);

-- Replaying the same publish for the same subject at the same instant is a
-- duplicate, not a second event.
CREATE UNIQUE INDEX uq_automation_events_key ON automation_events (event_key);
CREATE INDEX idx_automation_events_claim ON automation_events (status, created_at);

-- ------------------------------------------------------------------- runs --
CREATE TABLE workflow_runs (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id         uuid NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    workflow_version_id uuid NOT NULL REFERENCES workflow_versions(id),
    entity              varchar(30) NOT NULL,
    subject_id          uuid NOT NULL,
    trigger_event       varchar(100) NOT NULL,
    event_key           varchar(255) NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    position            varchar(64),
    last_error          text,
    started_at          timestamptz NOT NULL DEFAULT now(),
    finished_at         timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

-- The engine coalesces: a replay of the same trigger for the same workflow is
-- ignored, whatever the run became.
CREATE UNIQUE INDEX uq_workflow_runs_event ON workflow_runs (workflow_id, event_key);

-- At most one live run per (workflow, subject): the at-most-one contract. A
-- second trigger while one is RUNNING/WAITING simply cannot insert.
CREATE UNIQUE INDEX uq_workflow_runs_active
    ON workflow_runs (workflow_id, entity, subject_id)
    WHERE status IN ('RUNNING', 'WAITING');

CREATE INDEX idx_workflow_runs_subject ON workflow_runs (entity, subject_id, started_at);
CREATE INDEX idx_workflow_runs_stuck ON workflow_runs (status, updated_at)
    WHERE status IN ('RUNNING', 'WAITING');

-- -------------------------------------------------------------- run steps --
CREATE TABLE workflow_run_steps (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      uuid NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    step_id     varchar(64) NOT NULL,
    step_order  int NOT NULL,
    action      varchar(30) NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    attempts    int NOT NULL DEFAULT 0,
    last_error  text,
    executed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Exactly one ledger row per step per run; a step is never registered twice.
CREATE UNIQUE INDEX uq_workflow_run_steps ON workflow_run_steps (run_id, step_id);
CREATE INDEX idx_workflow_run_steps_status ON workflow_run_steps (run_id, status);

-- ------------------------------------------------------ scheduled steps --
CREATE TABLE workflow_scheduled_steps (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id     uuid NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    step_id    varchar(64) NOT NULL,
    kind       varchar(10) NOT NULL CHECK (kind IN ('WAIT', 'RETRY', 'APPROVAL')),
    run_after  timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_scheduled_steps_run_step ON workflow_scheduled_steps (run_id, step_id);
CREATE INDEX idx_scheduled_steps_due ON workflow_scheduled_steps (run_after, kind);

-- -------------------------------------------------------- effect outbox --
CREATE TABLE workflow_step_effects (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id        uuid NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    step_id       varchar(64) NOT NULL,
    attempt_group int NOT NULL,
    effect        varchar(30) NOT NULL,
    ref_id        uuid,
    summary       text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- The exactly-once guard, written in the same tx as the effect itself.
CREATE UNIQUE INDEX uq_step_effects_attempt ON workflow_step_effects (run_id, step_id, attempt_group);

-- ------------------------------------------------------- failure inbox --
CREATE TABLE workflow_run_failures (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      uuid NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    step_id     varchar(64),
    workflow_id uuid NOT NULL,
    entity      varchar(30) NOT NULL,
    subject_id  uuid NOT NULL,
    error       text NOT NULL,
    attempts    int NOT NULL DEFAULT 0,
    status      varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_run_failures_open ON workflow_run_failures (status, created_at)
    WHERE status = 'OPEN';