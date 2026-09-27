-- =====================================================================
-- Module 3 — minimum-viable-group alert latch
--
-- V10 added capacity_alerted_at for the SCARCITY alert (fill reached the
-- configured threshold, e.g. 90%). That one-shot guard cannot be reused for
-- the minimum-viable-group alert: the two events are independent and can both
-- be true for the same batch, and each must fire exactly once.
--
--   * scarcity  — "this departure is nearly sold out"   (fill >= 90%)
--   * min group — "this departure is unlikely to run"    (near departure,
--                 few travellers booked)
--
-- A batch can be nearly full AND still under the minimum viable group size
-- only in edge cases, but it can certainly cross 90% and later be evaluated
-- by the viability sweep, so sharing one column would make the second event
-- silently unreachable. Separate latch, same mechanics as V10.
--
-- Still no broker: the sweep is an in-process @Scheduled job writing a
-- Notification row + email through the Phase-1 escalation path
-- (see ADR-0003 — Redis stays cache-only, no pub/sub, no fan-out).
-- =====================================================================

ALTER TABLE batches ADD COLUMN min_group_alerted_at timestamptz NULL;

-- Partial index: the sweep only ever asks for "not yet alerted" rows, and
-- over a season almost every batch is in that state.
CREATE INDEX idx_batches_min_group_alert ON batches (departure_date)
    WHERE min_group_alerted_at IS NULL;
