# ems-backend

Spring Boot microservices behind a Spring Cloud Gateway. The Maven aggregator
at the repo root builds every module.

| Module             | Port | Purpose                                            |
| ------------------ | ---- | -------------------------------------------------- |
| `api-gateway`      | 8080 | Single entry point, JWT check, CORS, rate limiting |
| `identity-service` | 8081 | Users, roles, departments, authentication          |

## Running locally

Requires Docker with Compose v2, JDK 21, and Maven 3.8+.

### 1. Create the environment files

Each module reads a `.env.development` file from its own directory. Copy the
committed template in each module and edit if you need to:

```bash
cp api-gateway/.env.example api-gateway/.env.development
cp identity-service/.env.example identity-service/.env.development
```

The templates are filled in with the values from `docker-compose.yml`, so the
defaults work as-is. `.env.development` is gitignored — keep real credentials
there and out of `.env.example`.

`JWT_SECRET` must be identical in both files, or the gateway will reject the
tokens identity-service issues.

### 2. Start the infrastructure

```bash
docker compose up -d
```

Then confirm every container reports `(healthy)` — the first start pulls
images and initialises Postgres, so give it a minute:

```bash
docker compose ps
```

| Service  | Host ports    | Credentials                | UI                                               |
| -------- | ------------- | -------------------------- | ------------------------------------------------ |
| Postgres | 5432          | `ems` / `ems_local_dev`    | —                                                |
| RabbitMQ | 5672, 15672   | `ems` / `ems_local_dev`    | [localhost:15672](http://localhost:15672)        |
| Redis    | 6379          | no auth                    | —                                                |
| MinIO    | 9000, 9001    | `ems_minio` / `ems_local_dev` | [localhost:9001](http://localhost:9001)       |
| MailHog  | 1025, 8025    | no auth                    | [localhost:8025](http://localhost:8025)          |

On its very first start Postgres runs [`db/init.sql`](db/init.sql), which
creates one database per service: `identity_db`, `org_db`, `employee_db`,
`leave_db`, `timesheet_db`, `claim_db`, `recruitment_db`, `engagement_db`,
`document_db`, `notification_db`.

That script only runs while the data volume is empty. If you change it, reset
the volume to pick the change up:

```bash
docker compose down -v && docker compose up -d
```

### 3. Run the services

One module per terminal, from the module's own directory:

```bash
cd identity-service && mvn spring-boot:run
```

```bash
cd api-gateway && mvn spring-boot:run
```

identity-service creates its own tables on boot (`ddl-auto: update`), so there
is no migration step. The gateway is then reachable on
<http://localhost:8080> and proxies `/api/auth/**`, `/api/users/**` and
`/api/departments/**` to identity-service.

### Stopping

```bash
docker compose down
```

Add `-v` to delete the volumes as well, discarding all local data.

### Troubleshooting

- **`Could not resolve placeholder 'DB_URL'`** — the module has no
  `.env.development`, or it was created in the repo root instead of the module
  directory. Run step 1 again.
- **`Connection refused` on port 5432** — the stack is not up. Check
  `docker compose ps`.
- **A database is missing** — `db/init.sql` was added or edited after the
  volume already existed. Recreate it with `docker compose down -v`.
- **Port already allocated** — something else on the host owns that port; stop
  it, or change the host side of the mapping in `docker-compose.yml`.
