# Passing CI before merging

PR titles must follow `<type>(<scope>): <subject>`. The check reads the **PR title**,
not the branch name or the commit messages. Editing an open PR title reruns the
title check automatically. A title such as `Enhance profile management` is rejected
because it has no Conventional Commit prefix.

Examples:

- `feat(employee): add CV sections to employee profiles`
- `fix(ci): restore integration test image availability`
- `feat(leave): add leave request approval`

Allowed scopes: `gateway`, `identity`, `common`, `notification`, `org`, `employee`, `leave`, `timesheet`, `claim`, `recruitment`, `engagement`, `document`, `build`, `ci`, `deploy`, `docs`.

Run `mvn -B clean verify` from the backend root with Docker running. The tests
create isolated containers; they do not need your development services or databases.
Identity context tests override optional .env imports, JWT test settings and
use isolated PostgreSQL, RabbitMQ and Redis containers. Gateway context tests
also provide explicit route/JWKS configuration and a test-only `ALLOWED_ORIGINS`
value instead of requiring a local .env. Production CORS configuration stays required.
When investigating a CI-only failure, verify a copy of tracked source without local
`.env` files: a passing development checkout can mask missing test properties.
Do not skip integration tests to make a PR green.

## MinIO integration-test image

Docker Hub's `minio/minio` image is no longer available to fresh runners. The
official Quay release endpoint also returned `no such manifest` during this fix.
The test and local Compose now use the same release from the third-party
`dappros/minio` mirror, pinned to an immutable multi-platform manifest digest:

```text
dappros/minio:RELEASE.2025-09-07T16-13-09Z@sha256:624bad2fe79a8613aa3c5c9b765ebcab365080b8445cf1b05c357cc7753725f0
```

The linux/amd64 image was pulled and compared with this machine's previously
cached official `minio/minio` release. Both resolve to image ID
`sha256:69b2ec208575b69597784255eec6fa6a2985ee9e1a47f4411a51f7f5fdd193a9`.
This verifies the AMD64 image used by GitHub's Ubuntu runner; other platforms
were not independently compared. This is a historical development/test dependency,
not a production storage recommendation. Keep the release/digest aligned in the
test and Compose; a release tag alone does not guarantee registry availability.

If a pull fails, read the nested Docker error: missing image, rate limit, network
and Docker-daemon failures need different fixes. CI uploads Surefire reports on
failure to preserve those details. A cached local image can hide a registry problem.

References: [upstream image-removal report](https://github.com/apache/gravitino/issues/13111),
[mirror release/provenance](https://hub.docker.com/r/dappros/minio).

## Handling existing failed runs

A code/workflow fix needs a new commit on an open PR; rerunning an old commit uses
its old files. Fix an invalid title on an open PR, wait for all required checks,
then merge. Already-merged failed PR checks remain historical evidence; validate
the current development tree with the corrective PR instead.

Configure branch protection/rulesets on `development` to require the exact
`PR title` and build-check names currently shown by Actions, require PRs, and
restrict bypass permissions. This repository change does not configure GitHub
branch protection automatically.
