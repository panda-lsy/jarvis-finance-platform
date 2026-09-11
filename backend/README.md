# JARVIS AI Service (Python FastAPI)

`backend/` 现在是 **内部 AI 服务**，不再承担行情、SQLite、K 线、回测或模拟交易业务。

## 架构边界

```text
Browser / Mobile
      |
      v
Java Spring Boot :8200
      |
      | X-Internal-Service-Token
      v
Python FastAPI :8100
      |
      v
OpenAI Chat Completions compatible provider
```

- 浏览器和移动端不得直接调用 Python。
- Python 默认只监听 `127.0.0.1:8100`。
- 所有 `/api/ai/**`、`/api/health`、`/api/ready` 都要求 `PYTHON_SERVICE_TOKEN`。
- 用户、行情、K 线、回测、交易与数据库统一归 Java。

## 启动

```bash
cd backend
pip install -r requirements.txt

export PYTHON_SERVICE_TOKEN='<与 Java 完全一致的随机令牌>'
export AI_API_KEY='<上游 AI Key>'
# 可选
export AI_PROVIDER='cloudbase'
export AI_BASE_URL='<OpenAI-compatible upstream base URL>'
export AI_MODEL='hy3'
export AI_MODEL_DISPLAY_NAME='DeepSeek V4 Flash-0731'
export AI_TIMEOUT='60'

python -m uvicorn app.main:app --host 127.0.0.1 --port 8100
```

`AI_MODEL` 是发送给上游的真实模型标识；`AI_MODEL_DISPLAY_NAME` 只用于前端/运维页面展示。旧变量 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`、`OLLAMA_API_KEY` 仍兼容，但新部署统一使用 `AI_*`。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 内部 liveness |
| GET | `/api/ready` | AI Provider/API Key readiness |
| GET | `/api/ai/capabilities` | AI 能力/供应商/模型状态 |
| POST | `/api/ai/chat` | 多轮对话（Java 自动注入真实行情/K线研究上下文） |
| POST | `/api/ai/chat/stream` | SSE 流式多轮对话（同样使用确定性研究上下文） |
| POST | `/api/ai/financial/report` | 财报文本分析 |
| POST | `/api/ai/analyze/sentiment` | 研报情感分析 |
| POST | `/api/ai/analyze/chain` | 产业链分析 |
| POST | `/api/ai/analyze/risk` | 风险预警（VaR/ES/年化波动率/最大回撤，历史模拟法） |
| POST | `/api/ai/analyze/strategy` | 个性化策略生成（风险偏好问卷 → 风险等级 + 建议配置比例） |
| POST | `/api/ai/quote` | 行情智能解读 |

AI 对话中的价格、SMA5/SMA20、EMA12、RSI14、20期支撑/阻力，以及当前用户模拟盘的总敞口、净权益、总杠杆、维持保证金率、单仓 ROE、集中度和会计一致性检查，均由 `app/research_tools.py` 在 Python 内确定性计算；LLM 只负责解释这些结果。风险预警的 VaR/ES/波动率/最大回撤与个性化策略的得分/风险等级/配置比例同样在该确定性层计算，模型不得改写数值口径。Java 会覆盖客户端同名 `research_context`（风险预警则覆盖 `closes`），浏览器不能伪造系统量化上下文。

所有请求必须带：

```text
X-Internal-Service-Token: <PYTHON_SERVICE_TOKEN>
```

## 测试

```bash
python -m pytest -q tests
```
