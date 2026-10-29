# Timesheet drafts, submission and review

## Implementation and compatibility / PR notes

Backend and frontend branch: `feat/timesheet-approval`, cut from current local `development` tips.
Backend base: `a7420e35e360ac0a4d929f444f8f2c1633513c0a`; frontend base: `192fcfbea413bf28288d1646ebca9a4d4f2a39b1`.
Inspected actual project/team implementation (item 26) and leave workflow/outbox implementation (item 30).
Projects are owned by **org-service**, not a separate project-service; project membership is inherited from its team.

The new Maven module listens on **8086** and owns **timesheet_db**. Gateway development, staging and production
profiles route `/api/timesheets/**` to `TIMESHEET_SERVICE_URL` (local default `http://localhost:8086`).
No reads of other services' databases and no synchronous upstream calls are made by timesheet reads or writes.
Storage is disabled because timesheets contain no attachments. Review is implemented; summaries contain approved minutes only, with no payroll values.

Runtime dependencies match the installed Spring Boot 4.0.8 / Java 21 service stack: ems-common, JDBC via JPA,
PostgreSQL, Flyway, MVC/validation/security, AMQP, actuator and AspectJ for the shared idempotent consumer.
No new library versions or frontend packages are introduced. Existing shared outbox/storage/security code is unchanged.
Testcontainers PostgreSQL and RabbitMQ follow existing integration-test conventions.
Notification-service adds **test-only** Testcontainers PostgreSQL and JUnit integration dependencies, using the
parent-managed versions, to exercise its own Flyway migrations and durable delivery recovery. No new runtime
libraries, shared common changes, storage dependencies, or frontend packages are introduced. Lombok is inherited from the
existing module scaffold; it is not required by the new business code.

Existing project and team event payloads remain compatible. The sole producer addition is `project.deleted`,
carrying the same `{project: ProjectResponse}` snapshot shape as project updates. This tombstone lets old sheets
retain their project references while future entry dates stop accepting the deleted project. Existing consumers
need no changes. `timesheet.submitted` carries the frozen Sheet response, including its version, timestamps and
actor IDs, inside the existing EventEnvelope. Publication uses the shared transactional outbox and normal broker
retry/parking topology. Consumers must deduplicate its eventId, since broker delivery is at least once.

## Local startup

Provision an isolated PostgreSQL database named `timesheet_db` and grant a service-specific account access to it.
This task does not change the root Docker Compose/database initialization files because they are outside scope.
Copy `timesheet-service/.env.example` to `.env.development` inside that service and provide local credentials.
Set RabbitMQ configuration and `ORGANIZATION_TIMEZONE` consistently with the organization/leave deployment.
The default zone is Asia/Colombo. Changing the zone after ingestion requires a reviewed history migration.

Use the installed Java 21/Maven toolchain:

```sh
mvn -pl timesheet-service -am install -DskipTests
mvn -pl timesheet-service spring-boot:run
```

The service accepts only gateway-forwarded identity, following ems-common's GatewayAuthenticationFilter.
Expose the gateway to clients and keep service ports private. The gateway verifies JWTs and removes spoofed
identity headers. There are no employee ID parameters on self-write APIs; even admins cannot edit someone
else's sheet. Backfill and reviewer reconciliation are restricted separately to SYSTEM_ADMIN. Owner APIs remain self-only;
reviewer reads and summary queries have the scope described below.

Frontend: use the installed Node runtime and the existing Next.js commands. Open **`/timesheets`** directly.
No shared navigation files are changed under the page/service-only frontend scope. The page checks the session
cookie server-side; all API access still passes through the existing cookie BFF and authenticated gateway.
An active local employee and eligible project membership are required for writes; those checks run on the backend.

## Projections, ordering and history

`timesheet.q` subscribes to `user.verified`, `project.created`, `project.updated`, `project.deleted`,
`team.member.added`, `team.member.removed`, `user.assignment.updated` and `employee.profile.updated`. Shared IdempotentConsumer records and updates commit together.
Verified users are active employee shells, matching employee-service and leave-service conventions. Administrative
snapshots also support inactive employees. Identity currently lacks a general employee-status event; reconcile
status explicitly when it changes rather than relying on profile updates, which omit status.

Existing source contracts have no numeric aggregate version. The common **UTC occurredAt timestamp** is their
version, retained in PostgreSQL at microsecond precision. Employee state accepts strictly newer versions only.
Projects and memberships retain append-only dated history, including delayed older events. Duplicate versions
are no-ops; producer timestamps must be unique per project or team/user stream. Upstream clocks must be synchronized.
Numeric versions can be introduced later through an explicitly versioned event contract rather than guessed here.

For each entry date, the local lookup chooses the latest effective organization date and then latest version for
the project, resolves its team on that date, and chooses that employee's latest team membership on that date.
Project start/end dates also constrain eligibility. Because entries are date-based, changes within a day use the
last known state for that organization day; a removal on a date makes that whole date ineligible. Legacy joinedAt
is a zone-less server timestamp, so live membership effective dates use the unambiguous envelope timestamp.
Archived/completed project status does not itself remove membership; deletion and project dates do.

An advisory projection lock coordinates projection writes with draft validation/submission. Sheet mutation locks
and expected versions prevent concurrent overwrites. Date validation and daily totals are recalculated at submit.
New projection corrections can make a draft ineligible; they never alter frozen submitted entries.

## Initial backfill and reconciliation

New queues do not receive events published before they existed. Backfill is an explicit rollout prerequisite.
Use source APIs through the gateway, never source SQL or another service's database credentials.

1. Start timesheet-service first so its durable event queue is bound. Drain existing source/employee outboxes.
2. Pause organization project/team membership writes and identity/employee status changes during export/import.
   Wait for outstanding source transactions and employee-shell consumers to settle. Keep live consumer delivery enabled.
3. Provide a short-lived SYSTEM_ADMIN bearer token in `TIMESHEET_BACKFILL_TOKEN`; never place it on a command line,
   in source files or in captured output. Run:

   ```sh
   python3 timesheet-service/scripts/backfill.py --gateway http://localhost:8080 --apply
   ```

   The script reads paginated assigned/unassigned users, employee profiles, projects and team members through their
   public owner APIs. It strips profile details and sends only IDs, active status, project display/date fields and
   membership flags. It imports batches of at most 1000 records into `PUT /api/timesheets/admin/projections`.
   Without `--apply` it prints a reviewable snapshot containing IDs and project names, never credentials.
   Save that export locally and use `--input snapshot.json --apply` to import/retry the exact same watermark.
   Unverified users with no employee shell are imported inactive; source authorization/network errors abort the run.
4. Resume writes only after all batches succeed. Verify `/api/timesheets/projects?weekStart=YYYY-MM-DD` as a member.

Each import records the acting admin, asOf watermark, time and row counts in projection_audit. The import endpoint
also supports reviewed snapshots with `{asOf, effectiveDate, employees, projects, memberships}`. Each employee is
`{userId,active}`; project `{id,teamId,name,deleted,startDate,endDate}`; membership `{teamId,userId,member}`.
Rerunning the exact snapshot is safe: employee versions and history keys deduplicate it. If a batch fails, preserve
the same reviewed snapshot/watermark for replay. Ordinary snapshots are sparse updates, not replacement of all data.
For missed removals/deletions, supply explicit `member:false` / `deleted:true` tombstones with their effective dates.

The export establishes membership **from the snapshot date**, not from a project's creation date. Current APIs
cannot reconstruct past team assignments, removed members or deleted projects. To enter earlier days (including
Monday of a partially elapsed week), import reviewed historical snapshots from an authoritative audit export using
their genuine effective dates and asOf versions. Do not invent historical membership by extending current membership
backwards. The original zone-less joinedAt does not prove continuous past membership.

## API and validation

Canonical checked-in contract: `timesheet-service/api/openapi.json`.
Regenerate the frontend service types using its existing openapi-typescript installation:

```sh
node_modules/.bin/openapi-typescript ../ems-backend/timesheet-service/api/openapi.json -o 'app/(user)/timesheets/contract.d.ts'
```

| Method | Path after `/api/timesheets` | Behavior |
| --- | --- | --- |
| GET | `/calendar` | Organization timezone and current Monday |
| GET | `/projects?weekStart=...` | Local eligible projects and eligibleDates |
| GET | `/me?weekStart=...` | Own sheet for that Monday, zero or one item |
| POST | (root) | Create `{weekStart}` or return the existing sheet for that employee/week |
| GET | `/{id}` | Reload own sheet |
| PUT | `/{id}` | Replace draft entries using `{version,entries}` |
| DELETE | `/{id}?version=...` | Delete own draft; retain audit record |
| POST | `/{id}/submit` | `{version}`; freeze and emit one logical submission |
| PUT | `/admin/projections` | SYSTEM_ADMIN-only reviewed snapshot import |

Entries are `{date,projectId,minutes,description}`. Minutes are integers 0–1440; fractional JSON minutes are rejected.
Every entry, including a zero-minute entry, must reference a project with membership on its entry date. Dates must
belong to that sheet's Monday–Sunday week. Descriptions are trimmed, nonblank and at most 240 characters. Each
sheet accepts at most 500 entries; all entries on a date together must total at most 1440 minutes. Submit requires
at least one positive entry. Validation failures roll back the whole write. Database uniqueness enforces one sheet
per employee/week. Stale draft versions and frozen writes return 409. Another employee's sheet returns 404.

DRAFT→SUBMITTED→APPROVED/REJECTED is implemented. Only the owner can explicitly reopen REJECTED→DRAFT.
Created/updated/submitted/reviewed actor IDs and timestamps, mutation audit rows, and append-only decision rows
are persisted. A retry of submit returns the frozen result and adds no second event for that submission.
After reopen, the previous submission version cannot submit the new draft. Reviewed drafts cannot be deleted;
this preserves their decision history. Submission is serialized against saves and concurrent submits.

## Verify

Run `mvn -B -ntp verify` at the backend root and the frontend's lint, `tsc --noEmit`, and build commands.
Focused backend command: `mvn -pl timesheet-service -am test -Dtest=TimesheetIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`.
Backfill utility checks: `python3 -m unittest discover -s timesheet-service/scripts -p test_backfill.py -v`.
Tests use isolated Testcontainers databases/brokers. Gateway profile checks use the packaged gateway artifact, so
build api-gateway first when invoking only the focused module. The opt-in TIMESHEET_BROWSER_FIXTURE test starts an
isolated backend for browser checks and stops when `timesheet-service/target/browser-fixture/stop` is created.

Browser checks: sign in as a projected member, open `/timesheets`, choose a project, enter descriptions/minutes,
save, reload and submit. Verify totals and disabled edits after submission. Add rows totaling over 1440 on one day
and verify rejection with inputs intact. Interrupt a save request and verify edits remain visible; retry. Interrupt
the response to submit and use Retry submission; verify the same sheet and one event. Change to another member and
verify the first sheet cannot be written. Repeat reads with org-service unavailable. Check empty eligible-project
messaging before backfill and selectable project dates after backfill.

### Item 31 verification recorded on 2026-10-27

- Baseline backend root `mvn -B -ntp verify`: passed before changes.
- Backend root `mvn -B -ntp verify` after module/routes/event addition: passed (181 discovered tests, 3 skipped).
- Final focused timesheet `verify` after required-field hardening: 7 tests passed, none skipped; Spotless passed.
  This includes disposable PostgreSQL/RabbitMQ, concurrent submit, durable outbox dispatch, historical membership,
  admin-only backfill, stale employee events, and actual packaged-gateway RS256 checks in all three profiles.
- Backfill utility: 3 standard-library tests passed (pagination/missing shells, exact snapshot replay, upstream failure).
- Frontend baseline and final lint: 0 errors, 6 existing warnings; typecheck and production build passed.
  The generated types were produced with the installed openapi-typescript, without adding packages.
- Browser: cookie sign-in through actual gateway with synthetic identity, project selection with org-service down,
  save/reload, 1500-minute day rejected with edits retained, connection-reset save retained edits and succeeded on
  retry, lost submit response recovered through Retry submission, full reload retained frozen entries, second
  employee saw a separate empty week and only their team project. All 14 duration fields were disabled after submit.
  Read-only inspection of the disposable browser database confirmed SUBMITTED, 3 entries, and exactly 1 submitted event.

The browser identity provider was synthetic; no live user credentials or production data were used. Real-source
backfill export against identity/org/employee APIs was not run. Before rollout, provision timesheet_db, configure
service credentials/routing/timezone, perform the documented backfill, and supply authoritative historical
membership where needed. Approval/payroll consumers are outside this item; a durable subscriber to
`timesheet.submitted` must be provided by that downstream work. No deployment or Git commit was performed.


## Manager review (item 32) / PR description

Managers can open `/timesheets/review`, inspect each entry and approve or reject a submitted week. A rejection
requires a trimmed, nonblank reason of at most 500 characters. Employees see the complete decision history in
`/timesheets`; an active owner can reopen a rejected sheet, edit it and resubmit for a new decision. Reopening
clears only current submission/review fields; every prior decision retains reviewer, timestamp, outcome, reason,
and version. Decision history is ordered by version and cannot be deleted via the application.

### Authorization and reconciliation

The eligibility predicate matches leave's documented reviewer scopes: an **active** current reporting manager,
an active `DEPARTMENT_HEAD` in the employee's current department, or an active `HR_MANAGER` reviewer. Self-review
is always forbidden, including for HR. The same SQL predicate scopes inbox rows, reviewer detail and decisions. Draft entry detail remains owner-only, including after reopen.
Unlike leave's assigned-approver workflow, timesheet decisions do not route to a single assignee: any currently
eligible reviewer in those scopes may decide. A role claimed in a browser or an HR JWT alone does not grant
review authority; privileged reviewer snapshots must be reconciled locally by SYSTEM_ADMIN, as with leave.
No shared-database access or synchronous upstream authorization lookup is used.

Before rollout, reconcile active status, department, reporting manager and privileged reviewers from their
owner APIs through the gateway. Item 31's membership backfill does **not** supply this review scope. New bindings
receive future events only. Use these SYSTEM_ADMIN-only endpoints for initial reconciliation and missed changes:

- `PUT /api/timesheets/admin/employees/{userId}` with `{active,departmentId,reportingManagerUserId,asOf}`.
  IDs may be null to remove an assignment; self-reporting is rejected. `asOf` must be a genuine source watermark.
- `PUT /api/timesheets/admin/reviewers/{userId}` with `{role,asOf}`. Role is `DEPARTMENT_HEAD`, `HR_MANAGER`,
  or null to revoke the privileged scope; the employee must already be active.

Separate monotonic clocks for active status, identity department assignments and employee manager profiles keep
late profile snapshots from replacing newer identity department assignments. Live assignment events use
`assignedAt`, profile events use the envelope `occurredAt`. Profile `departmentId` is deliberately ignored, as
in leave. Reviewer-role events are not provided by current producers: role changes/revocations require explicit
reconciliation. Projection writes and decisions coordinate with the same service-local advisory lock, so an
in-flight decision checks a coherent current scope. Department summaries use current department membership.

### New API contract

The checked-in OpenAPI contract is additive (version 1.1.0); existing draft/submission routes and payloads remain
compatible. `Sheet` adds required `decisions: Decision[]`, empty before any decision. Current review fields remain
nullable. The frontend generates a page-local contract and uses the existing axios/cookie BFF. Page entry requires
a session cookie; all reads/writes are authorized again by timesheet-service. The UI does not treat button hiding
as authorization.

| Method | Path after `/api/timesheets` | Behavior |
| --- | --- | --- |
| GET | `/permissions` | Current active/reviewer capability flags for UI |
| GET | `/reviewer/inbox` | Submitted sheets in current reviewer scope; 403 without reviewer scope |
| GET | `/reviewer/{id}` | Scoped entries and full decision history; unrelated/self reviewer gets 403 |
| POST | `/{id}/approve` | `{version,reason?}`; decide submitted sheet |
| POST | `/{id}/reject` | `{version,reason}`; nonblank reason required |
| POST | `/{id}/reopen` | Owner-only `{version}`; rejected sheet becomes a draft |
| GET | `/summary?from=...&to=...&employeeId=...` | Approved minutes grouped by employee/week |
| GET | `/summary?from=...&to=...&departmentId=...` | Same-department head or HR department summary |

Summary dates are inclusive Mondays, at most 53 weeks. Choose employee or department, never both. Without a
scope parameter the summary is the active owner's own approved weeks. Another employee requires the reviewer
predicate; department scope requires an active same-department head or HR. Reporting managers do not implicitly
receive department-wide access. Draft, submitted and rejected sheets contribute no minutes. No pay/rate/currency
fields are stored or returned.

### Atomicity and events

Review locks the sheet row, checks authorization, `SUBMITTED` and the expected version, and performs a guarded
update. The new status/version, append-only decision, mutation audit and shared outbox insert commit in one
transaction. Concurrent approve/reject calls produce one 200 and one 409; the loser adds no decision or event.
Lost decision responses are resolved by reloading detail/history; repeating an old version returns 409 rather
than creating another logical event. Reopen also checks the current version and leaves the prior decision rows.

Each decision emits `timesheet.decided` in the existing EventEnvelope. Payload:
`{decisionId,sheetId,employeeId,weekStart,outcome,reviewerId,decidedAt,reason,version}`. One decisionId identifies one
logical decision, including a new review after resubmission. Existing `timesheet.submitted` consumers should treat
sheet ID plus submission version as the logical submission; sheet ID alone is no longer a lifetime submission key.
Migrations V3 (timesheets) and V7 (notifications) are additive and retain existing rows. Start notification-service
with its migration/queue before enabling decisions; shared outbox retries preserve events while it is unavailable.

`notification.timesheet.q` binds `timesheet.decided` and `user.verified`, using common retry/parking topology and
IdempotentConsumer. The consumer transaction stores one delivery per decisionId and unique eventId; it never sends
SMTP on the broker thread. A local contact projection accepts newer verifiedAt snapshots. V7 copies contacts
already owned by notification-service's leave replica. Missing historical contacts require replay of authoritative
`user.verified` events through the existing broker operations procedure; missing contact rows leave delivery pending.

Delivery workers lock due rows with `FOR UPDATE SKIP LOCKED`, retain attempts/error/next-attempt/sent timestamps,
and retry failures indefinitely with bounded backoff (up to 300 seconds). Successful rows are not resent on normal
redelivery or worker retries. Both outcomes have dedicated templates. Stable SMTP Message-ID uses decisionId.
SMTP cannot guarantee exactly-once delivery across an acceptance/commit crash; a relay may receive a duplicate
with the same Message-ID in that ambiguity window. Broker deduplication and durable logical delivery tracking do
not claim to eliminate that SMTP limitation. Approval state never depends on notification availability.

### Focused verification and disposable browser walkthrough

```sh
mvn -B -ntp -pl timesheet-service,notification-service -am verify
python3 -m unittest discover -s timesheet-service/scripts -p test_backfill.py -v
# frontend
npm run lint
npx tsc --noEmit
npm run build
```

For browser verification, start the existing opt-in disposable fixture (PostgreSQL/RabbitMQ; never development data):

```sh
TIMESHEET_BROWSER_FIXTURE=true mvn -pl timesheet-service test -Dtest=TimesheetBrowserFixtureTest
node timesheet-service/browser-fixture/gateway.mjs
# frontend, after build; test gateway binds only loopback
GATEWAY_INTERNAL_URL=http://127.0.0.1:18086 npm run start -- --hostname 127.0.0.1 --port 3006
```

Sign in at `http://127.0.0.1:3006/auth` with synthetic `manager@example.test` and any nonempty form-valid password.
The fixture manager (6201) reviews employee 6101; unrelated manager (6202) reviews only employee 6102.
Synthetic employee login is `employee@example.test`; unrelated manager login is `unrelated@example.test`.
Open `/timesheets/review`, inspect the 90-minute entry, try rejecting with no reason, then reject with a reason.
As employee, check history, reopen, change/save entries and resubmit. As manager, approve the new submission;
reload both pages and confirm both decisions remain. As unrelated manager, confirm the first employee is absent.
As employee, open the review URL and confirm denial and no review actions. API integration tests separately assert
403 for unrelated manager and self decision requests, and exercise simultaneous approve/reject.
Create `timesheet-service/target/browser-fixture/stop` to end the disposable backend. Stop the two local Node servers.
The fixture uses a synthetic identity/gateway; production identity, SMTP and actual-source reconciliation must be
validated in the rollout environment. Packaged-gateway integration tests exercise real RS256 identity verification.

### Item 32 verification executed on 2026-10-27

| Check | Evidence |
| --- | --- |
| Unchanged backend baseline | `mvn -B -ntp -f /tmp/ems-timesheet-baseline/pom.xml verify` on an archive of development `a7420e3`: BUILD SUCCESS, 182 discovered tests, 3 skipped, no failures/errors. |
| Timesheet service | `mvn -B -ntp -pl timesheet-service verify`: BUILD SUCCESS; 10 integration tests passed, opt-in browser fixture skipped in this suite. PostgreSQL/RabbitMQ containers exercised authorization, competing decisions (200/409, one decision/outbox row), rejection/reopen/resubmission, scoped approved minutes and all three packaged-gateway profiles. |
| Final privacy regression | `mvn -B -ntp -pl timesheet-service verify '-Dtest=TimesheetIntegrationTest#managerScopeAtomicDecisionAndSummary+rejectedSheetReopensAndResubmitsWithImmutableHistory+departmentAndHrScopesAreCurrentActiveAndProjectionClocksAreIndependent'`: BUILD SUCCESS, 3 passed, no skips. Includes reviewer denial for reopened draft detail. |
| Notification service | `mvn -B -ntp -pl notification-service clean verify`: BUILD SUCCESS, 18 tests passed, no skips. Includes real isolated PostgreSQL/Flyway, duplicate event/logical decision deduplication, missing recipient, stale contact snapshot, SMTP failure/retry and successful-send suppression; SMTP sender mocked. |
| Existing backfill utility | `python3 -m unittest discover -s timesheet-service/scripts -p test_backfill.py -v`: 3 passed. |
| Frontend baseline and final | `npm run lint`: 0 errors, 6 pre-existing warnings; `npx tsc --noEmit`: passed; `npm run build`: passed. Google Fonts fetching required network access. |
| Browser | Built frontend + real disposable timesheet backend + synthetic loopback identity/gateway. Manager inbox contained only employee 6101; unrelated manager contained only 6102; employee review URL denied with no decision actions. Empty rejection reason was blocked. Reject reason persisted, owner reopened, revised/saved/resubmitted, manager approved, and employee reload retained APPROVED and both decisions, with editing/reopen controls unavailable. Project label remains correct on final-build reload. |

Initial sandbox-only attempts could not access Docker or fetch existing Google Fonts; the recorded successful
runs used the required host access. The opt-in browser fixture was run separately and stopped after verification.
No real credentials, live data, production deployment or Git commit was used. Changes remain reviewable on both
`feat/timesheet-approval` branches; this document supplies PR implementation, dependency and compatibility notes.

External prerequisites not exercised here: real-source manager/department/role reconciliation; historical contact
event replay where no local contact exists; real production identity sessions; real SMTP acceptance/delivery;
and environment rollout of migrations, routing and durable subscriptions. These remain rollout checks. SMTP
acceptance/commit ambiguity remains as documented above. No final whole-repository backend run is claimed:
the unchanged whole-repository baseline and the affected services' verification runs are the executed evidence.

## Attendance clock and HR corrections (item 33)

Both repositories use `feat/attendance-clock`, based on backend development `8031a1c` and frontend
`9fa091e`. Items 31 and 32 were inspected in `TimesheetService`, `ReviewScope`, projection handling,
Flyway migrations, the checked-in contract, the cookie BFF and the timesheet/review pages.

Attendance is **recorded presence**, independent of timesheet approval and leave. Clock actions and
corrections do not create timesheets, approve entries, change leave balances or resolve leave conflicts.
No attendance events or downstream consumers are introduced; the existing shared outbox remains unchanged.
Storage stays disabled. There are no new runtime or test packages, PostgreSQL extensions, or library versions.

V4 adds `attendance_session` and `attendance_correction_audit` to the timesheet-owned database only.
It is additive and preserves existing sheet/review data. PostgreSQL's built-in GiST range operators enforce
non-overlap for each employee without requiring `btree_gist` or extension-install privileges. A partial unique
index independently enforces one open session; a check prevents negative duration. Intervals are half-open
`[clockIn, clockOut)`, so adjacent sessions are valid. Zero-duration closed sessions are allowed and contribute
zero presence. An open session extends indefinitely for overlap checking.

UTC instants use `timestamptz` at microsecond precision. The existing `ORGANIZATION_TIMEZONE` setting controls
all date grouping and frontend formatting, independently of the browser's timezone. Keep it consistent with
leave/organization deployments. History orders by UTC clock-in descending and UUID for stable pagination.
Concurrent changes can move rows between numbered pages; reload the history after corrections.

### Attendance API / retry contract

All paths below follow `/api/timesheets/attendance`. The additive OpenAPI contract is version 1.2.0;
existing timesheet payloads are unchanged. Frontend types are generated using the installed toolchain:

```sh
node_modules/.bin/openapi-typescript ../ems-backend/timesheet-service/api/openapi.json \
  -o 'app/(user)/attendance/contract.d.ts'
```

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/current` | Organization timezone, serverTime, reconciled HR capability and own zero/one open session |
| POST | `/clock-in` | `{requestId: UUID}`; server sets clock-in, requestId identifies the persisted session |
| POST | `/{id}/clock-out` | Close that specific own session using server time; no client timestamp |
| GET | `/history?page=0&size=20` | Own history; size 1–100, total count and pagination metadata |
| GET | `/summary?from=YYYY-MM-DD&to=YYYY-MM-DD` | Own daily recorded seconds, inclusive period up to 366 days, open count |
| GET | `/hr/history?employeeId=...&page=0&size=20` | Explicit active-HR-only employee lookup |
| POST | `/hr/{id}/corrections` | `{version,clockIn,clockOut,reason}`; explicit active-HR-only correction |
| GET | `/{id}/corrections` | Before/after audit, actor, reason, time and resulting version; owner or active HR |

Use one stable random requestId for each intended clock-in. Replays return the same session, even after it
closes or a later session opens. Competing distinct IDs while open return 409; they do not create another
session. Failed requests do not reserve their IDs; after a definite validation/conflict response reload state
before initiating a new action. Clock-out retries target the original session ID and preserve its persisted
close time; they never close a later session. In-flight browser clicks are guarded immediately and disabled
while pending. Only an unresolved operation token is retained in sessionStorage, keyed to the signed-in user;
attendance records and reload status always come from the server. A lost response can be recovered with
Retry clock action, including after reload. Cookie refresh replays retain the same operation body.

Clock-in requires the existing active employee projection; closed history remains readable by its owner.
Clock-out can finish an existing session after deactivation. HR permission requires an active local employee
and a reconciled `timesheet_reviewer` HR_MANAGER row, using item 32's SYSTEM_ADMIN reconciliation endpoint.
Manager, department-head and browser/JWT role labels alone grant no correction authority. All corrections
are authorized server-side, including when the UI hides the dialog. Target selection is only on explicit HR
routes; own APIs take identity from the gateway. Service ports must remain private under the existing trust model.

Corrections require a nonblank trimmed reason of at most 500 characters and the latest version. UTC instants
or ISO timestamps with an explicit offset are accepted; future times, negative duration and overlapping
sessions are rejected. A closed session cannot be reopened, preventing a delayed clock-out retry from
closing a reopened session. A correction may close an open session. A stale correction returns 409 without
adding an audit row; reload before retrying. Audit rows append reason, HR actor, correction time, before/after
instants and resulting version in the same transaction as the session update. Audit data is visible in the
page's Correction history. There is no application endpoint to delete sessions or audit rows.

Projection locks coordinate authority changes with writes; employee-specific transaction advisory locks
serialize clock/correction actions. Database constraints provide an additional guard against invalid direct
writes. No source-service database or synchronous upstream authorization call is used.

### Overnight sessions and summaries

A Colombo session from 22:00 on October 20 to 06:00 on October 21 remains one eight-hour session:
`2026-10-20T16:30:00Z` → `2026-10-21T00:30:00Z`. History shows both organization-local dates. A summary
allocates two hours to October 20 and six hours to October 21. Local midnight boundaries use ZoneId rules;
a daylight-saving transition can produce a 23- or 25-hour day. Sessions crossing the selected period are
clipped to that period. Open sessions are counted separately and excluded from duration totals until closed.
Daily durations aggregate microseconds before truncating to whole seconds; history also reports whole
seconds, so subsecond remainders may cause a one-second difference across split dates.

Open `/attendance` directly or use the dashboard clock's history link. The page uses existing React Query,
react-hook-form/Zod, button styles and the cookie API client/BFF. The async server page checks the session
cookie; the backend independently enforces every read/write permission. No shared navigation or BFF changes
are required. HR enters full ISO timestamps with an explicit offset in the correction dialog, avoiding
ambiguous local daylight-saving times.

### Verification and release gate

```sh
# Unchanged development archive baseline
mvn -B -ntp -f /tmp/attendance-baseline/pom.xml verify
# Focused implementation verification (disposable PostgreSQL/RabbitMQ)
mvn -B -ntp -pl timesheet-service clean verify
python3 -m unittest discover -s timesheet-service/scripts -p test_backfill.py -v
# Frontend
npm run lint
npx tsc --noEmit
npm run build
```

The disposable browser fixture now includes `hr@example.test` (6301), reconciled HR authority and an eight-hour
overnight presence session for employee 6101. Start it with item 32's commands. Sign in as employee, double-click
clock-in, reload `/attendance`, clock out, reload again and check a single closed session. Check the overnight
row and summary dates. As employee, verify no correction control and API denial for a different employee.
As HR, load employee 6101, open Correct session, try a blank reason, then save valid changed instants/reason.
Reload history and inspect before/after values, actor and reason. Check negative/overlapping corrections are
rejected with inputs retained. API integration tests separately exercise simultaneous requests and constraints.

Required promotion sequence: feature PRs into development in both repositories; required title/build gates
and review acceptance; development-to-staging PRs; staging leave failure demo plus timesheet review and
attendance walkthrough; release acceptance; tag the accepted release commit `v0.4.0` in each repository.
Do not tag the unaccepted feature head or bypass PR gates. Production deployment is excluded from this task.
Use Conventional Commit titles, for example `feat(timesheet): add attendance clock and audited corrections`
and `feat(timesheet): add attendance clock and HR correction UI`. Include these dependency/compatibility notes
and only the verification actually run in each PR's existing template. The current backend CI path filter does
not select timesheet-service; a green no-module run does not prove the focused tests ran. Fixing shared CI is
outside this change's allowed paths, so supply the focused verification as a separate acceptance gate.

### Attendance verification executed on 2026-10-29

| Check | Executed evidence |
| --- | --- |
| Untouched backend baseline | Archive of current development `8031a1c`; `mvn -B -ntp verify`: BUILD SUCCESS, 188 discovered tests, 0 failures/errors, 3 skipped. |
| Existing timesheet regression | All 10 `TimesheetIntegrationTest` cases passed in the changed service, including review decisions, rejection/reopen, scope/privacy and real packaged gateway probes for development/staging/production profiles. These profile probes are local tests, not a staging deployment. |
| Attendance integration | 5 `AttendanceIntegrationTest` cases passed against isolated PostgreSQL/Flyway. Concurrent identical/different clock-ins, simultaneous clock-outs, old-action retries with a later open session, no auto-timesheet creation, Colombo midnight allocation, 25-hour DST day, pagination, own/other authorization, HR correction auditing/revocation, blank reason, stale version, overlap, negative duration and database constraints. |
| Final attendance verification | `mvn -B -ntp -pl timesheet-service verify -Dtest=AttendanceIntegrationTest`: BUILD SUCCESS, 5 tests, 0 failures/errors/skips; final exception handler compiled and Spotless passed. The initial full focused run passed all 15 integration cases but failed formatting after the new exception-handler edit; formatting was repaired before this final run. |
| Existing backfill utility | 3 tests passed. |
| Frontend | Final `npm run lint`: 0 errors, same 6 existing warnings; `npx tsc --noEmit`: passed; final `npm run build`: passed. No packages added. Initial sandbox build could not fetch existing fonts; the successful build used host network access. |
| Built-frontend browser | Cookie sign-in through the synthetic loopback gateway and real disposable backend. Double-click clock-in produced one new session alongside the single seeded overnight session. Full reload restored the open session; clock-out persisted a 26-second session. Overnight display was 22:00–06:00 Colombo and 28,800 seconds. Employee had no correction controls. HR loaded employee 6101, blank reason was blocked, an offset timestamp correction changed the overnight duration to 25,200 seconds, and fetched audit showed before/after, HR actor 6301, reason and correction timestamp. |
| Existing review browser | Manager inbox scoped to employee 6101; 90-minute submitted detail displayed; blank rejection reason blocked; approval returned APPROVED/version 3 with reviewer 6201 retained in decision history. |

The browser's existing mobile navigation opens over the content and must be closed before interacting; no
shared navigation code was changed. Initial clicks hit that overlay, not the clock. Production cookie sign-in
and workflows were then verified successfully on the built frontend. A development server was also started
while diagnosing the overlay; it was not needed for the successful built-frontend checks.

Browser automation did not successfully populate native date-picker fields, so **browser period-summary form
submission is not claimed**. The summary API's overnight/DST/range behavior is covered by the executed backend
tests. Real identity sessions, source projection reconciliation and a deployed staging walkthrough (including
the leave failure demo and timesheet review) remain external rollout checks. No staging URL/test-account access
was supplied. No staging acceptance or `v0.4.0` tag is claimed; release tagging must wait for those gates.

All browser data is synthetic and all test databases/brokers disposable. No live data was deleted and no
production deployment was performed. PRs must carry these exact limitations rather than treating profile
probes or an unchanged baseline as deployed-staging verification.

Publication attempt: GitHub repository reads succeeded and both remote development tips matched the local
bases. Creating a Git tree in each repository returned HTTP 403, `Resource not accessible by integration`.
A noninteractive Git push dry-run also failed because no HTTPS credential is configured. Repository Contents
write access or an authenticated Git connection is required to publish the local commits and open the feature
PRs. This is an access prerequisite, not a successful PR, merge, staging promotion or release. No tag was made.
