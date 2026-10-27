ALTER TABLE employee_profile
    ADD COLUMN first_name varchar(50),
    ADD COLUMN last_name varchar(50),
    ADD COLUMN phone varchar(30),
    ADD COLUMN date_of_birth date,
    ADD COLUMN address varchar(500),
    ADD COLUMN emergency_contact_name varchar(100),
    ADD COLUMN emergency_contact_phone varchar(30),
    ADD COLUMN designation_id bigint,
    ADD COLUMN join_date date,
    ADD COLUMN reporting_manager_user_id bigint,
    ADD COLUMN department_id bigint;

-- Retain assignments that arrive before verification, and reject stale deliveries.
CREATE TABLE employee_assignment (
    user_id bigint PRIMARY KEY,
    department_id bigint,
    assigned_at timestamptz NOT NULL
);
