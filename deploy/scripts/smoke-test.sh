#!/usr/bin/env bash
set -euo pipefail

SMOKE_ENV="${SMOKE_ENV:-/etc/jarvis/smoke.env}"
if [ -f "$SMOKE_ENV" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$SMOKE_ENV"
  set +a
fi

SMOKE_API_BASE="${SMOKE_API_BASE:-https://agent.shengxia.me}"
LOCAL_API_BASE="${LOCAL_API_BASE:-http://127.0.0.1:8200}"
CHECK_PY_BLOCK="${CHECK_PY_BLOCK:-1}"
: "${SMOKE_EMAIL:?SMOKE_EMAIL is required}"
: "${SMOKE_PASSWORD:?SMOKE_PASSWORD is required}"

for cmd in curl python3; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: $cmd not found" >&2; exit 1; }
done

cookie_jar="$(mktemp)"
cleanup() { rm -f "$cookie_jar"; }
trap cleanup EXIT

curl_args=(--silent --show-error --fail-with-body --connect-timeout 5 --max-time 20)

assert_wrapped_ok() {
  local label="$1"
  local url="$2"
  local body
  body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$url")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200, d'
  echo "OK  $label"
}

assert_json_object() {
  local label="$1"
  local url="$2"
  local body
  body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$url")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d, dict) and len(d)>0, d'
  echo "OK  $label"
}

post_wrapped_ok() {
  local label="$1"
  local url="$2"
  local csrf="$3"
  local payload="$4"
  local body
  body="$(printf '%s' "$payload" | curl "${curl_args[@]}" \
    -b "$cookie_jar" -c "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf" \
    --data-binary @- \
    "$url")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200, d'
  echo "OK  $label"
}

echo "== Local readiness =="
assert_wrapped_ok "Java liveness" "$LOCAL_API_BASE/api/health/live"
assert_wrapped_ok "Java + DB readiness" "$LOCAL_API_BASE/api/health/ready"

echo "== Public edge =="
assert_wrapped_ok "public liveness" "$SMOKE_API_BASE/api/health/live"
assert_wrapped_ok "public readiness" "$SMOKE_API_BASE/api/health/ready"

if [ "$CHECK_PY_BLOCK" = "1" ]; then
  py_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 5 --max-time 20 "$SMOKE_API_BASE/py/api/health")"
  [ "$py_status" = "404" ] || {
    echo "ERROR: public /py endpoint should be 404, got HTTP $py_status" >&2
    exit 1
  }
  echo "OK  public /py blocked"
fi

echo "== CSRF + auth =="
csrf_body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$SMOKE_API_BASE/api/auth/csrf")"
csrf_token="$(printf '%s' "$csrf_body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200; print(d["data"]["token"])')"
[ -n "$csrf_token" ] || { echo "ERROR: empty CSRF token" >&2; exit 1; }
echo "OK  CSRF token"

login_payload="$(SMOKE_EMAIL="$SMOKE_EMAIL" SMOKE_PASSWORD="$SMOKE_PASSWORD" python3 -c 'import json,os; print(json.dumps({"email":os.environ["SMOKE_EMAIL"],"password":os.environ["SMOKE_PASSWORD"]}))')"
unset SMOKE_PASSWORD
post_wrapped_ok "login" "$SMOKE_API_BASE/api/auth/login" "$csrf_token" "$login_payload"
assert_wrapped_ok "current user" "$SMOKE_API_BASE/api/auth/me"
assert_wrapped_ok "database detail" "$SMOKE_API_BASE/api/health/db"

echo "== Business reads =="
assert_wrapped_ok "market prices" "$SMOKE_API_BASE/api/market/prices"
assert_wrapped_ok "daily K-line" "$SMOKE_API_BASE/api/market/kline?market=gold_etf&interval=day&limit=5"
assert_wrapped_ok "sim account" "$SMOKE_API_BASE/api/sim/account"
assert_json_object "AI capabilities" "$SMOKE_API_BASE/api/ai/capabilities"

post_wrapped_ok "logout" "$SMOKE_API_BASE/api/auth/logout" "$csrf_token" '{}'

echo "Smoke test PASSED: $SMOKE_API_BASE"
