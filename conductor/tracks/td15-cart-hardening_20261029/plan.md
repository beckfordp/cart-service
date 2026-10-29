# TD-15.8 Implementation Plan

## Phase 1 — Typed CartId
- [ ] Failing tests: CartId suite + malformed route id → 400; implement domain type, store, routes; update fixtures.
- [ ] Task: Conductor - User Manual Verification 'Phase 1 — Typed ids' (Protocol in workflow.md)

## Phase 2 — ScalaCheck and narrowing
- [ ] Add `munit-scalacheck` + properties; narrow `Cors`/`HealthRoutes`/route handlers.
- [ ] Task: Conductor - User Manual Verification 'Phase 2 — Purity' (Protocol in workflow.md)

## Phase 3 — Verification
- [ ] Full suite + coverage + scalafmt.
- [ ] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md)
