# Leave types, working calendar, balances and requests

`leave-service` owns leave_db, runs on port 8085 and receives `/api/leaves/**`
through the existing gateway routes. This increment adds explicit yearly allocations,
an append-only balance ledger, a working calendar and administration/self-balance UI.
This opening section records item 28. Item 29 request APIs and transitions are documented below; approval execution remains deferred to item 30. Accrual, carry-over and automatic yearly jobs are deferred.

## Stable API v1

Machine-readable contract: [leave.openapi.json](../leave-service/api/leave.openapi.json).
All paths below are relative to `/api/leaves`. Bodies are JSON. UUIDs are strings;
employee/actor IDs are identity user IDs. Days in responses are signed decimal strings
with exactly two fraction digits, never floating point. Inputs accept at most seven
integer digits and two fraction digits; send strings to preserve precision. Years
must be 1900–9999. Lists are JSON arrays; an unallocated employee/year returns `[]`.

| Method and path | Access | Body / response |
| --- | --- | --- |
| GET `/types` | Authenticated | All type records `{id,code,name,active}`, including inactive |
| POST `/types` | HR_MANAGER, SYSTEM_ADMIN | `{code,name,active}` → type, 201 |
| PUT `/types/{id}` | HR_MANAGER, SYSTEM_ADMIN | Same full body → type, 200 |
| GET `/balances/me?year=2026` | Active employee, self only | Balance array, 200; year defaults to organization-local current year |
| GET `/entitlements?employeeId=42&year=2026` | HR_MANAGER, SYSTEM_ADMIN | Balance array, 200; both parameters required |
| POST `/entitlements` | HR_MANAGER, SYSTEM_ADMIN | `{employeeId,typeId,year,days,reason}` → balance, 201 |
| POST `/entitlements/{id}/adjustments` | HR_MANAGER, SYSTEM_ADMIN | `{days,idempotencyKey,reason}` → ledger entry, 200 |
| GET `/entitlements/{id}/entries` | HR_MANAGER, SYSTEM_ADMIN | Ledger entry array ordered by createdAt/id |
| GET `/calendar` | Authenticated | `{timezone,workweek}` |
| GET `/holidays?year=2026` | Authenticated | `[{date,name}]`, ISO local dates |
| PUT `/holidays` | HR_MANAGER, SYSTEM_ADMIN | `{date,name}`, upsert by date, 204 |
| DELETE `/holidays/{date}` | HR_MANAGER, SYSTEM_ADMIN | Remove configured date, 204 |

Type codes are unique uppercase identifiers matching `^[A-Z][A-Z0-9_]{0,39}$`.
Names must be nonblank, at most 100 characters. Types are deactivated rather than
removed; their existing balances/history remain visible. Inactive types cannot
receive new allocations/reservations, but existing balances can be corrected/settled.
Reasons are required, nonblank and limited to 500 characters. Actors always come
from the authenticated gateway principal, never from request JSON.

A balance has `{id,employeeId,typeId,typeName,year,entitlement,adjustments,taken,reserved,available}`.
`taken` and `reserved` are positive quantities; `adjustments` is signed.
`available = entitlement + adjustments - taken - reserved`.
A ledger entry has `{id,entitlementId,kind,days,idempotencyKey,actorId,reason,createdAt}`;
kind is ADJUSTMENT (signed days) or TAKEN (negative days). Timestamps are UTC instants.

Allocation is immutable and unique per employee/type/year. A duplicate allocation
returns 409; correct it with an adjustment rather than overwriting the audit record.
Zero-day allocations are valid; zero adjustments are invalid. Adjustment keys are
required, at most 100 characters and scoped to the entitlement. An identical retry
(same actor, numeric days and trimmed reason) returns the original entry without
another outbox event. Reuse with different input returns 409. The `reservation:`
key prefix is reserved for internal settlement entries and returns 400 if used by
an adjustment. Clients must preserve their key across retries, including uncertain
network outcomes. The UI preserves it for retries of unchanged form input until a
successful response; closing/reloading the form ends that retry context.

Common errors retain ems-common's ErrorResponse (status/error/message/path/timestamp
and correlation handling): 400 invalid body/precision/year, 401 missing authentication,
403 insufficient role or missing/inactive employee projection, 404 unknown resource,
409 duplicate allocation/type code, conflicting retry key or insufficient available
balance. Invalid bodies can return 400 before method authorization is evaluated.
Employees cannot choose another identity on `/balances/me`; the principal is the
only employee selector. Arbitrary `employeeId` query parameters do not change it.
Privileged entitlements/history routes enforce roles server-side through Spring
method security, including calls sent directly through the BFF without the UI.

## Persistence and concurrency

Flyway V3 adds leave_type, leave_entitlement, leave_balance_entry, leave_reservation
and organization_holiday in the leave database only. Days use PostgreSQL numeric(9,2)
and Java BigDecimal. Foreign keys reference only leave-owned tables/projections.
Database triggers reject UPDATE/DELETE on entitlements and ledger rows. Corrections
are additive entries; schema migration/restore procedures must respect that audit.

Every adjustment, reservation and settlement first locks its entitlement row with
`SELECT FOR UPDATE`, then reads the committed ledger and active reservations in the
same transaction. A request that would overdraw returns 409 before writing. All
application balance writers must use this lock protocol. Unique constraints provide
a second guard against duplicate allocation/adjustment keys. This is a lock-protocol
invariant, not a cross-table SQL CHECK that would protect arbitrary manual INSERTs.

The internal `reserve(entitlementId,reservationId,days,actor,reason)` and
`settle(entitlementId,reservationId,taken,actor,reason)` methods are integration seams
for a later request workflow, with no public reservation HTTP endpoint. Reservation
IDs make retries idempotent. ACTIVE → TAKEN releases the reservation and appends one
debit atomically; ACTIVE → RELEASED restores availability. Identical terminal retries
are no-ops; changing terminal outcome returns 409. Date overlap, AM/PM selection,
manager approvals and cancellation/reversal authorization belong to that later workflow.
The balance layer accepts decimal quantities; it does not invent a half-day-only rule.

## Calendar configuration

`ORGANIZATION_TIMEZONE` defaults to **Asia/Colombo** and
`ORGANIZATION_WORKWEEK` defaults to **MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY**.
Both are application configuration under `ems.leave`; invalid timezone/day names
fail startup. Restart consistently across instances after changing policy. Workweek
values use java.time DayOfWeek names. The calendar counts inclusive local dates,
excludes configured holidays/weekends and rejects reversed or cross-year ranges.
Callers deriving dates from instants must use the configured organization timezone.

V3 seeds no holidays, types or entitlements. Operators must supply the approved
organization holiday calendar through privileged holiday endpoints. The opt-in
`leave-service/src/main/resources/fixtures/development-holidays.sql` contains one
explicitly synthetic 2099 date for a disposable development database. It is outside
Flyway locations and never auto-runs in any profile. No production dates are assumed.

## Events and compatibility

Allocation and adjustment insert events using ems-common OutboxPublisher inside
the domain transaction. The shared poller publishes to ems.events with routing keys:

- `leave.entitlement.allocated`: `{entitlementId,employeeId,typeId,year,days,actorId,reason}`.
- `leave.balance.adjusted`: the immutable ADJUSTMENT entry shape documented above.

Both use the existing EventEnvelope `{eventId,type,occurredAt,correlationId,payload}`.
Delivery is at least once; consumers deduplicate eventId. Decimal days are strings.
Configure a durable consumer binding for these new routing keys before operational
use; the shared mandatory publisher reports unroutable messages and retains/retries
them according to existing outbox policy. No producer/consumer outside leave-service
was changed. No reservation event contract is introduced in this increment.

No new dependency or version is introduced. The code reuses installed Java 21,
Boot 4, JDBC/JPA transactions, PostgreSQL/Flyway, Security/Validation, Jackson 3,
AMQP and ems-common security/errors/outbox. Storage stays disabled because there
are no attachments. Existing projection/outbox migrations and events are unchanged;
V3 is additive. Roll out backend/migration before the frontend. Previous frontend
versions are unaffected; the new page requires these new endpoints.

The frontend uses the existing Button/theme, React Hook Form/Zod validation,
TanStack Query and cookie-only Axios/BFF. Query keys include the authenticated user
so cached balances/history do not cross sessions. UI role checks hide administration;
the backend independently enforces every privileged operation. No client token store,
BFF bypass or shared-database lookup is added. Installed Next.js 16.2.4 guidance on
client boundaries, forms and authorization was consulted. Scope excludes proxy.ts;
the existing authenticated user layout and server API enforcement cover the new page.

Regenerate the frontend types with the installed generator from the frontend root:

```bash
./node_modules/.bin/openapi-typescript ../ems-backend/leave-service/api/leave.openapi.json -o app/types/leave-api.d.ts
```

No package.json change is needed. Deployments still need the existing gateway
LEAVE_SERVICE_URL wiring and CI leave-service path selection; those files are
outside this change's allowlist. Both branches were already named
feat/leave-types-and-balances at the local development tips when work began.

## Run and employee eligibility

Use installed Java 21, Maven and Docker. Configure leave-owned DB_URL, DB_USERNAME,
DB_PASSWORD and RABBITMQ_HOST/PORT/USERNAME/PASSWORD through the existing profile
imports; keep credentials out of source. Do not reset volumes to apply V3.
Set LEAVE_SERVICE_URL on the gateway. Restrict direct service access to trusted
internal traffic: it trusts X-User-Id/X-User-Roles after the gateway validates the JWT
and strips spoofed headers. Health is public; ping requires authentication.

Allocation and new reservations require a known active employee projection; self
balance reads also fail closed if active status is absent/false. HR can inspect or
correct existing balances for inactive employees. Existing employees/status changes
still require the established reconciliation procedure below. No shared database
access or automatic lifecycle synchronization is added.

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
which leave allocation/reservation and self-balance operations treat as ineligible until verification/reconciliation.

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

## Verification

From the backend root, with Docker available:

```bash
mvn -B verify
mvn -B -pl api-gateway,leave-service -am verify
```

The focused verification includes gateway packaging because LeaveIntegrationTest
starts the actual gateway jar with ephemeral RSA/JWKS fixtures in development,
staging and production. Tests use isolated PostgreSQL 17 and RabbitMQ 4, never
existing development or production databases. Surefire reports and gateway logs
are under module target directories.

From the frontend root:

```bash
npm run lint
npx tsc --noEmit
npm run build
```

The existing Next font setup needs Google Fonts network access for a fresh build.

### User-facing acceptance procedure

1. Reconcile a synthetic active employee in a disposable database. Sign in as HR
   or SYSTEM_ADMIN, open Leave Management and create an active Annual leave type.
2. Load that employee/year, allocate 10.00 days with an allocation reason, then
   apply +2.00 with a justified adjustment reason. Confirm actor/reason in history.
3. Reload the page and confirm 12.00 available, 0.00 taken and 0.00 reserved. Sign
   in as that employee: only own balances and the administration access notice appear.
4. As employee, POST entitlement/adjustment/type mutations and GET another employee's
   privileged entitlement/history route: expect 403 with valid request input. Changing
   an employeeId parameter on /balances/me must never select another user's data.
5. Concurrently reserve the last 1.00 day with two distinct reservation IDs using the
   internal service seam: exactly one succeeds, one returns 409. Retry adjustment
   requests with the same key concurrently: one ledger entry and one event remain.
6. Check an employee/year without allocation, loading state, invalid form values,
   API 403/temporary failure, retry recovery, inactive type editing and ledger history.

### Disposable browser fixture

From the backend root, start the opt-in test (stops when the stop file appears or
its one-hour safety window expires):

```bash
LEAVE_BROWSER_FIXTURE=true mvn -B -pl leave-service -am test -Dtest=LeaveBrowserFixtureTest -Dsurefire.failIfNoSpecifiedTests=false
```

In another terminal from the backend root run
`node leave-service/browser-fixture/gateway.mjs`; from the frontend root run
`GATEWAY_INTERNAL_URL=http://127.0.0.1:18080 npm run dev -- --port 13000`.
These are test-only local services. The proxy binds to loopback and intentionally
uses synthetic identities, never operational tokens. Sign in at localhost:13000/auth
as hr@example.test, employee@example.test, empty@example.test or
breakdown@example.test with any synthetic nonempty password of at least four
characters. HR and employee are the same user 6101 with different test roles;
empty is 6102; breakdown is 6103 with 10.00 entitlement, 2.00 taken, 1.00 reserved,
7.00 available. The real identity service is not used by this fixture.

Optional fault injection: put `{"status":503}` or `{"status":403}` in
`leave-service/target/browser-fixture/control.json`, then reload. Use `{}` to clear;
`{"delay":1500}` delays leave calls for loading-state inspection. Stop the fixture
by creating `leave-service/target/browser-fixture/stop` and stop the proxy/frontend
processes. Containers are owned and removed by Testcontainers; no existing volumes
are touched. Do not route production traffic through this test proxy.

### Evidence for this increment

On 27 October 2026:

- Clean baseline: archived the original development commit 74d2bfc to an isolated
  temporary directory and ran `mvn -B -pl api-gateway,leave-service -am verify`.
  BUILD SUCCESS, 26 tests reported, 25 executed successfully, zero failures/errors,
  one existing opt-in shared storage skip. No feature source was present in that
  snapshot. The temporary checkout was removed after saving reports and logs.
- Full `mvn -B verify`: BUILD SUCCESS across all modules; Surefire reports 164 tests,
  162 executed successfully, zero failures/errors, two skips (the existing opt-in
  shared storage smoke test and the deliberately opt-in browser fixture).
- Focused `mvn -B -pl api-gateway,leave-service -am verify`: BUILD SUCCESS. All 14
  ordinary leave tests passed (11 integration plus 3 MVC), including fresh V1–V3
  migration, 10.00 allocation + 2.00 audited adjustment → reloaded 12.00, employee
  mutation/cross-employee read denial, parallel adjustment retry deduplication,
  last-day reservation concurrency, negative adjustment rejection, settlement/release
  retry safety, append-only UPDATE/DELETE rejection, decimal precision, workweek and
  holiday exclusion. Allocation/adjustment events reached a real RabbitMQ audit
  queue through the shared outbox. Existing projection and three-profile real JWT
  gateway checks passed as part of the same run.
- Earlier full verification exposed a RabbitMQ 4 restriction on a transient,
  nonexclusive test audit queue. The fixture now declares a durable queue. Both
  focused and full verification passed after that correction; the earlier failed
  attempt is not counted as passing evidence.
- Frontend lint: zero errors, six pre-existing warnings outside the changed files.
  `npx tsc --noEmit` and `npm run build` passed. Initial restricted-network build
  failed on the repository's Google Font downloads; the network-enabled run passed.
  Regenerating the contract types produced byte-for-byte identical output.
- Browser verification used the real Next app, cookie-based BFF and actual leave
  service against disposable PostgreSQL/RabbitMQ. Only identity/login and gateway
  forwarding were synthetic fixtures; production identity login was not exercised.
  HR created Annual leave, allocated 10.00, applied a justified +2.00, saw the actor
  and reason in ledger history, and reloaded to 12.00. Employee-role sign-in showed
  the same own balance with no administration forms. Loading, zero/missing-reason
  form validation, temporary API failure and successful Retry balances recovery
  were observed. Type deactivation preserved the existing 12.00 balance. An
  unallocated year showed the empty state, a synthetic 403 showed the authorization
  message, and employee 6103 displayed 2.00 taken, 1.00 reserved and 7.00 available.
  A browser origin review briefly timed out; its permitted retry
  succeeded and verification resumed.

Logs are under `leave-service/target/verification/`; Surefire reports remain in each
module's target directory (also snapshotted in target/verification/full-surefire). Browser screenshots are under
`leave-service/target/browser-fixture/` (hr-reloaded-12.png, employee-12.png and
adjustment-history.png, plus employee-breakdown.png). These generated logs/screenshots are ignored build outputs.
The old scaffold PR's historical evidence is not represented as new verification.

External prerequisites: approved production holidays and actual entitlement amounts;
operator-controlled reconciliation for real employee/status inventory; gateway/network
and CI wiring outside the allowlist; durable downstream bindings for new leave events.
The existing opt-in shared Compose MinIO storage smoke test is unrelated and requires
STORAGE_INTEGRATION_TEST=true. No production deployment, real employee inventory,
real identity account login, or live database migration is part of this verification.

## Item 29: leave requests

Item 29 adds submission, calendar estimates, own history, scoped review, rejection,
cancellation and HR routing. No endpoint executes approvals. `APPROVED` is never
produced by this increment. The earlier sections describe item 28 and its evidence;
they are retained as historical context, not evidence for item 29.

### Request API and policy

The v1.1 additive contract remains in `leave-service/api/leave.openapi.json`.
All paths are relative to `/api/leaves`; all require gateway authentication.

| Method/path | Input / access |
| --- | --- |
| POST `/requests/estimate` | `{startDate,endDate,dayFraction}` → `{calculatedDays}`; calendar only, no balance guarantee |
| POST `/requests` | `{typeId,startDate,endDate,dayFraction,reason,idempotencyKey}`; active employee submits for principal only, 201 |
| GET `/requests/me` | Principal's full history, newest first |
| GET `/requests/inbox` | Pending requests assigned to the current eligible reviewer; active HR_MANAGER also gets pending requests for routing; excludes own |
| GET `/requests/{id}` | Owner, assigned currently eligible reviewer, or active HR_MANAGER; other identities get 403 |
| POST `/requests/{id}/reject` | `{reason,idempotencyKey}`; assigned currently eligible reviewer only |
| POST `/requests/{id}/cancel` | `{reason,idempotencyKey}`; owner only |
| POST `/requests/{id}/route` | `{approverId,reason,idempotencyKey}`; active HR_MANAGER only, another employee's pending request, different eligible target |
| PUT `/admin/reviewers/{userId}` | SYSTEM_ADMIN reconciles `{role,asOf}` after employee reconciliation; role DEPARTMENT_HEAD, HR_MANAGER or null to revoke |

Request responses contain id, employeeId, typeId, entitlementId, approverId,
startDate, endDate, dayFraction, calculatedDays (fixed decimal string), reason,
status, version, createdAt and updatedAt. Dates are inclusive organization-local
ISO dates. Audit timestamps are UTC instants; version starts at zero and increments
on each state or approver change. Action actor, reason, key and time are retained
in leave_request_action; submission reason is immutable. There is no browser day
count field. Estimates and submissions both call item 28's WorkingCalendar.
Submission always recalculates even if the preview was obtained earlier.

FULL charges working dates, excluding configured holidays. HALF is exactly 0.50
and requires the same single working start/end date. Zero-working-day, reversed,
cross-year and out-of-supported-year (1900–9999) ranges return 400. Split a
cross-year request into separate yearly requests. Date intervals cannot overlap
any PENDING, APPROVAL_IN_PROGRESS or APPROVED request for the same employee,
including across types. Half-day intervals occupy the entire date for overlap
purposes; AM/PM selection and combining two half-day requests are not supported.

### Transition table

| From | Operation / actor | To | Reservation |
| --- | --- | --- | --- |
| absent | submit / active employee | PENDING | Reserve calculated days |
| PENDING | reject / assigned eligible reviewer | REJECTED | Release exactly once |
| PENDING | cancel / owner | CANCELLED | Release exactly once |
| PENDING | route / active HR_MANAGER, never own request | PENDING | Keep; change approver, increment version |
| CANCELLED | repeat cancel / owner | CANCELLED | No-op; no new state event |
| any | identical recorded submission/action retry | current persisted state | No-op; no new reservation or event |
| PENDING | approval execution, item 30 only | APPROVAL_IN_PROGRESS | Deferred |
| APPROVAL_IN_PROGRESS | item 30 only | APPROVED or APPROVAL_FAILED | Deferred |
| APPROVED | cancellation/reversal, item 30 only | Deferred | Deferred |
| REJECTED / APPROVAL_FAILED / other unlisted source | new mutation in item 29 | 409 | Unchanged |

The schema recognizes all six states for compatibility with item 30. No operation
in item 29 enters APPROVAL_IN_PROGRESS, APPROVED or APPROVAL_FAILED. Rejection is
also restricted to PENDING because approval execution must own in-progress races.
No approval button is enabled, and there is no approval endpoint.

### Routing and the local projection prerequisite

Resolve the employee's reporting_manager_user_id from the local projection first;
the manager must be active and different from the employee. A manager can review
without an identity DEPARTMENT_HEAD role. When no active projected manager exists,
choose an active same-department DEPARTMENT_HEAD from leave_reviewer (lowest user
ID), then an active HR_MANAGER from that table (lowest user ID). A null department
never matches a department head. With no candidate, return 409 before reservation.
Current active status, manager relationship and fallback department/role eligibility
are checked again for detail access and rejection. A former reviewer loses access;
HR can route stranded pending requests. HR reading/routing requires the trusted
HR_MANAGER principal role and an active employee projection; SYSTEM_ADMIN alone
does not grant access to employees' request contents.

Existing item 28 producer events do not carry reviewer roles. This change does not
query another service's database or silently infer department-head roles. Operators
must reconcile reviewer snapshots using the SYSTEM_ADMIN endpoint, obtaining roles
from the existing identity API during the same controlled reconciliation procedure
as employees. asOf must not be future, newer snapshots win, stale/equal timestamps
are no-ops, and explicit null revokes the fallback role. Employee reconciliation
continues unchanged. Later role changes require this reconciliation until upstream
role lifecycle/replay events are added outside this allowlist. Snapshot rows retain
only role and timestamp. Managers need only the existing employee projection.

### Atomicity, retries and outbox

V4 adds leave_reviewer, leave_request and leave_request_action within leave_db.
Submission takes the employee transaction advisory lock used by projection updates,
checks prior submission key and overlaps across all types, then calls item 28 reserve
under the entitlement row lock. Request, reservation and outbox insert commit or
roll back together. An insufficient balance, overlap or missing approver leaves
no request, reservation or request event behind. All submission writers must keep
this employee-lock protocol; it is not a database exclusion constraint protecting
arbitrary SQL writes. Calendar edits affect later estimates/submissions, never the
persisted calculated days of an existing reservation.

Reject/cancel/route lock the request row. Release uses item 28 settle(RELEASED)
under its entitlement lock in that same transaction. Racing rejection/cancellation
has one winner; the loser receives 409. Repeated owner cancellation is a no-op even
with a new key. Submission keys are scoped per employee, action keys per request,
max 100 characters. Identical actor/action/reason/target retries return current
persisted state; conflicting reuse returns 409. Keys must be preserved across
uncertain responses. The UI preserves keys for unchanged input during the mounted
form's retry context; reloading ends that context. Submit retries return the same
request even after it reached a terminal state.

Each actual submission, rejection, cancellation or reroute publishes
`leave.request.changed` through the existing transactional OutboxPublisher:
`{request:<full response>,actorId,action,reason}` with action SUBMITTED, REJECTED,
CANCELLED or ROUTED. The existing EventEnvelope and at-least-once delivery apply;
consumers deduplicate eventId and can order by request.version. Bind a durable
consumer to this routing key before operational use. Retries do not emit another
event. Private reviewer reconciliation does not publish a domain event.

### Dependencies and rollout / PR compatibility

No dependencies or versions were added. Java 21/Boot 4/JDBC transactions,
PostgreSQL/Flyway, ems-common gateway security/errors/outbox and disabled storage
are reused. V4 is additive; V1–V3 are unchanged. Apply backend migration before the
frontend. Item 28 endpoints, employee snapshots and existing event shapes remain
compatible. The frontend contract types are regenerated with the installed
openapi-typescript generator. Requests use the existing cookie-based Axios/BFF,
React Hook Form/Zod, TanStack Query, Button and theme. Cache keys include user ID;
all request ownership, reviewer eligibility and routing checks run on the backend,
including direct BFF requests. Existing leave navigation reaches the expanded page.
Installed Next.js forms, authentication and BFF guidance was read before coding.

Production prerequisites remain approved holidays, real yearly allocations,
manager/status reconciliation, reviewer-role reconciliation for fallback routing,
gateway wiring and durable consumer bindings. Real identity login, real employee
inventory, production migration and deployment are outside this verification.

### Item 29 verification procedure

Run `mvn -B -pl api-gateway,leave-service -am verify` from ems-backend, then
`npm run lint`, `./node_modules/.bin/tsc --noEmit` and `npm run build` from
ems-frontend. In environments whose trusted TLS roots differ from Turbopack's
bundled roots, `NEXT_TURBOPACK_EXPERIMENTAL_USE_SYSTEM_TLS_CERTS=1 npm run build`
uses system trust for the existing Google Fonts downloads; it does not disable
certificate verification. No repository configuration change is needed.

Use the opt-in disposable browser fixture documented above. Item 29 additionally
seeds employee 6101 with 10.00 Fixture balance breakdown days in 2026, manager 6201,
and a synthetic holiday on 2026-11-02. Sign in as reviewer@example.test (user 6201,
EMPLOYEE principal role, projected manager) or routing@example.test (6202,
HR_MANAGER), in addition to the existing synthetic accounts.

1. As employee@example.test, request FULL leave from 2026-10-30 to 2026-11-03.
   Preview and persisted request must show 2.00, excluding Saturday/Sunday and the
   configured Monday holiday. Availability moves 10.00 → 8.00, reserved to 2.00.
2. Submit overlapping dates with a new key; expect 409 and unchanged availability.
   Cancel the original pending request with a reason, reload, and confirm CANCELLED,
   0.00 reserved and 10.00 available. Repeat cancellation via API with the same and
   another key; availability and state-event count must not change.
3. Request HALF on 2026-11-04 only; preview 0.50, pending reservation 0.50.
   A half-day range over multiple dates and a weekend-only request must be refused.
4. As reviewer@example.test, see only assigned pending requests, supply a reason,
   and reject. Approval remains disabled. Employee history must show REJECTED and
   restored availability. Unrelated identities' detail/reject/cancel calls fail 403.
5. As routing@example.test, route another employee's pending request to a different
   eligible reviewer, with a reason. Verify version increment and new reviewer ID;
   prior reviewer loses access, and HR cannot route its own request or assign the owner.
6. The integration suite submits concurrent overlapping requests with distinct keys
   and concurrent identical-key retries. Verify one request/reservation per winner,
   no orphan on insufficient funds, unchanged keys reject altered input, scoped
   inbox/detail access, fallback selection, rerouting, terminal retries, and 409 for
   item 30-only cancellation states. Every true state change has one outbox record.

### Item 29 verification evidence — 27 October 2026

- Both clean starting checkouts were already on feat/leave-requests at the current
  local development tips (backend 7f40e04, frontend 6fdc543). Item 28's actual
  calendar, reserve/settle, projection, security, outbox and UI implementations were
  inspected. No branch reset or unrelated source change was performed.
- Before feature changes, the focused baseline command
  `mvn -B -pl api-gateway,leave-service -am verify` completed BUILD SUCCESS.
  Its temporary log was lost when the execution session was interrupted; that
  result was inspected during the original run, not inferred from item 28 reports.
- Final focused command completed BUILD SUCCESS: 34 reported tests, 32 executed,
  zero failures/errors, two intentional skips (shared storage opt-in and browser
  fixture opt-in). All 14 leave integration tests and three MVC tests passed.
  Fresh V1–V4 migrations, holiday/weekend calculation, half-day validation,
  concurrent overlap and retry deduplication, insufficient-funds rollback,
  reservation/release counts, rejection/cancellation retry safety, ownership,
  fallback/routing and deferred approval-state guards passed. Existing real-JWT
  gateway probes passed in development, staging and production profiles.
- An initial feature suite run passed the new scenarios but timed out in the
  existing gateway startup probe while other builds/fixtures were running.
  Later focused runs passed without relaxing that test. The initial failure is
  not counted as passing evidence. A full all-module reactor build was not rerun
  for item 29; earlier all-module evidence above belongs to item 28.
- Final frontend lint completed with zero errors and six pre-existing warnings
  outside the changed files. Standalone TypeScript checking passed. Production
  build passed with
  `NEXT_TURBOPACK_EXPERIMENTAL_USE_SYSTEM_TLS_CERTS=1 npm run build`.
  A default build retry in the resumed environment failed on existing Google Fonts
  HTTPS downloads; using system certificate trust fixed the network prerequisite.
  The unchanged next.config.ts skips build-time type validation, so the separate
  successful TypeScript command is the typecheck evidence.
- Browser checks used the real Next app, cookie BFF and leave service against
  disposable PostgreSQL/RabbitMQ, with synthetic identity/login and gateway only.
  A FULL 30 October–3 November request previewed/persisted 2.00 excluding weekend
  and configured Monday holiday; available moved 10.00 → 8.00. A second overlapping
  submission showed the server conflict and left balance unchanged. Owner
  cancellation restored 10.00 and persisted CANCELLED after reload. A single-date
  HALF request previewed/persisted 0.50, and the assigned manager inbox exposed
  rejection with approval disabled. In the resumed disposable fixture, HR routed
  another half-day request from 6201 to eligible reviewer 6202 (version 1), then
  rejected as its assigned reviewer; it disappeared from the pending inbox.
  Employee history refreshed to REJECTED, version 2, with 0.00 reserved / 10.00
  available. No production identity login or real employee request was exercised.

Final logs: `leave-service/target/verification/item29/` (focused.log,
frontend-lint.log, frontend-typecheck.log, frontend-build-retry.log,
browser-backend.log, browser-frontend.log). Screenshots in
`leave-service/target/browser-fixture/`: request-reviewer.png,
request-restored-balance.png and request-rejected-history.png. All are ignored
build outputs, not committed artifacts. The interruption stopped the original
fixture; final browser checks used a newly created disposable database.

External prerequisites not exercised: real identity account login and authoritative
employee/status/manager/role inventory; approved production holidays and allocations;
production gateway/CI wiring, durable downstream request consumers and production
migration/deployment. No secret was committed and no live database was modified.

## Item 30: durable approval and approved cancellation

This section supersedes item 29's deferred approval statements. Both feature branches
start at their current local `development` tips: backend `eb89e10`, frontend `b1aa3c7`.
Item 29's actual request, reviewer projection, reservation, ledger, gateway security
and outbox implementations were inspected before extending them.

### API contract and permissions

The generated frontend contract is v1.2 in `leave-service/api/leave.openapi.json`.
All paths below are relative to `/api/leaves` and require gateway authentication.

| Method/path | Input and result |
| --- | --- |
| POST `/requests/{id}/approve` | `{version,idempotencyKey,reason}` → 202 Workflow, after commit |
| POST `/requests/{id}/cancel` | Pending cancellation retains the item 29 response. After approval starts, also require `version` and return Workflow (200). |
| GET `/requests/{id}/workflows` | Ordered Workflow array, under the request's existing detail authorization |
| GET `/workflows/{id}` | Workflow under the owning request's authorization |
| GET `/calendar?employeeId=…` | Employee scope; defaults to principal. Returns `{timezone,workweek,absences}`. |
| GET `/calendar?departmentId=…` | Department scope; mutually exclusive with employeeId |

A Workflow contains `id,requestId,operation,state,stepId,attempts,deadline,nextAttempt,error`.
Operation is APPROVE or CANCEL. Workflow and request states are distinct: cancellation
can be removing an absence while the request still reads APPROVED. Read workflow
progress until its terminal state. Request version increments when approval/cancellation
starts and at durable finalization. Start uses the client's expected version under the
same request-row lock as reject, route and pending cancel. A stale version returns 409.
Identical retries with the original actor, operation, reason, key and version return the
original workflow's current state, even after completion. Changed input returns 409.
A unique request/operation constraint prevents another approval or cancellation workflow,
even if a caller loses its key. Keys also share the existing request-action namespace.

Only the assigned, currently eligible reviewer may approve; ownership never permits
self-approval, including an HR principal. Approved/in-progress cancellation is owner-only.
The item 29 active-manager/department-head/HR projection rules still apply. Inbox now
also retains assigned in-progress, approved and approval-failed requests to expose their
workflow results. Its employee/reviewer/HR scope is unchanged. Authorization runs in
leave-service for requests forwarded through the cookie-based BFF, independently of UI
visibility. No client token store or direct service/database access was introduced.

Calendar reads require an active employee projection. Own calendar is available by
default; another employee requires an eligible reviewer relationship or active HR.
Department scope requires active HR or a reconciled head of that same department.
No department scope means no department-wide read. Absences contain only request ID,
employee ID, inclusive local dates and FULL/HALF; no leave type, reason, contact details
or other request contents. Existing timezone/workweek fields remain in the response.
Approved rows alone are public; partially created calendar rows are never exposed.

### Persisted orchestrator and compensation

The absence calendar is the `leave_absence` table inside leave-service's own database.
There is no calendar service and no cross-service database access. The explicit workflow
is the future integration boundary: keep its durable step ID as the idempotency/fencing
identifier when replacing a local calendar action with an external participant.

Approval states:

`DEBIT → CALENDAR → FINALIZE → APPROVED`

DEBIT calls the existing reservation settlement under the entitlement lock. The debit,
reservation transition, workflow advance, immutable audit and next outbox command commit
in one transaction. CALENDAR inserts at most one row per request. FINALIZE durably sets
request APPROVED and publishes `leave.approved` in that same transaction. There is no
email call in approval. New workflow commands carry `{workflowId,stepId}` in the existing
EventEnvelope on `leave.workflow.step`. The leave queue is bound to this routing key.

Every advance first locks the request, reloads the workflow and checks its current step
ID and terminal state. Duplicate or out-of-order commands therefore cannot repeat a
completed effect. There is no separate, unauthenticated success callback that could
advance a compensated workflow; local effect completion is committed with the step
transition. Old calendar/debit commands are ignored after compensation or cancellation.

Each step has a durable ID, deadline, attempt count and next-attempt timestamp. The
recovery poller scans persisted due rows every second, independently of broker delivery.
It resumes after restart without in-memory workflow state. Technical failures roll back
the step, then record a retry in a separate transaction with a two-second delay. Three
failed attempts or a 30-second required-step deadline switch approval to COMPENSATE.
Permanent calendar failure switches there immediately. Immutable workflow audit rows
retain step transitions, attempt/deadline snapshots, actor, reason and safe error text;
request actions and balance entries are also protected against UPDATE/DELETE.

COMPENSATE removes any partial absence, appends at most one positive TAKEN reversal
under `reservation:restore:<requestId>` if a debit exists, releases the reservation and
sets APPROVAL_FAILED. Those effects and one `leave.balance.restored` event commit
atomically. Taken is still net consumption, so existing balance fields and arithmetic
are unchanged. The original debit remains immutable. Reservation state plus the unique
internal ledger key prevent a second refund. Compensation retries without a terminal
retry limit: database outages must not strand a debit in an abandoned compensation.

Approved/in-progress cancellation fences the original approval before entering:

`REMOVE_CALENDAR → RESTORE → CANCELLED`

It uses the same persisted step engine, locks, restore primitive, immutable audits and
restart recovery. Cancellation before debit releases only the reservation. Cancellation
after debit creates one reversal. Race winners are serialized by the request lock; a
loser with a stale expected version must refresh, while identical recorded retries
remain valid. The final restoration event includes `workflowId,requestId,employeeId,outcome`.

### Independent email delivery and prerequisites

Notification-service's leave handler has its own durable `notification.leave.q`, retry
and parked queues through QueueFactory, bound to `leave.approved` and `user.verified`.
It deduplicates event IDs with ems-common and stores one logical delivery per workflow ID.
The approval event contains `workflowId,requestId,employeeId,startDate,endDate` only.
A minimal email recipient projection is owned by notification-service and consumes the
existing token-free verified-user contract, ordered by verifiedAt. It never reads leave
or identity databases. A contact arriving after approval unblocks the pending delivery.

SMTP runs from persisted delivery rows, one transaction/locked row per email, with
SKIP LOCKED across instances. Failed email or missing recipient retries indefinitely
with exponential backoff (2 seconds through 300 seconds). It cannot write leave state.
A successful send records sent_at; replayed events or a second envelope with the same
workflow ID do not produce another logical delivery. The stored template key is
`leave.approved`. A stable `<leave-WORKFLOW_ID@ems.local>` Message-ID survives send retries.
SMTP acceptance and database commit cannot be atomic: a crash after relay acceptance
can physically resend that same logical message. Relay/inbox deduplication is needed
if exactly one physical email is required; no exactly-once SMTP guarantee is claimed.

Before rollout, replay authoritative, verified `user.verified` snapshots into the new
notification queue for existing employees (same verifiedAt and confirmed email, a stable
event ID for retries). The queue cannot retroactively receive old verification events.
Missing recipients stay pending, rather than losing approved leave or inventing an
address. Identity currently has no bulk event replay API; use the existing controlled
snapshot/reconciliation process and obtain recipient data through authorized APIs.
Email changes require a suitable newer authoritative contact snapshot until an upstream
email lifecycle contract exists. No new producer or shared database lookup was added.

Existing prerequisites remain manager/status/reviewer reconciliation, actual approved
holidays/allocations, trusted gateway headers, broker/SMTP connectivity and operations
monitoring. The shared producer outbox keeps its existing retry/parking policy; operators
must replay exhausted producer/parked events after prolonged broker or malformed-event
incidents. Notification SMTP outages, once delivery is persisted, need no event replay.

### Frontend behavior and fault injection

Approve and cancel require a native modal confirmation showing dates, days and reason.
The UI saves the exact key/version/reason/operation in per-user/request sessionStorage
before POST, including across reload. Uncertain responses retain a single saved-decision
retry and disable a fresh decision. A definitive 400/403/409 is reconciled through the
scoped workflow read before clearing that context. Workflow queries poll while active,
stop at terminal states and invalidate balances, requests and calendar on completion.
The owner sees approved absences and explicit compensated-failure text. Existing Query,
form, Button/theme, cookie client/BFF and server authorization patterns are reused.
Installed Next.js use-client, forms, authentication and BFF guidance was consulted.

WorkflowFaults is programmatic only, with no HTTP injection endpoint. It supports
per-request permanent calendar failure and one-shot pre-commit crash checkpoints for
any step. It is enabled only with test/development profiles, and disabled if production
or staging is also active. Production/default-only profiles reject attempts to arm it.
The opt-in browser fixture can seed a failing request with
`LEAVE_BROWSER_FIXTURE=true LEAVE_BROWSER_SAGA_FAILURE=true`; its loopback synthetic
gateway also supports ignored target/browser-fixture/control.json containing
`{"loseDecisionResponse":true}` to simulate a lost approve/cancel response after commit.
Clear to `{}` before retrying. These controls are exclusively in test fixture sources.

### Dependencies, compatibility and PR notes

No new library, package or version was added. This reuses installed Java 21/Boot 4,
JDBC/JPA transactions, PostgreSQL/Flyway, Jackson 3, AMQP, JavaMail and ems-common
security/errors/outbox; storage remains disabled. Leave V5 and notification V6 are
additive migrations; V1–V4/V1–V5 respectively are unchanged. Leave V5 expands the ledger
check to allow internally keyed positive TAKEN reversal entries and adds immutable
action/workflow audit protection. Consumers interpreting individual TAKEN entries must
respect their sign; aggregate taken remains the same decimal net-usage field.

Deploy both backend migrations/handlers before the frontend. Old pending cancel bodies
and responses still work; approved/in-progress cancellation is a new versioned operation
with a Workflow response. Calendar keeps policy fields and adds scoped absence rows,
with active-projection eligibility now required. The new UI's types are generated from
the committed v1.2 backend contract using the already installed openapi-typescript.
The two repositories retain feat/leave-approval-saga; no unrelated source/config, shared
security/storage code, production service or live data was changed.

### Item 30 verification

From ems-backend:

```bash
mvn -B -pl api-gateway,leave-service,notification-service -am verify
python3 leave-service/verification/notification_recovery.py
```

The Python probe uses only the installed Docker CLI, Java and Python standard library.
It creates uniquely named disposable PostgreSQL databases in separate containers plus
RabbitMQ, starts packaged services, initially leaves SMTP stopped, then restarts
notification-service and starts a loopback SMTP sink. It asserts durable approval,
calendar/debit, pending delivery, recovery, duplicate event/workflow deduplication and
approved cancellation. It removes only its own containers/processes. Its test-only mail
health exclusion allows the deliberate SMTP outage during application readiness.

From ems-frontend:

```bash
npm run lint
./node_modules/.bin/tsc --noEmit
NEXT_TURBOPACK_EXPERIMENTAL_USE_SYSTEM_TLS_CERTS=1 npm run build
./node_modules/.bin/openapi-typescript ../ems-backend/leave-service/api/leave.openapi.json -o app/types/leave-api.d.ts
```

Use the existing opt-in browser fixture with the real production Next build and cookie
BFF. Verify confirmation/back-out, assigned-reviewer approval progress, one owner
absence and net debit, owner cancellation with restored balance and removed absence,
post-commit response loss plus reload/saved-key retry, and the seeded permanent calendar
failure with final failure text and restored owner balance. Fixtures use synthetic login
and gateway forwarding, not real identity accounts or operational employee data.

### Item 30 evidence — 27 October 2026

- Clean baseline used an isolated `git archive HEAD` of backend `eb89e10`, without
  feature edits. `mvn -B -pl api-gateway,leave-service,notification-service -am verify`
  passed: 47 reported tests, 45 executed, zero failures/errors, two intentional skips.
  Log: `target/verification/item30/clean-baseline.log` under leave-service. The first
  restricted attempt could not access Docker. A separate overlapping early feature
  run exposed an incorrect V5 constraint name; it is not counted as baseline evidence.
- Final focused reactor command passed with formatting checks: 54 reported tests,
  52 executed, zero failures/errors, two intentional skips (shared storage smoke and
  opt-in browser fixture). The 19 leave integration tests include original item 29
  behavior, reviewer/self/outsider denial, stale version refusal, identical retries,
  one debit/calendar/approval event, permanent calendar failure after debit, removal
  of a partial calendar row, one reversal/restoration event, stale/duplicate step
  delivery, pre-commit crash rollback, deadline compensation, concurrent approval
  progress/cancellation with replay, and application-context shutdown/restart across
  approval and cancellation. Existing real-JWT gateway checks passed in development,
  staging and production profiles. This is not a new full all-module reactor result.
  Log: `target/verification/item30/final-focused.log`.
- Notification's two new regression tests passed, covering serializable message
  construction/stable logical ID and failed-SMTP retry without marking sent. The
  first real SMTP probe found an empty-message-copy MessagingException; corrected
  construction and the regression tests precede the successful final probe. An
  earlier test-only readiness check also wrongly treated deliberate SMTP downtime
  as failed app startup; only the probe now excludes mail health during that outage.
- `python3 leave-service/verification/notification_recovery.py` passed with the final
  packaged services, real isolated PostgreSQL/RabbitMQ, and a loopback SMTP sink.
  It observed APPROVED with 1.00 taken and one absence while SMTP was stopped, a
  persisted failed MailSendException attempt, a notification-service process restart,
  then sent_at and exactly one logical leave message after SMTP returned. Replaying
  the approved workflow under a new event ID and duplicating that envelope left one
  delivery row/email. Approved cancellation restored 10.00 and removed the absence.
  Log: `target/verification/item30/notification-verified.log`; service logs are in its
  `notification/` sibling directory. Earlier failed probes are not passing evidence.
- Final frontend lint passed with zero errors and six pre-existing warnings outside
  changed files; standalone TypeScript and production build passed. The restricted
  Google Fonts fetch failed initially; the system-trust, network-enabled build
  succeeded. `next.config.ts` still skips build-time type checking, so the separate
  successful TypeScript command is the typecheck evidence. Logs:
  `frontend-lint-final.log`, `frontend-types-final.log`, `frontend-build-final.log`
  in the same ignored verification directory.
- Browser checks used the actual production Next app, cookie BFF and real leave
  service with disposable PostgreSQL/RabbitMQ. Identity login and gateway forwarding
  alone were synthetic. The assigned manager saw confirmation and persisted progress
  reach APPROVED; the owner saw 1.00 taken, 9.00 available and one absence. Confirmed
  cancellation reached CANCELLED, 0.00 taken/reserved, 10.00 available and no absence.
  A post-commit cancellation response was deliberately lost: a disabled fresh action
  and saved-decision retry appeared; reload retained that decision, and retry retrieved
  the same cancellation workflow without another debit/refund. A seeded permanent
  calendar failure reached APPROVAL_FAILED with explicit compensation text; the owner
  saw restored 10.00 and no absence. After the final polling change and rebuild,
  approval and cancellation again automatically reached terminal states and refreshed
  balances/calendar. Screenshots: `target/browser-fixture/item30-final-approved.png`,
  `item30-final-cancelled.png`, `item30-response-loss.png`, `item30-approval-failed.png`
  and `item30-restored-after-failure.png`. Generated logs/screenshots are ignored.

Not exercised: real identity login/accounts, production employee/role/contact inventory,
approved real holidays/entitlements, production gateway/CI wiring, production SMTP or
relay deduplication, production database migrations or deployment. Existing verified-user
recipient replay is an external rollout prerequisite. No live data was reset or deleted,
no new dependency was added and no secret is part of these changes.
