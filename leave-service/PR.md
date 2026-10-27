# feat(leave): submit, review, reject and cancel leave requests

Employees can now submit leave requests with server-calculated working days and
view their history. Managers receive a scoped inbox and can reject pending requests;
employees can cancel their own pending requests. The frontend previews server
calculations, displays history/status, preserves retry keys, and keeps approval
unavailable until item 30.

V4 adds requests, action audit records and local fallback-reviewer snapshots. An
employee transaction lock serializes overlap checks across leave types; item 28's
entitlement lock reserves balance atomically with the request and outbox event.
Reject/cancel release exactly once under a request lock. Submission/action keys
reject conflicting reuse and deduplicate retries. No operation enters APPROVED.
Managers come from the local projection; fallback selects a same-department head,
then HR. Active HR can route to a different eligible reviewer without self-approval.

## Dependencies and compatibility

No new dependencies or version changes. Reuses installed Java 21, Boot 4,
PostgreSQL/Flyway/JDBC, ems-common security/errors/outbox, and disabled storage.
Frontend uses existing React Hook Form/Zod, TanStack Query, Button/theme and the
cookie-based Axios/BFF. Types are generated from the additive OpenAPI v1.1 contract.

V1–V3, existing employee snapshot bodies and item 28 endpoints/events are unchanged.
Deploy the V4 backend before the frontend. Bind a durable consumer to
leave.request.changed. Existing producer events lack reviewer roles: SYSTEM_ADMIN
must reconcile local fallback-reviewer role snapshots through the new endpoint.
Managers use the existing projection. Real employee status/manager reconciliation,
approved holidays and entitlement allocations remain operational prerequisites.
There are no shared-database reads, attachment changes or unrelated refactors.

## Verification

See docs/leave.md, Item 29 verification evidence, for baseline, focused build,
frontend checks, browser workflows, limitations and the initial gateway timeout.
