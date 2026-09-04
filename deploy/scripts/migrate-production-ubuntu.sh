#!/usr/bin/env bash
set -Eeuo pipefail

# Ubuntu 24.04 单机生产迁移：旧 H2 -> PostgreSQL + 固定 commit 发布。
# 设计目标：准备阶段不停止现网；切换阶段停止 Nginx 阻断外部写入；失败自动恢复旧 unit/env/H2 服务。
# 必须显式：MIGRATION_CONFIRM=MIGRATE_JARVIS_PRODUCTION

APP_COMMIT="${APP_COMMIT:-2a942ea41e8bbcd1977e44e965224d3986bb54b6}"
REPO_URL="${REPO_URL:-https://github.com/panda-lsy/jarvis-finance-platform.git}"
CONFIRM="${MIGRATION_CONFIRM:-}"
JARVIS_ROOT="${JARVIS_ROOT:-/opt/jarvis}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/jarvis}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
STATE_DIR="$BACKUP_ROOT/pre-migration-$STAMP"
BUILD_DIR="$JARVIS_ROOT/build/$APP_COMMIT"
RELEASE_DIR="$JARVIS_ROOT/releases/$APP_COMMIT"
JAVA_ENV_NEXT="/etc/jarvis/java.env.next"
PYTHON_ENV_NEXT="/etc/jarvis/python.env.next"
CUTOVER_STARTED=0
COMPLETED=0

log() { printf '\n[%s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[ "${EUID:-$(id -u)}" -eq 0 ] || die "必须使用 root 执行"
[ "$CONFIRM" = "MIGRATE_JARVIS_PRODUCTION" ] || die "必须设置 MIGRATION_CONFIRM=MIGRATE_JARVIS_PRODUCTION"

command -v systemctl >/dev/null || die "systemctl 不可用"
systemctl is-active --quiet jarvis-java.service || die "旧 jarvis-java.service 当前不是 active，拒绝猜测生产状态"
systemctl is-active --quiet jarvis-ai.service || die "旧 jarvis-ai.service 当前不是 active，拒绝迁移"
systemctl is-active --quiet nginx || die "nginx 当前不是 active，拒绝迁移"

OLD_JAVA_ACTIVE=1
OLD_AI_ACTIVE=1
OLD_NGINX_ACTIVE=1
OLD_JAVA_PID="$(systemctl show -p MainPID --value jarvis-java.service)"
[[ "$OLD_JAVA_PID" =~ ^[1-9][0-9]*$ ]] || die "无法获取旧 Java PID"
OLD_JAVA_CWD="$(readlink -f "/proc/$OLD_JAVA_PID/cwd")"
[ -d "$OLD_JAVA_CWD" ] || die "无法读取旧 Java WorkingDirectory"

mapfile -t H2_FILES < <(find "$OLD_JAVA_CWD" -maxdepth 4 -type f -name 'research.mv.db' -print 2>/dev/null)
if [ "${#H2_FILES[@]}" -ne 1 ]; then
  echo "Detected H2 candidates:" >&2
  printf '  %s\n' "${H2_FILES[@]:-<none>}" >&2
  die "必须且只能检测到一个 research.mv.db"
fi
H2_FILE="${H2_FILES[0]}"
H2_DIR="$(dirname "$H2_FILE")"

OLD_AI_PID="$(systemctl show -p MainPID --value jarvis-ai.service)"
[[ "$OLD_AI_PID" =~ ^[1-9][0-9]*$ ]] || die "无法获取旧 Python AI PID"
[ -r "/proc/$OLD_AI_PID/environ" ] || die "无法读取旧 Python AI 环境"

proc_env() {
  local key="$1"
  tr '\0' '\n' < "/proc/$OLD_AI_PID/environ" | awk -F= -v k="$key" '$1==k {sub(/^[^=]*=/, ""); print; exit}'
}

AI_API_KEY="$(proc_env AI_API_KEY || true)"
[ -n "$AI_API_KEY" ] || AI_API_KEY="$(proc_env DEEPSEEK_API_KEY || true)"
[ -n "$AI_API_KEY" ] || AI_API_KEY="$(proc_env OLLAMA_API_KEY || true)"
[ -n "$AI_API_KEY" ] || die "无法从当前 Python 进程继承 AI API Key；为避免上线后 AI 不可用，迁移终止"
AI_BASE_URL="$(proc_env AI_BASE_URL || true)"
[ -n "$AI_BASE_URL" ] || AI_BASE_URL="$(proc_env DEEPSEEK_BASE_URL || true)"
[ -n "$AI_BASE_URL" ] || AI_BASE_URL="https://ollama.com/v1"
AI_MODEL="$(proc_env AI_MODEL || true)"
[ -n "$AI_MODEL" ] || AI_MODEL="$(proc_env DEEPSEEK_MODEL || true)"
[ -n "$AI_MODEL" ] || AI_MODEL="deepseek-v4-flash:0731"
AI_PROVIDER="$(proc_env AI_PROVIDER || true)"
if [ -z "$AI_PROVIDER" ]; then
  if [[ "$AI_BASE_URL" == *ollama.com* ]]; then AI_PROVIDER="ollama-cloud"; else AI_PROVIDER="deepseek"; fi
fi
AI_TIMEOUT="$(proc_env AI_TIMEOUT || true)"
[ -n "$AI_TIMEOUT" ] || AI_TIMEOUT="$(proc_env DEEPSEEK_TIMEOUT || true)"
[ -n "$AI_TIMEOUT" ] || AI_TIMEOUT="60"
[[ "$AI_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || die "当前 AI_TIMEOUT 不是正整数，拒绝迁移"

FRONTEND_ROOT="$(nginx -T 2>&1 | python3 -c '
import re, sys
text=sys.stdin.read()
roots=[]
pos=0
while True:
    m=re.search(r"\bserver\s*\{", text[pos:])
    if not m: break
    start=pos+m.start(); brace=text.find("{", start); depth=0; end=None
    for i in range(brace, len(text)):
        if text[i]=="{": depth+=1
        elif text[i]=="}":
            depth-=1
            if depth==0:
                end=i+1; break
    if end is None: break
    block=text[start:end]
    if re.search(r"\bserver_name\b[^;]*\bf\.shengxia\.me\b[^;]*;", block):
        roots += [r.strip() for r in re.findall(r"(?m)^\s*root\s+([^;]+);", block)]
    pos=end
roots=sorted(set(roots))
if len(roots)==1: print(roots[0])
')"
[ -n "$FRONTEND_ROOT" ] || die "无法从 Nginx 唯一识别 f.shengxia.me 的静态 root；为避免覆盖错误目录，拒绝自动切换"
[[ "$FRONTEND_ROOT" != *'$'* ]] || die "前端 Nginx root 含变量，无法安全自动部署"
[ -d "$FRONTEND_ROOT" ] || die "前端静态目录不存在: $FRONTEND_ROOT"

log "Detected current production"
echo "Java PID: $OLD_JAVA_PID"
echo "Java cwd: $OLD_JAVA_CWD"
echo "H2 file: $H2_FILE ($(du -h "$H2_FILE" | awk '{print $1}'))"
echo "Python PID: $OLD_AI_PID"
echo "AI provider/model: $AI_PROVIDER / $AI_MODEL"
echo "Frontend root: $FRONTEND_ROOT"
echo "App commit to deploy: $APP_COMMIT"

auto_restore_file() {
  local backup="$1" target="$2" existed="$3"
  if [ "$existed" = "1" ] && [ -f "$backup" ]; then
    cp -a "$backup" "$target"
  elif [ "$existed" = "0" ]; then
    rm -f "$target"
  fi
}

JAVA_ENV_EXISTED=0; [ -f /etc/jarvis/java.env ] && JAVA_ENV_EXISTED=1
PYTHON_ENV_EXISTED=0; [ -f /etc/jarvis/python.env ] && PYTHON_ENV_EXISTED=1
JAVA_UNIT_EXISTED=0; [ -f /etc/systemd/system/jarvis-java.service ] && JAVA_UNIT_EXISTED=1
AI_UNIT_EXISTED=0; [ -f /etc/systemd/system/jarvis-ai.service ] && AI_UNIT_EXISTED=1

rollback() {
  local rc="${1:-1}"
  [ "$COMPLETED" -eq 1 ] && exit "$rc"
  if [ "$CUTOVER_STARTED" -eq 1 ]; then
    echo >&2
    echo "ROLLBACK: migration/cutover failed; restoring old service definitions." >&2
    systemctl stop jarvis-java.service 2>/dev/null || true
    systemctl stop jarvis-ai.service 2>/dev/null || true
    auto_restore_file "$STATE_DIR/java.env" /etc/jarvis/java.env "$JAVA_ENV_EXISTED"
    auto_restore_file "$STATE_DIR/python.env" /etc/jarvis/python.env "$PYTHON_ENV_EXISTED"
    auto_restore_file "$STATE_DIR/jarvis-java.service" /etc/systemd/system/jarvis-java.service "$JAVA_UNIT_EXISTED"
    auto_restore_file "$STATE_DIR/jarvis-ai.service" /etc/systemd/system/jarvis-ai.service "$AI_UNIT_EXISTED"
    systemctl daemon-reload || true
    if [ -f "$STATE_DIR/frontend-root.tgz" ] && [ -d "$FRONTEND_ROOT" ]; then
      tar -C "$FRONTEND_ROOT" -xzf "$STATE_DIR/frontend-root.tgz" || true
    fi
    [ "$OLD_AI_ACTIVE" -eq 1 ] && systemctl restart jarvis-ai.service || true
    [ "$OLD_JAVA_ACTIVE" -eq 1 ] && systemctl restart jarvis-java.service || true
    [ "$OLD_NGINX_ACTIVE" -eq 1 ] && systemctl start nginx || true
    echo "Rollback attempted. H2 backup and PostgreSQL are retained for inspection: $STATE_DIR" >&2
  fi
  exit "$rc"
}
trap 'rc=$?; rollback "$rc"' ERR INT TERM

log "Backing up current service/Nginx definitions (no downtime)"
umask 077
mkdir -p "$STATE_DIR"
[ "$JAVA_ENV_EXISTED" -eq 1 ] && cp -a /etc/jarvis/java.env "$STATE_DIR/java.env"
[ "$PYTHON_ENV_EXISTED" -eq 1 ] && cp -a /etc/jarvis/python.env "$STATE_DIR/python.env"
[ "$JAVA_UNIT_EXISTED" -eq 1 ] && cp -a /etc/systemd/system/jarvis-java.service "$STATE_DIR/jarvis-java.service"
[ "$AI_UNIT_EXISTED" -eq 1 ] && cp -a /etc/systemd/system/jarvis-ai.service "$STATE_DIR/jarvis-ai.service"
tar -C /etc -czf "$STATE_DIR/nginx.tgz" nginx
systemctl status jarvis-java.service jarvis-ai.service --no-pager > "$STATE_DIR/systemd-status.txt" 2>&1 || true
ss -lntp > "$STATE_DIR/listeners.txt" 2>&1 || true
tar -C "$FRONTEND_ROOT" -czf "$STATE_DIR/frontend-root.tgz" .

log "Installing required packages while old service stays online"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y postgresql postgresql-client maven git python3-venv ca-certificates openssl nodejs npm
systemctl enable --now postgresql

PG_LISTEN="$(ss -lnt 2>/dev/null | awk '$4 ~ /:5432$/ {print $4}')"
[ -n "$PG_LISTEN" ] || die "PostgreSQL 未监听 5432"
if echo "$PG_LISTEN" | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\*|\[::\]|:::):5432'; then
  die "PostgreSQL 意外监听公网地址，拒绝继续"
fi

log "Preparing non-root runtime user and release directories"
if ! id jarvis >/dev/null 2>&1; then
  useradd --system --home "$JARVIS_ROOT" --shell /usr/sbin/nologin jarvis
fi
install -d -o jarvis -g jarvis -m 0750 "$JARVIS_ROOT" "$JARVIS_ROOT/releases" "$JARVIS_ROOT/build"
install -d -o root -g jarvis -m 0750 /etc/jarvis
install -d -o jarvis -g jarvis -m 0750 "$BACKUP_ROOT/postgres"

log "Checking out fixed application commit in isolated build directory"
rm -rf "$BUILD_DIR"
git clone --quiet "$REPO_URL" "$BUILD_DIR"
git -C "$BUILD_DIR" checkout --quiet --detach "$APP_COMMIT"
[ "$(git -C "$BUILD_DIR" rev-parse HEAD)" = "$APP_COMMIT" ] || die "Git commit 校验失败"

log "Building Java application and migration JAR"
(
  cd "$BUILD_DIR/java-backend"
  mvn -B -DskipTests clean package
)
APP_JAR="$(find "$BUILD_DIR/java-backend/target" -maxdepth 1 -type f -name 'gold-research-backend-*.jar' ! -name '*migration*' ! -name '*.original' | head -1)"
[ -f "$APP_JAR" ] || die "app.jar 构建失败"

rm -rf "$RELEASE_DIR"
install -d -o jarvis -g jarvis -m 0750 "$RELEASE_DIR/java-backend" "$RELEASE_DIR/backend"
cp "$APP_JAR" "$RELEASE_DIR/java-backend/app.jar"
(
  cd "$BUILD_DIR/java-backend"
  mvn -B -Pmigration-tool -DskipTests package
)
MIGRATION_JAR="$(find "$BUILD_DIR/java-backend/target" -maxdepth 1 -type f -name '*-migration.jar' | head -1)"
[ -f "$MIGRATION_JAR" ] || die "migration.jar 构建失败"
cp "$MIGRATION_JAR" "$RELEASE_DIR/java-backend/migration.jar"
cp -a "$BUILD_DIR/backend/app" "$RELEASE_DIR/backend/app"
cp "$BUILD_DIR/backend/requirements.txt" "$RELEASE_DIR/backend/requirements.txt"
printf 'release_id=%s\ngit_sha=%s\nbuilt_at=%s\n' "$APP_COMMIT" "$APP_COMMIT" "$(date -Iseconds)" > "$RELEASE_DIR/RELEASE"
(
  cd "$RELEASE_DIR"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
chown -R jarvis:jarvis "$RELEASE_DIR"

log "Building compatible Vue frontend before downtime"
(
  cd "$BUILD_DIR/frontend"
  npm ci
  npm run build
)
[ -f "$BUILD_DIR/frontend/dist/index.html" ] || die "frontend build failed"

log "Preparing Python runtime before downtime"
rm -rf "$JARVIS_ROOT/venv"
python3 -m venv "$JARVIS_ROOT/venv"
"$JARVIS_ROOT/venv/bin/pip" install --upgrade pip
"$JARVIS_ROOT/venv/bin/pip" install -r "$RELEASE_DIR/backend/requirements.txt"
chown -R jarvis:jarvis "$JARVIS_ROOT/venv"

DB_PASSWORD="$(openssl rand -hex 32)"
JWT_SECRET="$(openssl rand -hex 48)"
PYTHON_SERVICE_TOKEN="$(openssl rand -hex 48)"

escape_env() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

cat > "$JAVA_ENV_NEXT" <<EOF
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://127.0.0.1:5432/jarvis
DB_USERNAME=jarvis
DB_PASSWORD=$DB_PASSWORD
JWT_SECRET=$JWT_SECRET
PYTHON_SERVICE_TOKEN=$PYTHON_SERVICE_TOKEN
CORS_ALLOWED_ORIGINS=https://f.shengxia.me
EOF
{
  printf 'PYTHON_SERVICE_TOKEN=%s\n' "$PYTHON_SERVICE_TOKEN"
  printf 'AI_PROVIDER="%s"\n' "$(escape_env "$AI_PROVIDER")"
  printf 'AI_BASE_URL="%s"\n' "$(escape_env "$AI_BASE_URL")"
  printf 'AI_MODEL="%s"\n' "$(escape_env "$AI_MODEL")"
  printf 'AI_API_KEY="%s"\n' "$(escape_env "$AI_API_KEY")"
  printf 'AI_TIMEOUT=%s\n' "$AI_TIMEOUT"
} > "$PYTHON_ENV_NEXT"
chown root:jarvis "$JAVA_ENV_NEXT" "$PYTHON_ENV_NEXT"
chmod 0640 "$JAVA_ENV_NEXT" "$PYTHON_ENV_NEXT"

log "Creating PostgreSQL role/database"
if runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='jarvis'" | grep -q 1; then
  printf "ALTER ROLE jarvis WITH LOGIN PASSWORD '%s';\n" "$DB_PASSWORD" | runuser -u postgres -- psql -v ON_ERROR_STOP=1 >/dev/null
else
  printf "CREATE ROLE jarvis LOGIN PASSWORD '%s';\n" "$DB_PASSWORD" | runuser -u postgres -- psql -v ON_ERROR_STOP=1 >/dev/null
fi
if ! runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_database WHERE datname='jarvis'" | grep -q 1; then
  runuser -u postgres -- createdb -O jarvis -E UTF8 jarvis
fi
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U jarvis -d jarvis -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null

BUSINESS_TABLES="users sim_account sim_position sim_trade price_snapshot kline_daily audit_event"
EXISTING_BUSINESS="$(runuser -u postgres -- psql -d jarvis -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('users','sim_account','sim_position','sim_trade','price_snapshot','kline_daily','audit_event')")"
[ "${EXISTING_BUSINESS:-0}" = "0" ] || die "目标 PostgreSQL 已存在业务表，拒绝覆盖"

log "Preflight-testing new Python AI on temporary localhost:18100"
runuser -u jarvis -- env \
  PYTHON_SERVICE_TOKEN="$PYTHON_SERVICE_TOKEN" \
  AI_PROVIDER="$AI_PROVIDER" \
  AI_BASE_URL="$AI_BASE_URL" \
  AI_MODEL="$AI_MODEL" \
  AI_API_KEY="$AI_API_KEY" \
  AI_TIMEOUT="$AI_TIMEOUT" \
  bash -c "cd '$RELEASE_DIR/backend'; exec '$JARVIS_ROOT/venv/bin/python' -m uvicorn app.main:app --host 127.0.0.1 --port 18100" \
  > "$STATE_DIR/python-preflight.log" 2>&1 &
TEMP_AI_PID=$!
AI_PREFLIGHT_OK=0
for _ in $(seq 1 20); do
  if curl -fsS -H "X-Internal-Service-Token: $PYTHON_SERVICE_TOKEN" http://127.0.0.1:18100/api/ready >/dev/null 2>&1; then
    AI_PREFLIGHT_OK=1
    break
  fi
  sleep 1
done
kill "$TEMP_AI_PID" 2>/dev/null || true
wait "$TEMP_AI_PID" 2>/dev/null || true
[ "$AI_PREFLIGHT_OK" -eq 1 ] || die "新 Python AI 临时 readiness 失败；旧生产服务未停止"

log "PREPARATION COMPLETE — entering controlled downtime"
CUTOVER_STARTED=1
systemctl stop nginx
systemctl stop jarvis-java.service
systemctl is-active --quiet jarvis-java.service && die "旧 Java 未成功停止"
sleep 2

log "Cold-backup H2 after Java stopped"
H2_BACKUP_OUTPUT="$(H2_DIR="$H2_DIR" BACKUP_ROOT="$BACKUP_ROOT" bash "$BUILD_DIR/deploy/scripts/backup-h2.sh")"
echo "$H2_BACKUP_OUTPUT"
H2_BACKUP_DIR="$(printf '%s\n' "$H2_BACKUP_OUTPUT" | awk -F': ' '/H2 backup created:/ {print $2}')"
[ -d "$H2_BACKUP_DIR" ] || die "无法确定 H2 备份目录"
(cd "$H2_BACKUP_DIR" && sha256sum -c SHA256SUMS)

log "Migrating H2 backup to PostgreSQL"
JAVA_ENV="$JAVA_ENV_NEXT" \
H2_BACKUP_DIR="$H2_BACKUP_DIR" \
MIGRATION_JAR="$RELEASE_DIR/java-backend/migration.jar" \
  bash "$BUILD_DIR/deploy/scripts/run-h2-migration.sh"

log "Verifying PostgreSQL core schema after migration"
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U jarvis -d jarvis -v ON_ERROR_STOP=1 -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
for table in users sim_account sim_position sim_trade price_snapshot kline_daily; do
  count="$(PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U jarvis -d jarvis -tAc "SELECT count(*) FROM $table")"
  echo "PostgreSQL $table rows=$count"
done

log "Activating new environment and systemd definitions"
mv -f "$JAVA_ENV_NEXT" /etc/jarvis/java.env
mv -f "$PYTHON_ENV_NEXT" /etc/jarvis/python.env
chown root:jarvis /etc/jarvis/java.env /etc/jarvis/python.env
chmod 0640 /etc/jarvis/java.env /etc/jarvis/python.env
bash "$BUILD_DIR/deploy/scripts/install-units.sh"
chown -R jarvis:jarvis "$RELEASE_DIR"

log "Promoting fixed release while Nginx remains stopped"
bash "$BUILD_DIR/deploy/scripts/promote-release.sh" "$RELEASE_DIR"
curl -fsS http://127.0.0.1:8200/api/health/ready >/dev/null
curl -fsS http://127.0.0.1:8201/actuator/health >/dev/null

log "Creating first verified PostgreSQL backup before reopening traffic"
runuser -u jarvis -- /usr/local/sbin/jarvis-postgres-backup

log "Deploying compatible Vue frontend while Nginx is stopped"
cp -a "$BUILD_DIR/frontend/dist/." "$FRONTEND_ROOT/"

log "Reopening Nginx and checking public readiness"
nginx -t
systemctl start nginx
curl -fsS https://agent.shengxia.me/api/health/ready >/dev/null
curl -fsS https://agent.shengxia.me/api/market/prices >/dev/null
curl -kfsS --resolve f.shengxia.me:443:127.0.0.1 https://f.shengxia.me/ > "$STATE_DIR/frontend-served.html"
grep -q '<div id="app"></div>' "$STATE_DIR/frontend-served.html" || die "新版前端本机 HTTPS 校验失败"

log "Enabling backup and monitor timers"
systemctl start jarvis-postgres-backup.timer
systemctl start jarvis-monitor.timer

COMPLETED=1
trap - ERR INT TERM

log "MIGRATION SUCCESS"
echo "Release: $APP_COMMIT"
echo "H2 rollback backup: $H2_BACKUP_DIR"
echo "Pre-migration system backup: $STATE_DIR"
echo "PostgreSQL: localhost:5432/jarvis"
echo "Java: 127.0.0.1:8200"
echo "Java management: 127.0.0.1:8201"
echo "Python AI: 127.0.0.1:8100"
echo
echo "Listeners:"
ss -lntp | grep -E '(:80|:443|:8100|:8200|:8201|:5432)\\b' || true
echo
echo "IMPORTANT: Nginx /py removal + SSE buffering config should be verified next with nginx -T."
