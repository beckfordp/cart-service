# Tech Stack — cart-service

## Language / runtime
- Scala 3.9.0
- JDK 21

## Effects / HTTP
- Cats Effect 3.7.0
- http4s 0.23.37 (ember-server)
- circe 0.14.16

## API layer
- tapir 1.11.25 — route definitions and generated Swagger/OpenAPI docs
  (`purerest.docs.Docs`), served at `/docs`, kept in sync by construction

## Persistence (current scaffold — slated for removal)
- skunk 1.0.0, Flyway 11.8.2 (+ flyway-database-postgresql), postgresql JDBC
  42.7.13 — the generator's default Postgres layer. Per
  `gluon/docs/system-design.md`, cart-service is **Redis only**; this
  Postgres stack (plus `docker-compose.yml`'s `postgres` service) exists
  only because no field-spec was applied, and is this repo's first backlog
  item to delete, not extend.

## Config
- pureconfig 0.17.10 — typed config from `application.conf`

## Shared platform library
- `purerestlib` 0.1.0 (`io.github.beckfordp`) — tracing, structured logging,
  metrics, resilience (retry + circuit breaker), tapir-based docs. Consumed
  as a published GitHub Packages artifact, never vendored/`.dependsOn`.
  ADR 0005 flags a possible future migration off GitHub Packages (JitPack or
  Maven Central) — not yet adopted.

## Testing
- munit 1.3.6 + munit-cats-effect 2.2.1
- log4cats-testing 2.8.0 — assert on structured log output
- testcontainers-scala 0.43.6 (currently postgresql + munit modules; swap
  for a Redis module once the Postgres layer is dropped)
- scalafmt (default Scala 3 style) — `sbt scalafmtCheck test` run in CI

## Packaging / local deploy
- sbt-native-packager (`JavaAppPackaging`, `DockerPlugin`)
- Docker image: `eclipse-temurin:21-jre`
- Docker Compose — currently a local `postgres` service; replace with Redis
  once the persistence layer is redesigned

## Not yet in `build.sbt` (needed for upcoming backlog items)
- **Redis client** (e.g. `redis4cats` — the gvolpe reference project's
  choice, see `gluon/docs/product.md`'s "Reference project" section) — for
  the Redis cart data model/line items redesign and US-2.1

## Target infrastructure (platform-wide, from `gluon/docs/system-design.md`)
- Local: OrbStack Kubernetes (see ADR 0002)
- Promotion: dev → staging → prod, all on AWS EKS, single AWS account /
  multi-namespace (see ADR 0004)
- CI/CD: GitHub Actions, candidate release = container image tag promoted
  through environments with automated smoke-test gates
