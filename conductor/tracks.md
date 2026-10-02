# Project Tracks

This file tracks all major tracks for the project.

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track above.

- Generate cart-service bare scaffold, no field-spec applied (infra) — **already
  satisfied**: this repo's scaffold is generated (`build.sbt`, `src/`), and confirmed
  no field-spec was applied — every codegen field-substitution point in
  `CartStore.scala`/`CartRoutes.scala` is still a bare placeholder comment. Left here
  verbatim per the source backlog; prune once confirmed.
- Design the Redis cart data model (line items) and drop the generated Postgres layer (infra)
- US-2.1: add/remove items in a cart

---
