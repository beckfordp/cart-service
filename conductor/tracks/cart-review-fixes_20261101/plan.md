# Plan — cart-service review fixes (gluon TD-16.5)

## Phase 1: Pure domain core (FR-1)
- [ ] Task: tests first — `CartDomainSuite` (Sku/Quantity parsing, Cart add/remove/set, ScalaCheck line-range + no-overflow properties), red → `domain/Cart.scala`, `domain/Sku.scala`, `domain/Quantity.scala`, `CartError` → `domain/` + `InvalidSku`; green
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Pure domain core' (Protocol in workflow.md)

## Phase 2: Typed store and boundary parsing (FR-2)
- [ ] Task: tests first — store suites on `Either` results + line limit; route tests: reserved/blank body sku → 400 `Invalid sku`, reserved path sku → 400 and cart intact, over-limit add → 400 `Invalid quantity`; red → typed `CartStore` API, in-memory via core (`Concurrent`/`Clock`/`UUIDGen`), routes parse at the boundary; green
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Typed store and boundary parsing' (Protocol in workflow.md)

## Phase 3: Atomic Redis writes (FR-3)
- [ ] Task: tests first — Redis suite: over-limit add leaves the line unchanged; reserved/odd sku never deletes the cart; corrupt hash raises `CorruptCart` and is not deleted; write to a missing cart leaves no key; red → Lua scripts for create/add/remove/set, typed parse, cleanup removed; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Atomic Redis writes' (Protocol in workflow.md)

## Phase 4: Low-severity cleanup (FR-4)
- [ ] Task: CORS methods narrowed (test first); README rewritten; tech-stack purerestlib synced
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Low-severity cleanup' (Protocol in workflow.md)

**Risks:** Lua number semantics (doubles) — limit 999 keeps them exact; redis4cats
`Multi` output on nil — scripts always return a non-empty list.
