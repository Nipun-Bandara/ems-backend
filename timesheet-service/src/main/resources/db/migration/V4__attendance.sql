CREATE TABLE attendance_session (
 id uuid PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES employee_projection(user_id),
 clock_in timestamptz NOT NULL,
 clock_out timestamptz,
 version bigint NOT NULL DEFAULT 0,
 CHECK (clock_out IS NULL OR clock_out >= clock_in),
 -- Range GiST operators are built into PostgreSQL; no btree_gist extension required.
 EXCLUDE USING gist (int8range(employee_id,employee_id,'[]') WITH &&,
                     tstzrange(clock_in,clock_out,'[)') WITH &&)
);
CREATE UNIQUE INDEX attendance_one_open ON attendance_session(employee_id) WHERE clock_out IS NULL;
CREATE INDEX attendance_history ON attendance_session(employee_id,clock_in DESC,id);
CREATE TABLE attendance_correction_audit (
 id uuid PRIMARY KEY,
 session_id uuid NOT NULL REFERENCES attendance_session(id),
 actor_id bigint NOT NULL,
 corrected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
 before_in timestamptz NOT NULL,
 before_out timestamptz,
 after_in timestamptz NOT NULL,
 after_out timestamptz,
 version bigint NOT NULL,
 UNIQUE(session_id,version)
);
