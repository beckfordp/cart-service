# cart-service

A production-quality microservice built on
[purerest](https://github.com/beckfordp/purerest), generated from the
[pure-service-generator](https://github.com/beckfordp/pure-service-generator) giter8 template.
Its own `build.sbt` resolves `purerestlib` as a published GitHub Packages dependency.

## Prerequisites

- sbt / JDK 21 (for building and running)
- Docker Desktop (or another Docker engine) with Docker Compose v2
- A GitHub [personal access token](https://github.com/settings/tokens) with `read:packages`
  scope, exported as `GITHUB_TOKEN` (and `GITHUB_ACTOR` set to your GitHub username) — needed to
  resolve `purerestlib` from GitHub Packages. GitHub Packages requires authentication to *read*
  Maven artifacts even from a public repo.

## Quickstart

```
export GITHUB_ACTOR=<your-github-username>
export GITHUB_TOKEN=<your-PAT-with-read:packages>

docker compose up -d   # starts Redis (cart data is ephemeral, no volume)
sbt run                # starts the service on :8080
```

Then, in another terminal:

```
# Create an empty cart (no request body)
curl -X POST http://localhost:8080/carts

# Read it back (substitute the id from the response above)
curl http://localhost:8080/carts/<id>

# Add an item (adds to the line if the sku is already in the cart;
# quantity 1..999 per request and per line)
curl -X POST http://localhost:8080/carts/<id>/items \
  -H "Content-Type: application/json" -d '{"sku":"SKU-MUG-001","quantity":2}'

# Remove a line entirely (no-op if the sku isn't in the cart)
curl -X DELETE http://localhost:8080/carts/<id>/items/SKU-MUG-001

# Delete the whole cart
curl -X DELETE http://localhost:8080/carts/<id>

# Liveness / readiness (ready = Redis reachable)
curl http://localhost:8080/health
curl http://localhost:8080/health/ready
```

Swagger UI (generated from the same tapir endpoint definitions as the real routes — see
`purerest.docs.Docs`) is browsable at **http://localhost:8080/docs**.

## Development guidelines

Extending this service? See [`development-guidelines.md`](./development-guidelines.md) for the
pure-FP/tagless-final standards it follows — error modeling as ADTs, patterns to follow (and
avoid) — before adding new code.

## Testing

```
sbt scalafmtCheck test
```

Unit tests use an in-memory `CartStore`; `CartStoreRedisSuite` spins up a real, ephemeral Redis
container via Testcontainers — no local Redis or manual setup needed to run `sbt test`.

## Storage

Redis only (no Postgres): one hash per cart at `cart:<id>` (field = sku, value = quantity, plus
the reserved `__created_at`/`__updated_at` fields — skus may not start with `__`), with a TTL
(24h default, `CART_TTL_SECONDS`) refreshed on every write. Each write is a single Lua script, so
it is atomic. No other service reads or writes this Redis.

## Calling other services with resilience

This service doesn't call any other service out of the box, so purerest's retry + circuit-breaker
middleware (`purerest.resilience.Resilience.middleware`) isn't wired into `Main`. When you adapt
it to call a real downstream service, wrap its http4s `Client[F]` with `Resilience.middleware`
before building your own client on top of it — see
[`ClientResilienceExampleSuite`](./src/test/scala/cartservice/examples/ClientResilienceExampleSuite.scala)
for the pattern, tested against a dummy client so it stays correct as purerest evolves.
