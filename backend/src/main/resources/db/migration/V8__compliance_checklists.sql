-- =====================================================================
-- SecureTravels CRM â€” Phase 2, Module 1 (Documents & Compliance)
-- 1) Batch status gains READY_FOR_DEPARTURE (compliance hard gate).
-- 2) Trips gain a difficulty rating for the medical-cert threshold rule.
-- 3) Documents gain CONSENT_FORM (minor consent <18; signed form scan).
-- 4) Per-traveller compliance checklist table.
--
-- Checklist status tri-state mirrors the Red/Yellow/Green board:
--   MISSING (red)      â€” required item not provided at all
--   IN_PROGRESS (yellow) â€” provided but awaiting OPS review (e.g. uploaded)
--   VERIFIED (green)   â€” signed off by OPS/manager
-- =====================================================================

-- 1) READY_FOR_DEPARTURE batch status.
ALTER TABLE batches DROP CONSTRAINT batches_status_check;
ALTER TABLE batches ADD CONSTRAINT batches_status_check CHECK (status IN
    ('OPEN', 'CLOSED', 'CANCELLED', 'READY_FOR_DEPARTURE'));

-- 2) Trip difficulty (NULL = medical certificate never demanded for it).
ALTER TABLE trips ADD COLUMN difficulty varchar(20)
    CHECK (difficulty IN ('EASY', 'MODERATE', 'DIFFICULT', 'VERY_DIFFICULT'));

-- 3) Consent form documents for minors.
ALTER TABLE documents DROP CONSTRAINT documents_doc_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_doc_type_check CHECK (doc_type IN
    ('ID_PROOF', 'MEDICAL_CERT', 'TRIP_PHOTO', 'CONSENT_FORM'));

-- 4) Per-traveller compliance checklist (one row per traveller).
CREATE TABLE traveller_checklists (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    traveller_id            uuid NOT NULL UNIQUE REFERENCES travellers(id) ON DELETE CASCADE,
    id_proof_status         varchar(20) NOT NULL DEFAULT 'MISSING'
        CHECK (id_proof_status IN ('MISSING', 'IN_PROGRESS', 'VERIFIED')),
    id_proof_document_id    uuid REFERENCES documents(id),
    medical_status          varchar(20) NOT NULL DEFAULT 'MISSING'
        CHECK (medical_status IN ('MISSING', 'IN_PROGRESS', 'VERIFIED')),
    medical_document_id     uuid REFERENCES documents(id),
    emergency_status        varchar(20) NOT NULL DEFAULT 'MISSING'
        CHECK (emergency_status IN ('MISSING', 'IN_PROGRESS', 'VERIFIED')),
    emergency_contact_name  varchar(120),
    emergency_contact_phone varchar(20),
    minor_status            varchar(20) NOT NULL DEFAULT 'MISSING'
        CHECK (minor_status IN ('MISSING', 'IN_PROGRESS', 'VERIFIED')),
    minor_consent_document_id uuid REFERENCES documents(id),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_checklists_traveller ON traveller_checklists(traveller_id);
