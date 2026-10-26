# Projects

Both repositories use `feat/projects`, based on `development`.

All endpoints are routed through the gateway. Authentication is required for reads;
only `SYSTEM_ADMIN` and `DEPARTMENT_HEAD` may create, update, or delete projects.

| Method | Path | Success |
| --- | --- | --- |
| POST | `/api/projects` | 201, project |
| GET | `/api/projects` | 200, paginated projects |
| GET | `/api/projects/my` | 200, paginated caller membership projects |
| GET | `/api/projects/{id}` | 200, project |
| PUT | `/api/projects/{id}` | 200, updated project |
| DELETE | `/api/projects/{id}` | 204 |

POST and PUT accept the full editable project:

```json
{
  "name": "Customer launch",
  "description": "Prepare the release",
  "teamId": 4,
  "status": "PLANNED",
  "startDate": "2026-10-01",
  "endDate": "2026-11-01"
}
```

Name is required, nonblank, and limited to 100 characters. Description is optional
and limited to 2000 characters. Team ID must reference an existing team. Status is
required: `PLANNED`, `ACTIVE`, `ON_HOLD`, or `COMPLETED`. Dates are optional ISO dates;
when both are present, end date cannot precede start date. PUT clears optional fields
when omitted or null. Responses include `id` and immutable `createdAt`.

Both list endpoints accept `page` (zero based, default 0), `limit` (1–100, default
10), optional `teamId`, and optional `status`. They return:

```json
{ "projects": [], "hasNext": false, "hasPrevious": false }
```

Results are ordered by ascending ID. `/my` uses the authenticated principal from
the gateway and queries current `team_members` rows; caller-supplied user IDs are
never used. Team leads are included only if they are also members. Membership
removal and project reassignment immediately change visibility. The general list
and single-project reads remain available to all authenticated users.

Missing projects or teams return 404, invalid input returns 400, and prohibited
writes return 403. A team referenced by a project cannot be deleted (409); move
or delete its projects first.

Events are recorded atomically with project writes in the existing transactional
outbox, under aggregate type `project` and aggregate ID equal to the project ID:

| Type | Payload | Trigger |
| --- | --- | --- |
| `project.created` | `{ "project": <full project response> }` | Creation |
| `project.updated` | `{ "project": <full project response> }` | Every successful PUT |
| `project.status-changed` | `{ "id", "teamId", "previousStatus", "status" }` | PUT changes status |

The existing outbox poller delivers these routing keys through `ems.events`.
Project updates take a row lock so concurrent status transitions are serialized.

Frontend `/project` shows membership projects with filters and pagination.
`/project-management` provides creation, editing, deletion and team assignment for
the two write roles. Both are linked from the sidebar and protected by session
routing. Writes invalidate both project views.

Run backend checks, including PostgreSQL/Flyway membership integration tests and
the existing RabbitMQ integration tests (Docker required):

```sh
mvn -pl org-service,api-gateway -am verify
```

Frontend checks:

```sh
npx next typegen
npx tsc --noEmit
npm run build
```
