#!/usr/bin/env python3
"""
JARVIS Python AI Service

职责边界：
- Python 仅负责与大模型/AI 能力交互。
- 用户、交易、行情、K线、回测与业务持久化统一由 Java 主后端负责。
- 本服务只接受携带 PYTHON_SERVICE_TOKEN 的 Java 内部请求。
"""
from fastapi import Depends, FastAPI, HTTPException

from . import ai_service
from .ai_routes import require_internal_service, router as ai_router

app = FastAPI(title="JARVIS AI Service", version="2.0.0")
app.include_router(ai_router)


@app.get("/api/health", dependencies=[Depends(require_internal_service)])
def health():
    """Liveness：仅表示 Python 进程和 FastAPI 可响应。"""
    return {
        "status": "ok",
        "service": "jarvis-ai-service (python)",
        "role": "ai-only",
        "time": __import__("datetime").datetime.now().isoformat(),
    }


@app.get("/api/ready", dependencies=[Depends(require_internal_service)])
def ready():
    """Readiness：AI Provider/Key 必须已配置，发布流程才视为可服务。"""
    caps = ai_service.capabilities()
    if not caps.get("available"):
        raise HTTPException(status_code=503, detail=caps.get("message") or "AI provider unavailable")
    return {
        "status": "ready",
        "service": "jarvis-ai-service (python)",
        "provider": caps.get("provider"),
        "model": caps.get("model"),
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="127.0.0.1", port=8100, reload=False)
