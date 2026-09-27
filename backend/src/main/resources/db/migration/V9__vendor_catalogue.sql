-- =====================================================================
-- SecureTravels CRM — Phase 2, Module 2 (Vendor Management)
-- Upgrades the Phase-1 "guides" placeholder and the bare "driver_id"
-- into a single vendor catalogue covering guides, hotels, transport
-- and drivers (payout metadata kept bank-obfuscated — see SECURITY.md).
-- =====================================================================

-- 1) Vendors catalogue.
CREATE TABLE vendors (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category           varchar(20) NOT NULL
        CHECK (category IN ('GUIDE', 'HOTEL', 'TRANSPORT', 'DRIVER')),
    name               varchar(200) NOT NULL,
    phone              varchar(20),
    email              varchar(120),
    city               varchar(80),
    gstin              varchar(15),
    bank_account_ref   varchar(40),
    daily_rate         numeric(10,2),
    notes              text,
    is_active          boolean NOT NULL DEFAULT true,
    legacy_guide_id    uuid UNIQUE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_vendors_category_active ON vendors(category, is_active);
CREATE INDEX idx_vendors_city ON vendors(city);

-- 2) Backfill existing guides into the catalogue as GUIDE vendors.
INSERT INTO vendors (category, name, phone, daily_rate, is_active, legacy_guide_id, created_at, updated_at)
SELECT 'GUIDE', full_name, phone, daily_rate, is_active, id, created_at, now() FROM guides;

-- 3) Re-point batch/handoff guide references to the new vendor ids.
UPDATE batches b SET guide_id = v.id FROM vendors v WHERE v.legacy_guide_id = b.guide_id;
UPDATE operations_handoffs o SET guide_id = v.id FROM vendors v WHERE v.legacy_guide_id = o.guide_id;

-- 4) Swap the guide FKs from the old guides placeholder onto vendors.
ALTER TABLE batches DROP CONSTRAINT batches_guide_id_fkey;
ALTER TABLE operations_handoffs DROP CONSTRAINT operations_handoffs_guide_id_fkey;
ALTER TABLE batches ADD CONSTRAINT batches_guide_id_fkey
    FOREIGN KEY (guide_id) REFERENCES vendors(id);
ALTER TABLE operations_handoffs ADD CONSTRAINT operations_handoffs_guide_id_fkey
    FOREIGN KEY (guide_id) REFERENCES vendors(id);

-- 5) driver_id becomes a real vendor FK; ops can record which hotel /
--    transport vendor each handoff uses alongside the statuses.
ALTER TABLE operations_handoffs ADD CONSTRAINT operations_handoffs_driver_id_fkey
    FOREIGN KEY (driver_id) REFERENCES vendors(id);
ALTER TABLE operations_handoffs ADD COLUMN hotel_vendor_id uuid REFERENCES vendors(id);
ALTER TABLE operations_handoffs ADD COLUMN transport_vendor_id uuid REFERENCES vendors(id);
CREATE INDEX idx_handoffs_hotel_vendor ON operations_handoffs(hotel_vendor_id);
CREATE INDEX idx_handoffs_transport_vendor ON operations_handoffs(transport_vendor_id);

-- 6) The guides placeholder is gone; drop the backfill marker.
ALTER TABLE vendors DROP COLUMN legacy_guide_id;
DROP TABLE guides;