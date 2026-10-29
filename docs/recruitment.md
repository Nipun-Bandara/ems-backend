# Recruitment foundation — items 36–38

## PR description and compatibility

Item 36 adds recruitment-service on port **8088**, with its own **recruitment_db**
and gateway prefix `/api/recruitment/**`. Branch `feat/recruitment-service` starts
at local development `9126de8`. Recruitment remains in scope by default; items 37
(vacancies) and 38 (referrals) are specified below and are not implemented yet.
No public applicant portal, interview scheduling, candidate ranking, frontend or
dashboard changes are included.

Inspected actual item 26 code: org-service ProjectService/TeamService,
ProjectController, membership filtering and transactional OutboxPublisher events.
Inspected item 35 code: claim-service ReceiptController/ReceiptService, receipt
migration and common StorageService, including owner/reviewer checks, create-only
size-bound upload intents, HEAD verification, finalization and private downloads.
Recruitment must not inherit project/team membership as authority over candidates.
Managers and department heads have no candidate access merely from those roles.

Dependencies reuse parent-managed Java 21, Spring Boot 4.0.8 and Spring Cloud
2025.1.3: ems-common for gateway identity, correlation IDs, errors and outbox;
MVC/JSON/security for HTTP; JPA/PostgreSQL for common outbox persistence; Flyway
and its PostgreSQL driver for migrations; AMQP for common outbox delivery;
actuator for health. Tests reuse MVC and Testcontainers PostgreSQL/JUnit. No new
library versions or shared-common changes. Storage remains disabled until item
38 needs attachments; no new storage client or bucket is introduced here.
V1 extends the existing OutboxSchemaMigration, creating outbox_event and
processed_event in recruitment_db. Existing databases and migrations are unchanged.

There are no domain records, ownership checks, employee replicas, consumers or
bootstrap endpoints in this foundation. They are unnecessary for the ping/admin
policy. Before item 38 enables writes, add only the local eligibility projection
specified below. Never query employee_db, identity_db or org_db from this service.
No credentials, deployment or live-data changes are part of this PR.

## Current API and security boundary

| Method/path | Permission | Result |
| --- | --- | --- |
| GET `/api/recruitment/ping` | Any authenticated gateway identity | 200 `{ "service":"recruitment-service", "status":"ok", "userId":"42" }` |
| GET `/api/recruitment/admin/ping` | HR_MANAGER or SYSTEM_ADMIN | Same response |
| GET `/actuator/health` | Health probe | Actuator health |

Anonymous API requests return 401; authenticated non-HR/admin requests to
`/api/recruitment/admin/**` return 403 in the common error format. Method security
also protects the admin ping. All future administration routes live under that
prefix and retain the same policy. Unknown resources expose no candidate data.
The gateway verifies RS256 tokens and replaces client identity headers. The service
uses common GatewayAuthenticationFilter and must be reachable only on the private
service network. Sending X-User headers directly to the service is trusted traffic,
not a public authentication mechanism. CORS/rate limiting remain at the gateway.

## Item 37 contract: vacancies (future)

All paths below are relative to `/api/recruitment`. IDs are server-generated UUIDs;
versions are nonnegative integers starting at 0. Timestamps are UTC ISO-8601.

Vacancy fields: `id`, `title` (trimmed, 1–200), `description` (1–10000), optional
`departmentId` (positive reference only), `status`, `version`, `createdByUserId`,
`createdAt`, `updatedAt`. Never embed candidates or referral counts in employee views.

| API | Access and contract |
| --- | --- |
| POST `/admin/vacancies` | HR/admin; `{title,description,departmentId?}`; 201 vacancy in DRAFT, Location header |
| PUT `/admin/vacancies/{id}` | HR/admin; `{version,title,description,departmentId?}`; edit DRAFT only; 200 vacancy |
| GET `/admin/vacancies` | HR/admin; optional status; paginated vacancy metadata including drafts |
| GET `/admin/vacancies/{id}` | HR/admin; 200 vacancy |
| POST `/admin/vacancies/{id}/transitions` | HR/admin; `{version,status,reason?}`; 200 updated vacancy |
| GET `/vacancies` | Authenticated employees; OPEN only; paginated safe vacancy metadata |
| GET `/vacancies/{id}` | Authenticated employees; OPEN only; otherwise 404 |

Lifecycle: `DRAFT → OPEN`, `DRAFT → CANCELLED`, `OPEN → CLOSED`,
`OPEN → CANCELLED`. CLOSED/CANCELLED are terminal; no reopening or hard deletion.
Closing stops new referrals but allows review of existing submitted referrals.
Cancelling requires a nonblank reason and atomically rejects remaining SUBMITTED
or UNDER_REVIEW referrals with reason `VACANCY_CANCELLED`; DRAFT referrals can
only be withdrawn afterward. It must not silently delete candidate data.

Every mutation checks the expected version under a row lock/atomic compare and
increments it once. Stale versions or disallowed transitions return 409. Retrying
with a stale version returns 409; clients refetch before retrying. Validation is
400, unknown/inaccessible records 404, authorization failure 403. Store audit
actor, before/after state, reason, version and timestamp transactionally with
minimal outbox events (`vacancy.created`, `vacancy.status.changed`). Events must
not contain candidate PII. References never imply cross-database foreign keys.

## Item 38 contract: referrals and candidate privacy (future)

A referral belongs permanently to the authenticated submitting user. The request
cannot choose `referrerUserId`, transfer ownership, or select an object key.
HR/admin may review a submitted referral; they may not impersonate a referrer.
Candidate PII is limited to `candidateName` (1–200), `candidateEmail` (valid,
max 254), optional `candidatePhone` (max 40), and `referrerNote` (max 2000).
Submission requires `consentConfirmed=true` and a finalized CV. No global
candidate directory, duplicate-candidate search or ranking endpoint is allowed.

| API | Access and contract |
| --- | --- |
| POST `/referrals` | Active local employee; `{vacancyId,candidateName,candidateEmail,candidatePhone?,referrerNote?,consentConfirmed}`; 201 owned DRAFT |
| PUT `/referrals/{id}` | Active owner, DRAFT only; `{version,...candidate fields,consentConfirmed}`; 200 owner detail |
| GET `/referrals/me` | Active owner; only own referrals; paginated owner summaries |
| GET `/referrals/{id}` | Active owner only; 200 owner detail, otherwise 404 |
| POST `/referrals/{id}/submit` | Active owner; `{version}`; OPEN vacancy and finalized CV required; 200 owner detail |
| POST `/referrals/{id}/withdraw` | Active owner; `{version}`; 200 owner detail |
| GET `/admin/referrals` | HR/admin; submitted and later states only; filter vacancy/status; paginated restricted HR summaries |
| GET `/admin/referrals/{id}` | HR/admin; submitted and later only; 200 HR detail; DRAFT is 404 |
| POST `/admin/referrals/{id}/transitions` | HR/admin; `{version,status,reason?}`; 200 HR detail |

Lifecycle: `DRAFT → SUBMITTED` by owner; `DRAFT → WITHDRAWN` by owner;
`SUBMITTED → WITHDRAWN` or `UNDER_REVIEW → WITHDRAWN` by owner;
`SUBMITTED → UNDER_REVIEW` by HR/admin;
`SUBMITTED → REJECTED` or `UNDER_REVIEW → REJECTED` by HR/admin;
`UNDER_REVIEW → HIRED` by HR/admin. Rejection requires a reason. HIRED, REJECTED,
WITHDRAWN are terminal; no resubmission or hard-delete API. Candidate fields and CV
are immutable after submission. HR decisions on their own referral are forbidden
(403); another HR/admin must review. Audit history and minimal PII-free outbox
status events are committed in the same transaction. Apply the same version/error
rules as vacancies. Serialize submission with vacancy closure/cancellation, and
serialize withdrawal with HR decisions so only one competing mutation succeeds.

| Data | Active submitting referrer | Other employee/manager | HR/admin |
| --- | --- | --- | --- |
| Own candidate fields, CV, own note | Yes, including terminal state | No | Only after submission |
| Status and safe timeline | Own referral only | No | After submission |
| Internal HR notes/decision reasoning | No | No | After submission |
| Someone else's draft | No | No | No |

Owner status history exposes only state/time and a safe reason code; internal
HR comments and reviewer identity never appear in owner DTOs. Use separate owner
and HR response DTOs. Collection filtering must happen in SQL before pagination;
never fetch broadly and filter after serialization. All identifiers and download
routes repeat ownership/state checks; guessing an ID returns 404 for nonowners.
SYSTEM_ADMIN follows the HR privacy boundary, including no access to others'
drafts. No PII in logs, events, actuator output, dashboard aggregates, metrics or
public vacancy DTOs. Permission tests must cover lists, details, mutations and CVs.

CV routes (future): POST `/referrals/{id}/cv/upload-intents` with
`{filename,contentType,size}`; PUT `/referrals/{id}/cv/{intentId}/finalize`;
GET `/referrals/{id}/cv/{intentId}/download`; DELETE
`/referrals/{id}/cv/{intentId}`. Upload/finalize/delete are active-owner DRAFT-only;
download is active-owner or HR/admin after submission, excluding others' drafts.
Use common StorageService, private `ems-attachments`, server keys under
`recruitment/{owner}/{referral}/{intent}`, PDF only, 1–10 MiB, five-minute URLs,
exact content length and `If-None-Match: *`. HEAD must verify size/type and expiry
before finalization; submit requires finalized metadata. Responses follow item
35's intent/attachment/download shapes, never expose raw keys, and never accept
an arbitrary key. Tombstone deletions and retry orphan cleanup after outstanding
PUT URLs expire. Storage failure is 503; expired intent is 410. Content type/HEAD
validation is not malware scanning; any scanning requirement must be resolved
before enabling CV uploads in production.

Before enabling referrals, implement a recruitment-owned employee eligibility
projection keyed by identity `userId`, containing only `active` and ordered source
timestamps. Consume the existing authoritative identity/employee events with
common IdempotentConsumer and a dedicated durable queue; confirm actual payloads
then, handle deletion/revocation and prevent older bootstrap/events overwriting
newer state. Unknown/inactive referrers fail closed, including reads/downloads.
HR administration relies on trusted token roles, not a client-supplied role or
team projection. Ownership relies on the immutable stored referrer userId.
If history is not replayable, add SYSTEM_ADMIN-only reconciliation plus a local
bootstrap tool using authorized service APIs (never shared-database reads).
Define snapshot ordering and test revocation, duplicates and late events before
acceptance. No bootstrap or projection is needed for item 36.

Pagination for future lists: `page=0`, `limit=20` (1–100), stable createdAt/id
ordering; response `{items,hasNext,hasPrevious}`. Owner DTO includes referral id,
vacancyId, permitted candidate fields, status, version, timestamps and safe history.
HR DTO adds referrerUserId and internal history only on authorized HR routes.
Retention/anonymization periods require an owner-approved policy before production;
no destructive retention job is introduced by this foundation.

## Local setup and verification

Use installed JDK 21/Maven 3.8+. Supply recruitment-service/.env.development from
.env.example with local PostgreSQL and RabbitMQ credentials. The existing
`db/init.sql` provisions recruitment_db on a fresh infrastructure stack; an
existing volume may need an operator to provision that database separately.
Do not reset volumes or delete data. Compose provisioning changes are outside
this task's allowed paths. Restrict direct service access to the gateway network.
`RECRUITMENT_SERVICE_URL` defaults to http://localhost:8088 in development,
staging and production; set it to the private service URL in deployed environments.
After the focused build below, run from the backend root:
`java -jar recruitment-service/target/recruitment-service-0.0.1-SNAPSHOT.jar`.
The development profile imports recruitment-service/.env.development from there.

Focused verification: `mvn -B -pl ems-common,api-gateway,recruitment-service -am verify`.
Build the gateway in the same reactor: the recruitment test launches its packaged
JAR with a disposable RSA/JWKS server, verifies every route profile against the
real recruitment HTTP server, rejects anonymous/invalid tokens and tests that an
EMPLOYEE JWT cannot spoof SYSTEM_ADMIN via client headers. Database tests migrate
an empty disposable PostgreSQL 17 database and verify both common outbox tables.
No production identity, PostgreSQL, RabbitMQ or object storage is used by these tests.

If recruitment is explicitly deferred later, skip **36–38 together**, remove its
module/routes and explicitly remove any downstream recruitment dashboard/test
assumptions in the same authorized change. No deferral was requested here.

## Executed evidence — 29 October 2026 (Asia/Colombo)

- Installed toolchain: OpenJDK 21.0.12.1, Maven 3.8.7, Docker 29.9.2.
- Full reactor baseline `mvn -B clean verify` with Docker access: **BUILD SUCCESS**,
  all 11 modules including recruitment; 208 tests reported, 204 executed, zero
  failures/errors, four existing opt-in skips (common StorageIntegrationTest and
  leave/timesheet/claim browser fixtures). Item 35 ReceiptIntegrationTest ran all
  three tests successfully against disposable PostgreSQL/MinIO. The full build
  emitted existing connection-pool/worker-shutdown diagnostics during teardown;
  all module results were SUCCESS.
- Focused reactor `mvn -B -pl ems-common,api-gateway,recruitment-service -am verify`:
  **BUILD SUCCESS**. Recruitment: 3 tests, zero failures/errors/skips; common:
  15 reported, 14 executed, one pre-existing opt-in storage test skipped;
  gateway: one test passed. Recruitment JAR packaged and Spotless check passed.
- Disposable PostgreSQL 17.11 started empty; V1 migrated successfully; both common
  outbox tables were queried successfully with zero rows.
- Real packaged gateway in development, staging and production: authenticated ping
  200 with JWT userId 42 despite spoofed userId 999; anonymous/spoofed and invalid
  token requests 401; EMPLOYEE JWT plus forged SYSTEM_ADMIN header on admin ping
  403; supplied correlation ID preserved. MockMvc additionally checked HR_MANAGER
  and SYSTEM_ADMIN 200, and employee/department-head/missing/lookalike roles 403.
- Initial sandboxed baseline attempt failed at Docker access. Retried with Docker
  access; no application source workaround or skipped test was used for that failure.

The focused test disables recruitment messaging and storage because there are no
recruitment domain events or attachments yet. It uses generated RSA keys/JWKS,
not the live identity service. Production networking, real login/JWKS, service
credentials, database provisioning and recruitment-specific RabbitMQ delivery
remain external prerequisites; no deployment or live-service end-to-end test was
performed. Shared storage's opt-in test was not enabled by these commands.
