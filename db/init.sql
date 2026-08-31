-- Local development bootstrap for the composed Postgres instance.
--
-- Runs once, on first start, via /docker-entrypoint-initdb.d. It only executes
-- while the postgres named volume is empty; to re-run it, drop the volume:
--
--     docker compose down -v && docker compose up -d
--
-- One database per service. Every service shares the single POSTGRES_USER
-- created by the entrypoint, which is fine locally -- real environments give
-- each service its own credentials.

CREATE DATABASE identity_db;
CREATE DATABASE org_db;
CREATE DATABASE employee_db;
CREATE DATABASE leave_db;
CREATE DATABASE timesheet_db;
CREATE DATABASE claim_db;
CREATE DATABASE recruitment_db;
CREATE DATABASE engagement_db;
CREATE DATABASE document_db;
CREATE DATABASE notification_db;
