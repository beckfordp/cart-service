# Project Tracks

This file tracks all major tracks for the project.

- [x] **Track: US-2.1: add/remove items in a cart**
  *Link: [./tracks/cart-items_20261002/](./tracks/cart-items_20261002/)*

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track above.

- No CORS support (no `Access-Control-Allow-Origin` header) — confirmed
  2026-10-09 verifying gshop's US-2 Add-to-cart button: `curl` gets a
  clean 200 with no ACAO header, but every browser blocks gshop's
  `fetch()`. Likely a `pure-service-generator`-level gap (same template
  as catalog/order/inventory/payment/notification) — fix there first
  (`src/main/g8/src/main/scala/$package$/Main.scala`, wrap `routes`
  with http4s's `org.http4s.server.middleware.CORS` before
  `.orNotFound`), then port here. See `gluon/TECHNICAL_DEBT.md` TD-1 and
  `gluon/backlogs/cart-service.md`.

---
