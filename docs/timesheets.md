# Timesheet drafts and submission

## Implementation and compatibility / PR notes

Backend and frontend branch: `feat/timesheet-service`, based on their existing local `development` tips.
Inspected actual project/team implementation (item 26) and leave workflow/outbox implementation (item 30).
Projects are owned by **org-service**, not a separate project-service; project membership is inherited from its team.

The new Maven module listens on **8086** and owns **timesheet_db**. Gateway development, staging and production
profiles route `/api/timesheets/**` to `TIMESHEET_SERVICE_URL` (local default `http://localhost:8086`).
No reads of other services' databases and no synchronous upstream calls are made by timesheet reads or writes.
Storage is disabled because timesheets contain no attachments. Payroll and review transitions are deliberately absent.

Runtime dependencies match the installed Spring Boot 4.0.8 / Java 21 service stack: ems-common, JDBC via JPA,
PostgreSQL, Flyway, MVC/validation/security, AMQP, actuator and AspectJ for the shared idempotent consumer.
No new library versions or frontend packages are introduced. Existing shared outbox/storage/security code is unchanged.
Testcontainers PostgreSQL and RabbitMQ follow existing integration-test conventions. Lombok is inherited from the
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
else's sheet. Backfill is restricted separately to SYSTEM_ADMIN. All sheet reads are self-only.

Frontend: use the installed Node runtime and the existing Next.js commands. Open **`/timesheets`** directly.
No shared navigation files are changed under the page/service-only frontend scope. The page checks the session
cookie server-side; all API access still passes through the existing cookie BFF and authenticated gateway.
An active local employee and eligible project membership are required for writes; those checks run on the backend.

## Projections, ordering and history

`timesheet.q` subscribes to `user.verified`, `project.created`, `project.updated`, `project.deleted`,
`team.member.added` and `team.member.removed`. Shared IdempotentConsumer records and updates commit together.
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
node_modules/.bin/openapi-typescript ../ems-backend/timesheet-service/api/openapi.json -o app/services/timesheets-api.d.ts
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

Statuses DRAFT, SUBMITTED, APPROVED, REJECTED and review audit fields are stored. Only DRAFT creation/update/delete
and DRAFT→SUBMITTED are implemented. Created/updated/submitted actor IDs and timestamps, plus mutation audit rows,
are persisted. A retry of submit returns the already frozen result regardless of the original version and adds no
second submission event. Submission is serialized against saves and concurrent submits.

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

### Executed on 2026-10-27

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
