# Employee service

`employee-service` listens on port **8083**, uses **employee_db**, and receives
`/api/employees/**` through the gateway. Like org-service, it trusts the gateway's
identity headers, protects its ping, leaves actuator health public, uses Flyway,
and inherits correlation IDs, outbox support, retries and parking from ems-common.

## Run locally

Start the root Docker Compose infrastructure, then copy
`employee-service/.env.example` to `employee-service/.env.development`.
Add `EMPLOYEE_SERVICE_URL=http://localhost:8083` to the gateway's existing
`.env.development` (also configure this variable in staging and production).
Build with `mvn install -DskipTests`, then run `mvn spring-boot:run` from each
service directory: identity-service, employee-service, notification-service
(for the verification email), and api-gateway.

The existing `db/init.sql` already creates `employee_db`. No database reset is
needed. Employee migrations create the common outbox/idempotency tables and
`employee_profile`, with identity shell data, editable profile details, and avatar upload metadata. V4 adds the profile fields and the assignment replica. `user_id` is the identity-service ID, not a generated
employee ID or a cross-database foreign key.

## Event behavior

`employee.q` binds to `ems.events` with `user.registered`, `user.verified`, and `user.assignment.updated`.
Registration and verification resends are acknowledged without creating a row.
On verification, identity-service writes `user.verified` to its transactional
outbox. The shared poller delivers it to RabbitMQ; the employee router calls a
separate `@IdempotentConsumer("employee.user-verified")` handler through Spring's
proxy. The profile insert and processed-event claim commit together.

The shell uses `userId`, `email`, and `username` from the verification payload,
`ACTIVE` status, and `verifiedAt` for `createdAt`. It requires no HTTP call to
identity-service. A repeated event is skipped; a different event for an existing
user also leaves the profile unchanged. Invalid payloads roll back the claim
and use the shared retry/parking policy. Routing uses the envelope type so a
retry with the `employee.retry` routing key still reaches the verified handler.

## Acceptance check

1. Register a new account through `POST http://localhost:8080/api/auth/register`
   with `email`, `username`, and `password`. There should be no employee row yet.
2. Open the email in MailHog at <http://localhost:8025>. Redeem its token through
   `GET http://localhost:8080/api/auth/verify?token=TOKEN`.
3. Wait for outbox delivery (normally a few seconds), then inspect the row:

   ```bash
   docker exec ems-postgres psql -U ems -d employee_db -c \
     "SELECT user_id, email, username, status, created_at FROM employee_profile;"
   ```

4. Verify the link again: the profile count and original data stay unchanged.
   No request to employee-service is involved in steps 1–4.
5. Log in and call `GET http://localhost:8080/api/employees/ping` with the access
   token. It returns `{"service":"employee-service","status":"ok"}`; without
   authentication it returns 401.

`mvn -pl employee-service -am verify` runs the ping/security and topology tests,
plus Docker-backed PostgreSQL/RabbitMQ tests for delivery, duplicates, replay,
rollback/retry, registration ignoring, and retry routing.

## Profile API

All endpoints require authentication. Responses use `Cache-Control: no-store`.

| Endpoint | Access |
| --- | --- |
| `GET /api/employees/me` | Current user |
| `PUT /api/employees/me` | Current user |
| `GET /api/employees/{userId}` | Self, SYSTEM_ADMIN, HR_MANAGER, or a DEPARTMENT_HEAD in the same non-null department |
| `PUT /api/employees/{userId}` | SYSTEM_ADMIN or HR_MANAGER |

PUT replaces editable fields: `firstName` and `lastName` are required (1–50 characters).
Optional fields accept null: `phone` / `emergencyContactPhone` (6–30 characters,
phone characters), `dateOfBirth` (past date), `address` (up to 500 characters),
`emergencyContactName` (up to 100 characters), `designationId` / `reportingManagerUserId`
(positive IDs), and `joinDate` (today or earlier). Blank optional text is rejected;
clients should send null to clear it. Reporting to yourself is rejected.

`email`, `username`, `status`, `departmentId` and `avatarObjectKey` are read-only in
profile PUTs. Avatar keys are validated through the existing upload/confirmation
flow: self, SYSTEM_ADMIN, or HR_MANAGER may upload/confirm a photo. The response
includes the current object key and a short-lived signed `avatarUrl`.

Profile saves and avatar confirmations atomically append `employee.profile.updated`
to the transactional outbox, with the current profile fields, department ID and
avatar object key. Signed URLs are never published.

Identity publishes `user.assignment.updated` with `userId`, nullable `departmentId`,
and `assignedAt` after assignment changes, including department deletion. Employee
consumes it idempotently, retains assignments arriving before verification, and
ignores older assignment timestamps. Per-user transaction locks serialize shell
creation and assignment delivery. Authorization never queries identity.
Identity V7 bootstraps existing assignments through its outbox using the same event
contract. Start employee-service once before identity deployment to ensure its durable
queue is bound before bootstrap events are delivered. Access fails closed while the
replica catches up.

The checked-in OpenAPI contract is `docs/openapi/employee-profile.json`. In the
frontend, run `npm run gen:api:employee` to regenerate `app/types/employee-api.d.ts`.
The profile UI uses those generated schemas for its query and mutation types.

The integration suite also verifies profile completion, database persistence,
outbox publication, another employee's 403, early/stale assignments, and removal
of a department assignment.
