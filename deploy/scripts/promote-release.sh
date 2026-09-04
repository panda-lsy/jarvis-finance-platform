#!/usr/bin/env bash
set -euo pipefail

# 原子切换 /opt/jarvis/current -> release，并在本机做 AI/Java 健康检查。
# 任一健康检查失败会自动恢复旧 symlink 并重启旧版本。

ROOT="${JARVIS_ROOT:-/opt/jarvis}"
RELEASE="${1:-}"
JAVA_ENV="${JAVA_ENV:-/etc/jarvis/java.env}"
PYTHON_ENV="${PYTHON_ENV:-/etc/jarvis/python.env}"

if [ -z "$RELEASE" ]; then
  echo "Usage: $0 /opt/jarvis/releases/<release-id>" >&2
  exit 1
fi
RELEASE="$(readlink -f "$RELEASE")"

for f in \
  "$RELEASE/java-backend/app.jar" \
  "$RELEASE/backend/app/main.py" \
  "$RELEASE/backend/requirements.txt"; do
  [ -f "$f" ] || { echo "ERROR: release 缺少 $f" >&2; exit 1; }
done

[ -f "$JAVA_ENV" ] || { echo "ERROR: missing $JAVA_ENV" >&2; exit 1; }
[ -f "$PYTHON_ENV" ] || { echo "ERROR: missing $PYTHON_ENV" >&2; exit 1; }

PREVIOUS=""
if [ -L "$ROOT/current" ]; then
  PREVIOUS="$(readlink -f "$ROOT/current")"
fi

rollback() {
  local reason="$1"
  echo "ERROR: $reason" >&2
  if [ -n "$PREVIOUS" ] && [ -d "$PREVIOUS" ]; then
    echo "Rolling back to $PREVIOUS" >&2
    ln -sfn "$PREVIOUS" "$ROOT/current.rollback"
    mv -Tf "$ROOT/current.rollback" "$ROOT/current"
    systemctl restart jarvis-ai.service || true
    systemctl restart jarvis-java.service || true
  fi
  exit 1
}

ln -sfn "$RELEASE" "$ROOT/current.next"
mv -Tf "$ROOT/current.next" "$ROOT/current"

systemctl restart jarvis-ai.service

set -a
# shellcheck disable=SC1090
source "$PYTHON_ENV"
set +a

AI_OK=0
for _ in $(seq 1 20); do
  if curl --silent --show-error --fail \
      -H "X-Internal-Service-Token: $PYTHON_SERVICE_TOKEN" \
      http://127.0.0.1:8100/api/ready >/dev/null; then
    AI_OK=1
    break
  fi
  sleep 1
done
[ "$AI_OK" -eq 1 ] || rollback "Python AI health check failed"

systemctl restart jarvis-java.service
JAVA_OK=0
for _ in $(seq 1 30); do
  if curl --silent --show-error --fail \
      http://127.0.0.1:8200/api/health/ready >/dev/null; then
    JAVA_OK=1
    break
  fi
  sleep 1
done
[ "$JAVA_OK" -eq 1 ] || rollback "Java/DB readiness check failed"

if [ "${RUN_PUBLIC_SMOKE:-0}" = "1" ]; then
  SMOKE_COMMAND="${SMOKE_COMMAND:-/usr/local/sbin/jarvis-smoke-test}"
  [ -x "$SMOKE_COMMAND" ] || rollback "Smoke test command not executable: $SMOKE_COMMAND"
  "$SMOKE_COMMAND" || rollback "Production smoke test failed"
fi

systemctl --no-pager --full status jarvis-ai.service jarvis-java.service || true

echo "Release promoted successfully: $RELEASE"
if [ -n "$PREVIOUS" ]; then
  echo "Previous release retained for rollback: $PREVIOUS"
fi
