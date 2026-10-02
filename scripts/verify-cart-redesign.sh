#!/usr/bin/env bash
set -euo pipefail

# Verifies Phase 1 of redis-cart-model_20261002 (redesign Cart as line items, drop
# the generated Postgres layer): CartStoreSuite/CartRoutesSuite already cover the new
# algebra and trimmed routes through the Scala API, so the one thing worth confirming
# live, independent of the test suite, is that the real service actually boots with
# no Postgres config/dependency at all (CartServiceConfig dropped PostgresConfig
# entirely) and that the trimmed HTTP surface behaves as intended end-to-end: create/
# get/delete still work, and the removed PATCH/PUT whole-cart-update endpoints are
# actually gone, not just untested.
#
# No docker-compose/Postgres/Redis needed for this check - this phase's CartStore is
# a temporary in-memory implementation (Main.scala), so a plain `sbt run` is enough.
#
# Usage: ./scripts/verify-cart-redesign.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FAILED=0
SBT_PID=""

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "$SBT_PID" ] && kill "$SBT_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 90); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health || true)"
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

echo "1. Starting cart-service (sbt run) - confirms CartServiceConfig (no Postgres field anymore) loads cleanly and the server boots with no database at all..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/cart-service-verify.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to start - check /tmp/cart-service-verify.log" >&2
  tail -40 /tmp/cart-service-verify.log >&2 || true
  exit 1
fi
check "cart-service booted with no Postgres dependency" "200" "200"

echo
echo "2. POST /carts creates an empty cart..."
CREATE_STATUS="$(curl -s -o /tmp/cart-verify-created.json -w '%{http_code}' -X POST http://localhost:8080/carts)"
check "POST /carts status" "201" "$CREATE_STATUS"
CART_ID="$(grep -o '"id":"[^"]*"' /tmp/cart-verify-created.json | head -1 | cut -d'"' -f4)"
echo "   Created cart id: ${CART_ID}"

echo
echo "3. GET /carts/{id} returns the cart..."
GET_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/carts/${CART_ID}")"
check "GET /carts/{id} status" "200" "$GET_STATUS"

echo
echo "4. PATCH and PUT /carts/{id} no longer exist (dropped - no generic whole-cart update against a line-item model)..."
# The path still matches (GET/DELETE /carts/{id} exist), so http4s/tapir correctly
# answers 405 Method Not Allowed rather than 404 for the now-unsupported methods.
PATCH_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "http://localhost:8080/carts/${CART_ID}" -H 'Content-Type: application/json' -d '{}')"
check "PATCH /carts/{id} status (expect 405, method no longer supported)" "405" "$PATCH_STATUS"
PUT_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "http://localhost:8080/carts/${CART_ID}" -H 'Content-Type: application/json' -d '{}')"
check "PUT /carts/{id} status (expect 405, method no longer supported)" "405" "$PUT_STATUS"

echo
echo "5. DELETE /carts/{id} removes the cart, then GET returns 404..."
DELETE_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "http://localhost:8080/carts/${CART_ID}")"
check "DELETE /carts/{id} status" "204" "$DELETE_STATUS"
GET_AFTER_DELETE_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/carts/${CART_ID}")"
check "GET /carts/{id} after delete" "404" "$GET_AFTER_DELETE_STATUS"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
