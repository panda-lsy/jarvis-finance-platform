# JARVIS 生产部署方案

当前生产目标架构：

```text
Cloudflare
   ↓
Nginx :443
   ↓ /api/*
Java 127.0.0.1:8200
   ├─ PostgreSQL 127.0.0.1:5432
   └─ Python AI 127.0.0.1:8100
          ↓
       AI Provider
```

关键原则：

- 公网只暴露 Nginx 80/443（以及必要的 SSH 22）。
- Java 与 Python 只监听 localhost。
- 浏览器永远不直接访问 Python。
- Java 负责用户、交易、行情、回测、审计与数据库。
- Python 只负责 AI Provider 交互。
- 生产数据库使用 PostgreSQL + Flyway。
- 发布采用 `/opt/jarvis/releases/<release-id>` + `/opt/jarvis/current` 原子 symlink。

## 1. 服务器只读预检

先不要修改服务，执行：

```bash
sudo bash deploy/scripts/server-preflight.sh
```

最终应达到：

```text
127.0.0.1:8100 Python AI
127.0.0.1:8200 Java
127.0.0.1:5432 PostgreSQL（或仅私网）
0.0.0.0:80/443 Nginx
```

若 8100/8200 监听 `0.0.0.0`，必须在切换后关闭公网监听。

## 2. 构建 release

构建机需要 Java 17、Maven、Python 及 backend 测试依赖。

```bash
bash deploy/scripts/build-release.sh
```

输出：

```text
dist/releases/<timestamp>-<gitsha>/
├── RELEASE
├── SHA256SUMS
├── java-backend/
│   ├── app.jar
│   └── migration.jar
└── backend/
    ├── requirements.txt
    └── app/
```

`migration.jar` 是独立 H2 → PostgreSQL 工具，不需要服务器安装 Maven。

## 3. 服务器目录与 systemd

上传 release 到：

```text
/opt/jarvis/releases/<release-id>
```

然后从仓库部署目录执行：

```bash
sudo bash deploy/scripts/install-units.sh
```

它会创建非登录系统用户 `jarvis`、`/etc/jarvis/`、systemd unit，但不会自动启动。

## 4. 配置 Secret

编辑：

```text
/etc/jarvis/java.env
/etc/jarvis/python.env
```

权限建议：

```bash
sudo chown root:jarvis /etc/jarvis/*.env
sudo chmod 640 /etc/jarvis/*.env
```

必须生成独立随机值：

```text
JWT_SECRET
PYTHON_SERVICE_TOKEN
DB_PASSWORD
AI_API_KEY
```

`PYTHON_SERVICE_TOKEN` 在两个 env 文件中必须完全一致。

不要把真实 Secret 提交到 Git。

## 5. PostgreSQL 初始化

示例（按实际 PostgreSQL 版本/权限调整）：

```bash
sudo -u postgres psql
```

```sql
CREATE ROLE jarvis LOGIN PASSWORD '<strong-random-password>';
CREATE DATABASE jarvis OWNER jarvis ENCODING 'UTF8';
REVOKE ALL ON DATABASE jarvis FROM PUBLIC;
```

`java.env`：

```text
DB_URL=jdbc:postgresql://127.0.0.1:5432/jarvis
DB_USERNAME=jarvis
DB_PASSWORD=...
```

不要手工执行 `V1/V2.sql`。H2 迁移工具会先通过 Flyway 建 schema，并维护 `flyway_schema_history`。

## 6. H2 → PostgreSQL 首次迁移

### 6.1 停止旧 Java 写入

```bash
sudo systemctl stop jarvis-java.service || true
```

如果当前还是旧部署方式，先停止旧 Java 进程，确认没有进程继续写 H2。

### 6.2 备份 H2

指定旧 H2 data 目录，例如：

```bash
sudo H2_DIR=/path/to/old/java-backend/data \
  bash deploy/scripts/backup-h2.sh
```

脚本会输出类似：

```text
/var/backups/jarvis/h2-20260904-170000
```

不要迁移正在被 Java 写入的原始 H2 文件，始终对备份副本迁移。

### 6.3 执行迁移

```bash
sudo H2_BACKUP_DIR=/var/backups/jarvis/h2-20260904-170000 \
  MIGRATION_JAR=/opt/jarvis/releases/<release-id>/java-backend/migration.jar \
  bash deploy/scripts/run-h2-migration.sh
```

工具执行顺序：

1. 必须存在显式安全确认；
2. Flyway 创建/校验 PostgreSQL schema；
3. 检查所有目标业务表为空；
4. 只读打开 H2 备份；
5. 逐表复制；
6. 每表核对 source/copied/target 行数；
7. 重置 PostgreSQL identity sequence；
8. 任意数据复制错误时回滚整个 PostgreSQL 数据事务。

迁移成功后仍保留 H2 备份，至少到生产验证完成并再做一次 PostgreSQL 备份之后。

## 7. Python venv

一次性创建共享 venv：

```bash
sudo python3 -m venv /opt/jarvis/venv
sudo /opt/jarvis/venv/bin/pip install --upgrade pip
sudo /opt/jarvis/venv/bin/pip install -r /opt/jarvis/releases/<release-id>/backend/requirements.txt
sudo chown -R jarvis:jarvis /opt/jarvis/venv
```

每次 Python dependencies 变更时，先更新 venv，再 promote release。

## 8. Nginx

把：

```text
deploy/nginx/agent-api.locations.conf
```

内容合并到现有 `agent.shengxia.me` HTTPS `server {}` 中。

它会：

- `/api/*` → `127.0.0.1:8200`
- `/api/ai/chat/stream` 使用独立 SSE 配置，关闭 Nginx buffering/request buffering/gzip；
- `/py`、`/py/*` → 404；
- `/actuator`、`/actuator/*` → 404；
- Python 8100 与 Java management 8201 均无公网路由。

修改后：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

不要猜测 TLS 证书路径；沿用当前已工作的 Cloudflare/Nginx TLS server 配置。

## 9. 原子发布

先保证 release 归 `jarvis` 可读：

```bash
sudo chown -R jarvis:jarvis /opt/jarvis/releases/<release-id>
```

首次建立 current 或后续升级均使用：

```bash
sudo bash deploy/scripts/promote-release.sh \
  /opt/jarvis/releases/<release-id>
```

若已经配置好 `/etc/jarvis/smoke.env`，可把公网 smoke 也纳入原子发布判定：

```bash
sudo RUN_PUBLIC_SMOKE=1 \
  bash deploy/scripts/promote-release.sh \
  /opt/jarvis/releases/<release-id>
```

发布脚本会：

1. 原子切换 `/opt/jarvis/current`；
2. 重启 Python；
3. 使用内部 token 请求 `/api/ready`，要求 AI Provider 可用；
4. 重启 Java；
5. 检查 `127.0.0.1:8200/api/health/ready`，要求 Java 与数据库都 ready；
6. 若 `RUN_PUBLIC_SMOKE=1`，继续执行真实公网 smoke；
7. 任一步失败自动切回旧 release 并重启旧版本。

Java 健康探针语义：

- `/api/health`、`/api/health/live`：liveness，只表示 Java 进程可响应；
- `/api/health/ready`：readiness，必须在短超时内成功执行数据库 `SELECT 1`，失败返回 HTTP 503；
- `/api/health/db`：登录后可查看数据库产品与查询延迟，不暴露连接串、用户名或异常详情；
- `/api/health/ai`：登录后经 Java 检查 Python AI readiness。

## 10. PostgreSQL 自动备份

`install-units.sh` 会安装：

```text
/usr/local/sbin/jarvis-postgres-backup
jarvis-postgres-backup.service
jarvis-postgres-backup.timer
```

数据库迁移完成并确认 `/api/health/ready` 正常后启用定时器：

```bash
sudo systemctl start jarvis-postgres-backup.timer
sudo systemctl list-timers jarvis-postgres-backup.timer
```

默认每天约 **03:20** 执行一次，并带最多 10 分钟随机延迟；`Persistent=true`，服务器在计划时间关机时，恢复后会补跑。

备份默认目录：

```text
/var/backups/jarvis/postgres
```

默认保留 **14 天**。每份备份使用 PostgreSQL custom format，并且在发布为有效备份前完成：

1. `pg_dump --format=custom`；
2. `pg_restore --list` 可读性校验；
3. SHA256 校验文件；
4. 原子从临时文件移动为正式 `.dump`；
5. 仅清理过期 PostgreSQL dump，不触碰 H2 备份。

手工立即备份：

```bash
sudo -u jarvis /usr/local/sbin/jarvis-postgres-backup
```

日志：

```bash
journalctl -u jarvis-postgres-backup.service
```

> 备份不是恢复。上线后应定期在隔离环境真实演练 restore，只有能恢复的数据才算有效备份。

## 11. PostgreSQL 恢复

恢复是破坏性操作，脚本有三道保护：

- `jarvis-java.service` 仍在运行时拒绝恢复；
- 必须显式设置 `RESTORE_CONFIRM=RESTORE_POSTGRES`；
- 默认在覆盖前先做一次当前 PostgreSQL 安全备份。

示例：

```bash
sudo systemctl stop jarvis-java.service
sudo RESTORE_CONFIRM=RESTORE_POSTGRES \
  /usr/local/sbin/jarvis-postgres-restore \
  /var/backups/jarvis/postgres/jarvis-jarvis-YYYYMMDDTHHMMSSZ.dump
```

脚本会先验证 `pg_restore --list`，若存在同名 `.sha256` 则必须校验通过，然后使用 `--clean --if-exists --single-transaction` 恢复。完成后还会验证数据库可查询、Flyway history 和 `users` 表存在。

仅在灾难场景、当前库已经无法再备份时，才显式使用：

```bash
SKIP_PRE_RESTORE_BACKUP=1
```

恢复后：

```bash
sudo systemctl start jarvis-java.service
curl --fail http://127.0.0.1:8200/api/health/ready
```

readiness 不通过时不要恢复公网流量。

## 12. 生产 smoke test

`install-units.sh` 会安装：

```text
/usr/local/sbin/jarvis-smoke-test
/etc/jarvis/smoke.env
```

`smoke.env` 权限为 root-only。为它准备一个**独立低权限测试账号**，不要使用管理员或个人账号：

```text
SMOKE_API_BASE=https://agent.shengxia.me
SMOKE_EMAIL=...
SMOKE_PASSWORD=...
```

发布后执行：

```bash
sudo /usr/local/sbin/jarvis-smoke-test
```

它按顺序验证：

1. 本机 Java liveness；
2. 本机 Java + DB readiness；
3. 公网 liveness/readiness；
4. `/py/api/health` 必须为 404；
5. CSRF token；
6. Cookie 登录与 `/api/auth/me`；
7. 登录态数据库 health；
8. 行情与日 K；
9. 模拟账户读取（**不自动下单**）；
10. AI capabilities（不消耗生成额度）；
11. logout。

任何一步 HTTP/业务响应异常都会返回非零退出码。

## 13. 验证

本机：

```bash
curl http://127.0.0.1:8200/api/health
curl http://127.0.0.1:8201/actuator/prometheus | head
ss -lntp | grep -E '8100|8200|8201|5432'
```

公网：

```text
https://agent.shengxia.me/api/health       应 200
https://agent.shengxia.me/py/api/health    应 404
```

登录后检查：

- Cookie 是 `HttpOnly; Secure; SameSite=Strict`；
- 行情加载正常；
- 模拟盘下单正常；
- AI capabilities/聊天正常；
- `/api/audit/recent` 能看到登录和下单事件；
- 强平/行情 stale 状态与 UI 一致。

## 14. 回滚

代码回滚：重新执行 `promote-release.sh` 指向旧 release。

数据库切换初期若必须退回 H2：

1. 停止新 Java；
2. 保留当前 PostgreSQL，不删除；
3. 恢复迁移前 H2 备份；
4. 使用迁移前旧 Java release 和旧启动配置恢复；
5. 明确记录切换时间，避免将 PostgreSQL 期间的新交易与旧 H2 混合。

**一旦 PostgreSQL 上线后产生新的用户/交易数据，就不能简单把旧 H2 当成最新事实库。** 此时应优先修复 PostgreSQL 版本，而不是无条件回退数据层。

## 15. 生产监控与告警

`install-units.sh` 还会安装：

```text
/usr/local/sbin/jarvis-monitor
/etc/jarvis/monitor.env
jarvis-monitor.service
jarvis-monitor.timer
```

默认每 5 分钟检查：

- Java + PostgreSQL readiness；
- `127.0.0.1:8201/actuator/prometheus` 是否可抓取；
- Python AI readiness；
- PostgreSQL 最新备份是否超过 30 小时；
- 最近一次备份 systemd service 是否失败；
- 根文件系统剩余空间是否低于 15%。

首次启用前先确保至少已有一份已校验 PostgreSQL 备份，然后启动监控：

```bash
sudo -u jarvis /usr/local/sbin/jarvis-postgres-backup
sudo systemctl start jarvis-monitor.timer
sudo systemctl list-timers jarvis-monitor.timer
journalctl -u jarvis-monitor.service
```

可选在 `/etc/jarvis/monitor.env` 配置 `ALERT_WEBHOOK_URL`。监控会保存上一次状态 fingerprint，只在故障状态变化时发送一次告警，恢复时发送一次 RECOVERED，避免每 5 分钟重复刷屏。

Java 生产 profile 同时开启 Spring Boot Actuator + Prometheus：

```text
127.0.0.1:8201/actuator/prometheus
127.0.0.1:8201/actuator/health
```

management 端口只监听 localhost，Nginx 显式拒绝 `/actuator`。Prometheus 指标可用于后续 Grafana/告警规则，包括 `http.server.requests`、JVM、进程、Hikari 连接池等。

## 16. 上线后

当前已具备自动备份/恢复、readiness、smoke test、关键服务即时告警与 Prometheus 指标。下一阶段仍建议：

- 定期离机/异地复制 PostgreSQL 备份并演练恢复；
- 接入正式 Prometheus/Grafana，配置 5xx、429/502、Hikari 饱和度趋势告警；
- 配置 journald/Nginx 日志保留周期；
- 增加交易/强平业务指标；
- Cloudflare/WAF 规则。
