#!/usr/bin/env bash
set -euo pipefail

# PostgreSQL 自校验备份：custom format + pg_restore --list + SHA256 + 保留策略。
JAVA_ENV="${JAVA_ENV:-/etc/jarvis/java.env}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/jarvis/postgres}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

if [ -f "$JAVA_ENV" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$JAVA_ENV"
  set +a
fi

: "${DB_URL:?DB_URL is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

if ! [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] || [ "$RETENTION_DAYS" -lt 1 ]; then
  echo "ERROR: RETENTION_DAYS must be a positive integer" >&2
  exit 1
fi

command -v pg_dump >/dev/null 2>&1 || { echo "ERROR: pg_dump not found" >&2; exit 1; }
command -v pg_restore >/dev/null 2>&1 || { echo "ERROR: pg_restore not found" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "ERROR: sha256sum not found" >&2; exit 1; }

case "$DB_URL" in
  jdbc:postgresql://*) ;;
  *) echo "ERROR: unsupported DB_URL: expected jdbc:postgresql://host[:port]/database" >&2; exit 1 ;;
esac

url="${DB_URL#jdbc:postgresql://}"
authority="${url%%/*}"
database_with_query="${url#*/}"
database="${database_with_query%%\?*}"
if [ "$authority" = "$url" ] || [ -z "$database" ]; then
  echo "ERROR: invalid DB_URL" >&2
  exit 1
fi
if [[ "$authority" == *:* ]]; then
  host="${authority%%:*}"
  port="${authority##*:}"
else
  host="$authority"
  port="5432"
fi

umask 077
mkdir -p "$BACKUP_DIR"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="jarvis-${database}-${timestamp}"
tmp="$BACKUP_DIR/.${base}.dump.tmp"
dump="$BACKUP_DIR/${base}.dump"
manifest="$BACKUP_DIR/${base}.sha256"

cleanup() { rm -f "$tmp"; }
trap cleanup EXIT

export PGPASSWORD="$DB_PASSWORD"
pg_dump \
  --host="$host" \
  --port="$port" \
  --username="$DB_USERNAME" \
  --dbname="$database" \
  --format=custom \
  --compress=6 \
  --no-owner \
  --no-acl \
  --file="$tmp"

# 只有 pg_restore 能读取目录列表才算有效备份。
pg_restore --list "$tmp" >/dev/null
mv "$tmp" "$dump"
(
  cd "$BACKUP_DIR"
  sha256sum "$(basename "$dump")" > "$(basename "$manifest")"
)
unset PGPASSWORD
trap - EXIT

# 只清理本脚本产生的 PostgreSQL dump/manifest，不碰 H2 备份。
find "$BACKUP_DIR" -type f \( -name 'jarvis-*.dump' -o -name 'jarvis-*.sha256' \) \
  -mtime "+$RETENTION_DAYS" -delete

size="$(du -h "$dump" | awk '{print $1}')"
echo "PostgreSQL backup OK: $dump ($size)"
echo "Checksum: $manifest"
echo "Retention: ${RETENTION_DAYS} days"
