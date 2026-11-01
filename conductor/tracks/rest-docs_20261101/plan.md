# Plan — cart-service: REST docs convention (gluon TD-20.8)

## Phase 1: purerestlib 0.7.3
- [x] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3 (+ aligned pins); fix API drift until suite green; tech-stack.md synced — tapir 1.11.25 / http4s 0.23.37 / cats-effect 3.7.0 already match 0.7.3's pins, no drift; tech-stack now 0.7.3; 94/94 green 339e5a6
- [x] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md) — covered by suite (94 green incl. Redis Testcontainers; scalafmtCheckAll clean)

## Phase 2: Per-endpoint error outputs and docs coverage test
- [~] Task: tests first — `CartDocsSuite` walks each endpoint's `errorOutput` statuses; `Docs.undocumented` over the 7 endpoints `Main` serves (red: record the gap count); narrow any remaining outputs; the red test stays uncommitted pending description approval — error outputs already narrow (TD-16.5); error-status pin green; `Docs.undocumented` red at 35 gaps; red coverage test deliberately uncommitted
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Per-endpoint error outputs and docs coverage test' (Protocol in workflow.md)

## Phase 3: Descriptions and examples (after user approval of the Appendix)
- [ ] Task: coverage test first in `CartDocsSuite` (`Docs.undocumented == Nil`, red) → approved summaries/descriptions/tags, param descriptions/examples, DTO/error annotations + examples in `CartRoutes`/`HealthRoutes`; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md)

## Phase 4: Smoke test
- [ ] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; scalafmtCheck; CI green
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md)
