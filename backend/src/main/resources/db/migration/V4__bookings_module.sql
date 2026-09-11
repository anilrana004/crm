-- =====================================================================
-- SecureTravels CRM — Module 4 (Bookings)
-- Link a booking to its originating lead so the confirmation can drive
-- the lead stepper (QUOTATION_SENT -> BOOKING_CONFIRMED) and a
-- cancellation can reopen it. Seats are the same batch pool (I3); the
-- 2-hour provisional hold lives in seat_holds (already in V1).
-- =====================================================================

ALTER TABLE bookings ADD COLUMN lead_id uuid REFERENCES leads(id);

CREATE INDEX idx_bookings_lead ON bookings(lead_id);