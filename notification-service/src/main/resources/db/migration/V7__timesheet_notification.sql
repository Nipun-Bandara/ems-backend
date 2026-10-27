CREATE TABLE timesheet_notification_contact (
 employee_id bigint PRIMARY KEY, email varchar(320) NOT NULL, as_of timestamptz NOT NULL
);
-- Reuse recipient snapshots already owned by this service; no upstream database reads.
INSERT INTO timesheet_notification_contact SELECT employee_id,email,as_of FROM leave_notification_contact;
CREATE TABLE timesheet_notification_delivery (
 decision_id uuid PRIMARY KEY, event_id uuid NOT NULL UNIQUE, employee_id bigint NOT NULL,
 sheet_id uuid NOT NULL, week_start date NOT NULL,
 outcome varchar(20) NOT NULL CHECK(outcome IN ('APPROVED','REJECTED')), reason varchar(500) NOT NULL,
 attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(), sent_at timestamptz, last_error varchar(100)
);
CREATE INDEX timesheet_notification_pending ON timesheet_notification_delivery(next_attempt) WHERE sent_at IS NULL;
INSERT INTO notification_template(template_key,subject,body,created_at) VALUES
 ('timesheet.approved','Your timesheet is approved','Your timesheet for {{weekStart}} is approved. Sheet: {{sheetId}}.',now()),
 ('timesheet.rejected','Your timesheet needs changes','Your timesheet for {{weekStart}} was rejected. Reason: {{reason}}. Reopen it from My timesheets. Sheet: {{sheetId}}.',now());
