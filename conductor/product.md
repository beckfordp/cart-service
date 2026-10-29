# Product Guide — cart-service

## Context
Part of the **Gluon** platform (v1) — a production-grade microservices
e-commerce platform built pure-FP-first in Scala 3 (Cats Effect / http4s).
See the cross-repo [Gluon Product Vision](../../../docs/product.md) (in the
`gluon/` monorepo root) for the full platform vision, naming scheme, and
goals. This document scopes that vision down to cart-service's own slice.

## What this service does
cart-service owns the **Cart** domain entity — a customer's in-progress,
pre-checkout item selection — for the walking-skeleton shopping flow in
Gluon's user stories. Cart is ephemeral session state: per
`gluon/docs/system-design.md`'s services table, this service is **Redis
only** (Redis as primary store, no Postgres) — unlike every other Gluon
service, which is Postgres-backed.

Generated via `pure-service-generator` (giter8 template over `purerest`) as
a bare scaffold — no field-spec exists for cart (`gluon/specs/cart.yaml` is
absent), so none was applied, deliberately: a line-item/Redis-backed entity
isn't representable by generator field-spec syntax (no collection or
Redis-backed type support), so the domain model below was hand-designed
instead (`redis-cart-model_20261002`), replacing the generator's default
Postgres layer entirely.

## Domain model
- **Cart** — `id` (server-generated UUID string), `items: Map[sku, quantity]`
  (a customer's cart as a flat collection of line items — sku + quantity
  only, no product-name/price snapshot), `createdAt`/`updatedAt`.
  Hand-designed, not field-spec'd — codegen v1 has no collection/nested-entity
  support (same limitation that pushed order-service's `OrderItem` outside
  its own codegen'd entity).
- **Redis representation**: one Hash per cart at key `cart:<id>` — field name
  = sku, field value = quantity; two reserved metadata fields
  (`__created_at`/`__updated_at`) share the hash. A session TTL (24h default,
  `CART_TTL_SECONDS`-overridable) refreshes on every write, so an abandoned
  cart cleans itself up with no background job.
- `CartStore[F]`: `create`/`get`/`addItem`/`removeItem`/`setQuantity`/
  `delete`/`ping`, backed by `CartStore.redis` (redis4cats) in production,
  `CartStore.inMemory` in unit tests. No Postgres anywhere in this repo.
- **HTTP surface**: `POST /carts` (create), `GET /carts/{id}`,
  `DELETE /carts/{id}` (whole cart), `POST /carts/{id}/items` (add/increment
  an item), `DELETE /carts/{id}/items/{sku}` (remove an item entirely, no-op
  if absent). `CartStore.setQuantity` exists at the store level but isn't
  exposed over HTTP — no story has asked for it yet.

## User stories in scope (gluon/docs/user-stories.md)
- US-2.1 — add/remove items in a cart

## Sequencing (gluon/PLAN.md)
- **Phase 7** (independent of the fulfillment spine — can run anytime) —
  **complete**
  1. ~~design the Redis cart data model and drop the generated Postgres
     layer~~ — done (`redis-cart-model_20261002`)
  2. ~~US-2.1 — add/remove items, built on that Redis model~~ — done
     (`cart-items_20261002`)

## Events
None. cart-service neither publishes nor consumes Kafka events (per
`system-design.md`'s services table) — cart state is read/written
synchronously via its own REST API only.

## Out of scope for this service
- Checkout/order creation (order-service's job — cart-service's data is
  read by the checkout flow, not written by it)
- Payment, inventory reservation (payment-service / inventory-service)
- Catalog data (catalog-service's job; a cart line item is a logical
  reference + snapshot only, never a live catalog lookup or a cross-service
  Postgres FK)
- Postgres persistence of any kind — this is the one Gluon service with no
  database
