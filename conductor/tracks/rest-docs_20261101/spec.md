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
> DTO fields and error variants.
