#!/usr/bin/env bash
set -euo pipefail

# Verifies Phase 3 (final) of cart-items_20261002: the complete US-2.1 add/remove
# flow against a real Redis via docker-compose. CartRoutesSuite already covers
# add/remove in isolation against an in-memory store; this closes the loop end to
# end against the real running service, and confirms the cart's state survives a
# service restart (persisted in Redis, not an in-process artifact).
#
# Usage: ./scripts/verify-remove-item-end-to-end.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FAILED=0
SBT_PID=""

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "$SBT_PID" ] && kill "$SBT_PID" >/dev/null 2>&1 || true
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 90); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health/ready || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: cart-service did not become ready in time." >&2
  return 1
}

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "   OK: ${label} (got ${actual})"
  else
    echo "   FAIL: ${label} - expected ${expected}, got ${actual}" >&2
    FAILED=1
  fi
}

echo "1. docker compose up -d (fresh volumes) + sbt run..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/cart-service-remove-item-e2e.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to start - check /tmp/cart-service-remove-item-e2e.log" >&2
  tail -40 /tmp/cart-service-remove-item-e2e.log >&2 || true
  exit 1
fi
echo "   OK: cart-service ready"

echo
echo "2. Build a cart with two items (widget x3, gadget x1)..."
CART_ID="$(curl -s -X POST http://localhost:8080/carts | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
curl -s -X POST "http://localhost:8080/carts/${CART_ID}/items" -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":3}' >/dev/null
curl -s -X POST "http://localhost:8080/carts/${CART_ID}/items" -H 'Content-Type: application/json' -d '{"sku":"gadget","quantity":1}' >/dev/null
echo "   Cart id: ${CART_ID}"

echo
echo "3. Restarting cart-service (kill + sbt run again) - the cart must survive..."
kill "$SBT_PID" >/dev/null 2>&1 || true
wait "$SBT_PID" 2>/dev/null || true
sbt run >/tmp/cart-service-remove-item-e2e-2.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to restart - check /tmp/cart-service-remove-item-e2e-2.log" >&2
  tail -40 /tmp/cart-service-remove-item-e2e-2.log >&2 || true
  exit 1
fi
AFTER_RESTART="$(curl -s "http://localhost:8080/carts/${CART_ID}")"
echo "   Cart after restart: ${AFTER_RESTART}"
if echo "$AFTER_RESTART" | grep -q '"widget":3' && echo "$AFTER_RESTART" | grep -q '"gadget":1'; then
  echo "   OK: both items survived the restart (Redis-persisted)"
else
  echo "   FAIL: expected both widget:3 and gadget:1 after restart" >&2
  FAILED=1
fi

echo
echo "4. DELETE /carts/{id}/items/widget - removes widget entirely, gadget stays..."
REMOVE_BODY="$(curl -s -X DELETE "http://localhost:8080/carts/${CART_ID}/items/widget")"
echo "   Response: ${REMOVE_BODY}"
if echo "$REMOVE_BODY" | grep -q '"widget"'; then
  echo "   FAIL: widget should no longer be present" >&2
  FAILED=1
else
  echo "   OK: widget removed entirely"
fi
if echo "$REMOVE_BODY" | grep -q '"gadget":1'; then
  echo "   OK: gadget untouched"
else
  echo "   FAIL: expected gadget:1 to remain" >&2
  FAILED=1
fi

echo
echo "5. DELETE the same sku again (widget) - no-op, 200 + unchanged cart..."
NOOP_STATUS="$(curl -s -o /tmp/cart-remove-noop.json -w '%{http_code}' -X DELETE "http://localhost:8080/carts/${CART_ID}/items/widget")"
check "repeat DELETE status (no-op)" "200" "$NOOP_STATUS"
if grep -q '"gadget":1' /tmp/cart-remove-noop.json; then
  echo "   OK: cart unchanged (gadget still present)"
else
  echo "   FAIL: expected gadget:1 to remain unchanged" >&2
  FAILED=1
fi

echo
echo "6. DELETE against a nonexistent cart id - 404..."
NOTFOUND_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "http://localhost:8080/carts/does-not-exist/items/widget")"
check "nonexistent cart status" "404" "$NOTFOUND_STATUS"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
