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
optional Lombok dependency, though the claims code uses Java records. Shared storage was disabled in the initial claims feature; the receipt extension
below now enables it and adds the explicit receipt submission policy.

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

## Receipt attachments — feat/claim-receipts (2026-10-29)

### PR description and compatibility

Draft owners can attach receipts directly from the browser to private
`ems-attachments` storage, finalize verified metadata, preview/download receipts,
and remove draft receipts. **Proposed receipt policy implemented: every new claim
submission requires at least one finalized receipt.** The API returns HTTP 400 with
`Receipt policy: at least one finalized receipt is required before submission`.
The UI disables submission until a finalized receipt is loaded. Existing submitted
claims remain readable and submit retries keep their prior behavior; existing drafts
must add a receipt before submitting. Receipt mutations do not change the claim's
line-edit version; all mutations share the claim row lock with submission.

Both repositories' `feat/claim-receipts` branches started at their current local
`development` heads (backend `3cf92f7`, frontend `48cb350`). Inspected item 24's
EmployeeCvService, item 34's StorageService/StorageAutoConfiguration/BucketBootstrap,
EmployeeAvatarService and useFileUpload, plus claim security/local scope and outbox.
Reuses their exact-length, create-only presigned PUT, HEAD verification, cookie BFF
and query conventions. No Java/BFF binary proxy, shared-database reads, deployments,
new packages or new dependency versions. GenericContainer uses the already installed
Testcontainers stack and the same digest-pinned MinIO test image as employee-service.

Flyway V3 adds claim-owned receipt/intent metadata: generated UUID and object key,
claim and owner, filename, MIME type, byte size, expiry, finalized/deleted flags and
creation time. Claim responses and existing outbox event payloads stay compatible;
receipt metadata has separate authorized endpoints. Claim deletion detaches receipt
rows for compensation. Existing common storage code and other services are unchanged.
The optional receipt-intent extension to useFileUpload preserves avatar/CV request
shapes. Generated frontend types come from `claim-service/api/openapi.json`:

```bash
# From ems-frontend, using installed tooling
npx openapi-typescript ../ems-backend/claim-service/api/openapi.json -o 'app/(user)/claims/contract.d.ts'
```

### Receipt API and security

- POST `/api/claims/{claim}/receipts/upload-intents` accepts `filename`,
  `contentType`, `size`; returns `intentId`, `uploadUrl`, required `headers`,
  `maxSize` and `expiresAt`. Only the active draft owner can request an intent.
  Keys are server-generated under `claims/{owner}/{claim}/{uuid}`. Supported MIME
  types: PDF, JPEG, PNG, WebP; positive size, maximum **10 MiB (10,485,760 bytes)**;
  upload intent and signed PUT expire in five minutes.
- Browser PUTs the raw file directly to storage with the returned Content-Type and
  If-None-Match headers. Content-Length is signed and supplied by the browser.
  Create-only writes prevent replacement of a verified receipt.
- PUT `/api/claims/{claim}/receipts/{intentId}/finalize` accepts no object key.
  It checks draft ownership, claim/intent association, expiry and storage HEAD's
  actual byte length and MIME metadata against the intent before marking finalized.
  Oversized/type-mismatched objects fail even if client validation was bypassed.
  Expired unfinished intents return 410; missing objects return 400; transient
  verification failures return 503. A finalized intent retry returns the same
  receipt, with no duplicate attachment record. MIME metadata validation follows
  existing storage conventions; it does not decode or virus-scan file contents.
- GET `/api/claims/{claim}/receipts` returns finalized metadata only.
  GET `/api/claims/{claim}/receipts/{id}/download` issues a five-minute signed GET.
  Owner access requires active employee scope. Reviewers use the same current local
  reporting-manager/HR eligibility as claim review and cannot read drafts. Another
  employee cannot attach/finalize a foreign intent or obtain its download URL.
  Signed URLs are temporary bearer capabilities; do not log or share them.
- DELETE `/api/claims/{claim}/receipts/{id}` permits draft-owner removal/cancellation
  only. Submitted, approved, rejected and withdrawn claims retain receipts.
  Removing a receipt hides it immediately and retains a cleanup tombstone.

The cleanup worker runs every 60 seconds (override `ems.claims.receipt-cleanup-ms`),
processing up to 100 expired unfinished/cancelled/orphaned intents per run, after a
10-minute grace beyond upload expiry. It locks claim then intent, rechecks state,
uses SKIP LOCKED for competing workers, and retries storage failures without losing
metadata. Finalized receipts attached to any retained claim are excluded regardless
of upload expiry. Deleted-draft orphan objects are eligible. Storage deletion is
idempotent; rollback after storage deletion is safe to retry. Avoid a blanket bucket
lifecycle rule deleting `claims/` keys, which would also remove retained receipts.

UI progress reports the direct PUT; a completed PUT is retained in the hook while
finalization retries reuse its intent. A lost successful PUT response (412 on retry)
proceeds to server verification. After intent expiry, retry creates a new intent.
Reopening/reloading shows only finalized records. Preview/download URLs are fetched
on demand; select preview again to renew an expired link.

### External setup and reproduction

Storage is now enabled by default in claim-service. Use `claim-service/.env.example`
to configure MinIO locally, including endpoint/public endpoint, region, path-style
access and credentials supplied outside Git. For S3 leave endpoints/credentials empty
to use the configured region and workload/default credential chain. Storage bootstrap
uses the existing common private bucket creation and CORS behavior and fails startup
on connectivity/authentication errors. The existing bootstrap also checks avatar and
document buckets; the claim workload needs the corresponding existing bootstrap
permissions as well as receipt PUT/HEAD/GET/DELETE. Provision object storage/CORS before
starting the new service version. `STORAGE_ENABLED=false` permits metadata reads but
upload/download require storage and new submissions still require a receipt.

Allow the frontend origin and PUT/GET/HEAD with Content-Type and If-None-Match in
storage CORS. Public endpoint must be reachable by the browser without rewriting
signed hosts. No live storage, deployment or live-data deletion is performed by these
tests. Real S3 IAM/CORS, production credentials, live RS256 sign-in and real account
scope synchronization remain external prerequisites.

```bash
# From ems-backend; Docker required, all containers disposable
mvn -B -ntp -pl claim-service -am verify
mvn -B -ntp -pl claim-service -am -Dtest=ReceiptIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
# Optional browser backend (PostgreSQL + RabbitMQ + MinIO)
CLAIM_BROWSER_FIXTURE=true mvn -pl claim-service -am -Dtest=ClaimBrowserFixtureTest -Dsurefire.failIfNoSpecifiedTests=false test
node claim-service/browser-fixture/gateway.mjs
# From ems-frontend (production build first)
GATEWAY_INTERNAL_URL=http://127.0.0.1:18087 npm start -- --hostname 127.0.0.1 --port 3008
```

Use employee/manager/other/unrelated/hr at `example.test` and any test password in
this synthetic identity fixture only. Write `claim-service/target/browser-fixture/stop`
to stop its disposable containers. Never connect a deployment to the fixture.

### Receipt verification evidence — 2026-10-29, Asia/Colombo

- Initial restricted backend baseline could not access Docker. With Docker access,
  final `mvn -B -ntp -pl claim-service -am verify` passed: common 15 reported
  (14 executed, one storage opt-in skip), claims 10 reported (nine executed,
  one browser-fixture opt-in skip), **23 executed, no failures/errors**.
- Final focused `mvn -B -ntp -pl claim-service -am -Dtest=ReceiptIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false verify` passed: **three tests, no failures,
  errors or skips**, after adding storage SDK error handling, overwrite prevention
  and cleanup outage recovery checks.
- Tests use disposable PostgreSQL 17 and MinIO. Actual presigned PUT -> HEAD ->
  finalize -> submit -> signed GET for PDF/PNG; owner and manager downloads;
  unrelated employee denied; private drafts; foreign claim/intent association
  rejected; arbitrary body objectKey ignored; repeated finalization gives one row;
  create-only overwrite returns 412; oversized (>10 MiB) and wrong MIME objects
  inserted using privileged test-only S3 access fail finalization; expired unfinished
  intent returns 410; cleanup deletes abandoned/deleted-draft objects and preserves
  submitted finalized receipts after expiry. A simulated storage deletion outage
  retains the cleanup row, then a subsequent worker pass succeeds.
- Frontend `npm run lint` passed with six existing warnings, no errors.
  `npx tsc --noEmit` passed. Restricted production build failed fetching existing
  Google Fonts; final `npm run build` with network access passed. No dependencies
  or font settings were changed.
- Browser: production frontend on loopback 3008, existing cookie BFF, synthetic
  identity gateway 18087, actual claim-service and disposable PostgreSQL/RabbitMQ/
  MinIO. Saved a LKR 2.00 line; submission disabled without a receipt. PDF upload
  displayed progress and an injected 503 after successful finalization; Retry
  completed with exactly one PDF attachment. PNG upload also succeeded; submission
  retained both receipts and removed edit/upload/delete controls. Owner's signed
  PNG preview loaded from MinIO. Manager's scoped inbox displayed the submitted
  claim and both receipt controls; its PDF download SHA-256 exactly matched the
  uploaded PDF. Another employee's direct cookie-BFF download request returned
  HTTP 403. Final build's PDF preview control was inspected; inline PDF rendering
  depends on browser support, with the open/download link available separately.
- To reproduce the lost-confirmation browser test, start the fixture gateway with
  `CLAIM_RECEIPT_FAIL_FINALIZE_ONCE=true node claim-service/browser-fixture/gateway.mjs`.
  Only its first successful finalize response is replaced with a test 503; storage
  and database work already completed, so retry exercises idempotent finalization.
- Live RS256 sessions, actual source-account reconciliation, real S3 IAM/CORS and
  external production storage were not exercised. Synthetic identity is explicitly
  a local test fixture. No production deployment or live-data deletion occurred.
