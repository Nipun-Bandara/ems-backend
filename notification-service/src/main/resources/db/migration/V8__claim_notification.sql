CREATE TABLE claim_notification_contact (
 employee_id bigint PRIMARY KEY, email varchar(320) NOT NULL, as_of timestamptz NOT NULL
);
INSERT INTO claim_notification_contact SELECT employee_id,email,as_of FROM timesheet_notification_contact;
CREATE TABLE claim_notification_delivery (
 delivery_id uuid PRIMARY KEY, event_id uuid NOT NULL UNIQUE, employee_id bigint NOT NULL,
 claim_id uuid NOT NULL, total numeric(19,2) NOT NULL CHECK(total>0), currency varchar(3) NOT NULL,
 outcome varchar(20) NOT NULL CHECK(outcome IN ('SUBMITTED','APPROVED','REJECTED')), reason varchar(500) NOT NULL,
 attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(), sent_at timestamptz, last_error varchar(100),
 UNIQUE(claim_id,outcome)
);
CREATE INDEX claim_notification_pending ON claim_notification_delivery(next_attempt) WHERE sent_at IS NULL;
INSERT INTO notification_template(template_key,subject,body,created_at) VALUES
 ('claim.submitted','Your expense claim was submitted','Your claim {{claimId}} for {{currency}} {{total}} is awaiting review.',now()),
 ('claim.approved','Your expense claim is approved for reimbursement','Your claim {{claimId}} for {{currency}} {{total}} is approved for reimbursement. This does not confirm payment.',now()),
 ('claim.rejected','Your expense claim was rejected','Your claim {{claimId}} for {{currency}} {{total}} was rejected. Reason: {{reason}}.',now());
