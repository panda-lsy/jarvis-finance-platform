#!/usr/bin/env bash
set -euo pipefail

# 使用已经停止写入的 H2 备份迁移到一个空 PostgreSQL schema。
# 用法：
#   H2_BACKUP_DIR=/var/backups/jarvis/h2-20260904-170000 \
#   MIGRATION_JAR=/opt/jarvis/releases/<release>/java-backend/migration.jar \
#   sudo -E ./run-h2-migration.sh

JAVA_ENV="${JAVA_ENV:-/etc/jarvis/java.env}"
H2_BACKUP_DIR="${H2_BACKUP_DIR:-}"
MIGRATION_JAR="${MIGRATION_JAR:-/opt/jarvis/current/java-backend/migration.jar}"

if [ -z "$H2_BACKUP_DIR" ]; then
  echo "ERROR: 必须设置 H2_BACKUP_DIR" >&2
  exit 1
fi
if [ ! -f "$H2_BACKUP_DIR/research.mv.db" ]; then
  echo "ERROR: 未找到 $H2_BACKUP_DIR/research.mv.db" >&2
  exit 1
fi
if [ ! -f "$MIGRATION_JAR" ]; then
  echo "ERROR: 未找到迁移 JAR: $MIGRATION_JAR" >&2
  exit 1
fi
if [ ! -f "$JAVA_ENV" ]; then
  echo "ERROR: 未找到 Java 环境文件: $JAVA_ENV" >&2
  exit 1
fi
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet jarvis-java.service; then
  echo "ERROR: jarvis-java.service 仍在运行；迁移期间必须保持停止。" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$JAVA_ENV"
set +a

export H2_SOURCE_URL="jdbc:h2:file:${H2_BACKUP_DIR}/research;MODE=MySQL;ACCESS_MODE_DATA=r"
export H2_SOURCE_USERNAME="${H2_SOURCE_USERNAME:-sa}"
export H2_SOURCE_PASSWORD="${H2_SOURCE_PASSWORD:-}"
export MIGRATION_CONFIRM="MIGRATE_H2_TO_POSTGRES"

java -jar "$MIGRATION_JAR"

echo "Migration completed. Do not delete the H2 backup until production verification is complete."
