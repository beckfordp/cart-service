# Plan — cart-service: REST docs convention (gluon TD-20.8)

## Phase 1: purerestlib 0.7.3
- [ ] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3 (+ aligned pins); fix API drift until suite green; tech-stack.md synced
- [ ] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md)

## Phase 2: Per-endpoint error outputs and docs coverage test
- [ ] Task: tests first — `CartDocsSuite` walks each endpoint's `errorOutput` statuses; `Docs.undocumented` over the 7 endpoints `Main` serves (red: record the gap count); narrow any remaining outputs; the red test stays uncommitted pending description approval
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Per-endpoint error outputs and docs coverage test' (Protocol in workflow.md)

## Phase 3: Descriptions and examples (after user approval of the Appendix)
- [ ] Task: coverage test first in `CartDocsSuite` (`Docs.undocumented == Nil`, red) → approved summaries/descriptions/tags, param descriptions/examples, DTO/error annotations + examples in `CartRoutes`/`HealthRoutes`; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md)

## Phase 4: Smoke test
- [ ] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; scalafmtCheck; CI green
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md)
