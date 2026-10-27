# Leave service scaffold

`leave-service` runs on **8085**, owns **leave_db**, and receives `/api/leaves/**`
through api-gateway in development, staging and production. This scaffold adds
only infrastructure and a local employee projection; there are no entitlement,
balance, holiday, request or approval tables yet.

## Proposed policy (domain implementation pending)

The policy supplied for this scaffold is:

- HR assigns an explicit annual entitlement per employee and leave type. No
  automatic accrual or carry-over in this version. Entitlement quantities and
  leave types must be agreed before domain migrations are written.
- Organization timezone: **Asia/Colombo**. Working days: **Monday–Friday**, excluding
  configured organization holidays. Calendar dates are interpreted in that timezone.
- Support full working days and a half-day on one date, with AM/PM selection.
  A half-day cannot span dates. Reject non-working dates and overlapping requests.
- Submission reserves the requested working-day quantity. Available balance is
  entitlement minus approved debits minus pending reservations. Validate and
  reserve atomically to prevent concurrent overspending.
- Approval releases the reservation and debits the same quantity atomically.
  Rejection or cancellation of a pending request releases its reservation.
  Repeated transitions cannot debit or release twice.

Proposals requiring product confirmation before implementation: employees cancel
only pending requests; HR reverses approved requests with an audit reason and
restores the debit; requests crossing annual periods must be split; unassigned
managers fail closed and HR resolves the assignment. No silent admin overrides.
Holiday administration, annual reset timing, approval delegation, entitlement
adjustments and negative-balance exceptions still need explicit decisions.

### Role-to-action matrix

This is the proposed future domain authorization model. Only ping and employee
reconciliation are implemented in this scaffold. Use the repository's exact roles;
there is no generic MANAGER role. Being a reporting manager is a relationship.

| Action | EMPLOYEE / any authenticated employee | DEPARTMENT_HEAD | HR_MANAGER | SYSTEM_ADMIN |
| --- | --- | --- | --- | --- |
| Ping (implemented) | Yes | Yes | Yes | Yes |
| Own balance/history; submit; cancel pending | Self, active projection required | Self | Self | Self |
| View assigned employees' requests | No | Only direct reports in own department | All | No automatic domain access |
| Approve/reject | No | Direct reports in own department; never self | All except self | No automatic domain access |
| Set entitlement; configure holidays; reverse approval | No | No | Yes, audited | No automatic domain access |
| Reconcile employee projection (implemented) | No | No | No | Yes |

FINANCE_MANAGER and USER have only the self-service access of an authenticated
active employee; neither role grants leave administration.

A relationship or department alone grants no approval power. Future method
security must check role, projected department and reporting-manager relationship,
reject self approval, and fail closed for missing/unknown/inactive projection data.
HR's own request requires a different authorized HR approver. This matrix is a
proposal, not a claim that these future endpoints already exist.

## Actual template and compatibility

Inspected the implemented employee profile/CV work and ems-common, rather than
assuming plan item numbers describe contracts. The module copies employee-service's
Boot 4 / Java 21 Maven structure, three profiles, Dockerfile, gateway-header security,
`@EnableMethodSecurity`, shared error responses and correlation logging. Development
and staging validate Hibernate mappings; production keeps the template's `ddl-auto:
none`. Flyway runs in all three profiles. V1 subclasses `OutboxSchemaMigration` and
executes `db/common/outbox.sql`; V2 adds only `employee_projection`.

Dependencies match employee-service: ems-common, JPA, AMQP, Actuator, Web MVC,
Jackson 3, Security, Validation, AspectJ, PostgreSQL and Flyway (including its
PostgreSQL module); Web MVC test and Testcontainers PostgreSQL/RabbitMQ are test
only. AspectJ enables shared idempotent-consumer interception, JPA supports the
shared outbox entities, and JDBC performs atomic projection updates. No new library
or managed version is introduced. Storage is explicitly disabled because this
scaffold has no attachments; future attachments should use ems-common StorageService.
Lombok remains aligned with the template. No cross-database joins or foreign keys.

The current PR's root pom change makes CI select the whole reactor. Subsequent
leave-only PRs need a follow-up CI path-filter entry; `.github` changes are outside
this task's allowed paths. Deployment/Compose wiring is likewise an external step.

## Run and configuration

Use installed Java 21, Maven and Docker. Local Compose infrastructure already
creates leave_db on its first initialization. With an existing volume, an operator
can create the missing empty leave_db without resetting/deleting any volume.

Copy `leave-service/.env.example` to `.env.development` locally. Profiles import
`./leave-service/.env.<profile>` and `./.env.<profile>` as literal properties.
Configure `DB_URL` (leave_db), `DB_USERNAME`, `DB_PASSWORD`, `RABBITMQ_HOST`,
`RABBITMQ_PORT`, `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD`; production/staging
credentials belong in their secret configuration, never in source control.
Set `LEAVE_SERVICE_URL` on the gateway in **every** environment. Its fallback is
`http://localhost:8085`; containers must use the service's reachable network URL.
The gateway's existing JWKS and Redis configuration still applies.

Build from the backend root, then run the leave jar or `mvn spring-boot:run` inside
leave-service. Expose the service only to trusted gateway/internal traffic: like
employee-service, it trusts X-User-Id/X-User-Roles after the gateway validates a JWT
and strips client-supplied identities. Direct public service access is unsafe.
Actuator health is public; `GET /api/leaves/ping` requires authentication and returns
`{"service":"leave-service","status":"ok"}`. Errors use the common shape and
X-Correlation-Id propagates through HTTP and messaging.

## Projection contracts, ordering and gaps

QueueFactory declares durable `leave.q`, `leave.retry`, `leave.parked` and bindings
to the shared events/dead-letter exchanges. Dispatch uses envelope type, including
retry deliveries. Registration events containing verification tokens are not bound.

| Existing event | Fields consumed | Ordering source |
| --- | --- | --- |
| `user.verified` | positive userId, verifiedAt | verifiedAt for initial active=true, matching employee shell behavior |
| `user.assignment.updated` | positive userId, nullable departmentId, assignedAt | assignedAt for department |
| `employee.profile.updated` | positive userId, nullable reportingManagerUserId | envelope occurredAt for reporting manager |

Profile events contain departmentId, but it can be stale relative to identity
assignment; leave ignores it. Missing nullable fields are invalid; explicit null
clears the corresponding relationship. Unknown extra producer fields are ignored.
Only required projection values are persisted, without contact information or tokens.
An assignment/profile can arrive before verification: active remains unknown (NULL),
which future leave operations must treat as ineligible until verification/reconciliation.

`@IdempotentConsumer("leave.employee-projection")` commits the processed-event claim
and projection updates in one transaction. Invalid input rolls both back for the
shared retry/parking policy. A per-user transaction advisory lock serializes all
streams and reconciliation. Independent status/assignment/profile timestamps accept
only strictly newer updates; older and equal-time updates cannot overwrite state.
Timestamps are stored at PostgreSQL microsecond precision. Equal-time conflicts are
first-writer-wins; upstream clocks and timestamp precision remain a limitation of
the existing event contracts, which have no monotonic aggregate version.

**Producer gaps:** employee.profile.updated currently omits status, and profile
creation emits no employee snapshot event. No existing event communicates later
active/inactive transitions, and no producer replay endpoint exists. This task's
file allowlist excludes both producers, so no producer was modified. Verification
covers new employees' current ACTIVE shell semantics; authoritative status for
existing employees is supplied by reconciliation. Full automatic lifecycle sync
requires a follow-up in employee-service: publish an additive status field plus an
aggregate revision on every profile creation/status change/update, with a replay
operation using the transactional outbox. Existing consumers ignore extra fields,
but verify that compatibility in that producer PR. Until then, reconcile status
changes operationally; do not claim deactivations arrive automatically.

## Idempotent bootstrap and reconciliation

`PUT /api/leaves/admin/employees/{userId}` is SYSTEM_ADMIN-only through method
security. It returns 204. Example body (use the actual snapshot timestamp):

```json
{"active":true,"departmentId":7,"reportingManagerUserId":99,"asOf":"2026-10-27T05:00:00Z"}
```

userId/relationship IDs must be positive; no self manager; active and asOf are
required; future timestamps are rejected. Null relationship IDs clear them. One
transaction updates each stream only if asOf is strictly newer than that stream's
watermark. Repeating the exact snapshot is a no-op; replaying it after newer live
events cannot revert those fields. 204 means accepted, including a stale no-op.
No outbox message is emitted for this private local replica.

Procedure for employees predating this consumer:

1. Start leave-service first and confirm leave.q/bindings exist. Pause assignment,
   profile and status writes for the reconciliation window and drain existing
   producer outboxes/consumer queues (including pending retries or parked events).
   This establishes a consistent snapshot boundary without cross-service revisions.
2. A SYSTEM_ADMIN enumerates identity users via
   `GET /api/users?assigned=true&page=0&limit=100`, incrementing page until
   `hasNext=false`. Despite its name, assigned=true currently returns all users
   (`UserServiceImpl.getUsers`); assigned=false returns only unassigned users.
   Retrieve each employee via `GET /api/employees/{userId}`. A 404 means no
   employee shell: record/exclude that ID and investigate any expected employee
   whose verification has not produced a shell. Get the current department from
   the identity response, not a database read.
   Check actual status and reporting manager from the employee response. Build a
   minimal manifest of the four projection values; do not copy personal profile data.
3. Choose one UTC asOf after the drained writes and snapshot reads, before writes
   resume. Keep producer clocks synchronized. For each manifest row call the PUT
   through the gateway with a SYSTEM_ADMIN bearer token. Save the exact manifest
   and timestamp securely for retries; never timestamp a stale manifest as "now".
4. Repeat failed rows with the same timestamp/body. Confirm expected counts and
   values in leave_db using leave-owned operational access, then resume writes.
   Live events after the boundary apply normally. Repeat the procedure to reconcile
   drift or status changes. Do not wipe processed_event, replay registration tokens,
   reset migrations or query another service's database.

The existing APIs allow enumeration plus per-employee reads, but lack an atomic
verified-employee snapshot/replay API, hence the quiesced boundary above. Identity V7's old
assignment bootstrap already ran for some installations and cannot populate a
new queue retroactively. The endpoint is usable now without reapplying V7. A fully
automated source export/replay remains a producer follow-up outside the allowlist.

## Verification commands

From the backend root with Docker available:

```bash
mvn -B clean verify
mvn -B -pl api-gateway,leave-service -am verify
```

The focused command includes gateway packaging because LeaveIntegrationTest starts
that actual jar in each profile. It creates an ephemeral RSA key/JWKS fixture and
checks real JWT validation and HTTP forwarding to the running leave service. It
uses temporary PostgreSQL/RabbitMQ containers and never requires live databases,
real credentials or changes to an existing employee. Gateway logs are in
`leave-service/target/gateway-{development,staging,production}.log`.

### Evidence from this change (27 October 2026)

- Baseline `mvn -B clean verify` passed before the scaffold was added.
- Initial focused `mvn -B -pl leave-service -am spotless:apply test` passed,
  including 8 leave tests before the gateway/concurrency additions.
- Final `mvn -B clean verify` passed for the full reactor: 159 tests reported,
  158 executed successfully, zero failures/errors, one existing opt-in storage
  smoke test skipped. All **10 leave tests** executed successfully.
- PostgreSQL 17: a fresh database named leave_db applied V1 (shared outbox and
  processed-event tables) and V2 (employee projection). Hibernate validation passed.
- RabbitMQ 4: leave.q, leave.retry and leave.parked existed. Real broker delivery,
  duplicate claim suppression and retry-envelope routing passed. Separate tests
  covered older/equal events, null clearing, concurrent ordering, transaction
  rollback and same-ID retry, and repeated bootstrap followed by newer live events.
- Packaged gateway, each of development/staging/production: valid RS256 JWT ping
  returned 200; anonymous requests with spoofed identity headers returned 401;
  invalid tokens returned 401; correlation ID was preserved end to end.
- Reconciliation method security denied ordinary/HR/department-head users, allowed
  SYSTEM_ADMIN, and rejected self-manager/future-date snapshots. An existing
  synthetic employee was populated without any original verification replay.

The root's existing StorageIntegrationTest requires STORAGE_INTEGRATION_TEST=true
and a Compose MinIO on localhost:9000; it was not enabled. Employee-service's
separate container-backed MinIO integration tests did execute successfully. Leave
has storage disabled. No live employee export, live reconciliation, real identity
login, deployment networking or production database was exercised. Those require
operator access and the documented snapshot boundary. Automatic status lifecycle
sync and automated producer replay remain the explicitly documented producer gaps.

Detailed logs for this run are in leave-service/target/root-verify.log, alongside
the three gateway logs and Surefire XML reports. The PR description draft is
leave-service/PR.md; it includes dependency and compatibility notes.
