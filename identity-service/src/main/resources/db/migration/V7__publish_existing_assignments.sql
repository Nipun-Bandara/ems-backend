-- Bootstrap employee-service's replica via the same event contract used for new assignments.
-- No cross-database reads are needed; the outbox poller delivers these after commit.
INSERT INTO outbox_event (id, aggregate_type, aggregate_id, type, payload, created_at, attempts, next_attempt_at)
SELECT gen_random_uuid(), 'user', user_id::text, 'user.assignment.updated',
       jsonb_build_object('userId', user_id, 'departmentId', department_id, 'assignedAt', CURRENT_TIMESTAMP),
       CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP
FROM users;
