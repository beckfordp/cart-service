#!/usr/bin/env bash
set -euo pipefail

# Verifies Phase 3 (final) of redis-cart-model_20261002: cart-service wired end to
# end against a real Redis via docker-compose, with no Postgres anywhere in the
# stack. CartStoreRedisSuite/CartServiceConfigSuite already cover the pieces in
# isolation (against a Testcontainers Redis and in-process config loading
# respectively) - the one thing worth confirming live is the full real thing: real
# docker-compose Redis, real sbt run, real HTTP requests, and that a cart actually
# survives a service restart (persisted in Redis, not an in-memory artifact of a
# single process).
#
# Usage: ./scripts/verify-redis-wiring-end-to-end.sh

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

echo "1. docker compose up -d (fresh volumes) - confirms only Redis comes up, no Postgres..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
SERVICES="$(docker compose ps --services)"
check "docker-compose brings up exactly 'redis'" "redis" "$SERVICES"

echo
echo "2. Starting cart-service (sbt run) - confirms CartServiceConfig's new required"
echo "   'redis' block loads cleanly and /health/ready reflects real Redis reachability..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/cart-service-e2e-verify.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to start - check /tmp/cart-service-e2e-verify.log" >&2
  tail -40 /tmp/cart-service-e2e-verify.log >&2 || true
  exit 1
fi
check "cart-service booted against real docker-compose Redis" "200" "200"

echo
echo "3. POST /carts creates an empty cart, persisted in the real Redis..."
CREATE_STATUS="$(curl -s -o /tmp/cart-e2e-created.json -w '%{http_code}' -X POST http://localhost:8080/carts)"
check "POST /carts status" "201" "$CREATE_STATUS"
CART_ID="$(grep -o '"id":"[^"]*"' /tmp/cart-e2e-created.json | head -1 | cut -d'"' -f4)"
echo "   Created cart id: ${CART_ID}"

echo
echo "4. Restarting cart-service (kill + sbt run again) - the cart must survive,"
echo "   proving it's persisted in Redis, not an in-process artifact..."
kill "$SBT_PID" >/dev/null 2>&1 || true
wait "$SBT_PID" 2>/dev/null || true
sbt run >/tmp/cart-service-e2e-verify-2.log 2>&1 &
SBT_PID=$!
if ! wait_ready; then
  echo "   FAIL: cart-service failed to restart - check /tmp/cart-service-e2e-verify-2.log" >&2
  tail -40 /tmp/cart-service-e2e-verify-2.log >&2 || true
  exit 1
fi
GET_AFTER_RESTART_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/carts/${CART_ID}")"
check "GET /carts/{id} survives a service restart (Redis-persisted)" "200" "$GET_AFTER_RESTART_STATUS"

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
