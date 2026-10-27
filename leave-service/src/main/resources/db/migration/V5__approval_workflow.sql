CREATE TABLE leave_workflow (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL REFERENCES leave_request(id),
 operation varchar(10) NOT NULL CHECK (operation IN ('APPROVE','CANCEL')),
 state varchar(30) NOT NULL,
 step_id uuid NOT NULL,
 actor_id bigint NOT NULL,
 reason varchar(500) NOT NULL,
 expected_version bigint NOT NULL,
 idempotency_key varchar(100) NOT NULL,
 attempts integer NOT NULL DEFAULT 0,
 deadline timestamptz NOT NULL DEFAULT (now() + interval '30 seconds'),
 next_attempt timestamptz NOT NULL DEFAULT now(),
 error varchar(500),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(request_id,idempotency_key), UNIQUE(request_id,operation)
);
CREATE INDEX leave_workflow_due ON leave_workflow(next_attempt) WHERE state NOT IN ('APPROVED','APPROVAL_FAILED','CANCELLED');
CREATE TABLE leave_absence (
 request_id uuid PRIMARY KEY REFERENCES leave_request(id),
 workflow_id uuid NOT NULL REFERENCES leave_workflow(id),
 employee_id bigint NOT NULL REFERENCES employee_projection(user_id),
 start_date date NOT NULL,
 end_date date NOT NULL,
 day_fraction varchar(4) NOT NULL
);
CREATE TABLE leave_workflow_audit (
 id bigserial PRIMARY KEY,
 workflow_id uuid NOT NULL REFERENCES leave_workflow(id),
 step_id uuid NOT NULL,
 state varchar(30) NOT NULL,
 actor_id bigint NOT NULL,
 reason varchar(500) NOT NULL,
 attempts integer NOT NULL,
 deadline timestamptz NOT NULL,
 next_attempt timestamptz NOT NULL,
 error varchar(500),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER leave_workflow_audit_immutable BEFORE UPDATE OR DELETE ON leave_workflow_audit
 FOR EACH ROW EXECUTE FUNCTION reject_leave_audit_mutation();
CREATE TRIGGER leave_request_action_immutable BEFORE UPDATE OR DELETE ON leave_request_action
 FOR EACH ROW EXECUTE FUNCTION reject_leave_audit_mutation();
-- Reversals are positive TAKEN entries: sum(TAKEN) continues to be net usage.
ALTER TABLE leave_balance_entry DROP CONSTRAINT leave_balance_entry_check;
ALTER TABLE leave_balance_entry ADD CHECK (days <> 0 AND (kind <> 'TAKEN' OR days < 0 OR idempotency_key LIKE 'reservation:restore:%'));
