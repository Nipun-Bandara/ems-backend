-- Local event projection only. No leave policy/domain tables yet.
CREATE TABLE employee_projection (
    user_id bigint PRIMARY KEY CHECK (user_id > 0),
    active boolean,
    department_id bigint CHECK (department_id > 0),
    reporting_manager_user_id bigint CHECK (reporting_manager_user_id > 0 AND reporting_manager_user_id <> user_id),
    status_at timestamp(6) with time zone,
    assignment_at timestamp(6) with time zone,
    profile_at timestamp(6) with time zone
);
CREATE INDEX idx_employee_projection_manager ON employee_projection (reporting_manager_user_id);
CREATE INDEX idx_employee_projection_department ON employee_projection (department_id);
