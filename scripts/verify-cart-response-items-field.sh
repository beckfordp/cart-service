#!/usr/bin/env bash
set -euo pipefail

# Verifies Phase 1 of cart-items_20261002 (Error ADT + CartResponse items field):
# CartRoutesSuite already proves CartResponse.apply carries items through at the
# Scala-API level, so the one thing worth confirming live is that the real running
# service's JSON responses actually include the new "items" field now - a real,
# if small, observable change to every existing POST/GET response. The new
# InvalidQuantity error output isn't reachable via any real route yet (that's
# Phase 2), so nothing to check for it here.
#
# Usage: ./scripts/verify-cart-response-items-field.sh

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
sbt run >/tmp/cart-service-items-field-verify.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to start - check /tmp/cart-service-items-field-verify.log" >&2
  tail -40 /tmp/cart-service-items-field-verify.log >&2 || true
  exit 1
fi
echo "   OK: cart-service ready"

echo
echo "2. POST /carts - response body must include an \"items\" field..."
CREATE_BODY="$(curl -s -X POST http://localhost:8080/carts)"
echo "   Response: ${CREATE_BODY}"
if echo "$CREATE_BODY" | grep -q '"items"'; then
  echo "   OK: response includes an items field"
else
  echo "   FAIL: response missing items field" >&2
  FAILED=1
fi
if echo "$CREATE_BODY" | grep -q '"items":{}'; then
  echo "   OK: a freshly created cart's items is an empty object"
else
  echo "   FAIL: expected an empty items object on a freshly created cart" >&2
  FAILED=1
fi

echo
echo "3. GET /carts/{id} - same items field present..."
CART_ID="$(echo "$CREATE_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
GET_BODY="$(curl -s "http://localhost:8080/carts/${CART_ID}")"
if echo "$GET_BODY" | grep -q '"items":{}'; then
  echo "   OK: GET response includes items"
else
  echo "   FAIL: GET response missing items" >&2
  FAILED=1
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
