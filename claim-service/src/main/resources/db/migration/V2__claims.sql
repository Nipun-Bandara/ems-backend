CREATE TABLE employee_projection (
 user_id bigint PRIMARY KEY CHECK(user_id>0), active boolean NOT NULL DEFAULT false,
 active_at timestamptz NOT NULL DEFAULT '-infinity', department_id bigint,
 assignment_at timestamptz NOT NULL DEFAULT '-infinity', reporting_manager_user_id bigint,
 profile_at timestamptz NOT NULL DEFAULT '-infinity', CHECK(reporting_manager_user_id<>user_id)
);
CREATE TABLE claim_reviewer (
 user_id bigint PRIMARY KEY REFERENCES employee_projection(user_id),
 role varchar(30) CHECK(role IN ('HR_MANAGER')), as_of timestamptz NOT NULL
);
CREATE TABLE claim (
 id uuid PRIMARY KEY, employee_id bigint NOT NULL REFERENCES employee_projection(user_id),
 currency varchar(3) NOT NULL, status varchar(20) NOT NULL DEFAULT 'DRAFT'
 CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','WITHDRAWN')),
 total numeric(19,2) NOT NULL DEFAULT 0 CHECK(total>=0), version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 submitted_at timestamptz
);
CREATE INDEX claim_owner ON claim(employee_id,created_at);
CREATE INDEX claim_pending ON claim(created_at) WHERE status='SUBMITTED';
CREATE TABLE claim_line (
 claim_id uuid NOT NULL REFERENCES claim(id) ON DELETE CASCADE, position integer NOT NULL,
 category varchar(80) NOT NULL CHECK(length(trim(category))>0), expense_date date NOT NULL,
 description varchar(500) NOT NULL CHECK(length(trim(description))>0), currency varchar(3) NOT NULL,
 amount numeric(19,2) NOT NULL CHECK(amount>0), PRIMARY KEY(claim_id,position)
);
CREATE TABLE claim_history (
 id uuid PRIMARY KEY, claim_id uuid NOT NULL REFERENCES claim(id) ON DELETE CASCADE,
 action varchar(20) NOT NULL, actor_id bigint NOT NULL, reason varchar(500) NOT NULL DEFAULT '',
 version bigint NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(), UNIQUE(claim_id,version)
);
