#!/usr/bin/env bash
set -euo pipefail

# 安装 systemd 单元与目录。不会写入真实 secret，也不会自动启动服务。
# 需 root 执行，且建议在 release 已上传后运行。

if [ "${EUID:-$(id -u)}" -ne 0 ]; then
  echo "ERROR: 请使用 root 执行" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if ! id jarvis >/dev/null 2>&1; then
  useradd --system --home /opt/jarvis --shell /usr/sbin/nologin jarvis
fi

install -d -o jarvis -g jarvis -m 0750 /opt/jarvis /opt/jarvis/releases
install -d -o root -g jarvis -m 0750 /etc/jarvis
install -d -o jarvis -g jarvis -m 0750 /var/backups/jarvis/postgres
install -d -o root -g root -m 0750 /var/lib/jarvis-monitor

install -m 0644 "$DEPLOY_DIR/systemd/jarvis-ai.service" /etc/systemd/system/jarvis-ai.service
install -m 0644 "$DEPLOY_DIR/systemd/jarvis-java.service" /etc/systemd/system/jarvis-java.service
install -m 0644 "$DEPLOY_DIR/systemd/jarvis-postgres-backup.service" /etc/systemd/system/jarvis-postgres-backup.service
install -m 0644 "$DEPLOY_DIR/systemd/jarvis-postgres-backup.timer" /etc/systemd/system/jarvis-postgres-backup.timer
install -m 0644 "$DEPLOY_DIR/systemd/jarvis-monitor.service" /etc/systemd/system/jarvis-monitor.service
install -m 0644 "$DEPLOY_DIR/systemd/jarvis-monitor.timer" /etc/systemd/system/jarvis-monitor.timer
install -m 0750 "$DEPLOY_DIR/scripts/backup-postgres.sh" /usr/local/sbin/jarvis-postgres-backup
install -m 0750 "$DEPLOY_DIR/scripts/restore-postgres.sh" /usr/local/sbin/jarvis-postgres-restore
install -m 0750 "$DEPLOY_DIR/scripts/smoke-test.sh" /usr/local/sbin/jarvis-smoke-test
install -m 0750 "$DEPLOY_DIR/scripts/monitor-health.sh" /usr/local/sbin/jarvis-monitor

if [ ! -f /etc/jarvis/java.env ]; then
  install -m 0640 -o root -g jarvis "$DEPLOY_DIR/env/java.env.example" /etc/jarvis/java.env
  echo "Created /etc/jarvis/java.env from template - EDIT SECRETS BEFORE STARTING."
fi
if [ ! -f /etc/jarvis/python.env ]; then
  install -m 0640 -o root -g jarvis "$DEPLOY_DIR/env/python.env.example" /etc/jarvis/python.env
  echo "Created /etc/jarvis/python.env from template - EDIT SECRETS BEFORE STARTING."
fi
if [ ! -f /etc/jarvis/smoke.env ]; then
  install -m 0600 -o root -g root "$DEPLOY_DIR/env/smoke.env.example" /etc/jarvis/smoke.env
  echo "Created /etc/jarvis/smoke.env from template - EDIT TEST ACCOUNT BEFORE SMOKE TEST."
fi
if [ ! -f /etc/jarvis/monitor.env ]; then
  install -m 0600 -o root -g root "$DEPLOY_DIR/env/monitor.env.example" /etc/jarvis/monitor.env
  echo "Created /etc/jarvis/monitor.env from template - configure webhook/thresholds if needed."
fi

systemctl daemon-reload
systemctl enable jarvis-ai.service jarvis-java.service jarvis-postgres-backup.timer jarvis-monitor.timer

echo "Units installed but application services/timer not started."
echo "Next: configure /etc/jarvis/*.env, create/migrate PostgreSQL, prepare /opt/jarvis/current, then promote release."
echo "After PostgreSQL is ready: first run /usr/local/sbin/jarvis-postgres-backup once, then start jarvis-postgres-backup.timer and jarvis-monitor.timer."
