-- =====================================================================
-- SecureTravels CRM — Module 8 (Sales Dashboard + Targets)
--
-- Legacy M4/M5/M6 rolled into a monthly sales-target table. A row with
-- user_id IS NULL is the company-wide target for that month; neither value
-- is allowed to be all-zero (must set bookings and/or revenue). Single-row
-- per (user, month) guaranteed by partial unique indexes (Postgres treats
-- NULLs as distinct in unique constraints, hence the two indexes).
-- =====================================================================

CREATE TABLE sales_targets (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid REFERENCES users(id),
    month           date NOT NULL,                       -- first day of the target month
    target_bookings integer NOT NULL DEFAULT 0 CHECK (target_bookings >= 0),
    target_revenue  numeric(12,2) NOT NULL DEFAULT 0 CHECK (target_revenue >= 0),
    created_by      uuid REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_sales_target_at_least_one CHECK (target_bookings > 0 OR target_revenue > 0)
);

CREATE UNIQUE INDEX uq_sales_target_user_month   ON sales_targets(user_id, month)   WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_sales_target_company_month ON sales_targets(month)           WHERE user_id IS NULL;