# Spec: Backport CORS support from pure-service-generator (TD-1.2)

## Overview
cart-service has no CORS support (confirmed 2026-10-09 via gshop's Add-to-cart button). Same proven pattern as the generator's `cors_20261010` track and catalog-service's own `cors_20261010` track — a direct, no-new-design port.

## Functional Requirements
1. Add `Cors.scala` (package `cartservice`) — identical to the proven `Cors.middleware[F[_]: Async]`: allow-all origins, GET/POST/PATCH/PUT/DELETE, `Content-Type` header, credentials disallowed.
2. Wire `Cors.middleware` into `Main.scala`, wrapping the fully-composed routes before `.withHttpApp(routes.orNotFound)`.
3. Add `CorsSuite.scala` — same 3 assertions, against cart-service's own `/carts` route.

## Non-Functional Requirements
- No new dependency.
- No change to cart-service's domain routes/logic.

## Acceptance Criteria
- [ ] `CorsSuite` passes (3 tests), 100% coverage on `Cors.scala`.
- [ ] Full `sbt test` suite green, no regressions.
- [ ] Real verification via `scripts/verify-cors.sh`: GET `/carts` with `Origin` shows ACAO; OPTIONS preflight shows correct Allow-Methods/-Headers.

## Out of Scope
- The CORS policy itself (already decided at the generator level).
- order-service, inventory-service, payment-service, notification-service — separate tracks.
