# Spec: Journey telemetry (gluon PERF-4.1, 4.8)

## Overview
gluon PLAN.md Performance 4 for cart-service: adopt purerestlib 0.8.1 cross-cutting telemetry and
add the service's own business counters for the Christmas Rush dashboard.

## Functional Requirements
1. Bump purerestlib to 0.8.1; `Main` passes `runtime.metrics` to `Metrics.oteljava` (PERF-4.1).
2. `CartJourneyMetrics` store decorator: `cart.operations{operation=create|add_item|remove_item|set_quantity|delete, outcome=ok|not_found}` (PERF-4.8).
   No pool: cart is Redis-only.

## Non-Functional Requirements
- No ids on metric labels. Coverage > 80% on new code. scalafmt clean.

## Acceptance Criteria
- Unit tests for the new metrics. Live: metrics port shows the new series after traffic.

## Out of Scope
- Spans / OTLP (Perf 5).
