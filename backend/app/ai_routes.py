#!/usr/bin/env python3
"""AI 路由: 统一挂载到主应用 /api/ai/*"""
from typing import List, Dict, Optional, Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from . import ai_service

router = APIRouter(prefix="/api/ai", tags=["ai"])


class ChatReq(BaseModel):
    messages: List[Dict[str, str]]
    temperature: Optional[float] = 0.7


class ReportReq(BaseModel):
    content: str = ""
    text: Optional[str] = ""


class SentimentReq(BaseModel):
    reports: List[str] = []


class ChainReq(BaseModel):
    node: str
    context: Optional[str] = ""


class QuoteReq(BaseModel):
    price_data: Dict[str, Any] = {}


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
    return {"code": 200, "message": "ok", "data": _guard(ai_service.chat, messages=req.messages, temperature=req.temperature)}


@router.post("/financial/report")
def financial_report(req: ReportReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.financial_report, content=req.content or req.text or "")}


@router.post("/analyze/sentiment")
def analyze_sentiment(req: SentimentReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.analyze_sentiment, reports=req.reports)}


@router.post("/analyze/chain")
def analyze_chain(req: ChainReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.analyze_chain, node=req.node, context=req.context or "")}


@router.post("/quote")
def smart_quote(req: QuoteReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.smart_quote, price_data=req.price_data)}
