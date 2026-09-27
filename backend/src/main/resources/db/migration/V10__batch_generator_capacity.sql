-- =====================================================================
-- Module 3 — Batch Generator & Capacity Alerts (Phase 2, no broker)
--
-- One-shot guard column for the capacity-crossing alert, mirroring how
-- tasks.escalated_at works in Phase 1: an OPEN->alert notification is
-- raised ONCE when seatsBooked/maxCapacity first crosses the configured
-- fill percent (default 90), then this timestamp latches so the alert
-- never re-fires on subsequent seat movements. Capacity *colors* are
-- derived in the service/DTO layer (BatchResponse.fillPercent +
-- capacityColor), so they need no column.
--
-- Nothing here requires a broker: alerts reuse the Phase-1 escalation
-- path (Notification row + EmailNotifier + audit), and the seasonal
-- batch generator is a plain transactional service method that walks a
-- recurrence rule and reuses BatchService.createBatch per resulting date.
-- Redis remains a read-through cache only (see ADR-0003 / ARCHITECTURE).
-- =====================================================================

ALTER TABLE batches ADD COLUMN capacity_alerted_at timestamptz NULL;

CREATE INDEX idx_batches_capacity_alert ON batches (trip_id) WHERE capacity_alerted_at IS NULL;
