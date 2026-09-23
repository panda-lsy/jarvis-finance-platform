#!/usr/bin/env python3
"""
JARVIS Python AI Service

职责边界：
- Python 仅负责与大模型/AI 能力交互。
- 用户、交易、行情、K线、回测与业务持久化统一由 Java 主后端负责。
- 本服务只接受携带 PYTHON_SERVICE_TOKEN 的 Java 内部请求。
"""
from fastapi import Body, Depends, FastAPI, HTTPException
from fastapi.responses import JSONResponse

from . import ai_service, news_semantic, stock_research
from .ai_routes import require_internal_service, router as ai_router
from .rss import RSSSourceNotFound, RSSValidationError, rss_store

app = FastAPI(title="JARVIS AI Service", version="2.0.0")
app.include_router(ai_router)


# RSS 模块用领域异常表达失败原因，在这里统一映射为 HTTP 语义——
# 避免把 ValueError / LookupError 泄漏成 500，或让端点各自 try/except 重复样板。
@app.exception_handler(RSSValidationError)
async def _rss_validation_handler(_request, exc: RSSValidationError):
    return JSONResponse(status_code=400, content={"detail": str(exc)})


@app.exception_handler(RSSSourceNotFound)
async def _rss_not_found_handler(_request, exc: RSSSourceNotFound):
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.post("/internal/rss/source", dependencies=[Depends(require_internal_service)])
def add_rss_source(source: dict = Body(...)):
    return rss_store.add_source(source)


@app.get("/internal/rss/source", dependencies=[Depends(require_internal_service)])
def list_rss_sources():
    return rss_store.list_sources()


@app.post("/internal/rss/fetch/{source_id}", dependencies=[Depends(require_internal_service)])
def fetch_rss(source_id: str):
    """抓取一次并返回结构化结果；`ok=false` 时 `error` 说明失败原因。"""
    return rss_store.crawl(source_id)


@app.get("/internal/rss/articles", dependencies=[Depends(require_internal_service)])
def list_rss_articles(source_id: str | None = None):
    """列出已标准化文章；可选 `source_id` 过滤到单个资讯源。"""
    return rss_store.list_articles(source_id)


@app.post("/internal/rss/digest", dependencies=[Depends(require_internal_service)])
def rss_digest(refresh: bool = True, force: bool = False):
    """刷新并返回合并后的最新资讯（每日要闻的唯一入口）。

    抓取与去重留在 Python 侧，Java 主后端只做薄代理与降级展示。
    单源失败逐源返回 error，不会让整个响应失败；`force=true` 绕过最小抓取间隔。
    """
    return rss_store.digest(refresh=refresh, force=force)


@app.post("/internal/rss/rerank", dependencies=[Depends(require_internal_service)])
def rerank_rss(payload: dict = Body(...)):
    """对已通过订阅筛选的 V1 候选执行可选语义重排。

    模型服务未配置或失败时返回 available=false + 原始 items，Java 必须保留 V1 顺序。
    """
    query = str(payload.get("query") or "").strip()
    articles = payload.get("articles")
    if not isinstance(articles, list):
        articles = []
    return news_semantic.rerank_articles(query, articles)


@app.post("/internal/research/stock-news", dependencies=[Depends(require_internal_service)])
def search_stock_news(payload: dict = Body(...)):
    """检索个股相关新闻；配置 Tavily 时走实时搜索，否则从国内 RSS 缓存降级。"""
    try:
        return stock_research.search_stock_news(
            payload.get("query"), payload.get("limit", 8))
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


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
        "display_name": caps.get("display_name"),
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="127.0.0.1", port=8100, reload=False)
