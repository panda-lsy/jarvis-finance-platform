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
export AI_PROVIDER='ollama-cloud'
export AI_BASE_URL='https://ollama.com/v1'
export AI_MODEL='deepseek-v4-flash:0731'
export AI_TIMEOUT='60'

python -m uvicorn app.main:app --host 127.0.0.1 --port 8100
```

旧变量 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`、`OLLAMA_API_KEY` 仍兼容，但新部署统一使用 `AI_*`。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 内部 liveness |
| GET | `/api/ready` | AI Provider/API Key readiness |
| GET | `/api/ai/capabilities` | AI 能力/供应商/模型状态 |
| POST | `/api/ai/chat` | 多轮对话（兼容非流式） |
| POST | `/api/ai/chat/stream` | SSE 流式多轮对话 |
| POST | `/api/ai/financial/report` | 财报文本分析 |
| POST | `/api/ai/analyze/sentiment` | 研报情感分析 |
| POST | `/api/ai/analyze/chain` | 产业链分析 |
| POST | `/api/ai/quote` | 行情智能解读 |

所有请求必须带：

```text
X-Internal-Service-Token: <PYTHON_SERVICE_TOKEN>
```

## 测试

```bash
python -m pytest -q tests
```
