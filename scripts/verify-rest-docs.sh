#!/usr/bin/env bash
set -euo pipefail

# Verifies the REST docs convention end-to-end (track rest-docs_20261101,
# gluon TD-20.8): brings up Redis + a real sbt run instance, fetches the
# served OpenAPI spec at /docs/docs.yaml, and checks it carries every
# endpoint's summary/description/tag, the parameter and error descriptions,
# DTO field descriptions and body examples, and that each endpoint lists only
# the error statuses it can return - not just that CartDocsSuite's in-process
# Docs.undocumented check passes.
#
# Requires: sbt, Docker, python3 with PyYAML, GITHUB_TOKEN/GITHUB_ACTOR set
# (purerestlib resolution).
#
# Usage: ./scripts/verify-rest-docs.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SBT_PID=""
SPEC="$(mktemp)"

cleanup() {
  echo
  echo "Cleaning up..."
  if [ -n "$SBT_PID" ]; then
    pkill -9 -P "$SBT_PID" >/dev/null 2>&1 || true
    kill -9 "$SBT_PID" >/dev/null 2>&1 || true
  fi
  docker compose down -v >/dev/null 2>&1 || true
  rm -f "$SPEC"
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

echo "1. docker compose up -d (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: redis starting"

echo
echo "2. Starting cart-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run </dev/null >/tmp/cart-service-verify-rest-docs.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: cart-service ready on :8080"

echo
echo "3. GET /docs/docs.yaml..."
status="$(curl -s -o "$SPEC" -w '%{http_code}' http://localhost:8080/docs/docs.yaml)"
[ "$status" = "200" ] || { echo "FAIL: /docs/docs.yaml returned $status" >&2; exit 1; }
echo "   OK: served (200)"

echo
echo "4. Checking the spec..."
python3 -I - "$SPEC" <<'PY'
import sys, yaml

spec = yaml.safe_load(open(sys.argv[1]))
paths, schemas = spec["paths"], spec["components"]["schemas"]
failed = False

def check(label, ok):
    global failed
    print(("   OK: " if ok else "   FAIL: ") + label, file=sys.stdout if ok else sys.stderr)
    failed |= not ok

# Success + declared errors per endpoint; tapir adds 400 for undecodable
# inputs on its own.
allowed = {
    ("/carts", "post"): {"201"},
    ("/carts/{id}", "get"): {"200", "400", "404"},
    ("/carts/{id}/items", "post"): {"200", "400", "404"},
    ("/carts/{id}/items/{sku}", "delete"): {"200", "400", "404"},
    ("/carts/{id}", "delete"): {"204", "400", "404"},
    ("/health", "get"): {"200"},
    ("/health/ready", "get"): {"200", "503"},
}
for (path, method), statuses in allowed.items():
    op = paths.get(path, {}).get(method)
    name = f"{method.upper()} {path}"
    if op is None:
        check(f"{name} documented", False)
        continue
    check(f"{name} has summary/description/tag",
          bool(op.get("summary")) and bool(op.get("description")) and bool(op.get("tags")))
    expected_tag = "health" if path.startswith("/health") else "carts"
    check(f"{name} tagged '{expected_tag}'", op.get("tags") == [expected_tag])
    for p in op.get("parameters", []):
        check(f"{name} parameter '{p['name']}' described with example",
              bool(p.get("description")) and ("example" in p or "example" in p.get("schema", {})))
    responses = op.get("responses", {})
    check(f"{name} lists only {sorted(statuses)} (got {sorted(responses)})",
          set(responses) <= statuses)
    for code, r in responses.items():
        if code.startswith("2"):
            continue
        check(f"{name} {code} described", bool(r.get("description")))

post = paths["/carts/{id}/items"]["post"]
check("POST /carts/{id}/items request body has an example",
      any("example" in c for c in post["requestBody"]["content"].values()))
check("POST /carts/{id}/items 400 has an example body",
      any("example" in c for c in post["responses"]["400"]["content"].values()))

get_cart = paths["/carts/{id}"]["get"]
check("GET /carts/{id} 404 has an example body",
      any("example" in c for c in get_cart["responses"]["404"]["content"].values()))
check("GET /carts/{id} response body has an example",
      any("example" in c for c in get_cart["responses"]["200"]["content"].values()))

for name in ["AddItemRequest", "CartResponse", "ErrorResponse"]:
    props = schemas.get(name, {}).get("properties", {})
    # A field that only references another component is described by it
    # (tapir folds a single-use field's description into the component).
    def described(s):
        ref = s.get("$ref", "").rsplit("/", 1)[-1]
        return bool(s.get("description")) or bool(ref and schemas.get(ref, {}).get("description"))
    missing = [f for f, s in props.items() if not described(s)]
    check(f"schema {name} fields all described"
          + (f" (missing: {missing}: {[props[f] for f in missing]})" if missing else ""),
          bool(props) and not missing)

sys.exit(1 if failed else 0)
PY

echo
echo "All checks passed."
