#!/usr/bin/env bash
set -euo pipefail

# Verifies Phase 2 of redis-cart-model_20261002 (Redis-backed CartStore
# implementation): CartStoreRedisSuite already proves the store's behavior against a
# real Testcontainers Redis, so the one thing worth confirming independent of the
# test suite - which just ran in an already-warm sbt session with dependencies
# already resolved - is that the new redis4cats/testcontainers-scala-redis
# dependencies actually resolve cleanly from scratch. Main.scala isn't wired to
# CartStore.redis yet (that's this track's own Phase 3), so there's no new live HTTP
# behavior to check at this checkpoint.
#
# Usage: ./scripts/verify-redis-store-clean-build.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "1. sbt clean - removing all compiled classes and cached incremental state..."
sbt clean >/tmp/cart-service-clean-build.log 2>&1
echo "   OK: clean completed"

echo
echo "2. sbt scalafmtCheck test - full clean rebuild, dependency resolution, and the"
echo "   full suite (including CartStoreRedisSuite against a real Redis container)..."
if sbt scalafmtCheck test >>/tmp/cart-service-clean-build.log 2>&1; then
  echo "   OK: clean build + full test suite passed"
  tail -5 /tmp/cart-service-clean-build.log
else
  echo "   FAIL: clean build or test suite failed - see /tmp/cart-service-clean-build.log" >&2
  tail -60 /tmp/cart-service-clean-build.log >&2
  exit 1
fi

echo
echo "All checks passed."
