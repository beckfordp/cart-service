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
a **bare scaffold** — no field-spec exists for cart (`gluon/specs/cart.yaml`
is absent), so none was applied. This is deliberate, not an oversight: per
`gluon/backlogs/cart-service.md`'s first line, the next work on this repo is
to design the Redis cart data model by hand (not generator field-spec
syntax, which has no notion of a Redis-backed or collection-shaped entity)
and drop the generated Postgres layer entirely.

## Domain model
- **Cart** (generated scaffold, **not yet hand-extended**) — currently only
  `id`/`createdAt`/`updatedAt`; every codegen field-substitution point
  (`CartStore.scala`, `CartRoutes.scala`) is still a bare
  `/* codegen:fields:* */` placeholder comment, since no field-spec was
  applied. The real shape — a customer's cart as a collection of line items
  (`sku`, `quantity`, product snapshot fields) — isn't representable by
  codegen v1 (no collection/nested-entity support, same limitation that
  pushed order-service's `OrderItem` outside its own codegen'd entity) and
  must be hand-designed against Redis data structures directly, not
  retrofitted onto the generated `CartStore`/Postgres shape.
- The generated persistence layer (`CartStore.postgres`, `Migrations.scala`,
  Flyway, skunk, the `cart` Postgres database/table, `docker-compose.yml`'s
  `postgres` service) is all **scaffold to be deleted**, not a starting
  point to extend — see "Out of scope" and `conductor/tech-stack.md`.

## User stories in scope (gluon/docs/user-stories.md)
- US-2.1 — add/remove items in a cart

## Sequencing (gluon/PLAN.md)
- **Phase 7** (independent of the fulfillment spine — can run anytime)
  1. *(infra, this repo's backlog item 1)* design the Redis cart data model
     (line items) and drop the generated Postgres layer
  2. US-2.1 — add/remove items, built on that Redis model

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
- Postgres persistence of any kind, once the redesign above lands — this is
  the one Gluon service with no database
