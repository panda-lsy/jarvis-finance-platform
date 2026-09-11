#!/usr/bin/env python3
"""AI 路由: 统一挂载到主应用 /api/ai/*"""
from typing import List, Dict, Optional, Any, Literal
import hmac
import json
import os

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import ai_service

INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token"
PYTHON_SERVICE_TOKEN = os.getenv("PYTHON_SERVICE_TOKEN", "")


def require_internal_service(
    x_internal_service_token: Optional[str] = Header(default=None, alias=INTERNAL_TOKEN_HEADER),
):
    """AI 接口仅允许 Java 主后端通过内部服务令牌调用。"""
    if not PYTHON_SERVICE_TOKEN:
        raise HTTPException(status_code=503, detail="PYTHON_SERVICE_TOKEN 未配置")
    if not x_internal_service_token or not hmac.compare_digest(
        x_internal_service_token, PYTHON_SERVICE_TOKEN
    ):
        raise HTTPException(status_code=401, detail="invalid internal service token")


router = APIRouter(
    prefix="/api/ai",
    tags=["ai"],
    dependencies=[Depends(require_internal_service)],
)


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=10_000)


class ChatReq(BaseModel):
    messages: List[ChatMessage] = Field(min_length=1, max_length=20)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    # 仅由 Java 主后端注入；Python 会在调用 LLM 前对其中行情/K线做确定性计算。
    research_context: Optional[Dict[str, Any]] = None


class ReportReq(BaseModel):
    content: str = Field(default="", max_length=50_000)
    text: Optional[str] = Field(default="", max_length=50_000)


class SentimentReq(BaseModel):
    reports: List[str] = Field(default_factory=list, min_length=1, max_length=20)


class ChainReq(BaseModel):
    node: str = Field(min_length=1, max_length=100)
    context: Optional[str] = Field(default="", max_length=10_000)


class QuoteReq(BaseModel):
    price_data: Dict[str, Any] = Field(default_factory=dict)


class RiskReq(BaseModel):
    """风险预警（FR-10）：closes 为历史收盘价序列。

    样本量是否足够由确定性计算层判断，以便统一返回 available=False，
    而不是在接口校验阶段直接返回 422。
    """
    closes: List[float] = Field(max_length=2000)
    confidence: float = Field(default=0.95, ge=0.5, le=0.99)
    portfolio_value: Optional[float] = Field(default=None, gt=0)
    symbol: Optional[str] = Field(default=None, max_length=32)


class StrategyReq(BaseModel):
    """个性化策略生成（FR-11）：风险偏好问卷字段。

    范围在接口层做硬校验（问卷由前端固定选项产生，越界视为客户端错误）；
    等级与配置比例的映射逻辑放在确定性计算层。
    """
    horizon_years: float = Field(ge=0.5, le=30)
    max_drawdown_pct: float = Field(ge=1, le=60)
    target_return_pct: float = Field(ge=0, le=50)
    capital: Optional[float] = Field(default=None, gt=0, le=1_000_000_000)
    experience: Literal["none", "basic", "rich"] = "basic"


def _guard(fn, **kw):
    """执行并统一把 RuntimeError 转 502"""
    try:
        return fn(**kw)
    except RuntimeError as e:
        raise HTTPException(status_code=502, detail=str(e))


@router.get("/capabilities")
def get_capabilities():
    return {"code": 200, "message": "ok", "data": ai_service.capabilities()}


@router.post("/chat")
def chat(req: ChatReq):
    messages = [message.model_dump() for message in req.messages]
    return {"code": 200, "message": "ok", "data": _guard(
        ai_service.chat,
        messages=messages,
        temperature=req.temperature,
        research_context=req.research_context,
    )}


@router.post("/chat/stream")
def chat_stream(req: ChatReq):
    messages = [message.model_dump() for message in req.messages]
    try:
        events = ai_service.open_chat_stream(
            messages=messages,
            temperature=req.temperature,
            research_context=req.research_context,
        )
    except RuntimeError as e:
        raise HTTPException(status_code=502, detail=str(e))

    def sse_events():
        for event in events:
            event_type = str(event.get("type", "message"))
            data = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
            yield f"event: {event_type}\ndata: {data}\n\n"

    return StreamingResponse(
        sse_events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/financial/report")
def financial_report(req: ReportReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.financial_report, content=req.content or req.text or "")}


@router.post("/analyze/sentiment")
def analyze_sentiment(req: SentimentReq):
    if sum(len(item) for item in req.reports) > 100_000:
        raise HTTPException(status_code=413, detail="研报文本总长度不能超过100000字符")
    return {"code": 200, "message": "ok", "data": _guard(ai_service.analyze_sentiment, reports=req.reports)}


@router.post("/analyze/chain")
def analyze_chain(req: ChainReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.analyze_chain, node=req.node, context=req.context or "")}


@router.post("/analyze/risk")
def analyze_risk(req: RiskReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.analyze_risk,
                           closes=req.closes,
                           confidence=req.confidence,
                           portfolio_value=req.portfolio_value,
                           symbol=req.symbol)}


@router.post("/analyze/strategy")
def analyze_strategy(req: StrategyReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.generate_strategy,
                           horizon_years=req.horizon_years,
                           max_drawdown_pct=req.max_drawdown_pct,
                           target_return_pct=req.target_return_pct,
                           capital=req.capital,
                           experience=req.experience)}


@router.post("/quote")
def smart_quote(req: QuoteReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.smart_quote, price_data=req.price_data)}
