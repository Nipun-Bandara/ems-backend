-- The six constants of com.ems.identity_service.enums.Role.
--
-- Registration resolves roles by name, so an empty roles table makes every
-- register call that asks for a role fail. Seeded here rather than from code
-- so a fresh database is usable the moment the migrations finish.
--
-- role_id is left to the identity column: nothing references these rows by a
-- fixed id. ON CONFLICT keys on role_name, which uk716hgxp60ym1lifrdgp67xt5k
-- makes unique, so re-running this against a populated table is a no-op.

INSERT INTO roles (role_name, description) VALUES
    ('SYSTEM_ADMIN',     'System administrator'),
    ('DEPARTMENT_HEAD',  'Department head'),
    ('HR_MANAGER',       'HR manager'),
    ('FINANCE_MANAGER',  'Finance manager'),
    ('EMPLOYEE',         'Employee'),
    ('USER',             'Default role for a newly registered user')
ON CONFLICT (role_name) DO NOTHING;
