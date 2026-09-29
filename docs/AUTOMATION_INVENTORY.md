# SecureTravels CRM — Automation Inventory (Phase 6 Step 0)

> **Status: Phase 6 kickoff artefact (2026-09-29).** Ground truth for what
> today's code does *automatically*, and the classification that decides what
> the Phase 6 workflow engine must own (**MIGRATE**) versus what stays as hard,
> non-configurable code (**CORE**). Every MIGRATE item becomes a Module 5
> template; every CORE item is a boundary the engine must never be able to
> bypass. Companion ADRs: `0006-workflow-automation-engine-build-vs-buy.md`,
> `0007-kafka-gate.md`.

## 1. How automations run today

Today there is **no configurable rule engine**. Every automation is one of
four hard-coded shapes, all in-process and broker-free (ADR 0003 / 0005):

| Shape | Used by |
|---|---|
| **Synchronous call inside the business transaction** | `FollowUpAutomation` (from `LeadService`), `CapacityAlertService.onSeatsBooked` (seat-write path), `OperationsService.onBookingConfirmed`/`onBookingCancelled`, round-robin assignment |
| **`@TransactionalEventListener(AFTER_COMMIT)`** | `BookingConfirmationNotifier`, `WhatsAppRoutingListener`, `EmailRoutingListener`, `SmsRoutingListener` — committed message routing; one domain event (`BookingConfirmedEvent`) plus three queued-message events (`WhatsAppQueuedEvent`, `EmailQueuedEvent`, `SmsQueuedEvent`) |
| **`@RabbitListener` consumer** | `WhatsAppDispatchListener` (optional `BROKER` mode; `INLINE` default runs the same dispatcher, ADR 0005) |
| **`@Scheduled` sweeps** | `SeatHoldSweep` (1 min), `FollowUpSweep` (5 min), `CapacityAlertSweep` (1 h), `PaymentSweep` (24 h), `WhatsAppDispatchService.recovery-sweep`, plus dispatch retry loops |

Domain **events published today** (the only seam the engine plugs into):

- `BookingConfirmedEvent` — booking reaches CONFIRMED (carries resolved values, published in-tx).
- `WhatsAppQueuedEvent`, `EmailQueuedEvent`, `SmsQueuedEvent` — a committed outbound-message row is ready for delivery (these are delivery-plane events; **not** business trigger events).

Everything else is a direct call or a scheduled query.

## 2. Trigger inventory (business events the engine will see)

Phase 6 introduces one `EventPublisher` that emits **domain events by id**
(next to each mutation) and one trigger schema `{module}.{entity}.{action}`.
The emit points the engine will subscribe to:

| Trigger | Emit points today | Future engine trigger name |
|---|---|---|
| Lead created (API + website webhook + inbound reply) | `LeadService.create`, `WebhookService`, `InboundMessageService` | `lead.created` |
| Lead status changed | `LeadService` status transitions | `lead.updated` (condition on `status =`) |
| Lead field updated (travel date, budget, persons, owner) | `LeadService.PATCH` | `lead.updated` |
| Booking confirmed / cancelled | `BookingService` (already publishes `BookingConfirmedEvent`) | `booking.confirmed`, `booking.cancelled` |
| Payment created / status changed / due soon | `PaymentService`, `PaymentSweep` | `payment.created`, `payment.updated`, `payment.overdue` |
| Task due / overdue / SLA breached | `FollowUpSweep`, task lifecycle | time-trigger (cron) + `task.overdue` |
| Seat hold placed / expired | `SeatHoldSweep` | **CORE — not a trigger** (invariant) |
| Batch fill changed | `BookingService` seat path | `batch.seats_updated` |
| Outbound message queued (WhatsApp/Email/SMS) | dispatch services | `message.queued` (delivery plane; usually not a rule trigger) |
| Inbound reply / opt-out received | `InboundMessageService`, consent | `inbound.received` (CORE consumption) |
| Document uploaded | `DocumentService` | `document.uploaded` |
| Compliance checklist item changed | `ComplianceService` | `compliance.item_updated` |
| Time-based (no event) | sweeps | cron / `date_field − Nd` time triggers |

## 3. The inventory — MIGRATE vs CORE

### 3.1 MIGRATE — becomes a workflow template (rule logic, currently hard-coded)

| # | Automation | Today (file, trigger) | Side effects | Module 5 template |
|---|---|---|---|---|
| M1 | **New-lead speed-to-lead** | `FollowUpAutomation.onLeadCreated` (`LeadService.create` + webhook path) + `RoundRobinService` | Assign owner (round-robin when absent), create `INITIAL_CALL` task due +5 min / SLA +1 h, IN_APP notification + email | `new-lead-speed-to-lead` |
| M2 | **Follow-up cadence** | `FollowUpAutomation.onInterested` (status → INTERESTED) | Cancel-then-reschedule `FOLLOW_UP_1D/3D/8D/15D` (cumulative from lead creation, spec 19.2) + notifications | `follow-up-cadence` |
| M3 | **Quotation no-response chase** | `FollowUpAutomation.onQuotationSent` (status → QUOTATION_SENT) | `QUOTATION` task due +2 d, notifications | `quotation-no-response-chase` |
| M4 | **Booking-confirmation send** | `BookingConfirmationNotifier` (AFTER_COMMIT on `BookingConfirmedEvent` → `SendGateService`) | WhatsApp `BOOKING_CONFIRMED` template via the Phase 5 send-gate | `booking-confirmation-send` |
| M5 | **Balance-due reminder** | `PaymentSweep` (daily) → `PaymentService.ensureReminder` | `PAYMENT_REMINDER` task due within 3 d (deduped per booking) | `balance-due-reminder` |
| M6 | **Payment overdue marking** | `PaymentSweep` (daily) | PENDING past due → OVERDUE + ops payment mirror (`OperationsService.syncPaymentStatus`) | folded into `balance-due-reminder` (or `payment-overdue`) |
| M7 | **OPS prep task + ops notification** | `OperationsService.onBookingConfirmed` | OPS prep task (deduped) + notify all OPS; **handoff row itself stays CORE (I6)** | `booking-confirmed-ops` (or folds into M4's workflow) |
| M8 | **Batch scarcity alert** | `CapacityAlertService.onSeatsBooked` (seat-write path, latched, MANDATORY tx) | First crossing of `app.capacity.alert-fill-percent` → notify first OPS + email + audit; one-shot latch | `batch-scarcity-alert` |
| M9 | **Lead booking-confirmed cleanup** | `FollowUpAutomation.onBooked` (status → BOOKING_CONFIRMED) | Cancel all open follow-up tasks + notify owner | step inside `new-lead-speed-to-lead` or separate |

### 3.2 NEW — required by the Module 5 template library, no equivalent today

| # | Automation | Reason it exists | Module 5 template |
|---|---|---|---|
| N1 | **Document-missing reminder** | Compliance board computes RED/AMBER/YELLOW (`ComplianceService`) but nothing chases travellers automatically today | `document-missing-reminder` (trigger: near-departure + `compliance.item_updated`, `document.uploaded`) |
| N2 | **Post-trip review + 90-day re-engagement** | No current automation (Phase 8/11 flavor) | `post-trip-review-and-re-engagement` |
| N3 | **Inactive-lead nudge** | No current automation; `leads.last_contacted_at` exists | `inactive-lead-nudge` (time-trigger) |

### 3.3 CORE — hard-coded, the engine may never bypass or re-route

| # | Automation | File | Why it stays CORE |
|---|---|---|---|
| C1 | **Seat-hold expiry** (2 h, release seats, reopen closed batch) | `SeatHoldSweep` | Seat-pool invariant I3; releases run under `PESSIMISTIC_WRITE` on the batch row. Data integrity, not a business rule. |
| C2 | **Compliance → READY_FOR_DEPARTURE gate** | `ComplianceService.markReadyForDeparture` | Hard safety gate enforced server-side with explicit OPS action; a misconfigured workflow must never mark a batch departed. |
| C3 | **Ops handoff auto-creation** (one row per booking CONFIRMED) | `OperationsService.onBookingConfirmed` | Invariant I6 (UNIQUE booking_id FK); the handoff is the source of truth the ops board reads. (Only its *task/notification* parts are M1–M8.) |
| C4 | **Payment-webhook confirmation** | *not yet built* (Phase 2/5 gateway callback sets `gateway_ref`/COMPLETED) | Money truth from an external callback must be a hard, audited code path, never configurable. |
| C5 | **Vendor conflict block** (guide/driver double-book) | *not yet built* (reserved; `OperationsService` assigns per-handoff today) | Safety/accounting block; must never be expressible as a skip-able workflow step. |
| C6 | **Emergency/departure-viability alert** (minimum viable group) | `CapacityAlertSweep` + `CapacityAlertService.onMinimumGroupRisk` (latched) | Operational safety: must fire every time regardless of workflow config, dry-run, consent, or kill switch. |
| C7 | **Consent enforcement** | `SendGateService`, `ConsentService`, opt-out paths, suppression tables | DPDPA. Every engine `SEND_MESSAGE` goes through the send-gate; consent-blocked sends become **SKIPPED** steps, never an error or a workaround. |
| C8 | **Dispatch mechanics** (WhatsApp/Email/SMS queue→deliver, retries, recovery sweeps, DLQ, dedupe) | `WhatsAppDispatchService`, `EmailRoutingListener`, `SmsRoutingListener`, `WhatsAppDispatchListener` | Delivery state machines + idempotency owned by the dispatch layer (ADR 0005). The engine treats a send as "call the send-gate". |
| C9 | **Inbound routing + attribution + lead creation from replies** | `InboundMessageService`, `InteraktWebhookService`, `SmsWebhookService`, `EmailWebhookService` | Canonical, deduplicated inbound path feeding attribution and opt-out. Engine may later *consume* `inbound.received`, never duplicate the routing. |
| C10 | **Audit logging** | `AuditService`, `audit_log` | Append-only provenance of who/what/when incl. every workflow activation/change. |
| C11 | **Commission ledger credit** on booking confirm | `CommissionLedgerService` (ON CONFLICT DO NOTHING) | Financial snapshot; one row per booking, revoke-never-delete. |
| C12 | **Customer360 aggregate maintenance** | `Customer360Service.maintainAggregates` | Derived counters on the canonical record; consistency, not a rule. |
| C13 | **Lead heat scoring** | `LeadScoringService` (ADR 0004) | Rule-based derivation evaluated on create/update; the engine will read scored fields via the field registry, not re-implement them (and Phase 11 upgrades the rule itself to ML). |
| C14 | **Round-robin cursor** (`assignment_state`) | `RoundRobinService` | Assignment *mechanics* stay here; a MIGRATE template may call it as the `ASSIGN_OWNER` action. |

## 4. Time-trigger polarity (what "cron in the engine" must reproduce)

| Today's sweep | Engine replacement | CORE/CONTINUES |
|---|---|---|
| `SeatHoldSweep` (1 min) | none — stays scheduled C1 | CORE |
| `FollowUpSweep` (5 min): PENDING→OVERDUE, SLA→escalate | time-trigger: `task.due_at` / `task.sla_deadline` → UPDATE_FIELD + NOTIFY_USER (one-shot, latched by `escalated_at =` idempotency) | MIGRATE |
| `CapacityAlertSweep` (1 h): min-group viability | none — stays scheduled C6 | CORE |
| `PaymentSweep` (24 h): overdue + reminders | time-triggers: `payment.due_date` → UPDATE_FIELD / CREATE_TASK | MIGRATE |
| dispatch recovery sweeps | none — stays in dispatchers | CORE |

## 5. Guardrail ground rules (carried into Module 3)

1. **CORE runs before the engine:** seat-hold expiry, compliance gate, consent, dispatch, audit, and the ops handoff execute in their transactions regardless of workflow state.
2. **SEND_MESSAGE is always the Phase 5 send-gate.** The engine never calls a provider directly.
3. **Scripting stays out.** Every MIGRATE rule above is expressible as trigger + condition tree over the field registry + a closed action set — the validator test suite proves arbitrary expressions are rejected.
4. **Latches become exactly-once idempotency keys.** Today's `capacity_alerted_at`/`min_group_alerted_at`/`escalated_at` latches map to Module 2's outbox/dedup keys `(run_id, step_id, attempt-group)` and a "skip if already applied" check.
5. **Consent-blocked and cancelled steps are SKIPPED with a reason**, never FAILED.
6. Migration is **shadow-mode per MIGRATE item** (Module 6): recreate as template → parity run vs legacy → feature-flag off legacy behind per-automation slice → delete the old hard-coded path. C items are never migrated.