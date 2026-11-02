# Spec: US-2.1 — add/remove items in a cart

## Overview
Implements US-2.1 ("As a customer, I can add/remove items in a cart, so I can build
an order before checking out") by adding item-level HTTP endpoints to `CartRoutes`,
built on the `CartStore[F]` line-item algebra (`create`/`get`/`addItem`/`removeItem`/
`setQuantity`/`delete`/`ping`) from the `redis-cart-model_20261002` track.

**Dependency:** this track cannot start until `redis-cart-model_20261002` is
implemented — `CartStore[F]` must already expose `addItem`/`removeItem` before these
routes can be wired to it.

Resulting `CartRoutes` surface after this track: `POST /carts` (create empty cart),
`GET /carts/{id}`, `DELETE /carts/{id}` (whole cart) — all pre-existing — plus this
track's two new endpoints below. (The old PATCH/PUT whole-cart-update endpoints were
already dropped by `redis-cart-model_20261002`, since no generic "update" exists
against a line-item model.)

## Functional Requirements
1. **`POST /carts/{id}/items`** — add an item. Body: `{sku: String, quantity: Int}`.
   Calls `CartStore.addItem(id, sku, quantity)` (increments if sku already present).
   Returns `200` + the full updated `Cart`. `404` (`CartNotFound`) if the cart id
   doesn't exist. `400` (new `InvalidQuantity` error) if `quantity <= 0`.
2. **`DELETE /carts/{id}/items/{sku}`** — remove an item. Calls
   `CartStore.removeItem(id, sku)`. Returns `200` + the full updated `Cart`. Removing
   a sku not currently in the cart is a **no-op** (succeeds, returns the unchanged
   cart) — not an error, since Redis `HDEL` is naturally idempotent. `404`
   (`CartNotFound`) if the cart id doesn't exist.
3. **`CartResponse` gains an `items: Map[String, Int]` field** mirroring `Cart`,
   surfaced in every response (create/get/add/remove) — needed so a client can
   actually see cart contents, which the generated scaffold's response never had.
4. **Error ADT:** add `InvalidQuantity` alongside `CartNotFound` in `CartError`, each
   with its own explicit `errorOut` mapping (400 vs. 404) — no catch-all translator,
   per `development-guidelines.md`.

## Non-Functional Requirements
- Swagger/OpenAPI docs (`/docs`) reflect both new endpoints automatically, generated
  from the same tapir definitions as the real routes.
- Stays tagless-final / `F[_]: Async`, typed-error-ADT style throughout — no change
  to that constraint.

## Acceptance Criteria
- [ ] `POST /carts/{id}/items` with a new sku adds it at the given quantity; `200` +
      full cart including the new item.
- [ ] `POST /carts/{id}/items` with an existing sku increments (not overwrites) its
      quantity.
- [ ] `POST /carts/{id}/items` with `quantity <= 0` returns `400`, cart unchanged.
- [ ] `POST /carts/{id}/items` against a nonexistent cart id returns `404`.
- [ ] `DELETE /carts/{id}/items/{sku}` removes that sku entirely (not a decrement) and
      returns `200` + the cart without it.
- [ ] `DELETE /carts/{id}/items/{sku}` for a sku not in the cart is a no-op: `200` +
      unchanged cart.
- [ ] `DELETE /carts/{id}/items/{sku}` against a nonexistent cart id returns `404`.
- [ ] `sbt scalafmtCheck test` passes.

## Out of Scope
- A dedicated "set exact quantity" HTTP endpoint (`CartStore.setQuantity` exists but
  isn't exposed over HTTP here) — add/remove is this story's literal scope;
  exact-quantity-set can be a later story.
- Auto-creating a cart on first add — explicitly decided against; client must
  `POST /carts` first.
- Any catalog-service call to validate a sku is a real product — cart-service still
  never calls another service directly.
- Checkout/order creation — order-service's job (US-3.1).
