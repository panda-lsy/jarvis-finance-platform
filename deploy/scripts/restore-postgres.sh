#!/usr/bin/env bash
set -euo pipefail

# 破坏性恢复脚本：恢复前必须停 Java，并显式设置 RESTORE_CONFIRM=RESTORE_POSTGRES。
JAVA_ENV="${JAVA_ENV:-/etc/jarvis/java.env}"
BACKUP_FILE="${1:-}"
RESTORE_CONFIRM="${RESTORE_CONFIRM:-}"
SKIP_PRE_RESTORE_BACKUP="${SKIP_PRE_RESTORE_BACKUP:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "${EUID:-$(id -u)}" -ne 0 ]; then
  echo "ERROR: restore must run as root" >&2
  exit 1
fi

[ -n "$BACKUP_FILE" ] || { echo "Usage: RESTORE_CONFIRM=RESTORE_POSTGRES $0 /path/to/backup.dump" >&2; exit 1; }
[ -f "$BACKUP_FILE" ] || { echo "ERROR: backup not found: $BACKUP_FILE" >&2; exit 1; }
[ "$RESTORE_CONFIRM" = "RESTORE_POSTGRES" ] || {
  echo "ERROR: set RESTORE_CONFIRM=RESTORE_POSTGRES to acknowledge destructive restore" >&2
  exit 1
}

if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet jarvis-java.service; then
  echo "ERROR: jarvis-java.service is still running; stop it before restore" >&2
  exit 1
fi

if [ -f "$JAVA_ENV" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$JAVA_ENV"
  set +a
fi

: "${DB_URL:?DB_URL is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

for cmd in pg_restore psql sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: $cmd not found" >&2; exit 1; }
done

case "$DB_URL" in
  jdbc:postgresql://*) ;;
  *) echo "ERROR: unsupported DB_URL: expected jdbc:postgresql://host[:port]/database" >&2; exit 1 ;;
esac
url="${DB_URL#jdbc:postgresql://}"
authority="${url%%/*}"
database_with_query="${url#*/}"
database="${database_with_query%%\?*}"
if [[ "$authority" == *:* ]]; then
  host="${authority%%:*}"
  port="${authority##*:}"
else
  host="$authority"
  port="5432"
fi

# 先验证 dump 目录可读取；存在同名校验文件时必须通过 SHA256。
pg_restore --list "$BACKUP_FILE" >/dev/null
checksum="${BACKUP_FILE%.dump}.sha256"
if [ -f "$checksum" ]; then
  (
    cd "$(dirname "$BACKUP_FILE")"
    sha256sum --check "$(basename "$checksum")"
  )
else
  echo "WARN: checksum sidecar not found: $checksum" >&2
fi

export PGPASSWORD="$DB_PASSWORD"

# 默认在覆盖前再做一次当前 PostgreSQL 备份；灾难恢复时可显式跳过。
if [ "$SKIP_PRE_RESTORE_BACKUP" != "1" ]; then
  echo "Creating pre-restore safety backup..."
  JAVA_ENV="$JAVA_ENV" "$SCRIPT_DIR/backup-postgres.sh"
else
  echo "WARN: pre-restore backup explicitly skipped" >&2
fi

echo "Restoring $BACKUP_FILE into database '$database' on $host:$port ..."
pg_restore \
  --host="$host" \
  --port="$port" \
  --username="$DB_USERNAME" \
  --dbname="$database" \
  --clean \
  --if-exists \
  --no-owner \
  --no-acl \
  --single-transaction \
  "$BACKUP_FILE"

# 最小恢复后校验：数据库可查询，Flyway history 与核心表存在。
psql \
  --host="$host" \
  --port="$port" \
  --username="$DB_USERNAME" \
  --dbname="$database" \
  --set=ON_ERROR_STOP=1 \
  --tuples-only \
  --no-align \
  --command="SELECT 1; SELECT COUNT(*) FROM flyway_schema_history; SELECT COUNT(*) FROM users;" >/dev/null

unset PGPASSWORD

echo "PostgreSQL restore OK: $BACKUP_FILE"
echo "Next: start jarvis-java.service and verify /api/health/ready before reopening traffic."
