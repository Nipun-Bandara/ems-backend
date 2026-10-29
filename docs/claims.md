# Expense claims and review

## PR description and compatibility

Adds claim-service on port **8087**, owning **claim_db**, with gateway routes at
`/api/claims/**` in development, staging and production (`CLAIM_SERVICE_URL`, default
`http://localhost:8087`). Both repositories use `feat/claim-service`, based on local
development heads backend `8b05c3b` and frontend `dd5ce52`. No deployment or live-data
migration was performed by this task. Compose and database provisioning remain external
because they are outside the requested file scope.

Inspected the actual item 26 implementation in org-service ProjectService/TeamService
(team-owned project membership and transactional domain events) and item 33 in
TimesheetService, ReviewScope, ProjectionHandler, migrations, notification delivery,
OpenAPI and cookie BFF pages. Claims use the same common gateway authentication/error
handling, local replica, Flyway, transactional outbox, idempotent consumer and delivery
conventions. Projects, timesheets and their event contracts are unchanged.

The new module uses existing parent-managed Java 21 / Spring Boot 4.0.8 dependencies:
ems-common; JPA/JDBC and PostgreSQL for domain writes and the shared JPA outbox;
Flyway plus PostgreSQL migrations; MVC, JSON, validation and security for the API;
AMQP and AspectJ for common messaging and idempotent consumers; actuator for health.
Its test dependencies are the existing MVC test and Testcontainers PostgreSQL,
RabbitMQ and JUnit stack. No new versions, frontend packages, shared common changes,
or cross-service database access are introduced. The scaffold retains the existing
optional Lombok dependency, though the claims code uses Java records. Shared storage
is explicitly disabled: this feature has no receipt-upload or attachment requirement.

Notification V8 is additive after existing V7. It creates claim-owned recipient and
pending-delivery tables and copies recipient snapshots already owned by notification-service.
Claims V1 installs the shared outbox schema; V2 installs local replicas, claims,
line items and history. Existing service migrations are unchanged.

## Amounts and transitions

`CLAIM_CURRENCY` defaults to `LKR`; configure an ISO currency with two fractional
minor-unit digits. New claims must use that configured currency, and every line must
match its claim. Existing claims retain their own currency if configuration changes.
No conversion occurs. Amounts and totals are **decimal strings** in the backend
OpenAPI contract (`claim-service/api/openapi.json`) and generated frontend types.
Java BigDecimal and PostgreSQL numeric(19,2) recompute and persist totals; the UI
uses integer minor units for its preview. Positive line amounts accept at most two
decimal places and 17 integer digits; an overflowing total is refused before writes.
Empty drafts are allowed, but submission requires a nonempty claim. At most 100
lines, category 1–80 characters, description 1–500, and a valid expense date.

| State | Permitted next action |
| --- | --- |
| DRAFT | Owner edit/delete or submit |
| SUBMITTED | Owner withdraw; scoped reviewer approve/reject |
| APPROVED | Terminal; approval **for reimbursement**, not confirmation of payment |
| REJECTED | Terminal; retained reason/history; employee may create a separate claim |
| WITHDRAWN | Terminal; retained history |

All writes and history/outbox inserts join one transaction. Draft writes require
owner and version. Submission freezes currency and every line; no reopen route is
provided. Reads hold shared claim locks while assembling line/history responses;
writes hold row locks and transitions check the expected version. Concurrent
approve/reject or approve/withdraw requests have one winner; the other receives 409.
A submit retry returns the current immutable claim without publishing again, even
if review has finished. Withdrawal retries return the withdrawn claim. Review
retries return 409, without another decision or event. History records actor,
action, timestamp, reason and version; rejection requires a nonblank reason.

## Scope, bootstrap and event ordering

Owner APIs require a locally replicated active employee. The reviewer predicate
is identical for inbox, detail and decisions: an active reporting manager from
`employee.profile.updated`, or an active HR reviewer in the local reviewer replica.
Self-review is always forbidden, including HR. Department membership alone and
SYSTEM_ADMIN/FINANCE_MANAGER request headers grant no review privilege. Drafts are
private. Another employee's owner-detail request receives 404; unauthorized review
requests receive 403. The service must be private behind the existing gateway,
which authenticates tokens and strips spoofed identity headers.

Start the claim consumer before exporting existing users. It subscribes to
`user.verified`, `user.assignment.updated`, and `employee.profile.updated`. Unknown
users receive inactive shells so assignment/profile events can arrive before
verification. Independent `active_at`, `assignment_at` and `profile_at` watermarks
prevent an old event or bootstrap snapshot from erasing newer fields. Verification
uses `verifiedAt`, assignment uses `assignedAt`, and profile uses envelope
`occurredAt`, matching their producers. Equal timestamps are no-ops. Shared event-ID
deduplication joins the same transaction. A shared/exclusive advisory lock serializes
scope changes against claim permissions and decisions.

Bootstrap uses authenticated owning-service HTTP APIs, never their databases:

```bash
# Supply a SYSTEM_ADMIN bearer token through CLAIM_BOOTSTRAP_TOKEN (never commit it).
# Pause source employee/profile, user assignment, ban and role writes during export.
python claim-service/scripts/bootstrap.py --gateway http://localhost:8080 > /tmp/claim-scope.json
# Review the snapshot before importing. Keep its original asOf on retries.
python claim-service/scripts/bootstrap.py --gateway http://localhost:8080 --input /tmp/claim-scope.json --apply
```

The script reads assigned and unassigned users, employee status, reporting manager,
department and HR roles through their existing APIs. It imports employees first,
then reviewer records through SYSTEM_ADMIN-only `PUT /api/claims/admin/employees/{userId}`
and `/admin/reviewers/{userId}`. Imports are restartable and versioned; there is no
all-users atomic import. Missing source profiles remain inactive. A reviewer
`role:null` is an ordered revocation. Banned employees are inactive on reconciliation.

**External operating prerequisite:** current upstream contracts have no role-change,
ban or employee-deactivation events. Run/reapply a fresh administrator snapshot when
those fields change; revoke sensitive review access through these admin APIs as
part of that operation. Neither token roles nor a stale bootstrap is a substitute
for reconciliation. This is the same explicit reviewer-replica convention as the
installed timesheet implementation, without changing upstream services.

## API and frontend

The contract contains create, `/me`, `/{id}` GET/PUT/DELETE, `/permissions`,
`/{id}/submit`, `/{id}/withdraw`, `/reviewer/inbox`, `/reviewer/{id}`, and
`/{id}/approve|reject`, plus administrator reconciliation endpoints. Mutations
carry `version`; DELETE uses a query parameter. Money is always a string.

Frontend `/claims` offers draft CRUD, line items, exact totals, submission, status,
withdrawal and history/rejection reasons. `/claims/review` offers the scoped inbox,
frozen detail, approval for reimbursement and rejection with reason. The existing
role-based navigation gains only the Expense claims entry; the review link is
shown after service permissions resolve. Server pages check session and current
scope; the backend independently enforces every read/action. Expired access tokens
use the existing cookie BFF refresh flow. Query keys include the signed-in user;
mutations disable controls and invalidate the relevant claims data. Submitted
claims render read-only tables. Unsaved forms disable submission until saved.

Regenerate types from the backend contract using the installed toolchain:

```bash
# From ems-frontend
npx openapi-typescript ../ems-backend/claim-service/api/openapi.json -o 'app/(user)/claims/contract.d.ts'
```

## Events and notifications

The common outbox publishes `claim.submitted`, `claim.approved`, and
`claim.rejected` with the frozen Claim response inside the common EventEnvelope.
The `notification.claim` consumer stores one logical delivery per `(claim_id,outcome)`
and deduplicates event IDs, including duplicate envelopes with different event IDs.
The claimant receives submission acknowledgment and approval/rejection results;
reviewers use the scoped inbox. SMTP runs in a recovery worker, not the broker
thread. Missing contacts and SMTP errors retain pending delivery with bounded
backoff. Contact snapshots consume ordered `user.verified` events and bootstrap
from notification-service's existing local contacts. Replay missing verified-user
contacts before expecting delivery for users absent from those local snapshots.

A stable Message-ID and SKIP LOCKED prevent concurrent/retry duplication in the
normal delivery path. SMTP acceptance followed by a process/database failure can
still cause a transport redelivery; SMTP is not an exactly-once transaction. No
claim state is changed by delivery. Approved wording explicitly means reimbursement
authorization and does not report a claim paid. No bank transfer code exists here.

## Verification evidence — 2026-10-29, Asia/Colombo

- Initial backend `mvn -B -ntp verify` failed when sandbox Docker access was unavailable.
  Reran with Docker access: **full reactor verify passed**, 201 reported tests,
  **197 executed, 0 failures/errors, 4 intentional skips** (shared storage opt-in and
  leave/timesheet/claim browser fixtures). This includes existing project/timesheet,
  gateway, common security/outbox and all other module baselines.
- Final focused `mvn -B -ntp -pl claim-service,notification-service -am
  -Dtest=ClaimIntegrationTest,ClaimMessagingIntegrationTest,ClaimNotificationIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false verify` passed: **8 tests, no failures/errors**.
  This includes the additional submission/approval notification wording check added
  after the full reactor's notification test compilation.
- ClaimIntegrationTest: two persisted lines `0.10` and `0.20` total exactly `0.30`;
  approval and rejection; positive/precision/currency validation; private drafts;
  stale writes; frozen edits/deletes; self/unrelated review refusal; owner access;
  scoped inbox; submission/review retry counts; concurrent competing decisions and
  withdrawal; ordered bootstrap/profile fields, duplicate events and HR revocation.
- ClaimMessagingIntegrationTest: disposable PostgreSQL 17 + RabbitMQ 4; verified-user
  event builds a replica through the broker, and the actual outbox emits one
  submission and one approval carrying the exact two-line total after submit retry.
- ClaimNotificationIntegrationTest: duplicate event IDs and duplicate logical
  outcomes; delayed contact and SMTP failure/recovery; stable Message-ID; one
  submission/approval delivery each with reimbursement wording. SMTP is mocked.
- `python -B -m unittest discover -s claim-service/scripts -p 'test_*.py'`: passed,
  export/import against disposable owning-service API fixtures, active/inactive and
  HR/null roles, preserved watermark and identical repeated imports. `--help` and
  syntax check passed. Real source-account bootstrap was not run.
- Frontend `npm run lint`: passed with **six existing warnings, zero errors**.
  `npx tsc --noEmit`: passed. Initial production builds failed fetching existing
  Google Fonts in the sandbox; `npm run build` with network access **passed**,
  including both dynamic claims routes. No font configuration was changed.
- Browser verification used the production frontend on localhost:3007, the existing
  cookie BFF and a loopback synthetic identity gateway on 18087, talking to the
  actual claim-service with disposable PostgreSQL/RabbitMQ. Employee created and
  saved both lines; total persisted as `0.30`; submission removed edit controls;
  direct employee reviewer-page access was denied by the server; manager inbox
  showed the claim and approval removed it; employee saw APPROVED with both lines.
  Rejection was disabled until a reason was entered; the saved reason was readable
  in employee history. Pending owner withdrawal visibly became WITHDRAWN.
- Browser identity is deliberately synthetic: a live RS256 login/session chain,
  external SMTP acceptance and real source bootstrap were not exercised. Existing
  gateway auth tests passed in the reactor. Provision a private claim_db, broker,
  scope snapshots and recipient contacts before local end-to-end use.

Reproduce the optional browser fixture (not for deployment): run
`CLAIM_BROWSER_FIXTURE=true mvn -pl claim-service -am -Dtest=ClaimBrowserFixtureTest
-Dsurefire.failIfNoSpecifiedTests=false test`, then
`node claim-service/browser-fixture/gateway.mjs`. Run the built frontend with
`GATEWAY_INTERNAL_URL=http://127.0.0.1:18087 npm start -- --hostname 127.0.0.1 --port 3007`.
Synthetic emails are employee/manager/other/unrelated/hr at `example.test` (the
fixture ignores the password). Write `claim-service/target/browser-fixture/stop`
to stop and remove its disposable test containers. Never route a deployed app to
this synthetic identity fixture.
