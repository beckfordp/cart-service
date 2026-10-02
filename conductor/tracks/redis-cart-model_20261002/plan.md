# Implementation Plan: Design the Redis cart data model and drop the generated Postgres layer

## Phase 1: Redesign domain model & CartStore algebra, remove generated Postgres implementation

- [x] Task: Write failing CartStoreSuite tests for the new line-item algebra: create() returns an empty cart; addItem on a new sku inserts it; addItem on an existing sku increments its quantity; removeItem deletes a sku entirely; setQuantity with a positive value sets it exactly; setQuantity with 0 or negative removes the sku; get on a missing cart returns None; delete returns true/false correctly; ping returns true `f2233ec`
- [x] Task: Redefine `Cart` (id, items: Map[String, Int], createdAt, updatedAt) and the `CartStore[F]` trait (create/get/addItem/removeItem/setQuantity/delete/ping), removing every `/* codegen:fields:* */` placeholder `f2233ec`
- [x] Task: Reimplement `CartStore.inMemory` against the new trait, backed by `Ref[F, Map[String, Cart]]` `f2233ec`
- [x] Task: Run tests, confirm CartStoreSuite green `f2233ec` - 18 passed, 0 failed
- [x] Task: Delete the generated Postgres implementation: `CartStore.postgres`, `Migrations.scala`, `MigrationsSuite`, `CartStorePostgresSuite`, the Flyway migration SQL, and `PostgresConfig` from `CartServiceConfig.scala` (+ its test fixtures) `f2233ec`
- [x] Task: Remove skunk/flyway/flyway-database-postgresql/postgresql-JDBC dependencies from `build.sbt` `f2233ec` - also removed the otel4s `dependencyOverrides` block, which existed only to resolve Skunk's own otel4s conflict
- [x] Task: Trim `CartRoutes`/`CartRoutesSuite` to the operations the new store still supports: drop the PATCH/PUT (`UpdateCartRequest`) endpoints entirely (no generic whole-cart "update" exists against a line-item model) — create/get/delete stay as a thin resource shell; add/remove-item HTTP endpoints remain US-2.1's job, not added here `f2233ec` - also dropped `CreateCartRequest`'s now-pointless body (`create()` takes no params); required a matching update to `Main.scala` (removed the `Migrations.run` call and `CartStore.postgres` wiring, temporarily using `CartStore.inMemory` pending Phase 2/3) and to `HealthRoutesSuite`/`CartDocsSuite`'s stub stores - not called out as separate plan lines but necessary for compilation
- [x] Task: Run the full test suite, confirm green `f2233ec` - 39 passed, 0 failed; `sbt scalafmtCheck test` clean
- [ ] Task: Conductor - User Manual Verification 'Redesign domain model & CartStore algebra' (Protocol in workflow.md)

## Phase 2: Redis-backed CartStore implementation

- [ ] Task: Add `redis4cats` dependency to `build.sbt`
- [ ] Task: Write a failing Testcontainers-Redis integration suite (`CartStoreRedisSuite`, mirroring the deleted `CartStorePostgresSuite`'s role): create/get/addItem-increments/addItem-new-sku/removeItem/setQuantity-to-zero-removes/delete/ping, plus a TTL assertion (key has a positive TTL after any write)
- [ ] Task: Implement `CartStore.redis[F]` using redis4cats hash commands (HSET/HINCRBY/HDEL/HGETALL) + EXPIRE on every write, instrumented with the same timed-histogram pattern the Postgres store used (`db.client.operation.duration`, `db.system="redis"`)
- [ ] Task: Run tests, confirm CartStoreRedisSuite green
- [ ] Task: Conductor - User Manual Verification 'Redis-backed CartStore' (Protocol in workflow.md)

## Phase 3: Config, Main.scala wiring, docker-compose

- [ ] Task: Add `RedisConfig` (host, port) to `CartServiceConfig.scala`; update `CartServiceConfigSuite` and `application.conf`
- [ ] Task: Replace `docker-compose.yml`'s `postgres` service with a `redis` service (`redis:7-alpine`, `redis-cli ping` healthcheck)
- [ ] Task: Update `Main.scala`: remove the `Migrations.run` call, build/use `CartStore.redis` instead of `CartStore.postgres`, wire `RedisConfig`
- [ ] Task: Update any remaining `CartStore` stub implementations in `HealthRoutesSuite`/`CartDocsSuite` for the new trait shape
- [ ] Task: Run the full test suite, confirm green
- [ ] Task: Verify coverage (`sbt coverage test coverageReport`), target >80% on new/changed code
- [ ] Task: Conductor - User Manual Verification 'Redis wiring + docker-compose' (final, Protocol in workflow.md)

Three phases, each independently testable: Phase 1 redesigns the algebra and proves it in-memory while removing the old Postgres code (so nothing half-compiles against two shapes at once), Phase 2 adds the real Redis implementation against that same algebra, Phase 3 wires it into the running service and local dev environment.
