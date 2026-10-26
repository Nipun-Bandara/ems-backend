# Teams

All endpoints are available through the gateway at `/api/teams`. Authentication is
required for reads. Only `SYSTEM_ADMIN` and `DEPARTMENT_HEAD` can write.

| Method | Path | Request | Success |
| --- | --- | --- | --- |
| POST | `/api/teams` | Team fields | 201, team with members |
| GET | `/api/teams?departmentId=4` | Optional department filter | 200, array of teams with members |
| GET | `/api/teams/{id}` | — | 200, team with members |
| PUT | `/api/teams/{id}` | Team fields | 200, team with members |
| DELETE | `/api/teams/{id}` | — | 204 |
| POST | `/api/teams/{id}/members` | `{ "userId": 42 }` | 200, team with members |
| DELETE | `/api/teams/{id}/members/{userId}` | — | 204 |

Team fields:

```json
{ "name": "Platform", "departmentId": 4, "leadUserId": 42 }
```

Names must be nonblank and at most 100 characters. Department and user IDs must be
positive. The department must exist. `leadUserId` is optional; PUT with null or an
omitted lead clears it. Setting a lead does not implicitly change membership.

Unknown teams return 404. Invalid fields return 400. A department with teams cannot
be deleted (409); delete or move its teams first. Team deletion removes its
memberships. Adding an existing member or removing an absent member is idempotent.

The `team_members` primary key is `(team_id, user_id)`. User IDs and lead IDs are
plain Long values without foreign keys to identity-service. Org-service has no
replicated users projection, so it accepts these IDs for later reconciliation.

`team.created`, `team.member.added` and `team.member.removed` are recorded in the
transactional outbox with aggregate type `team` and the team ID as aggregate ID.
Removing members through team deletion also records `team.member.removed` for each
member. Duplicate membership requests do not emit duplicate events. Team row
locks serialize concurrent membership writes and team deletion.

The frontend Organization page uses these endpoints via its existing authenticated
API proxy. Employees can browse the cards, member dialogs and organization chart.
Admins and department heads can create, edit and delete teams and manage members.
The identity directory is only accessible to these two roles; other readers see
user IDs. IDs also remain visible when a directory entry is missing.

Run service and gateway checks (requires Docker for the existing broker test):

```sh
mvn -pl org-service,api-gateway -am verify
```
