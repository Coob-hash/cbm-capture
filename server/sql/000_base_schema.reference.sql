-- VENDORED COPY - DO NOT EDIT
--
-- Verbatim copy of sql/schema.sql from release 2026_07_13, kept here only so CI can apply
-- migration 001 to the real schema rather than to a hand-made fixture. The canonical file
-- lives in 2026_07_13/GPT_Output/sql/schema.sql; if that changes, re-copy this one.
--
-- Community-Based Maintenance: end-of-day AI Agent proof-of-concept
-- PostgreSQL 14+ recommended. All mutating n8n queries should use prepared parameters.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$ BEGIN
  CREATE TYPE request_status AS ENUM (
    'RECEIVED', 'CLAIMED', 'NEEDS_LOCALIZATION', 'ATTACHED_DUPLICATE',
    'CONVERTED_TO_TICKET', 'REJECTED_INPUT', 'FAILED'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE batch_status AS ENUM (
    'PROCESSING', 'PENDING_FM_APPROVAL', 'APPROVED', 'REJECTED', 'COMPLETED', 'FAILED'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE ticket_status AS ENUM (
    'PENDING_FM_APPROVAL', 'READY_FOR_DISPATCH', 'DISPATCHING', 'ASSIGNED',
    'ESCALATED', 'PENDING_APPROVAL', 'REWORK', 'IFC_SYNC_PENDING',
    'CLOSED', 'CANCELLED'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE offer_status AS ENUM ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE slot_status AS ENUM ('AVAILABLE', 'RESERVED', 'BOOKED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS processing_batches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_date DATE NOT NULL,
  status batch_status NOT NULL DEFAULT 'PROCESSING',
  item_count INTEGER NOT NULL DEFAULT 0,
  grounded_count INTEGER NOT NULL DEFAULT 0,
  unresolved_count INTEGER NOT NULL DEFAULT 0,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  agent_report JSONB,
  report_html TEXT,
  fm_decision TEXT,
  fm_actor TEXT,
  fm_decided_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS one_open_batch_per_business_date
  ON processing_batches (business_date)
  WHERE status IN ('PROCESSING', 'PENDING_FM_APPROVAL');

CREATE TABLE IF NOT EXISTS maintenance_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_system TEXT NOT NULL DEFAULT 'google_drive',
  source_file_id TEXT NOT NULL,
  source_file_name TEXT NOT NULL,
  source_mime_type TEXT,
  source_web_url TEXT,
  reporter_email TEXT,
  building_id TEXT NOT NULL DEFAULT 'ROOM-POC',
  status request_status NOT NULL DEFAULT 'RECEIVED',
  batch_id UUID REFERENCES processing_batches(id),
  existing_ticket_id BIGINT,
  manual_ifc_global_id TEXT,
  failure_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_system, source_file_id)
);

CREATE TABLE IF NOT EXISTS batch_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id UUID NOT NULL REFERENCES processing_batches(id) ON DELETE CASCADE,
  request_id UUID NOT NULL REFERENCES maintenance_requests(id),
  image_width INTEGER,
  image_height INTEGER,
  visual_evidence JSONB,
  localization JSONB,
  ifc_global_id TEXT,
  ifc_class TEXT,
  ifc_name TEXT,
  ifc_storey TEXT,
  ifc_space TEXT,
  duplicate_of_ticket_id BIGINT,
  agent_classification JSONB,
  severity INTEGER CHECK (severity BETWEEN 1 AND 5),
  operational_impact INTEGER CHECK (operational_impact BETWEEN 1 AND 5),
  required_skill TEXT,
  safety_risk BOOLEAN NOT NULL DEFAULT false,
  complexity_factor NUMERIC(4,2),
  estimated_cost_min NUMERIC(12,2),
  estimated_cost_max NUMERIC(12,2),
  priority_score NUMERIC(6,2),
  final_rank INTEGER,
  ranking_rationale TEXT,
  confidence NUMERIC(5,4),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (batch_id, request_id)
);

CREATE TABLE IF NOT EXISTS tickets (
  id BIGSERIAL PRIMARY KEY,
  uuid UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  request_id UUID NOT NULL REFERENCES maintenance_requests(id),
  batch_id UUID NOT NULL REFERENCES processing_batches(id),
  building_id TEXT NOT NULL,
  status ticket_status NOT NULL DEFAULT 'PENDING_FM_APPROVAL',
  batch_rank INTEGER NOT NULL,
  priority_score NUMERIC(6,2) NOT NULL,
  priority_label TEXT NOT NULL,
  severity INTEGER NOT NULL CHECK (severity BETWEEN 1 AND 5),
  operational_impact INTEGER NOT NULL CHECK (operational_impact BETWEEN 1 AND 5),
  safety_risk BOOLEAN NOT NULL DEFAULT false,
  required_skill TEXT NOT NULL,
  damage_description TEXT NOT NULL,
  estimated_cost_min NUMERIC(12,2),
  estimated_cost_max NUMERIC(12,2),
  ranking_rationale TEXT NOT NULL,
  ifc_global_id TEXT NOT NULL,
  ifc_class TEXT,
  ifc_name TEXT,
  ifc_storey TEXT,
  ifc_space TEXT,
  before_file_id TEXT NOT NULL,
  before_evidence_uri TEXT,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  assigned_technician_id TEXT,
  scheduled_start TIMESTAMPTZ,
  scheduled_end TIMESTAMPTZ,
  after_file_id TEXT,
  after_evidence_uri TEXT,
  qa_assessment JSONB,
  fm_completion_approved_by TEXT,
  fm_completion_approved_at TIMESTAMPTZ,
  ifc_revision TEXT,
  ifc_sha256 TEXT,
  ifc_sync_attempts INTEGER NOT NULL DEFAULT 0,
  sla_deadline TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE maintenance_requests
  DROP CONSTRAINT IF EXISTS maintenance_requests_existing_ticket_id_fkey;
ALTER TABLE maintenance_requests
  ADD CONSTRAINT maintenance_requests_existing_ticket_id_fkey
  FOREIGN KEY (existing_ticket_id) REFERENCES tickets(id);

ALTER TABLE batch_items
  DROP CONSTRAINT IF EXISTS batch_items_duplicate_of_ticket_id_fkey;
ALTER TABLE batch_items
  ADD CONSTRAINT batch_items_duplicate_of_ticket_id_fkey
  FOREIGN KEY (duplicate_of_ticket_id) REFERENCES tickets(id);

-- Exactly one active work order per IFC occurrence in a building.
CREATE UNIQUE INDEX IF NOT EXISTS one_active_ticket_per_asset
  ON tickets (building_id, ifc_global_id)
  WHERE status NOT IN ('CLOSED', 'CANCELLED');

CREATE TABLE IF NOT EXISTS ticket_evidence (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  request_id UUID REFERENCES maintenance_requests(id),
  evidence_type TEXT NOT NULL CHECK (evidence_type IN ('BEFORE', 'DUPLICATE', 'AFTER', 'OTHER')),
  source_file_id TEXT,
  uri TEXT,
  sha256 TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (ticket_id, evidence_type, source_file_id)
);

CREATE TABLE IF NOT EXISTS technicians (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL UNIQUE,
  skills TEXT[] NOT NULL,
  zones TEXT[] NOT NULL DEFAULT ARRAY['ROOM-POC'],
  certifications TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  active BOOLEAN NOT NULL DEFAULT true,
  rating NUMERIC(3,2) NOT NULL DEFAULT 5.00,
  open_jobs INTEGER NOT NULL DEFAULT 0,
  last_assigned_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS availability_slots (
  id BIGSERIAL PRIMARY KEY,
  technician_id TEXT NOT NULL REFERENCES technicians(id),
  slot_start TIMESTAMPTZ NOT NULL,
  slot_end TIMESTAMPTZ NOT NULL,
  status slot_status NOT NULL DEFAULT 'AVAILABLE',
  reserved_by_ticket BIGINT REFERENCES tickets(id),
  reserved_at TIMESTAMPTZ,
  CHECK (slot_end > slot_start),
  UNIQUE (technician_id, slot_start, slot_end)
);

CREATE TABLE IF NOT EXISTS offers (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT NOT NULL REFERENCES tickets(id),
  technician_id TEXT NOT NULL REFERENCES technicians(id),
  slot_id BIGINT NOT NULL REFERENCES availability_slots(id),
  token TEXT NOT NULL UNIQUE,
  status offer_status NOT NULL DEFAULT 'PENDING',
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  responded_at TIMESTAMPTZ
);

-- The primary race-safety invariant: at most one live offer per ticket.
CREATE UNIQUE INDEX IF NOT EXISTS one_pending_offer_per_ticket
  ON offers (ticket_id) WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS ticket_events (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT REFERENCES tickets(id),
  request_id UUID REFERENCES maintenance_requests(id),
  batch_id UUID REFERENCES processing_batches(id),
  event_type TEXT NOT NULL,
  actor TEXT,
  payload JSONB NOT NULL DEFAULT '{}'::JSONB,
  correlation_id UUID NOT NULL DEFAULT gen_random_uuid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ticket_events_ticket_created_idx
  ON ticket_events (ticket_id, created_at);

CREATE TABLE IF NOT EXISTS cost_rules (
  required_skill TEXT NOT NULL,
  severity INTEGER NOT NULL CHECK (severity BETWEEN 1 AND 5),
  base_min NUMERIC(12,2) NOT NULL,
  base_max NUMERIC(12,2) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'EUR',
  PRIMARY KEY (required_skill, severity),
  CHECK (base_max >= base_min)
);

INSERT INTO cost_rules(required_skill, severity, base_min, base_max) VALUES
  ('carpentry',1,60,120),('carpentry',2,100,220),('carpentry',3,180,400),('carpentry',4,350,800),('carpentry',5,700,1800),
  ('plumbing',1,70,150),('plumbing',2,120,280),('plumbing',3,220,500),('plumbing',4,450,1100),('plumbing',5,900,2500),
  ('electrical',1,80,180),('electrical',2,150,350),('electrical',3,280,650),('electrical',4,550,1300),('electrical',5,1000,3000),
  ('hvac',1,90,200),('hvac',2,180,400),('hvac',3,350,800),('hvac',4,700,1800),('hvac',5,1400,4000),
  ('general',1,50,100),('general',2,90,180),('general',3,160,350),('general',4,300,700),('general',5,600,1500)
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION cbm_log_event(
  p_event_type TEXT,
  p_actor TEXT DEFAULT 'system',
  p_payload JSONB DEFAULT '{}'::JSONB,
  p_ticket_id BIGINT DEFAULT NULL,
  p_request_id UUID DEFAULT NULL,
  p_batch_id UUID DEFAULT NULL
) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE v_id BIGINT;
BEGIN
  INSERT INTO ticket_events(ticket_id, request_id, batch_id, event_type, actor, payload)
  VALUES (p_ticket_id, p_request_id, p_batch_id, p_event_type, p_actor, COALESCE(p_payload, '{}'::JSONB))
  RETURNING id INTO v_id;
  RETURN v_id;
END $$;

CREATE OR REPLACE FUNCTION cbm_approve_batch(p_batch_id UUID, p_actor TEXT)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE v_count INTEGER;
BEGIN
  UPDATE processing_batches
     SET status='APPROVED', fm_decision='APPROVE', fm_actor=p_actor,
         fm_decided_at=now(), updated_at=now()
   WHERE id=p_batch_id AND status='PENDING_FM_APPROVAL';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Batch % is not pending approval', p_batch_id;
  END IF;

  UPDATE tickets
     SET status='READY_FOR_DISPATCH', updated_at=now()
   WHERE batch_id=p_batch_id AND status='PENDING_FM_APPROVAL';
  GET DIAGNOSTICS v_count = ROW_COUNT;

  PERFORM cbm_log_event('BATCH_APPROVED', p_actor,
    jsonb_build_object('released_ticket_count', v_count), NULL, NULL, p_batch_id);
  RETURN v_count;
END $$;

CREATE OR REPLACE FUNCTION cbm_reject_batch(p_batch_id UUID, p_actor TEXT, p_reason TEXT)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE v_count INTEGER;
BEGIN
  UPDATE processing_batches
     SET status='REJECTED', fm_decision='REJECT', fm_actor=p_actor,
         fm_decided_at=now(), updated_at=now(),
         agent_report=COALESCE(agent_report,'{}'::jsonb) || jsonb_build_object('fm_rejection_reason', p_reason)
   WHERE id=p_batch_id AND status='PENDING_FM_APPROVAL';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Batch % is not pending approval', p_batch_id;
  END IF;

  UPDATE tickets SET status='CANCELLED', updated_at=now()
   WHERE batch_id=p_batch_id AND status='PENDING_FM_APPROVAL';
  GET DIAGNOSTICS v_count = ROW_COUNT;

  UPDATE maintenance_requests r
     SET status='RECEIVED', batch_id=NULL, failure_reason=p_reason, updated_at=now()
    FROM batch_items bi
   WHERE bi.batch_id=p_batch_id AND bi.request_id=r.id
     AND r.status='CONVERTED_TO_TICKET';
  PERFORM cbm_log_event('BATCH_REJECTED', p_actor,
    jsonb_build_object('reason', p_reason, 'cancelled_ticket_count', v_count), NULL, NULL, p_batch_id);
  RETURN v_count;
END $$;

-- Reserve one actual slot and create exactly one pending offer.
CREATE OR REPLACE FUNCTION cbm_create_offer(
  p_ticket_id BIGINT,
  p_technician_id TEXT,
  p_expiry_hours INTEGER DEFAULT 4
) RETURNS TABLE(offer_id BIGINT, token TEXT, slot_id BIGINT, slot_start TIMESTAMPTZ, slot_end TIMESTAMPTZ)
LANGUAGE plpgsql AS $$
DECLARE
  v_ticket tickets%ROWTYPE;
  v_slot availability_slots%ROWTYPE;
  v_token TEXT;
  v_offer_id BIGINT;
BEGIN
  SELECT * INTO v_ticket FROM tickets WHERE id=p_ticket_id FOR UPDATE;
  IF NOT FOUND OR v_ticket.status NOT IN ('READY_FOR_DISPATCH','DISPATCHING') THEN
    RAISE EXCEPTION 'Ticket % is not dispatchable', p_ticket_id;
  END IF;
  IF v_ticket.ifc_global_id IS NULL OR v_ticket.ifc_global_id='' THEN
    RAISE EXCEPTION 'Ticket % has no trusted IFC GlobalId', p_ticket_id;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM processing_batches b WHERE b.id=v_ticket.batch_id AND b.status='APPROVED') THEN
    RAISE EXCEPTION 'Ticket % batch is not FM-approved', p_ticket_id;
  END IF;
  IF EXISTS (SELECT 1 FROM offers o WHERE o.ticket_id=p_ticket_id AND o.status='PENDING') THEN
    RAISE EXCEPTION 'Ticket % already has a pending offer', p_ticket_id;
  END IF;

  SELECT s.* INTO v_slot
    FROM availability_slots s
   WHERE s.technician_id=p_technician_id
     AND s.status='AVAILABLE'
     AND s.slot_start >= now()
     AND (v_ticket.sla_deadline IS NULL OR s.slot_start <= v_ticket.sla_deadline)
   ORDER BY s.slot_start
   FOR UPDATE SKIP LOCKED
   LIMIT 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'No available slot for technician %', p_technician_id;
  END IF;

  UPDATE availability_slots
     SET status='RESERVED', reserved_by_ticket=p_ticket_id, reserved_at=now()
   WHERE id=v_slot.id;

  v_token := encode(gen_random_bytes(32), 'hex');
  INSERT INTO offers(ticket_id, technician_id, slot_id, token, expires_at)
  VALUES (p_ticket_id, p_technician_id, v_slot.id, v_token, now() + make_interval(hours=>p_expiry_hours))
  RETURNING id INTO v_offer_id;

  UPDATE tickets SET status='DISPATCHING', updated_at=now() WHERE id=p_ticket_id;
  PERFORM cbm_log_event('OFFER_CREATED','system',
    jsonb_build_object('offer_id',v_offer_id,'technician_id',p_technician_id,'slot_id',v_slot.id),p_ticket_id);

  RETURN QUERY SELECT v_offer_id, v_token, v_slot.id, v_slot.slot_start, v_slot.slot_end;
END $$;

CREATE OR REPLACE FUNCTION cbm_accept_offer(p_token TEXT)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
  v_offer offers%ROWTYPE;
  v_ticket tickets%ROWTYPE;
BEGIN
  SELECT * INTO v_offer FROM offers WHERE token=p_token FOR UPDATE;
  IF NOT FOUND OR v_offer.status<>'PENDING' OR v_offer.expires_at < now() THEN
    RETURN 'INVALID_OR_EXPIRED';
  END IF;

  SELECT * INTO v_ticket FROM tickets WHERE id=v_offer.ticket_id FOR UPDATE;
  IF NOT FOUND OR v_ticket.status<>'DISPATCHING' OR v_ticket.assigned_technician_id IS NOT NULL THEN
    RETURN 'TICKET_NOT_AVAILABLE';
  END IF;

  UPDATE offers SET status='ACCEPTED', responded_at=now() WHERE id=v_offer.id;
  UPDATE availability_slots SET status='BOOKED' WHERE id=v_offer.slot_id AND status='RESERVED';
  UPDATE tickets
     SET status='ASSIGNED', assigned_technician_id=v_offer.technician_id,
         scheduled_start=(SELECT slot_start FROM availability_slots WHERE id=v_offer.slot_id),
         scheduled_end=(SELECT slot_end FROM availability_slots WHERE id=v_offer.slot_id),
         updated_at=now()
   WHERE id=v_offer.ticket_id;
  UPDATE technicians
     SET open_jobs=open_jobs+1, last_assigned_at=now()
   WHERE id=v_offer.technician_id;
  PERFORM cbm_log_event('OFFER_ACCEPTED',v_offer.technician_id,
    jsonb_build_object('offer_id',v_offer.id,'slot_id',v_offer.slot_id),v_offer.ticket_id);
  RETURN 'ACCEPTED';
END $$;

CREATE OR REPLACE FUNCTION cbm_close_offer(p_token TEXT, p_outcome TEXT)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE v_offer offers%ROWTYPE; v_new_status offer_status;
BEGIN
  SELECT * INTO v_offer FROM offers WHERE token=p_token FOR UPDATE;
  IF NOT FOUND OR v_offer.status<>'PENDING' THEN RETURN 'NO_CHANGE'; END IF;
  v_new_status := CASE upper(p_outcome) WHEN 'DECLINED' THEN 'DECLINED'::offer_status ELSE 'EXPIRED'::offer_status END;
  UPDATE offers SET status=v_new_status, responded_at=now() WHERE id=v_offer.id;
  UPDATE availability_slots
     SET status='AVAILABLE', reserved_by_ticket=NULL, reserved_at=NULL
   WHERE id=v_offer.slot_id AND status='RESERVED';
  PERFORM cbm_log_event('OFFER_'||v_new_status::TEXT,'system',
    jsonb_build_object('offer_id',v_offer.id),v_offer.ticket_id);
  RETURN v_new_status::TEXT;
END $$;

CREATE OR REPLACE FUNCTION cbm_mark_escalated(p_ticket_id BIGINT, p_reason TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  UPDATE tickets SET status='ESCALATED', updated_at=now() WHERE id=p_ticket_id AND status='DISPATCHING';
  PERFORM cbm_log_event('DISPATCH_ESCALATED','system',jsonb_build_object('reason',p_reason),p_ticket_id);
END $$;

CREATE OR REPLACE FUNCTION cbm_record_completion_decision(
  p_ticket_id BIGINT, p_actor TEXT, p_decision TEXT, p_reason TEXT DEFAULT NULL
) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE v_status ticket_status;
BEGIN
  SELECT status INTO v_status FROM tickets WHERE id=p_ticket_id FOR UPDATE;
  IF v_status <> 'PENDING_APPROVAL' THEN RETURN 'INVALID_STATE'; END IF;
  IF upper(p_decision)='REJECT' THEN
    UPDATE tickets SET status='REWORK', updated_at=now() WHERE id=p_ticket_id;
    PERFORM cbm_log_event('FM_COMPLETION_REJECTED',p_actor,jsonb_build_object('reason',p_reason),p_ticket_id);
    RETURN 'REWORK';
  END IF;
  UPDATE tickets SET fm_completion_approved_by=p_actor, fm_completion_approved_at=now(), updated_at=now()
   WHERE id=p_ticket_id;
  PERFORM cbm_log_event('FM_COMPLETION_APPROVED',p_actor,'{}'::jsonb,p_ticket_id);
  RETURN 'APPROVED_FOR_IFC';
END $$;

CREATE OR REPLACE FUNCTION cbm_finalize_ifc_success(
  p_ticket_id BIGINT, p_revision TEXT, p_sha256 TEXT
) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_ticket tickets%ROWTYPE;
BEGIN
  SELECT * INTO v_ticket FROM tickets WHERE id=p_ticket_id FOR UPDATE;
  IF v_ticket.fm_completion_approved_at IS NULL THEN
    RAISE EXCEPTION 'FM approval is required before closure';
  END IF;
  UPDATE tickets
     SET status='CLOSED', ifc_revision=p_revision, ifc_sha256=p_sha256,
         closed_at=now(), updated_at=now()
   WHERE id=p_ticket_id;
  UPDATE technicians SET open_jobs=GREATEST(open_jobs-1,0)
   WHERE id=v_ticket.assigned_technician_id;
  PERFORM cbm_log_event('IFC_SYNC_SUCCEEDED','ifc-service',
    jsonb_build_object('revision',p_revision,'sha256',p_sha256),p_ticket_id);
END $$;

CREATE OR REPLACE FUNCTION cbm_finalize_ifc_failure(p_ticket_id BIGINT, p_error TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  UPDATE tickets SET status='IFC_SYNC_PENDING', ifc_sync_attempts=ifc_sync_attempts+1, updated_at=now()
   WHERE id=p_ticket_id AND fm_completion_approved_at IS NOT NULL;
  PERFORM cbm_log_event('IFC_SYNC_FAILED','ifc-service',jsonb_build_object('error',p_error),p_ticket_id);
END $$;

-- Seed PoC technicians and a few future slots. Replace emails and times as needed.
INSERT INTO technicians(id,name,email,skills,zones,rating) VALUES
 ('TECH-CARP-01','Demo Carpenter','carpenter@example.com',ARRAY['carpentry','general'],ARRAY['ROOM-POC'],4.80),
 ('TECH-PLUMB-01','Demo Plumber','plumber@example.com',ARRAY['plumbing','general'],ARRAY['ROOM-POC'],4.70),
 ('TECH-ELEC-01','Demo Electrician','electrician@example.com',ARRAY['electrical'],ARRAY['ROOM-POC'],4.90),
 ('TECH-GEN-01','Demo Generalist','generalist@example.com',ARRAY['general','carpentry'],ARRAY['ROOM-POC'],4.50)
ON CONFLICT DO NOTHING;

INSERT INTO availability_slots(technician_id,slot_start,slot_end)
SELECT t.id,
       ((g.d::date + time '09:00') AT TIME ZONE 'Europe/Rome'),
       ((g.d::date + time '11:00') AT TIME ZONE 'Europe/Rome')
FROM technicians t
CROSS JOIN generate_series(current_date + 1, current_date + 14, interval '1 day') AS g(d)
WHERE extract(isodow from g.d) < 6
ON CONFLICT DO NOTHING;
