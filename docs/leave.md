# Leave types, working calendar and balances

`leave-service` owns leave_db, runs on port 8085 and receives `/api/leaves/**`
through the existing gateway routes. This increment adds explicit yearly allocations,
an append-only balance ledger, a working calendar and administration/self-balance UI.
Leave requests, approval policy, accrual, carry-over and automatic yearly jobs are deferred.

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
