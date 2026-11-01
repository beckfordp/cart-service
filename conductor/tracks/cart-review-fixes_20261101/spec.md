# Spec: cart-service review fixes (gluon TD-16.5)

## Overview
Full review of cart-service main + test code against `code_styleguides/scala-fp.md`
(Review checklist) and `general.md`, 2026-11-01. Baseline: 72 tests green at `f1d3c05`.
Fix every High/Medium finding and cheap Lows. No wire-contract change: request/response
shapes and existing error bodies stay identical (a newly rejected input reuses the
existing `400 {"error": ...}` shape).

## Findings

### High
- **H1 Reserved-field collision.** A sku is a raw `String` written as a Redis hash field
  beside the `__created_at`/`__updated_at` metadata. `DELETE /carts/{id}/items/__created_at`
  deletes createdAt, the cart no longer parses, `mutateIfExists`'s cleanup deletes the
  whole cart and the call returns 404. `POST .../items {"sku":"__created_at"}` →
  `HINCRBY` on a timestamp → 500. Blank skus are accepted.
- **H2 Unbounded quantity.** `addItem` increments without limit: in-memory `Int` overflows
  to a negative quantity; in Redis the value outgrows `Int`, `parseCart` fails, the cart
  reads as missing and the cleanup deletes it (data loss).
- **H3 Corrupt collapsed into missing.** `parseCart` returns `Option` (`Try(...).toOption`):
  a malformed hash is indistinguishable from an absent one, so it is reported as 404 and
  deleted rather than surfaced as a failure.
- **H4 Non-atomic Redis writes.** Mutations are `EXISTS` → write → `HSET updatedAt` →
  `EXPIRE` → `HGETALL`, five round trips; a concurrent delete/expiry between them
  resurrects the key (patched by a best-effort cleanup). `create` is `HSET` then
  `EXPIRE`: cancellation or failure between them leaves a cart with no TTL forever.
- **H5 Business rules in the shell, duplicated.** Increment, "quantity ≤ 0 removes the
  line" (`setQuantity`) and "quantity must be positive" live in both store
  implementations and the route; `Cart` lives in `CartStore.scala`, not `domain/`.
- **H6 Over-broad capability.** `CartStore.inMemory[F: Sync]` only needs `Ref` + time +
  ids (`Concurrent`/`Clock`/`UUIDGen`); both stores mint ids with
  `Sync.delay(UUID.randomUUID())` instead of `UUIDGen`.

### Medium
- **M1 Option/Boolean store results.** `addItem`/`removeItem`/`setQuantity` return
  `F[Option[Cart]]`, `delete` `F[Boolean]`; with a quantity limit `addItem` has two
  failure reasons, so results become `Either[CartError, _]` through to the edge.
- **M2 Raw input re-validated inside.** `sku`/`quantity` reach the store unparsed; the
  route checks `quantity <= 0` inline. Parse once at the boundary into `Sku`/`Quantity`.

### Low
- **L1** CORS allows `PATCH`/`PUT`; no route serves them (only GET/POST/DELETE).
- **L2** README is the generator's: Postgres, `PATCH`/`PUT /carts/{id}`, a create body.
- **L3** tech-stack.md says purerestlib 0.1.0; build pins 0.4.0.
- **L4** `ping` probes with `EXISTS __cart_ping__` rather than `PING` — works; skipped
  (cosmetic, no behaviour gain).

## Functional Requirements
- **FR-1 (H5, H2)** Pure core in `domain/`: `Cart` entity + `Cart.empty`/`addItem`/
  `removeItem`/`setQuantity`; `Quantity` (1..`Quantity.Max` = 999 per line) with
  `parse` and `parseTarget` (≤ 0 → remove); `Sku` (non-blank, ≤ 128 chars, no `__`
  prefix); `CartError` moved to `domain/` with a new `InvalidSku` case. Value tests +
  ScalaCheck properties (a line never leaves 1..Max; add never overflows).
- **FR-2 (M1, M2, H6, H1)** `CartStore` takes `Sku`/`Quantity`; mutations return
  `F[Either[CartError, Cart]]`, `delete` `F[Either[CartError, Unit]]`. In-memory store
  delegates to the core, constrained by `Concurrent`+`Clock`+`UUIDGen`. Routes parse
  body `sku`/`quantity` (invalid → `400 {"error":"Invalid sku"}` /
  `{"error":"Invalid quantity"}`; line above Max → `Invalid quantity`) and the path
  `sku` as `Sku` (tapir 400 on a reserved/blank one).
- **FR-3 (H3, H4, H2)** Redis store: every write is one Lua `EVAL` (existence check,
  write, `__updated_at`, `EXPIRE`, read back) so no partial write or resurrection is
  possible; the line limit is checked in the script with `Quantity.Max` passed from the
  core. `create` = one script (`HSET` + `EXPIRE`). A hash that fails to parse raises
  `CorruptCart` (500), never reads as missing; the cleanup path is removed.
- **FR-4 (L1–L3)** CORS methods GET/POST/DELETE; README rewritten for the real API;
  tech-stack purerestlib version synced.

## Non-Functional
- TDD per workflow; scalafmtCheck green; >80% coverage on changed code.
- Wire format unchanged for every request that succeeded before.

## Acceptance Criteria
- Each finding's failing-first test is green; full suite green (Redis Testcontainers).
- Reserved sku → 400, never deletes a cart; line > 999 → 400 with the cart intact.

## Out of Scope
- Typed `Map[Sku, Quantity]` inside `Cart.items` (kept `Map[String, Int]`: items are
  only written through parsed `Sku`/`Quantity`).
- Exposing `setQuantity` over HTTP; REST docs (TD-20.8, next track).
