# Plan — cart-service review fixes (gluon TD-16.5)

## Phase 1: Pure domain core (FR-1) [checkpoint: cfc0c3c]
- [x] Task: tests first — `CartDomainSuite` (Sku/Quantity parsing, Cart add/remove/set, ScalaCheck line-range + no-overflow properties), red → `domain/Cart.scala`, `domain/Sku.scala`, `domain/Quantity.scala`, `CartError` → `domain/` + `InvalidSku`; green — 85/85 green; Cart/Sku/Quantity/CartError in domain/, InvalidSku added aa0d49e
- [x] Task: Conductor - User Manual Verification 'Phase 1: Pure domain core' (Protocol in workflow.md) — covered by suite (85 green)

## Phase 2: Typed store and boundary parsing (FR-2) [checkpoint: 59c9614]
- [x] Task: tests first — store suites on `Either` results + line limit; route tests: reserved/blank body sku → 400 `Invalid sku`, reserved path sku → 400 and cart intact, over-limit add → 400 `Invalid quantity`; red → typed `CartStore` API, in-memory via core (`Concurrent`/`Clock`/`UUIDGen`), routes parse at the boundary; green — red by construction (old Option API + reserved sku accepted); 89/89 green 48eec2e
- [x] Task: Conductor - User Manual Verification 'Phase 2: Typed store and boundary parsing' (Protocol in workflow.md) — covered by suite (89 green)

## Phase 3: Atomic Redis writes (FR-3) [checkpoint: 02249f7]
- [x] Task: tests first — Redis suite: over-limit add leaves the line unchanged; reserved/odd sku never deletes the cart; corrupt hash raises `CorruptCart` and is not deleted; write to a missing cart leaves no key; red → Lua scripts for create/add/remove/set, typed parse, cleanup removed; green — red: over-limit + corrupt-hash failed against old store; 93/93 green ebe93bd
- [x] Task: Conductor - User Manual Verification 'Phase 3: Atomic Redis writes' (Protocol in workflow.md) — covered by suite (93 green, real Redis via Testcontainers)

## Phase 4: Low-severity cleanup (FR-4) [checkpoint: 47dacd4]
- [x] Task: CORS methods narrowed (test first); README rewritten; tech-stack purerestlib synced — preflight test pins GET/POST/DELETE only; README rewritten for the Redis-backed real routes; tech-stack purerestlib 0.4.0 + Lua atomicity noted; 94/94 green bbd79a8
- [x] Task: Conductor - User Manual Verification 'Phase 4: Low-severity cleanup' (Protocol in workflow.md) — covered by suite (94 green incl. Redis Testcontainers; scalafmtCheckAll clean)

**Risks:** Lua number semantics (doubles) — limit 999 keeps them exact; redis4cats
`Multi` output on nil — scripts always return a non-empty list.
