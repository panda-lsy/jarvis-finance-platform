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
    return {"code": 200, "message": "ok", "data": _guard(ai_service.chat, messages=messages, temperature=req.temperature)}


@router.post("/chat/stream")
def chat_stream(req: ChatReq):
    messages = [message.model_dump() for message in req.messages]
    try:
        events = ai_service.open_chat_stream(messages=messages, temperature=req.temperature)
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


@router.post("/quote")
def smart_quote(req: QuoteReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.smart_quote, price_data=req.price_data)}
