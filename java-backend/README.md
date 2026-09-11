# 贾维斯金融投研平台 - Java 主后端

Java 是系统的**唯一业务后端**，负责用户、数据库、行情、K 线、回测、模拟交易、风控和 AI 网关。
Python 仅作为内部 AI provider adapter，由 Java 使用内部令牌调用。

## 技术栈

- Java 17 + Spring Boot 3.3
- Spring Security + JWT HttpOnly Cookie
- Spring Data JPA
- 本地开发：H2
- 生产：PostgreSQL + Flyway
- WebClient：行情源与内部 Python AI 服务

## 本地启动

```bash
cd java-backend
export JWT_SECRET='<至少32字符随机密钥>'
export PYTHON_SERVICE_TOKEN='<与 Python 一致>'
mvn spring-boot:run
```

默认端口 `8200`。

秒级行情相关环境变量：`MARKET_LIVE_POLL_INTERVAL_MS` / `MARKET_STREAM_INTERVAL_MS` / `JD_LIVE_POLL_INTERVAL_MS` 默认 `1000`，SSE 全局连接上限 `MARKET_STREAM_MAX_SUBSCRIBERS` 默认 `2000`，数据库快照持久化 `MARKET_PERSIST_INTERVAL_MS` / `JD_PERSIST_INTERVAL_MS` 默认 `30000`。生产环境不建议把持久化周期也降到 1 秒。

## 生产启动

```bash
export SPRING_PROFILES_ACTIVE=prod
export JWT_SECRET='<随机密钥>'
export PYTHON_SERVICE_TOKEN='<内部服务随机令牌>'
export DB_URL='jdbc:postgresql://127.0.0.1:5432/jarvis'
export DB_USERNAME='jarvis'
export DB_PASSWORD='<数据库密码>'

java -jar target/gold-research-backend-*.jar
```

`prod` profile 下 Java 只监听 `127.0.0.1:8200`，由 Nginx/Cloudflare 提供公网入口。

## 主要 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/auth/csrf` | 获取 Cookie-CSRF token |
| POST | `/api/auth/register` | 注册并写入 HttpOnly JWT Cookie |
| POST | `/api/auth/login` | 登录并写入 HttpOnly JWT Cookie |
| POST | `/api/auth/verification/email` | Resend 发送注册验证码（需 CSRF） |
| POST | `/api/auth/verification/email/confirm` | 校验一次性邮箱注册验证码 |
| POST | `/api/auth/password/reset/request` | 发送密码重置验证码（响应不泄露邮箱是否注册） |
| POST | `/api/auth/password/reset` | 使用一次性验证码重置密码并使旧 JWT 失效 |
| PATCH | `/api/auth/profile` | 修改当前用户昵称 |
| GET | `/api/auth/github/authorize` | 跳转 GitHub OAuth 授权 |
| GET | `/api/auth/github/callback` | GitHub OAuth 回调并写入登录 Cookie |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 清除登录 Cookie |
| GET | `/api/health` / `/api/health/live` | Java liveness |
| GET | `/api/health/ready` | Java + 数据库 readiness，DB 不可用返回 503 |
| GET | `/api/health/db` | 登录后查看数据库产品与查询延迟 |
| GET | `/api/health/ai` | 登录后 Java → Python AI readiness |
| GET | `/api/market/prices` | 最近行情快照（优先返回秒级内存行情） |
| GET | `/api/market/prices/stream` | SSE 秒级行情流（黄金ETF、伦敦金、积存金） |
| GET | `/api/market/kline` | 日/分钟 K 线 |
| GET | `/api/market/jd/prices` | 积存金最近快照 |
| GET | `/api/market/jd/kline` | 积存金分钟 K 线 |
| GET | `/api/backtest` | Java 数据库上的双均线回测；支持 `as_of` 锁定数据截止日，并返回策略版本/数据指纹 |
| GET | `/api/sim/account` | 模拟账户 |
| POST | `/api/sim/order` | 幂等模拟下单 |
| GET | `/api/sim/trades` | 成交记录 |
| GET/POST | `/api/ai/**` | JWT 鉴权后代理 Python AI |
| POST | `/api/ai/chat/stream` | SSE 流式 AI 对话 |

## 关键约束

- 模拟盘金额/数量/价格持久化使用 `BigDecimal`。
- 下单使用数据库最新行情快照；过期行情禁止成交。
- 同用户下单使用悲观行锁，`clientOrderId` 提供幂等保护。
- 风控扫描按用户独立事务执行，只有新鲜行情才允许强平。
- 行情与日 K 由 Java 定时采集，GET 接口不负责外部抓取或写库。
- 实时报价默认每 1 秒刷新 Java 内存缓存；全站使用单一 1Hz SSE 广播器把同一 payload fan-out 给所有浏览器，避免每个客户端各自启动定时任务；数据库默认每 30 秒持久化一次快照。
- 秒级缓存落库时保留原始采集时间，超过 10 秒的旧 tick 不再持久化，避免上游断流后旧价格被误判为新鲜行情。
- HttpOnly JWT Cookie 配合 Cookie-CSRF；浏览器 POST 必须携带 `X-XSRF-TOKEN`。认证入口另使用长期 HttpOnly 随机 `jarvis_device` Cookie 做设备维度限流，不包含用户身份信息。
- 登录按账号/IP/设备限流；注册按 IP/设备限流；验证码按邮箱/IP/设备分别限流。内存窗口有过期回收和最大 key 数保护，避免随机来源造成内存型 DoS。
- AI 请求按用户限流，Python 只接受 `X-Internal-Service-Token`；聊天上下文中的行情、K线和当前用户模拟盘快照由 Java 服务端注入，客户端同名字段会被覆盖。
- AI 功能权限由 Java 后端按 `AI_CHAT`、`AI_CHAT_STREAM`、`AI_REPORT`、`AI_SENTIMENT`、`AI_CHAIN`、`AI_QUOTE`、`AI_RISK`、`AI_STRATEGY` 白名单校验。
- AI 流式链路使用 MVC `SseEmitter`；浏览器主动停止/断开时取消 Java → Python 订阅。
- 核心行情与模拟交易接入 Micrometer：采集成功/失败、源延迟、stale tick、采集调度 heartbeat、快照拒绝落库、SSE 在线数/广播 heartbeat/发送失败、订单成功/幂等重放、风控 stale skip、强平次数均暴露到 Prometheus；指标不使用 userId 或自由 symbol 作为标签。
- 生产 Hikari 获取连接超时 5 秒、validation 2 秒，readiness 使用数据库 `SELECT 1`。
- Actuator/Prometheus 仅监听 `127.0.0.1:8201`，供服务器本机监控抓取。

## 数据库迁移

生产 schema 位于：

```text
src/main/resources/db/migration/
```

生产启用 Flyway，Hibernate 使用 `ddl-auto=validate`；禁止继续使用 `ddl-auto=update` 管生产 schema。

## 测试

```bash
mvn test
```

测试包含真实 Spring + H2 事务集成用例：并发下单验证同账户悲观锁防超卖；同一 `clientOrderId` 并发重试验证 exactly-once；5x 杠杆仓位价格下跌验证强平原子落库；stale 暴跌行情验证风控拒绝错误强平。CI 还会在 PostgreSQL 16 上再次执行悲观锁、并发幂等和强平事务用例，避免只验证 H2 语义。
