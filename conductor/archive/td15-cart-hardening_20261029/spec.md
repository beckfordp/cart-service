# TD-15.8 — Cross-repo consistency hardening (cart-service)

## Overview

Cart-service slice of gluon TD-15: typed `CartId` at the boundary, ScalaCheck,
and narrower effect bounds.

## Functional Requirements

1. **Typed ids** — opaque `CartId` minted/parsed once; routes `path[CartId]`;
   store takes `CartId`; malformed → 400.
2. **Tests** — `munit-scalacheck` + cart total/quantity properties.
3. **Effect constraints** — `Cors`/`HealthRoutes`/route handlers narrowed where
   possible.

## Acceptance Criteria

- Malformed cart id → 400; full suite + scalafmt green.
