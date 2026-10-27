-- Local authoritative snapshots supplied by SYSTEM_ADMIN; no cross-database joins.
CREATE TABLE leave_reviewer (
    user_id bigint PRIMARY KEY REFERENCES employee_projection(user_id),
    role varchar(30) CHECK (role IN ('DEPARTMENT_HEAD','HR_MANAGER')),
    as_of timestamp(6) with time zone NOT NULL
);
CREATE TABLE leave_request (
    id uuid PRIMARY KEY REFERENCES leave_reservation(id),
    employee_id bigint NOT NULL REFERENCES employee_projection(user_id),
    type_id uuid NOT NULL REFERENCES leave_type(id),
    entitlement_id uuid NOT NULL REFERENCES leave_entitlement(id),
    approver_id bigint NOT NULL REFERENCES employee_projection(user_id),
    start_date date NOT NULL,
    end_date date NOT NULL,
    day_fraction varchar(4) NOT NULL CHECK (day_fraction IN ('FULL','HALF')),
    calculated_days numeric(9,2) NOT NULL CHECK (calculated_days > 0),
    reason varchar(500) NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('PENDING','APPROVAL_IN_PROGRESS','APPROVED','REJECTED','CANCELLED','APPROVAL_FAILED')),
    version bigint NOT NULL DEFAULT 0,
    idempotency_key varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(employee_id,idempotency_key),
    CHECK (employee_id <> approver_id),
    CHECK (end_date >= start_date AND extract(year FROM start_date)=extract(year FROM end_date)),
    CHECK (day_fraction='FULL' OR start_date=end_date)
);
CREATE INDEX idx_leave_request_overlap ON leave_request(employee_id,start_date,end_date)
 WHERE status IN ('PENDING','APPROVAL_IN_PROGRESS','APPROVED');
CREATE INDEX idx_leave_request_approver ON leave_request(approver_id,status);
CREATE TABLE leave_request_action (
    request_id uuid NOT NULL REFERENCES leave_request(id),
    idempotency_key varchar(100) NOT NULL,
    actor_id bigint NOT NULL,
    action varchar(20) NOT NULL,
    reason varchar(500) NOT NULL,
    approver_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(request_id,idempotency_key)
);
