# Spec: REST docs convention (gluon TD-20.8)

## Overview
cart-service `/docs` shows bare paths and unexplained schemas. Apply the
TD-20 convention (purerest README "Documenting endpoints"), mirroring
order-service `rest-docs_20261101`, and assert it in a test. The proposed
descriptions/examples live in the Appendix below and are **not applied** to
code until the user approves them (TD-20.1's rule).

## Functional Requirements
- FR1: purerestlib 0.4.0 → 0.7.3 (+ aligned pins); suite green; tech-stack.md
  synced.
- FR2: Per-endpoint error outputs — already narrowed by TD-16.5
  (`cart-review-fixes_20261101`): POST `/carts/{id}/items` lists 400 invalid
  quantity, 400 invalid sku, 404; GET/DELETE `/carts/{id}` 404; DELETE
  `/carts/{id}/items/{sku}` 404; POST `/carts` and both health endpoints none.
  Pin with a `CartDocsSuite` test walking each endpoint's `errorOutput`
  statuses.
- FR3: Every endpoint `.summary`/`.description`/`.tag` (`carts`, `health`) —
  see Appendix.
- FR4: `id` path param (UUID) and `sku` path param described + example.
- FR5: Every request/response DTO + field `@description`, scalar fields
  `@encodedExample`, a body example on every request/response.
- FR6: Every error variant described + example body; fixed messages share one
  value between example and mapping.
- FR7: `CartDocsSuite`: `Docs.undocumented(all 7 served endpoints) == Nil`
  (the exact list `Main` passes to `Docs.routes`).
- FR8: `scripts/verify-rest-docs.sh` checks the served `/docs/docs.yaml`.

## Non-Functional
- Wire format unchanged (encode/decode identical); scalafmtCheck clean.
- Examples use UUID-shaped ids (CartId is server-minted) and the real seed SKU
  convention `SKU-MUG-001`; no secrets.

## Acceptance Criteria
- Full suite green, zero gaps, verify script PASS, CI green.

## Out of Scope
- Behaviour changes; exposing `setQuantity` over HTTP.

## Appendix: proposed descriptions (pending approval, 2026-11-01)

> Not yet applied to code. Tables mirror order-service's approved appendix
> (`rest-docs_20261101`): endpoint summaries/descriptions/tags, parameters,
> DTO fields and error variants. `Docs.undocumented` currently reports 35
> gaps over the 7 served endpoints (RED, test uncommitted).

### Endpoints

Tags: `carts` for every `/carts*` operation, `health` for both health
operations. No endpoint has a request body except `POST /carts/{id}/items`;
none has query or header inputs.

| Endpoint | summary | description |
|---|---|---|
| POST /carts | Create a cart | Creates an empty cart with a server-generated UUID and a 24h TTL (refreshed by every write). Not idempotent: each call is a new cart. No request body. |
| GET /carts/{id} | Get a cart | One cart with its line items and timestamps. 404 when no such cart exists (never created, deleted, or expired by its TTL). |
| POST /carts/{id}/items | Add an item | Adds the sku's line, or increments it by `quantity`. The body is validated before the cart lookup: a blank/over-long/reserved sku or a quantity outside 1..999 → 400, and an add that would take an existing line past 999 → 400, leaving the cart unchanged. Not idempotent: each call increments. 404 when the cart doesn't exist. |
| DELETE /carts/{id}/items/{sku} | Remove an item | Drops the sku's line entirely, whatever its quantity; a sku not in the cart is a no-op returning the unchanged cart. The path sku is validated like a body sku (blank/over-long/reserved `__` prefix is a tapir 400 before the handler). 404 when the cart doesn't exist. |
| DELETE /carts/{id} | Delete a cart | Hard-deletes the cart and every line. Deleting an already-absent cart → 404. |
| GET /health | Liveness check | 200 while the process is up. |
| GET /health/ready | Readiness check | 200 when Redis answers a ping, else 503. |

### Parameters

| Endpoint(s) | Parameter | description | example |
|---|---|---|---|
| GET/DELETE /carts/{id}, POST /carts/{id}/items, DELETE /carts/{id}/items/{sku} | `id` (path) | Cart id (UUID). A malformed id returns 400. | `3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90` |
| DELETE /carts/{id}/items/{sku} | `sku` (path) | Catalog sku: non-blank, at most 128 chars, never starting with `__` (reserved for cart metadata). | `SKU-MUG-001` |

### DTO fields

Every class also gets a class-level `@description` and a body example
(`@encodedExample` on the class, or `.example` on the body) showing the full
payload.

- **AddItemRequest** — class: "One line to add to a cart."; example
  `{"sku":"SKU-MUG-001","quantity":2}`.
  - `sku`: "Catalog sku; non-blank, at most 128 chars, never starting with
    `__` (reserved for cart metadata)." example `SKU-MUG-001`
  - `quantity`: "Units to add, 1..999; an add that would take the line past
    999 is rejected." example `2`
- **CartResponse** — class: "A cart with its line items and timestamps.";
  example
  `{"id":"3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90","items":{"SKU-MUG-001":2},"createdAt":"2026-11-01T09:30:00Z","updatedAt":"2026-11-01T09:45:00Z"}`.
  - `id`: "Cart id (UUID)." example
    `3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90`
  - `items`: "Line items: sku -> quantity (1..999 per line); empty for a new
    cart." example `{"SKU-MUG-001":2}`
  - `createdAt`: "When the cart was created (UTC)." example
    `2026-11-01T09:30:00Z`
  - `updatedAt`: "When the cart last changed (UTC); a read does not bump it."
    example `2026-11-01T09:45:00Z`
- **ErrorResponse** — class: "Error body."; example
  `{"error":"Cart not found"}`.
  - `error`: "Human-readable reason." example `Cart not found`

### Error variants

The fixed messages below are shared between each variant's example and its
real `ErrorResponse` mapping, so the docs cannot drift from the wire.

| Status | Endpoint(s) | When it happens | Example body |
|---|---|---|---|
| 404 | GET/DELETE `/carts/{id}`, POST `/carts/{id}/items`, DELETE `/carts/{id}/items/{sku}` | No cart with that id (never created, deleted, or expired by TTL) | `{"error":"Cart not found"}` |
| 400 | POST `/carts/{id}/items` | Body sku blank, over 128 chars, or starting with `__` | `{"error":"Invalid sku"}` |
| 400 | POST `/carts/{id}/items` | Body quantity outside 1..999, or an add that would take the line past 999 | `{"error":"Invalid quantity"}` |
| 503 | GET `/health/ready` | Redis did not answer the readiness ping | (no body) |

Tapir-generated decode failures are outside an endpoint's `errorOut` and keep
their default responses (no custom JSON body): 400 for a malformed UUID `id`
on every `/carts/{id}` operation, and 400 for an invalid `sku` path value on
DELETE `/carts/{id}/items/{sku}`. Their OpenAPI descriptions come from tapir,
so `Docs.undocumented` does not flag them; no annotation is proposed for them.
