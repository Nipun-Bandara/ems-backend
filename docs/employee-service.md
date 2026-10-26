# Employee service scaffold

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
`employee_profile`, whose only columns are `user_id`, `email`, `username`,
`status`, and `created_at`. `user_id` is the identity-service ID, not a generated
employee ID or a cross-database foreign key.

## Event behavior

`employee.q` binds to `ems.events` with `user.registered` and `user.verified`.
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
