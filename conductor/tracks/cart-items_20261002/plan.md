# Implementation Plan: US-2.1 — add/remove items in a cart

**Prerequisite:** `redis-cart-model_20261002` must be implemented first —
`CartStore[F]`'s `addItem`/`removeItem` must already exist before Phase 2/3 can call
them.

## Phase 1: Error ADT + CartResponse items field [checkpoint: 8f670b7]

- [x] Task: Write failing tests asserting `CartResponse` serializes an `items` field and `InvalidQuantity` maps to a 400 JSON error body `ac62b33` - the latter tested via a throwaway ad-hoc tapir endpoint wired with `invalidQuantityOutput`, since no real endpoint uses it yet
- [x] Task: Add `InvalidQuantity` to `CartError` (alongside `CartNotFound`) `ac62b33`
- [x] Task: Add `items: Map[String, Int]` to `CartResponse`; update `CartResponse.apply(entity)` to carry `entity.items` through `ac62b33`
- [x] Task: Run tests, confirm green `ac62b33` - 58 passed, 0 failed
- [x] Task: Conductor - User Manual Verification 'Error ADT + CartResponse items field' (Protocol in workflow.md) `8f670b7` - verified via committed script (prompt-off), see git note on checkpoint commit

## Phase 2: POST /carts/{id}/items (add item)

- [x] Task: Write failing CartRoutesSuite tests: adding a new sku succeeds (200 + cart with the item); adding an existing sku increments its quantity; `quantity <= 0` returns 400; add against a nonexistent cart id returns 404 `56c1c10`
- [x] Task: Add `AddItemRequest(sku: String, quantity: Int)` case class + circe codec `56c1c10`
- [x] Task: Define `addItemEndpoint` (tapir, a `oneOf` error output distinguishing 400 `InvalidQuantity` from 404 `CartNotFound`) and `addItemServerEndpoint`, calling `CartStore.addItem` `56c1c10` - used `oneOfVariantValueMatcher` to discriminate on the CartError value itself; quantity validation happens at the HTTP boundary before ever calling the store
- [x] Task: Wire the new endpoint into `CartRoutes.routes` and `Main.scala`'s endpoint list `56c1c10`
- [x] Task: Run tests, confirm green `56c1c10` - 62 passed, 0 failed
- [ ] Task: Conductor - User Manual Verification 'POST /carts/{id}/items' (Protocol in workflow.md)

## Phase 3: DELETE /carts/{id}/items/{sku} (remove item)

- [ ] Task: Write failing CartRoutesSuite tests: removing an existing sku succeeds (200 + cart without it); removing a sku not present is a no-op (200 + unchanged cart); remove against a nonexistent cart id returns 404
- [ ] Task: Define `removeItemEndpoint` and `removeItemServerEndpoint`, calling `CartStore.removeItem`
- [ ] Task: Wire the new endpoint into `CartRoutes.routes` and `Main.scala`'s endpoint list
- [ ] Task: Run the full test suite, confirm green
- [ ] Task: Verify coverage (`sbt coverage test coverageReport`), target >80% on new/changed code
- [ ] Task: Conductor - User Manual Verification 'DELETE /carts/{id}/items/{sku}' (final, Protocol in workflow.md)

Three phases, each independently testable: Phase 1 is a pure data-shape change (no
new endpoints yet), Phase 2 adds the add-item endpoint in isolation, Phase 3 adds
remove-item and closes out the story with a full-suite + coverage check.
