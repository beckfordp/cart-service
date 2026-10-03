#!/usr/bin/env bash
set -euo pipefail

# Verifies Phase 2 of cart-items_20261002 (POST /carts/{id}/items): CartRoutesSuite
# already covers this through the Scala API against an in-memory store, so the one
# thing worth confirming live is the real endpoint against the real Redis-backed
# service - including that the oneOf error output actually serializes distinct
# 400/404 bodies over the wire, not just at the tapir-value level.
#
# Usage: ./scripts/verify-add-item-endpoint.sh

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

echo "1. docker compose up -d + sbt run..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/cart-service-add-item-verify.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to start - check /tmp/cart-service-add-item-verify.log" >&2
  tail -40 /tmp/cart-service-add-item-verify.log >&2 || true
  exit 1
fi
echo "   OK: cart-service ready"

echo
echo "2. POST /carts to create a cart..."
CART_ID="$(curl -s -X POST http://localhost:8080/carts | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
echo "   Cart id: ${CART_ID}"

echo
echo "3. POST /carts/{id}/items with a new sku..."
ADD_BODY="$(curl -s -X POST "http://localhost:8080/carts/${CART_ID}/items" -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":3}')"
echo "   Response: ${ADD_BODY}"
if echo "$ADD_BODY" | grep -q '"widget":3'; then
  echo "   OK: item added at the given quantity"
else
  echo "   FAIL: expected widget:3 in response" >&2
  FAILED=1
fi

echo
echo "4. POST again with the same sku - should increment, not overwrite..."
INCR_BODY="$(curl -s -X POST "http://localhost:8080/carts/${CART_ID}/items" -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":2}')"
echo "   Response: ${INCR_BODY}"
if echo "$INCR_BODY" | grep -q '"widget":5'; then
  echo "   OK: quantity incremented to 5"
else
  echo "   FAIL: expected widget:5 in response" >&2
  FAILED=1
fi

echo
echo "5. POST with quantity <= 0 - should be 400..."
INVALID_STATUS="$(curl -s -o /tmp/cart-add-item-invalid.json -w '%{http_code}' -X POST "http://localhost:8080/carts/${CART_ID}/items" -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":0}')"
check "invalid quantity status" "400" "$INVALID_STATUS"
if grep -q '"error"' /tmp/cart-add-item-invalid.json; then
  echo "   OK: JSON error body present"
else
  echo "   FAIL: expected a JSON error body" >&2
  FAILED=1
fi

echo
echo "6. POST against a nonexistent cart id - should be 404..."
NOTFOUND_STATUS="$(curl -s -o /tmp/cart-add-item-notfound.json -w '%{http_code}' -X POST "http://localhost:8080/carts/does-not-exist/items" -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":1}')"
check "nonexistent cart status" "404" "$NOTFOUND_STATUS"
if grep -q '"error"' /tmp/cart-add-item-notfound.json; then
  echo "   OK: JSON error body present"
else
  echo "   FAIL: expected a JSON error body" >&2
  FAILED=1
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
