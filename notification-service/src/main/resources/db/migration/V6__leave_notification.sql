-- Leave-specific recipient replica and durable delivery. No leave database access.
CREATE TABLE leave_notification_contact (
 employee_id bigint PRIMARY KEY,
 email varchar(320) NOT NULL,
 as_of timestamptz NOT NULL
);
CREATE TABLE leave_notification_delivery (
 workflow_id uuid PRIMARY KEY,
 event_id uuid NOT NULL UNIQUE,
 employee_id bigint NOT NULL,
 request_id uuid NOT NULL,
 start_date date NOT NULL,
 end_date date NOT NULL,
 attempts integer NOT NULL DEFAULT 0,
 next_attempt timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz,
 last_error varchar(100)
);
CREATE INDEX leave_notification_pending ON leave_notification_delivery(next_attempt) WHERE sent_at IS NULL;
INSERT INTO notification_template(template_key,subject,body,created_at)
 VALUES ('leave.approved','Your leave is approved','Your leave from {{startDate}} through {{endDate}} is approved. Request: {{requestId}}.',now());
