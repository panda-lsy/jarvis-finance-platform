# JARVIS 金融投研平台

JARVIS 是一个面向金融研究与模拟交易的 Web 平台，提供多市场行情、K 线与技术指标、可复现回测、模拟盘、AI 研究助手、用户认证和管理员权限管理。

> 仅供研究与模拟交易使用，不构成投资建议。

## 当前生产架构

```text
浏览器（f.shengxia.me）
          │ HTTPS / Cookie-CSRF
          ▼
Cloudflare → Nginx
                │ /api/**
                ▼
      Java Spring Boot :8200
        ├── PostgreSQL :5432
        └── Python AI :8100
              │ 内部服务令牌
              ▼
        CloudBase / OpenAI 兼容 AI Provider
```

- Java 是唯一业务后端，负责认证、用户、行情、K 线、回测、模拟交易、风控、审计、权限和 AI 网关。
- Python 仅负责 AI Provider 适配与研究上下文计算，不承载用户、行情或交易数据。
- 浏览器只访问 Java `/api/**`，不直连 Python、PostgreSQL 或第三方行情源。
- 生产数据库使用 PostgreSQL，schema 由 Flyway 管理；Java 使用 Hibernate `validate` 校验结构。
- 生产前端由远端 Nginx 提供；GitHub Pages 工作流保留用于构建/备用预览，不是当前生产入口。

## 已实现能力

| 模块 | 当前能力 |
| --- | --- |
| 认证 | 邮箱注册/登录、Resend 邮箱验证码、密码重置、GitHub OAuth、HttpOnly JWT、Cookie-CSRF |
| 多市场 | A 股、美股、加密货币；默认标的与自选标的可添加、删除、修改 |
| 自定义标的 | 按代码解析并补全名称、币种和数据源；用户偏好持久化到 PostgreSQL，并保留 localStorage 兜底 |
| 行情 | 黄金 ETF、伦敦金、积存金，以及 A 股/美股/加密货币扩展行情 |
| K 线 | 日 K、5/10/15/30 分钟、1 小时；交易时段支持报价盯盘与低频 K 线刷新 |
| 技术分析 | SMA、EMA、布林带、RSI、MACD、ADX、ATR、随机指标、Williams %R、ROC、支撑/阻力等 |
| 模拟盘 | 账户、仓位、交易记录、幂等下单、悲观锁、杠杆与风控强平 |
| 回测 | Java 从数据库读取数据，支持 `as_of` 截止日、策略版本和数据指纹，保证结果可复现 |
| AI 研究 | 行情解读、产业链、研报情感分析、财报分析、流式对话；Java 统一鉴权并代理 AI |
| 管理后台 | 用户启停、角色、AI 配额和功能权限管理 |
| 运维 | PostgreSQL 备份/恢复、readiness、Prometheus 指标、Grafana 模板、行情源熔断切换和告警 |

## 目录结构

```text
.
├── frontend/                 # Vue 3 + Vite Web 前端
├── java-backend/             # Spring Boot 3 Java 业务后端
├── backend/                  # FastAPI 内部 AI 服务
├── deploy/                   # 生产发布、Nginx、systemd、备份与监控
├── specs/                    # 当前功能需求、设计与实施计划
├── mobile/                   # React Native / Expo 移动端
├── .github/workflows/        # CI 与前端备用构建工作流
├── config/                   # 应用配置和模板
├── ops/                      # 运维及自动化工具
└── scripts/                  # 本地服务启动与停止脚本
```

## 本地开发

### 1. 启动 Python AI 服务

```bash
cd backend
python -m venv .venv
source .venv/bin/activate       # Windows 可使用 .venv\Scripts\Activate.ps1
pip install -r requirements.txt

export PYTHON_SERVICE_TOKEN='<与 Java 一致的随机令牌>'
export AI_API_KEY='<AI Provider Key>'
export AI_BASE_URL='<OpenAI 兼容接口地址>'
export AI_MODEL='hy3'
export AI_MODEL_DISPLAY_NAME='DeepSeek V4 Flash-0731'

python -m uvicorn app.main:app --host 127.0.0.1 --port 8100
```

### 2. 启动 Java 业务后端

```bash
cd java-backend
export JWT_SECRET='<至少32字符的随机密钥>'
export PYTHON_SERVICE_TOKEN='<与 Python 一致的随机令牌>'
mvn spring-boot:run
```

默认监听 `127.0.0.1:8200`。本地默认使用 H2；使用生产配置时再设置 `SPRING_PROFILES_ACTIVE=prod`、`DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173/`。Vite 将 `/api/**` 代理到 `http://127.0.0.1:8200`。

## 前端页面与入口

```text
frontend/src/
├── components/LoginView.vue          # 登录、注册、验证码和密码重置
├── components/CrossMarketView.vue    # 多市场、自选标的和技术分析
├── components/SimTradeView.vue       # 模拟盘
├── components/AiCenter.vue            # AI 研究助手
├── components/AdminView.vue           # 管理员后台
├── pages/MarketPage.vue               # 黄金/积存金行情
├── pages/BacktestPage.vue             # 可复现回测
├── pages/SentimentPage.vue            # 多空研报
├── components/OpsView.vue             # 运维状态
└── api/client.js                      # Java API 客户端与认证请求封装
```

## 关键 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/auth/csrf` | 获取 Cookie-CSRF token |
| POST | `/api/auth/register` | 邮箱注册 |
| POST | `/api/auth/login` | 邮箱登录 |
| GET | `/api/auth/github/authorize` | GitHub OAuth 登录 |
| GET | `/api/market/instruments` | 默认市场标的 |
| GET | `/api/market/instruments/resolve` | 解析自定义标的 |
| GET/PUT | `/api/market/preferences` | 读取/保存当前用户标的偏好 |
| GET | `/api/market/extended/quote` | 多市场实时报价 |
| GET | `/api/market/extended/kline` | 多市场 K 线与技术指标 |
| GET | `/api/backtest` | 可复现回测 |
| GET/POST | `/api/sim/*` | 模拟盘账户、交易和记录 |
| GET/POST | `/api/ai/*` | Java 鉴权后的 AI 网关 |
| GET | `/api/health/ready` | Java 与 PostgreSQL readiness |

完整接口与生产操作说明见 [`java-backend/README.md`](java-backend/README.md) 和 [`deploy/README.md`](deploy/README.md)。

## 测试与构建

前端：

```bash
cd frontend
npm run test:p0
npm run build
```

Java：

```bash
cd java-backend
mvn test
```

Python AI：

```bash
cd backend
python -m pytest -q tests
```

仓库 CI 会执行部署脚本语法检查、Java 测试、PostgreSQL schema/锁策略测试、前端 P0 测试与构建，以及 Python 测试。

## 生产部署

生产发布使用带版本号的 release 目录和原子软链接，不直接覆盖正在运行的目录：

```bash
bash deploy/scripts/build-release.sh

sudo bash deploy/scripts/promote-release.sh \
  /opt/jarvis/releases/<release-id>
```

发布前应完成 PostgreSQL 备份；发布脚本会重启 Python/Java 并检查 AI readiness、Java/数据库 readiness。生产入口：

- 前端：<https://f.shengxia.me>
- API：<https://agent.shengxia.me/api/>
- Python 公网路径 `/py/**`：应返回 404
- Actuator 公网路径 `/actuator/**`：应返回 404，仅允许服务器本机监控访问

生产环境的 JWT、数据库、Resend、GitHub OAuth、AI Provider 和内部服务令牌只通过服务器环境文件注入，严禁写入 Git、README、前端构建产物或日志。

## 相关文档

- [`specs/`](specs/)：当前功能需求、设计说明和实施计划
- [`frontend/README.md`](frontend/README.md)：前端开发与安全边界
- [`java-backend/README.md`](java-backend/README.md)：Java API、认证、交易和测试
- [`backend/README.md`](backend/README.md)：内部 AI 服务
- [`deploy/README.md`](deploy/README.md)：生产部署、备份、监控和回滚
- [`tools/test-agent/README.md`](tools/test-agent/README.md)：需求驱动的浏览器验收辅助工具
- [`deploy/monitoring/grafana/README.md`](deploy/monitoring/grafana/README.md)：Grafana 配置

## 安全边界

- 不在前端保存 JWT、AI API Key、GitHub Client Secret、Resend API Key 或 Python 内部令牌。
- 所有浏览器写请求使用 Cookie-CSRF；请求层会在 CSRF 失败时自动刷新 token 并仅重试一次。
- 模拟交易使用数据库锁、幂等键和事务回滚，行情过期时禁止成交。
- 用户自定义标的只允许受限市场和代码格式，名称由后端解析或由用户填写，不接受任意外部地址。
