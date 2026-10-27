CREATE TABLE employee_projection (
 user_id BIGINT PRIMARY KEY CHECK (user_id > 0), active BOOLEAN NOT NULL,
 version TIMESTAMPTZ NOT NULL
);
-- Append-only history: delayed events must not erase historical membership.
CREATE TABLE project_history (
 project_id BIGINT NOT NULL CHECK (project_id > 0), version TIMESTAMPTZ NOT NULL,
 effective_date DATE NOT NULL, team_id BIGINT NOT NULL CHECK (team_id > 0),
 name VARCHAR(100) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 start_date DATE, end_date DATE, PRIMARY KEY(project_id, version)
);
CREATE INDEX project_history_date ON project_history(project_id, effective_date, version DESC);
CREATE TABLE membership_history (
 team_id BIGINT NOT NULL CHECK (team_id > 0), user_id BIGINT NOT NULL CHECK (user_id > 0),
 version TIMESTAMPTZ NOT NULL, effective_date DATE NOT NULL, member BOOLEAN NOT NULL,
 PRIMARY KEY(team_id, user_id, version)
);
CREATE INDEX membership_history_date ON membership_history(team_id,user_id,effective_date,version DESC);
CREATE TABLE timesheet (
 id UUID PRIMARY KEY, employee_id BIGINT NOT NULL REFERENCES employee_projection(user_id),
 week_start DATE NOT NULL CHECK (extract(isodow FROM week_start)=1),
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), submitted_at TIMESTAMPTZ,
 created_by BIGINT NOT NULL, updated_by BIGINT NOT NULL, submitted_by BIGINT,
 reviewed_at TIMESTAMPTZ, reviewed_by BIGINT, review_reason VARCHAR(500),
 UNIQUE(employee_id,week_start)
);
CREATE TABLE timesheet_entry (
 sheet_id UUID NOT NULL REFERENCES timesheet(id) ON DELETE CASCADE,
 position INTEGER NOT NULL, entry_date DATE NOT NULL,
 project_id BIGINT NOT NULL, minutes INTEGER NOT NULL CHECK(minutes BETWEEN 0 AND 1440),
 description VARCHAR(240) NOT NULL CHECK(length(trim(description))>0),
 PRIMARY KEY(sheet_id,position)
);
CREATE TABLE timesheet_audit (
 id BIGSERIAL PRIMARY KEY, sheet_id UUID NOT NULL, actor_id BIGINT NOT NULL,
 action VARCHAR(20) NOT NULL, version BIGINT NOT NULL, at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE projection_audit (
 id BIGSERIAL PRIMARY KEY, actor_id BIGINT NOT NULL, as_of TIMESTAMPTZ NOT NULL,
 imported_at TIMESTAMPTZ NOT NULL DEFAULT now(), employees INTEGER NOT NULL,
 projects INTEGER NOT NULL, memberships INTEGER NOT NULL
);
