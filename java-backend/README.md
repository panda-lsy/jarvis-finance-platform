# 贾维斯金融投研平台 - Java 主后端 (Spring Boot 3)

Java 主后端，负责金融数据交互、AI 大模型集成与投研分析服务。
Python 仅保留为 AI/数据采集的辅助微服务。

## 技术栈

- Java 17 + Spring Boot 3.3
- Spring Web / Validation / Data JPA
- H2 数据库（默认文件库，`data/research`）
- WebClient (WebFlux) 对接金融API & DeepSeek
- 统一响应 `ApiResponse<T>` + 全局异常处理

## 启动

```bash
cd java-backend
export DEEPSEEK_API_KEY=<你的DeepSeek Key>   # 配置AI
mvn spring-boot:run                          # 或 mvn package && java -jar target/*.jar
```

默认端口 **8200**。

## 已验证 API

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| GET | `/api/health` | 健康检查 | ✅ |
| GET | `/api/gold/quote` | 黄金实时报价 (Java直连腾讯, GBK) | ✅ |
| GET | `/api/gold/kline?limit=120` | 历史K线 | ✅ |
| GET | `/api/ai/status` | AI协议状态 | ✅ |
| POST | `/api/ai/chat` | 通用对话 (走DeepSeek) | 🟡 待API Key |

## AI 多协议架构

`AiGateway` 统一入口，按 `ai.protocol` 路由：

| 协议 | 适配器 | 端点 |
|------|--------|------|
| `openai-chat` | OpenAIChatProvider | `POST {base}/chat/completions` |
| `openai-responses` | OpenAiResponsesProvider | `POST {base}/responses` |
| `anthropic-messages` | AnthropicMessagesProvider | `POST {base}/v1/messages` |

配置：`src/main/resources/application.yml` 的 `ai.*`。
DeepSeek 走 OpenAI Chat 协议兼容。

## 金融场景 AI 方法 (AiGateway)

- `analyzeFinancialReport(text)` — 财报智能解析(JSON)
- `predictPrice(history, market)` — 智能报价/价格预测
- `analyzeSentiment(reports)` — 研报情感分析/争议点
- `analyzeChain(node, context)` — 产业链上下游分析

## 目录

```
java-backend/
├── pom.xml
└── src/main/
    ├── java/com/jarvis/research/
    │   ├── ResearchBackendApplication.java
    │   ├── config/        # 平台/配置属性
    │   ├── common/        # ApiResponse + 全局异常
    │   ├── controller/    # Health/Gold/Ai
    │   ├── service/       # GoldPriceService
    │   └── ai/            # AiProvider 抽象 + 3协议实现 + AiGateway
    └── resources/application.yml
```

## 下一步 (打磨方向)

- JPA 实体 + 仓储（K线、报价、财报、研报落库）
- 金融数据清洗管线
- 财报 OCR 识别 (Python 辅助) + DeepSeek 结构化
- 产业链知识图谱 / 风险预警
- Vue.js 投研工作台对接
