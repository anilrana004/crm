# ADR 0006 — Workflow automation engine: build vs buy

- **Status:** Accepted · Ratified (Phase 6 Step 0)
- **Date:** 2026-09-29
- **Supersedes/related:** complements ADR 0002 (modular monolith over
  microservices) and ADR 0005 (no cross-service domain-event bus). ADR 0003
  reserved `com.securetravels.crm.automation` for this phase.
- **Note on numbering:** the Phase-6 kickoff named this "ADR-0005 (build vs
  buy)" and the Kafka question "ADR-0006", but `0005` is taken by the RabbitMQ
  communication-delivery ADR. These are **0006** (this file) and **0007**
  (Kafka gate).

## Context

Phase 6 replaces ~20 hard-coded business rules (see `AUTOMATION_INVENTORY.md`:
lead follow-up ladder, payment reminders, quota/chase sends, capacity alerts)
with **configurable trigger → condition → action workflows** a Manager edits
and an Admin activates, without code deploys.

The question is whether to build a small engine inside the existing monolith
or adopt an off-the-shelf workflow engine. The deciding context:

- **Scale and team (ADR 0002, Phase 4 reassessment 2026-09-28):** one site,
  5 users, one JVM, one Postgres. Not a multi-service, multi-region problem.
- **Security posture (SECURITY.md):** no scripting / no eval / no SpEL-OGNL;
  everything parameterized and allow-listed; all outbound sends through the
  Phase 5 consent gate; SSRF-hardened webhook calls only.
- **Test hermiticity (ADR 0005):** the full suite (397 tests) runs with **no
  broker** and must keep doing so. The engine must not introduce a hard
  infrastructure dependency.
- **Durability needs:** exactly-once effects, durable `WAIT`, retries that
  survive restarts. These are achievable with Postgres transactions — which we
  already operate — rather than a second state store.
- **Volume (ADR 0007):** projected workflow-trigger traffic is a few thousand
  evaluations/day even at 10× design scale — trivially in-process.

## Options considered

### Option A — Build custom (chosen)

A `automation/` package: `Workflow` + immutable `WorkflowVersion`, JSON
trigger/condition/step definitions, an action registry (the closed list from
Module 1), durable `WorkflowRun`/`WorkflowStepExecution` rows, outbox/dedup for
exactly-once effects, and a `scheduled_steps` table polled with
`SELECT … FOR UPDATE SKIP LOCKED` for durable `WAIT`/time-triggers. All schema
in one Postgres, all state in the same transaction as the effect.

Pros:

- Matches the build: one JVM + one Postgres. No new service, no new broker,
  no second state store, no operator surface.
- Security model is exactly the one we already enforce; nobody ships a
  scripting language by accident (the condition validator is *ours*).
- The reserved `automation/` package (ADR 0003) is already the intended seam.
- The engine is ~as complex as the rules it replaces (a dozen actions), so the
  "not invented here" risk is bounded; the hard parts (idempotency, durable
  waits, dedupe) are reuses of the Phase 5 dispatch techniques already proven
  in `WhatsAppDispatchService`.
- Tests stay hermetic: the engine is just Spring + Postgres, which the suite
  already boots.
- No licensing/commercial risk; full control of the sandbox surface.

Cons:

- We own correctness of the run/step/durable-wait machinery (Module 2 non-
  negotiables) — small, but real.
- The UI (Module 5) is ours; no BPMN designer to reuse.

### Option B — Temporal

Pros: battle-tested durable execution, exactly-once workflows, timers, retry
and cancellation semantics out of the box.

Cons: an always-on cluster (frontend/history/matching/worker services + gRPC +
its own Postgres/MySQL/Cassandra), a second state store that must be backed up
and recovered (DISASTER_RECOVERY.md burden), a required SDK on every worker,
and a test story that cannot run without a Temporal server. For 5 users and a
few thousand evaluations/day, that is the infrastructure the whole program has
deliberately deferred (ADR 0002/0003/0005). We would be adding a distributed
systems dependency to make an in-process rule engine durable — the wrong
trade.

### Option C — Camunda 8 (Zeebe)

Pros: real BPMN, visual tooling, built-in flow control (exclusive/parallel
gateways), WebModeler for Managers.

Cons: Zeebe is a Kafka-partitioned broker — adopting Camunda 8 *is* adopting a
Kafka-like infrastructure, which reopens the entire ADR 0007 question by
stealth. BPMN XML is a large spec; our conditions are a JSON tree we control.
The platform drags roster/identity/operate components and a second database.
Overkill for this rule set and contrary to the "no scripting, allow-listed"
posture (BPMN encourages expression languages).

### Option D — n8n / Zapier / Make / Carnot / third-party workflow SaaS

Pros: fastest to a visual builder.

Cons: our automation touches customer PII (name, mobile, email, health
checklist data). Sending it to a third-party SaaS for rule execution violates
the DPDPA/consent posture and the Phase-1 threat model without a vendor
assessment. Also re-introduces webhook fan-out we would have to secure
(SSRF, signature) — a worse integral for `CALL_WEBHOOK` than our own allow-listed
HTTP client.

### Option E — Spring Statemachine / Quartz / Java rule engines (Drools)

Pros: small, familiar.

Cons: a state machine is a workflow primitive, not a workflow definition
(no versioning, no declarative JSON, no per-run orchestration); Quartz is a
scheduler only (durable waits still ours); Drools is a rules engine with a DSL
and a learning/ops cost that buys nothing over a JSON tree for 8 templates.
Rolling these in would be *more* complexity for less.

## Decision

**Build a custom workflow automation engine inside the monolith** (Option A),
in the reserved `com.securetravels.crm.automation` package, backed by Postgres,
executed in-process, with an explicit closed action registry and a JSON
condition tree validated against a per-entity field allow-list. No Temporal,
no Camunda, no third-party rule SaaS, no new scripting surface.

Scope discipline (unchanged from the kickoff):

- Definition model must stay data, not code — every rule is a versioned DB row
  with JSON trigger/conditions/steps, not a compiled unit.
- Exactly-once effects via deterministic idempotency keys committed with the
  effect in one transaction; durable `WAIT` via a `scheduled_steps` table and
  a `SKIP LOCKED` poller; retries and cancellation per Module 2.
- Guardrails (Module 3) are not afterthoughts: causal depth cap, rate limits,
  kill switch, blast radius, dry-run, consent-as-skip, audit, SSRF-hardened
  webhooks — built before the UI and before the migration.
- The engine is a **feature of the monolith**, never a separate deployable.

## Consequences

- `automation/` becomes the differential package for this phase; everything
  it emits is audited and PII-free by id where possible.
- Databases grow the Module 1/2 tables (V17+); `DATABASE.md` is updated with
  those migrations.
- The pattern library in `WhatsAppDispatchService` (claim row → attempt →
  record state) is the template for step claiming; `InboundMessageService`'s
  dedup is the template for the outbox/dedup table.
- Manager-editable rules only extend the action registry after review; adding
  an action codec is a code change with an API-stability discussion, not a
  config change.
- No additional infrastructure to run or back up beyond the existing
  Postgres instance (ADR 0007 reaffirms no Kafka is needed for this).

## Revisit triggers

Revert to a buy decision (and raise a new ADR) only if a hard signal appears
in the signoff checks:

1. The Module 3 10× load test shows the in-process engine cannot sustain
   projected peak trigger volume without stalling the business transaction
   path (see ADR 0007 for the number).
2. A second consumer genuinely needs workflow *history/state* that the
   monolith cannot serve synchronously (a real cross-service bus, per 0005).
3. The rule set grows past what templates can contain (e.g. dozens of branches
   per workflow with multi-day interleaved parallel waits) such that building
   and maintaining Module 4/5 tooling for it exceeds the cost of adopting a
   platform.