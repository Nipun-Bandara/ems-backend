-- Independent field clocks prevent late profile events overwriting identity assignments.
ALTER TABLE employee_projection ADD COLUMN department_id bigint,
 ADD COLUMN reporting_manager_user_id bigint, ADD COLUMN assignment_at timestamptz,
 ADD COLUMN profile_at timestamptz;
CREATE TABLE timesheet_reviewer (
 user_id bigint PRIMARY KEY REFERENCES employee_projection(user_id),
 role varchar(30) CHECK(role IN ('DEPARTMENT_HEAD','HR_MANAGER')), as_of timestamptz NOT NULL
);
CREATE TABLE timesheet_decision (
 id uuid PRIMARY KEY, sheet_id uuid NOT NULL REFERENCES timesheet(id),
 outcome varchar(20) NOT NULL CHECK(outcome IN ('APPROVED','REJECTED')),
 reviewer_id bigint NOT NULL, decided_at timestamptz NOT NULL DEFAULT now(),
 reason varchar(500) NOT NULL, version bigint NOT NULL,
 UNIQUE(sheet_id,version), CHECK(outcome <> 'REJECTED' OR length(trim(reason))>0)
);
CREATE INDEX timesheet_inbox ON timesheet(employee_id,week_start) WHERE status='SUBMITTED';
