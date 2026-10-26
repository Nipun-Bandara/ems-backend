# Direct object storage uploads

`ems-common` provides `StorageService.presignedUpload(bucket, key, contentType, ttl)`,
`presignedDownload(bucket, key, ttl)` and `delete(bucket, key)`. Storage is opt-in via
`ems.storage.enabled=true`; employee-service enables it. Startup creates the private
`ems-avatars`, `ems-documents` and `ems-attachments` buckets if absent. It fails fast
on storage authentication or connectivity errors.

## Development

Start `docker compose up -d`. Employee-service's development profile defaults to
MinIO at localhost:9000 with the compose credentials. Copy employee-service's
`.env.example` to `.env.development` for database and broker settings.
The compose MinIO service allows the frontend origins localhost:3000 and
127.0.0.1:3000. Recreate that container after changing its CORS environment setting.
MinIO data lives in Docker's named volume, never a service upload directory.

For a containerized employee-service set STORAGE_ENDPOINT=http://minio:9000 and
STORAGE_PUBLIC_ENDPOINT=http://localhost:9000 (or a public storage hostname).
The public endpoint is used when signing; never rewrite the host in a signed URL.

## API and browser

All profile/avatar routes authorize the employee themself or SYSTEM_ADMIN.

1. POST `/api/employees/{id}/avatar/upload-url` with
   `{"contentType":"image/png","size":12345}`. Supported types are JPEG, PNG and
   WebP, with a positive size up to 5 MiB. Response contains `uploadUrl`, `objectKey`
   and `headers` required by the signed request. URLs expire after five minutes.
2. Browser PUTs the raw File directly to `uploadUrl`, including the returned
   Content-Type and If-None-Match headers. The browser supplies Content-Length;
   its exact value is signed. The create-only condition prevents replacement
   after verification. Neither the Next.js proxy nor employee-service receives bytes.
3. PUT `/api/employees/{id}/avatar` with `{"objectKey":"..."}`. The service checks
   that this is the employee's latest unexpired upload and checks storage metadata
   for the exact approved type and size before persisting the object key.
4. GET `/api/employees/{id}` returns profile fields and `avatarUrl` (null before an
   upload), a five-minute signed GET URL. Object keys are not exposed by GET.

The frontend hook reports PUT progress, calls the callback after successful upload,
and updates the shared profile query so the sidebar and profile change together.
Queries refresh every four minutes and on focus to renew image URLs.
Metadata validates the declared media type; this flow does not decode image contents.
Old and abandoned avatar objects are retained; configure a bucket lifecycle policy
or a cleanup job if retention must be bounded.

## Production S3

Use the production Spring profile, STORAGE_REGION and optionally
STORAGE_ALLOWED_ORIGINS (comma-separated frontend origins). Leave STORAGE_ENDPOINT
and STORAGE_PUBLIC_ENDPOINT empty for AWS endpoints and path-style access false.
Leave credentials empty to use the AWS default credential chain / workload role,
or supply STORAGE_ACCESS_KEY and STORAGE_SECRET_KEY through the deployment platform.
The role needs HeadBucket/ListBucket, CreateBucket, GetObject, PutObject, DeleteObject,
and PutBucketCORS when configuring browser origins. CORS bootstrap updates the bucket
rule on real S3; coordinate this with infrastructure-managed bucket policies.

## Verification

Run `mvn -pl employee-service -am verify` for unit, endpoint and container integration
tests (Docker required). EmployeeProfileIntegrationTest performs the complete
metadata -> signed PUT -> callback -> persisted profile -> signed GET flow against
isolated PostgreSQL, RabbitMQ and MinIO containers, keeping image bytes in memory.
With compose MinIO running, `STORAGE_INTEGRATION_TEST=true mvn -pl ems-common -am test
-Dtest=StorageIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` additionally
checks exact-length/create-only signatures and rejects overwriting an uploaded key.

For browser acceptance: sign in as a verified user, open /profile, choose a supported
photo and confirm progress then the updated profile and sidebar. In DevTools Network,
the binary PUT must target port 9000 (or production S3), while API calls carry only
JSON. Refresh /profile to verify persistence. No application code writes upload files.
