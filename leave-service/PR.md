# feat(leave): add leave types, annual entitlements and balance ledger

The leave scaffold had no policy or balance data. HR_MANAGER and SYSTEM_ADMIN can
now administer leave types, explicitly allocate one employee/type/year entitlement,
and make justified, audited corrections. Employees can read only their own balances.
The frontend adds Leave Management with administration forms, ledger history and a
balance panel separating entitlement, adjustments, taken, reserved and available days.

V3 adds leave-owned tables with numeric(9,2) quantities, immutable allocation/ledger
records and a reservation integration seam. Entitlement row locks serialize every
balance mutation; negative available balances are rejected. Adjustment retry keys
are unique per entitlement and identical retries return the original audit entry.
Allocation/adjustment events commit atomically through the existing shared outbox.
The working calendar defaults to Asia/Colombo and Monday–Friday, supports configured
holidays, and includes only an opt-in synthetic development fixture. No accrual,
carry-over, yearly jobs or public leave-request/approval workflow is introduced.

## Dependencies and compatibility

No dependency or version changes. Reuses ems-common security/errors/outbox,
PostgreSQL/Flyway/JDBC with the existing JPA transaction manager, and the installed
React Hook Form/Zod, TanStack Query, Button/theme and cookie-only Axios/BFF stack.
Storage remains disabled because there are no attachment operations.

V3 is additive; previous migrations, projection events, gateway routes and consumers
are unchanged. Deploy backend/migration before the frontend. The stable OpenAPI
contract is leave-service/api/leave.openapi.json; frontend leave-api.d.ts is generated
from it with the installed openapi-typescript. Decimal strings preserve precision.
See docs/leave.md for endpoints, error/retry semantics, events and operating steps.

Existing employees/status changes still need the established SYSTEM_ADMIN
reconciliation procedure. Operators must supply approved production holidays and
real yearly amounts, configure durable consumers for new leave event routing keys,
and verify gateway/network and CI path wiring outside this task's allowlist.
There are no shared-database queries, new storage conventions or unrelated refactors.

## Verification

- Clean original development baseline, isolated snapshot: focused gateway/leave
  verify passed (26 reported, 25 executed successfully, one existing storage skip).
- Full `mvn -B verify`: BUILD SUCCESS; 164 tests reported, 162 executed successfully,
  zero failures/errors. Two explicit skips: existing opt-in shared storage smoke
  test and the opt-in browser fixture (run separately for browser verification).
- Focused `mvn -B -pl api-gateway,leave-service -am verify`: BUILD SUCCESS. All 14
  ordinary leave tests pass, including 10 + 2 → 12 after reload, permission denial,
  concurrent last-day reservation, concurrent adjustment-key deduplication,
  append-only/precision guards, holidays, settlement/release retries and actual
  RabbitMQ outbox delivery. Packaged gateway JWT verification passes in all profiles.
- Frontend lint, typecheck and production build pass; six pre-existing lint warnings
  remain outside the changed files. Contract regeneration matches checked-in types.
- Browser: real Next/BFF and leave service with disposable PostgreSQL/RabbitMQ;
  synthetic identity/gateway forwarding only. HR type creation, 10-day allocation,
  justified +2-day adjustment, ledger actor/reason and reload to 12 verified.
  Employee view hides administration. Type deactivation preserves balances; nonzero
  taken/reserved breakdown, loading, validation, empty, authorization error and
  temporary API failure/retry recovery were observed. Detailed final evidence is in docs/leave.md.

An earlier verification attempt failed because RabbitMQ 4 rejects transient
nonexclusive test queues. The audit fixture now declares a durable queue; focused
and full verification passed after the fix. Initial restricted-network frontend
build could not fetch existing Google Fonts; the network-enabled build passed.

Generated verification logs/screenshots are under leave-service/target. Real identity
login, live employee reconciliation, production migration/deployment and the shared
Compose storage smoke test were not exercised. No secrets or live-data deletion.
