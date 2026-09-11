-- =====================================================================
-- SecureTravels CRM — Module 9 (End-to-end automation chain)
-- Public webhook lead intake:
--   • webhook_logs     — every inbound web request (success / duplicate /
--                        failure) with the raw payload for auditability
--   • assignment_state — round-robin sales-exec assignment cursor
--                        (least-recently-assigned first, reset per month)
-- =====================================================================

CREATE TABLE assignment_state (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 uuid NOT NULL REFERENCES users(id),
    month                   date NOT NULL,
    last_assigned_at        timestamptz NOT NULL DEFAULT now(),
    leads_assigned_this_month integer NOT NULL DEFAULT 0,
    UNIQUE (user_id, month)
);

CREATE INDEX idx_assignment_state_month_assign
    ON assignment_state(month, last_assigned_at);

CREATE TABLE webhook_logs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source        varchar(30) NOT NULL,
    payload       text NOT NULL,
    lead_id       uuid REFERENCES leads(id),
    status        varchar(20) NOT NULL CHECK (status IN ('success', 'duplicate', 'failed')),
    error_message text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_logs_created ON webhook_logs(created_at);