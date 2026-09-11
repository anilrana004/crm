-- ============================================================
-- SecureTravels CRM — Phase 1 — PostgreSQL Schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------- Enums ----------

CREATE TYPE user_role AS ENUM ('admin','manager','sales','ops');
CREATE TYPE lead_status AS ENUM ('new','interested','quotation_sent','booking_confirmed','lost');
CREATE TYPE lead_source AS ENUM (
  'google_ads','facebook_ads','instagram','website','whatsapp','referral','justdial','walk_in','b2b','existing_customer'
);
CREATE TYPE task_type AS ENUM (
  'initial_call','follow_up_1d','follow_up_2d','follow_up_5d','follow_up_7d','no_activity','quotation','payment_reminder','custom'
);
CREATE TYPE task_status AS ENUM ('pending','completed','overdue','cancelled');
CREATE TYPE advance_status AS ENUM ('pending','received','refunded');
CREATE TYPE payment_status AS ENUM ('pending','partial','completed','overdue','cancelled');
CREATE TYPE hotel_status AS ENUM ('pending','confirmed','cancelled');
CREATE TYPE transport_status AS ENUM ('pending','assigned','confirmed','cancelled');
CREATE TYPE ops_balance_status AS ENUM ('pending','partial','cleared');
CREATE TYPE offer_tag AS ENUM ('kashmir','char_dham','family_tour','anniversary_package');
CREATE TYPE notification_channel AS ENUM ('in_app','email','sms','whatsapp');
CREATE TYPE notification_status AS ENUM ('pending','sent','failed');

-- ---------- users ----------

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  full_name VARCHAR(255) NOT NULL,
  role user_role NOT NULL DEFAULT 'sales',
  phone VARCHAR(20),
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_users_role ON users(role);

-- ---------- drivers (separate entity, Module 11 / Phase 2 vendor-ready) ----------

CREATE TABLE drivers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  full_name VARCHAR(255) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  vehicle_type VARCHAR(100),
  vehicle_number VARCHAR(50),
  license_number VARCHAR(100),
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_drivers_active ON drivers(is_active);

-- ---------- packages (Module 10) ----------

CREATE TABLE packages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) NOT NULL,
  slug VARCHAR(100) UNIQUE NOT NULL,
  cost DECIMAL(12,2) NOT NULL DEFAULT 0,
  duration_days SMALLINT NOT NULL DEFAULT 1,
  itinerary TEXT,
  inclusions TEXT,
  exclusions TEXT,
  departure_date DATE,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_packages_active ON packages(is_active);

-- ---------- leads (Module 1) ----------

CREATE TABLE leads (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_name VARCHAR(255) NOT NULL,
  mobile_number VARCHAR(20) NOT NULL,
  whatsapp_number VARCHAR(20),
  email VARCHAR(255),
  source lead_source NOT NULL,
  destination VARCHAR(255),
  package_id UUID REFERENCES packages(id),
  travel_date DATE,
  num_persons SMALLINT DEFAULT 1,
  budget DECIMAL(12,2),
  lead_owner_id UUID NOT NULL REFERENCES users(id),
  status lead_status NOT NULL DEFAULT 'new',
  follow_up_date TIMESTAMPTZ,
  remarks TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_leads_owner ON leads(lead_owner_id);
CREATE INDEX idx_leads_status ON leads(status);
CREATE INDEX idx_leads_source ON leads(source);
CREATE INDEX idx_leads_followup ON leads(follow_up_date) WHERE follow_up_date IS NOT NULL;
CREATE INDEX idx_leads_created ON leads(created_at);
CREATE INDEX idx_leads_mobile ON leads(mobile_number);

-- ---------- tasks / follow-up automation (Module 3) ----------

CREATE TABLE tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
  assigned_to UUID NOT NULL REFERENCES users(id),
  type task_type NOT NULL DEFAULT 'custom',
  status task_status NOT NULL DEFAULT 'pending',
  title VARCHAR(255) NOT NULL,
  description TEXT,
  due_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_tasks_lead ON tasks(lead_id);
CREATE INDEX idx_tasks_assigned ON tasks(assigned_to);
CREATE INDEX idx_tasks_due ON tasks(due_at);
CREATE INDEX idx_tasks_status ON tasks(status);

-- ---------- call logs (Module 9 follow-up call details) ----------

CREATE TABLE call_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id),
  call_type VARCHAR(50) DEFAULT 'follow_up',
  notes TEXT,
  outcome VARCHAR(50),
  duration_secs INTEGER,
  called_at TIMESTAMPTZ DEFAULT now(),
  created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_call_logs_lead ON call_logs(lead_id);

-- ---------- payments (Module 7) ----------

CREATE TABLE payments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
  booking_amount DECIMAL(12,2) NOT NULL,
  advance_amount DECIMAL(12,2) DEFAULT 0,
  advance_status advance_status NOT NULL DEFAULT 'pending',
  balance_amount DECIMAL(12,2) GENERATED ALWAYS AS (booking_amount - advance_amount) STORED,
  due_date DATE NOT NULL,
  payment_status payment_status NOT NULL DEFAULT 'pending',
  received_at TIMESTAMPTZ,
  notes TEXT,
  created_by UUID REFERENCES users(id),
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_payments_lead ON payments(lead_id);
CREATE INDEX idx_payments_due ON payments(due_date);
CREATE INDEX idx_payments_status ON payments(payment_status);

-- ---------- operations (Module 11) ----------

CREATE SEQUENCE ops_booking_seq START 1;

CREATE TABLE operations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id VARCHAR(50) UNIQUE NOT NULL,
  lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
  customer_name VARCHAR(255) NOT NULL,
  package_name VARCHAR(255),
  pax SMALLINT NOT NULL DEFAULT 1,
  travel_date DATE NOT NULL,
  hotel_status hotel_status NOT NULL DEFAULT 'pending',
  transport transport_status NOT NULL DEFAULT 'pending',
  driver_assigned_id UUID REFERENCES drivers(id),
  advance_status advance_status NOT NULL DEFAULT 'pending',
  balance_status ops_balance_status NOT NULL DEFAULT 'pending',
  ops_notes TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_ops_booking_id ON operations(booking_id);
CREATE INDEX idx_ops_lead ON operations(lead_id);
CREATE INDEX idx_ops_travel_date ON operations(travel_date);
CREATE INDEX idx_ops_driver ON operations(driver_assigned_id);

-- ---------- customers + trip history (Module 13) ----------

CREATE TABLE customers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  full_name VARCHAR(255) NOT NULL,
  mobile_number VARCHAR(20) NOT NULL,
  whatsapp_number VARCHAR(20),
  email VARCHAR(255),
  total_trips SMALLINT DEFAULT 0,
  last_trip_date DATE,
  total_spent DECIMAL(12,2) DEFAULT 0,
  suggest_offer BOOLEAN DEFAULT false,
  offer_tags offer_tag[] DEFAULT '{}',
  notes TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(mobile_number, email)
);
CREATE INDEX idx_customers_mobile ON customers(mobile_number);
CREATE INDEX idx_customers_suggest ON customers(suggest_offer) WHERE suggest_offer;

CREATE TABLE customer_trips (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  lead_id UUID REFERENCES leads(id),
  package_name VARCHAR(255),
  travel_date DATE NOT NULL,
  booking_amount DECIMAL(12,2),
  pax SMALLINT DEFAULT 1,
  created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_customer_trips_customer ON customer_trips(customer_id);
CREATE INDEX idx_customer_trips_date ON customer_trips(travel_date);

-- ---------- notifications (Modules 3 / 7 / 14) ----------

CREATE TABLE notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  lead_id UUID REFERENCES leads(id),
  task_id UUID REFERENCES tasks(id),
  channel notification_channel NOT NULL DEFAULT 'in_app',
  subject VARCHAR(255),
  message TEXT NOT NULL,
  status notification_status NOT NULL DEFAULT 'pending',
  read_at TIMESTAMPTZ,
  sent_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, read_at);
CREATE INDEX idx_notifications_status ON notifications(status);

-- ---------- sales targets (Module 6) ----------

CREATE TABLE sales_targets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id),
  month DATE NOT NULL,
  target_bookings INTEGER NOT NULL DEFAULT 0,
  target_revenue DECIMAL(12,2) DEFAULT 0,
  created_by UUID REFERENCES users(id),
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(user_id, month)
);
CREATE INDEX idx_targets_month ON sales_targets(month);

-- ---------- whatsapp templates (Module 8) ----------

CREATE TABLE whatsapp_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_key VARCHAR(50) UNIQUE NOT NULL,
  name VARCHAR(100) NOT NULL,
  message_template TEXT NOT NULL,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

-- ---------- webhook logs (Module 14) ----------

CREATE TABLE webhook_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
  lead_id UUID REFERENCES leads(id),
  status VARCHAR(50) NOT NULL,
  error_message TEXT,
  created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_webhook_logs_created ON webhook_logs(created_at);

-- ---------- round-robin assignment state (Module 14) ----------

CREATE TABLE assignment_state (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  last_assigned_at TIMESTAMPTZ DEFAULT now(),
  leads_assigned_this_month INTEGER DEFAULT 0,
  month DATE NOT NULL,
  UNIQUE(user_id, month)
);

-- ---------- cron run guard (idempotent automation) ----------

CREATE TABLE cron_runs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_key VARCHAR(100) NOT NULL,
  ran_at TIMESTAMPTZ NOT NULL DEFAULT now()
);