# feat(leave): scaffold leave service and employee projection

## What changed

Add leave-service on port 8085 with leave_db, all three gateway routes, the
employee-service security/profile/error conventions, shared Flyway outbox schema,
and QueueFactory topology. A local employee projection consumes verified, profile
and assignment events with transactional deduplication, independent timestamp
watermarks and per-user serialization. An admin-only PUT bootstraps existing
employees and reconciles drift without cross-database access.

Document the supplied annual-entitlement policy and proposed action matrix before
adding any leave domain tables. See docs/leave.md for contracts and operations.

## Item

Leave service scaffold, following the completed employee implementation (items
11/26 as referenced in the task). No leave domain workflows are implemented yet.

## Dependencies and compatibility

Dependencies and managed versions match employee-service: ems-common supplies
security/correlation/errors/topology/outbox; JPA and AspectJ enable its persistence
and consumer advice; Flyway/PostgreSQL own schema migration; AMQP handles delivery;
MVC/Security/Validation serve the authenticated API. Testcontainers PostgreSQL and
RabbitMQ verify real infrastructure. No new third-party library/version. Shared
storage stays disabled until leave attachments require it.

Existing event producers/consumers and databases are unchanged. Profile department
snapshots are deliberately ignored in favor of identity assignment timestamps.
Current profile events omit status and profile creation emits no snapshot. New
verification events supply initial ACTIVE state; existing employees/status changes
need the documented reconciliation procedure. Automatic lifecycle publication and
versioned replay require a producer follow-up outside the task's file allowlist.
Timestamp ties use first-writer-wins; reconciliation requires a quiesced, drained
snapshot boundary because existing contracts lack monotonic revisions.

The root pom selects the full reactor in this PR's CI. A future leave-only PR needs
a CI path-filter entry (outside this task's allowed paths). Environment deployment
wiring and real employee inventory reconciliation are operational prerequisites.

## Verification

- [x] Baseline and final `mvn -B clean verify`: passed; final reactor reports
  159 tests, zero failures/errors, one existing opt-in storage test skipped.
- [x] All 10 leave tests passed, including fresh leave_db migration, Rabbit queue
  declaration/delivery, duplicate/stale/concurrent event handling and bootstrap.
- [x] Packaged gateway in development, staging and production: valid JWT ping 200,
  no token/spoofed identity 401, invalid token 401, correlation ID preserved.
- [x] Spotless and `git diff --check` passed.

Final verification details and external prerequisites are recorded in docs/leave.md.
The opt-in shared Compose storage smoke test and live employee reconciliation were
not run. Test logs and gateway profile logs are generated under target; no tokens
or private signing keys persist.

No production deployment, live-data deletion, producer schema change or secret
commit is part of this PR.
