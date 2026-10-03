# Spec: Design the Redis cart data model and drop the generated Postgres layer

## Overview
Replace cart-service's generated Postgres/skunk/Flyway/docker-compose persistence
layer with a hand-designed Redis-backed `Cart` domain model and `CartStore[F]`
algebra, reflecting `gluon/docs/system-design.md`'s "Redis only, primary store" row
for this service. The generated scaffold's `Cart`/`CartStore`/`CartRoutes` currently
have no domain fields at all (every codegen field-substitution point is an unfilled
placeholder, since no field-spec exists for cart) — this track fills that gap with a
hand-designed model shaped for line items, not a field-spec.

This track covers persistence + the `CartStore[F]` algebra only. `CartRoutes`'s
public HTTP surface is explicitly deferred to the US-2.1 track — this track's job is
to make sure `CartStore[F]` already exposes the primitives US-2.1 will need, so that
track doesn't have to redesign persistence again.

## Functional Requirements
1. **Domain model.** `Cart` = `id: String`, `items: Map[String, Int]` (sku →
   quantity), `createdAt`/`updatedAt: Instant`. A cart with an empty `items` map is
   valid (newly created, still empty) — not an error state.
2. **Redis representation.** One Redis Hash per cart at key `cart:<id>`. Each line
   item is one hash field: field name = sku, field value = quantity (string-encoded
   int). Two reserved metadata fields, `__created_at`/`__updated_at` (ISO-8601
   strings), live in the same hash — skus are assumed never to start with `__`
   (accepted constraint, not enforced at this layer).
3. **`CartStore[F]` algebra**, replacing the generated create/get/update/delete
   shape:
   - `create(): F[Cart]` — new cart, empty items, both timestamps = now.
   - `get(id): F[Option[Cart]]`
   - `addItem(id, sku, quantity): F[Option[Cart]]` — increments existing quantity
     via Redis `HINCRBY` (atomic, no read-modify-write race) if the sku is already
     present, inserts it otherwise; bumps `updatedAt`. `None` if the cart doesn't
     exist.
   - `removeItem(id, sku): F[Option[Cart]]` — deletes the sku's hash field entirely
     (`HDEL`); bumps `updatedAt`. `None` if the cart doesn't exist.
   - `setQuantity(id, sku, quantity): F[Option[Cart]]` — sets a sku's quantity to an
     exact value (`HSET`), removing the field instead if `quantity <= 0`; bumps
     `updatedAt`. `None` if the cart doesn't exist.
   - `delete(id): F[Boolean]` — deletes the whole hash key.
   - `ping: F[Boolean]` — Redis `PING`, mirrors the generated store's health-check
     role.
4. **TTL.** Every write that touches a cart's hash key refreshes a Redis `EXPIRE` to
   a configured session TTL (default 24h, via `CartServiceConfig`) — reflects "cart
   is ephemeral session state"; an abandoned cart cleans itself up with no background
   job.
5. **In-memory test double.** Keep the existing `CartStore.inMemory` pattern (used by
   unit tests) but reshape it to the same line-item algebra, backed by
   `Ref[F, Map[String, Cart]]`.
6. **Remove the generated Postgres layer entirely:** `Migrations.scala`, the
   skunk/flyway/flyway-database-postgresql/postgresql-JDBC deps in `build.sbt`,
   `PostgresConfig` from `CartServiceConfig.scala`, the Flyway migration SQL,
   `docker-compose.yml`'s `postgres` service, `CartStorePostgresSuite`/
   `MigrationsSuite`, and the `Migrations.run` call in `Main.scala`.
7. **Add the Redis layer to replace it:** `redis4cats` dependency, a `RedisConfig`
   (host/port) in `CartServiceConfig.scala`, `docker-compose.yml`'s Postgres service
   replaced with a `redis` service, `Main.scala` wired to the Redis-backed
   `CartStore`.

## Non-Functional Requirements
- No read-modify-write races on concurrent `addItem`/`removeItem`/`setQuantity`
  against the same cart — via Redis's own atomic hash commands, not client-side
  locking.
- Same structured-logging/tracing/metrics instrumentation pattern the generated
  Postgres store used (`db.client.operation.duration` histogram, `db.system`/
  `db.operation` attributes), ported to `db.system = "redis"`.
- Stays tagless-final / `F[_]: Async` throughout, per `development-guidelines.md`.

## Acceptance Criteria
- [ ] `sbt scalafmtCheck test` passes with no Postgres/skunk/Flyway code or
      dependencies remaining anywhere in the repo.
- [ ] A real, ephemeral Redis container (Testcontainers) backs an integration suite
      exercising create/get/addItem/removeItem/setQuantity/delete/ping, mirroring the
      removed `CartStorePostgresSuite`'s role.
- [ ] `addItem` on an existing sku increments rather than overwrites its quantity;
      `setQuantity` to 0 or below removes the item.
- [ ] `docker compose up -d` brings up Redis only — no Postgres container.
- [ ] `CartServiceConfig` has no `PostgresConfig` field; a new `RedisConfig` exists
      instead.
- [ ] A cart's Redis key carries a TTL after every write; `ping` reflects Redis
      reachability.

## Out of Scope
- `CartRoutes`'s HTTP endpoints — deferred to the US-2.1 track. (This track does drop
  the PATCH/PUT whole-cart-update endpoints, since no generic "update" operation
  exists against a line-item model any more — it does not add any new item-level
  endpoints.)
- Product name/price snapshotting into line items — sku + quantity only.
- Any cross-service call to catalog-service to validate a sku exists.
- Changing the session-TTL value from a sensible default — tunable later without a
  new track.
