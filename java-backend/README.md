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
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 清除登录 Cookie |
| GET | `/api/health` / `/api/health/live` | Java liveness |
| GET | `/api/health/ready` | Java + 数据库 readiness，DB 不可用返回 503 |
| GET | `/api/health/db` | 登录后查看数据库产品与查询延迟 |
| GET | `/api/health/ai` | 登录后 Java → Python AI readiness |
| GET | `/api/market/prices` | 最近行情快照 |
| GET | `/api/market/kline` | 日/分钟 K 线 |
| GET | `/api/market/jd/prices` | 积存金最近快照 |
| GET | `/api/market/jd/kline` | 积存金分钟 K 线 |
| GET | `/api/backtest` | Java 数据库上的双均线回测 |
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
- HttpOnly JWT Cookie 配合 Cookie-CSRF；浏览器 POST 必须携带 `X-XSRF-TOKEN`。
- AI 请求按用户限流，Python 只接受 `X-Internal-Service-Token`。
- AI 流式链路使用 MVC `SseEmitter`；浏览器主动停止/断开时取消 Java → Python 订阅。
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
