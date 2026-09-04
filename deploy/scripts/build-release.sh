#!/usr/bin/env bash
set -euo pipefail

# 在构建机执行。输出一个可上传到 /opt/jarvis/releases/<id> 的目录。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"
STAMP="$(date +%Y%m%d-%H%M%S)"
SHA="$(git rev-parse --short=12 HEAD 2>/dev/null || echo nogit)"
RELEASE_ID="${RELEASE_ID:-$STAMP-$SHA}"
OUT="${RELEASE_OUT:-$ROOT/dist/releases/$RELEASE_ID}"

rm -rf "$OUT"
mkdir -p "$OUT/java-backend" "$OUT/backend"

echo "[1/4] Java tests + production app jar"
(
  cd java-backend
  "$MVN" clean test package
)
APP_JAR="$(find java-backend/target -maxdepth 1 -type f -name 'gold-research-backend-*.jar' ! -name '*migration*' ! -name '*.original' | head -1)"
[ -n "$APP_JAR" ] || { echo "ERROR: app jar not found" >&2; exit 1; }
cp "$APP_JAR" "$OUT/java-backend/app.jar"

echo "[2/4] Standalone H2 -> PostgreSQL migration jar"
(
  cd java-backend
  "$MVN" -Pmigration-tool -DskipTests package
)
MIGRATION_JAR="$(find java-backend/target -maxdepth 1 -type f -name '*-migration.jar' | head -1)"
[ -n "$MIGRATION_JAR" ] || { echo "ERROR: migration jar not found" >&2; exit 1; }
cp "$MIGRATION_JAR" "$OUT/java-backend/migration.jar"

echo "[3/4] Python AI tests"
(
  cd backend
  PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 "$PYTHON" -m pytest -q
)
cp -a backend/app "$OUT/backend/app"
cp backend/requirements.txt "$OUT/backend/requirements.txt"
find "$OUT/backend" -type d -name '__pycache__' -prune -exec rm -rf {} +
find "$OUT/backend" -type f -name '*.pyc' -delete

echo "[4/4] Checksums + metadata"
printf 'release_id=%s\ngit_sha=%s\nbuilt_at=%s\n' \
  "$RELEASE_ID" "$SHA" "$(date -Iseconds)" > "$OUT/RELEASE"
(
  cd "$OUT"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)

echo "Release ready: $OUT"
du -sh "$OUT"
