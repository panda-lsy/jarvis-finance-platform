#!/usr/bin/env bash
set -euo pipefail

# 在切 PostgreSQL 前执行。为避免 H2 文件级备份不一致，Java 服务运行时拒绝备份。
H2_DIR="${H2_DIR:-/opt/jarvis/current/java-backend/data}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/jarvis}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="$BACKUP_ROOT/h2-$STAMP"

if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet jarvis-java.service; then
  echo "ERROR: jarvis-java.service 仍在运行。请先停止 Java，再做 H2 文件备份。" >&2
  exit 1
fi

mkdir -p "$DEST"
shopt -s nullglob
files=("$H2_DIR"/research*.db)
if [ ${#files[@]} -eq 0 ]; then
  echo "ERROR: 未找到 H2 文件: $H2_DIR/research*.db" >&2
  exit 1
fi

cp -a "${files[@]}" "$DEST/"
(
  cd "$DEST"
  sha256sum ./* > SHA256SUMS
)
chmod -R go-rwx "$DEST"

echo "H2 backup created: $DEST"
cat "$DEST/SHA256SUMS"
