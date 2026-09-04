#!/usr/bin/env bash
set -euo pipefail

# 只读服务器预检，不修改系统。
echo '== OS =='
uname -a

echo
echo '== Java / Python / PostgreSQL / Nginx =='
command -v java || true
java -version 2>&1 | head -3 || true
command -v python3 || true
python3 --version || true
for cmd in psql pg_dump pg_restore; do
  command -v "$cmd" || true
  "$cmd" --version || true
done
command -v curl || true
curl --version 2>/dev/null | head -1 || true
command -v nginx || true
nginx -v 2>&1 || true

echo
echo '== Listening ports =='
ss -lntp 2>/dev/null | grep -E '(:22|:80|:443|:8100|:8200|:8201|:5432)\b' || true

echo
echo '== Existing JARVIS services =='
systemctl --no-pager --full status jarvis-java.service jarvis-ai.service jarvis-postgres-backup.timer 2>/dev/null || true

echo
echo '== Legacy related processes =='
ps -ef | grep -E 'uvicorn|java.*8200|websocket_server|dashboard_v3|api_server' | grep -v grep || true

echo
echo '== Java process working directories =='
for pid in $(pgrep -f 'java' 2>/dev/null || true); do
  [ -d "/proc/$pid" ] || continue
  printf 'pid=%s cwd=' "$pid"
  readlink -f "/proc/$pid/cwd" 2>/dev/null || true
done

echo
echo '== H2 database candidates =='
for root in /opt /srv /var/www /root; do
  [ -d "$root" ] || continue
  find "$root" -maxdepth 6 -type f \( -name '*.mv.db' -o -name '*.h2.db' -o -name '*.trace.db' \) -printf '%p %s bytes\n' 2>/dev/null || true
done

echo
echo '== PostgreSQL service / databases =='
systemctl --no-pager --full status postgresql.service 2>/dev/null | head -20 || true
if command -v psql >/dev/null 2>&1 && id postgres >/dev/null 2>&1; then
  sudo -u postgres psql -Atqc "select current_setting('server_version');" 2>/dev/null || true
  sudo -u postgres psql -Atqc "select datname from pg_database where datistemplate = false order by datname;" 2>/dev/null || true
fi

echo
echo '== Nginx references to 8100/8200/py =='
grep -RInE '8100|8200|location .*/py' /etc/nginx 2>/dev/null || true

echo
echo '== Firewall =='
if command -v ufw >/dev/null 2>&1; then ufw status verbose || true; fi
if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --list-all || true; fi

echo
echo 'Expected final state:'
echo '  127.0.0.1:8100 -> Python AI only'
echo '  127.0.0.1:8200 -> Java API only'
echo '  127.0.0.1:8201 -> Java management/Prometheus only'
echo '  public 80/443 -> Nginx only'
echo '  PostgreSQL 5432 -> localhost/private only'
