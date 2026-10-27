CREATE TABLE leave_type (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0),
    active boolean NOT NULL DEFAULT true
);
CREATE TABLE leave_entitlement (
    id uuid PRIMARY KEY,
    employee_id bigint NOT NULL REFERENCES employee_projection(user_id),
    type_id uuid NOT NULL REFERENCES leave_type(id),
    year integer NOT NULL CHECK (year BETWEEN 1900 AND 9999),
    days numeric(9,2) NOT NULL CHECK (days >= 0),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE(employee_id, type_id, year)
);
CREATE TABLE leave_balance_entry (
    id uuid PRIMARY KEY,
    entitlement_id uuid NOT NULL REFERENCES leave_entitlement(id),
    kind varchar(20) NOT NULL CHECK (kind IN ('ADJUSTMENT', 'TAKEN')),
    days numeric(9,2) NOT NULL CHECK (days <> 0 AND (kind <> 'TAKEN' OR days < 0)),
    idempotency_key varchar(100) NOT NULL,
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE(entitlement_id, idempotency_key)
);
CREATE TABLE leave_reservation (
    id uuid PRIMARY KEY,
    entitlement_id uuid NOT NULL REFERENCES leave_entitlement(id),
    days numeric(9,2) NOT NULL CHECK (days > 0),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'TAKEN')),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX idx_leave_entry_entitlement ON leave_balance_entry(entitlement_id);
CREATE INDEX idx_leave_reservation_active ON leave_reservation(entitlement_id) WHERE status = 'ACTIVE';
CREATE TABLE organization_holiday (
    date date PRIMARY KEY,
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0)
);
-- Enforce append-only audit rows even for accidental application UPDATE/DELETE.
CREATE FUNCTION reject_leave_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'leave audit records are append-only';
END;
$$;
CREATE TRIGGER leave_balance_entry_immutable BEFORE UPDATE OR DELETE ON leave_balance_entry
    FOR EACH ROW EXECUTE FUNCTION reject_leave_audit_mutation();
CREATE TRIGGER leave_entitlement_immutable BEFORE UPDATE OR DELETE ON leave_entitlement
    FOR EACH ROW EXECUTE FUNCTION reject_leave_audit_mutation();
