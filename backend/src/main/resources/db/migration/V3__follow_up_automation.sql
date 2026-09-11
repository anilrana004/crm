-- =====================================================================
-- SecureTravels CRM — Module 2 (Follow-up automation)
-- In-app / email notification feed. Every scheduled follow-up task
-- generates one IN_APP row here (email rows are emitted by the stub
-- notifier behind a flag and are not persisted until SMTP is wired).
-- =====================================================================

CREATE TABLE notifications (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id),
    channel    varchar(20) NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL')),
    title      varchar(200) NOT NULL,
    body       text,
    link       varchar(300),
    is_read    boolean NOT NULL DEFAULT false,
    read_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user ON notifications(user_id, is_read, created_at DESC);