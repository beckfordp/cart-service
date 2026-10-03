# Product Guidelines — cart-service

## Code & engineering conventions
Canonical source: [`development-guidelines.md`](../development-guidelines.md)
(pure-FP, tagless-final, ADT error modeling, no partial functions, no
null/var, explicit DI, exhaustive pattern matching). This file doesn't
restate those — keep following them as the service grows, including
through the Redis rewrite (e.g. `CartStore[F]` stays an algebra trait with
a Redis-backed instance, not a special case that bypasses tagless-final
style).

## API design conventions
- Plain resource JSON responses, standard HTTP status codes — no response
  envelope (e.g. no JSON:API)
- Errors: small JSON error object, status mapped 1:1 from the domain error
  ADT (`CartError`) — no catch-all exception translator, one explicit
  `errorOut` mapping per case
- REST paths pluralized (`/carts`, `/carts/{id}`)
- No generic whole-cart PATCH/PUT — a line-item model has no single "update"
  operation; item-level mutation is add/remove/set-quantity instead (see
  US-2.1's own track)
- **Redis only, no Postgres** — unlike every other Gluon service, this one
  has no dedicated database. Its "one store per service" boundary is a
  Redis keyspace/key-prefix convention (`cart:<id>`) rather than a Postgres
  database name; still never read/written by any other service directly.
  No Flyway, no schema migrations, no skunk.
- Swagger/OpenAPI docs generated from the same tapir endpoint definitions as
  the real routes (`purerest.docs.Docs`) — stays in sync by construction

## Testing
- scalafmt + munit + munit-cats-effect
- An in-memory `CartStore` for unit tests, a real, ephemeral Redis container
  via Testcontainers for integration tests — same shape as every other
  Gluon service's Testcontainers-Postgres pattern, just pointed at Redis
- `sbt scalafmtCheck test` before every commit (CI enforces the same)
