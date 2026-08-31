#!/usr/bin/env python3
"""
贾维斯·黄金 - 本机后端服务 (FastAPI)
提供: 实时价 / 历史K线 / 回测 / 存储概览 API
本机持久化: SQLite (data/gold.db)
"""
from typing import Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from .db import PriceStore
from .price_source import MARKETS as SOURCE_MARKETS
from . import scheduler
from .backtest import run_backtest
from .ai_routes import router as ai_router
from .jd_ws import ws_handler, start_poller

store = PriceStore()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时先加载历史 + 启动后台周期抓取
    scheduler.load_historical(store, count=120)
    scheduler.start_background(interval=300)
    # 京东积存金双源轮询 (每 1 分钟) + WS 推送
    import asyncio
    start_poller(store, asyncio.get_running_loop())
    yield


app = FastAPI(title="JARVIS Gold Backend", version="1.1.0", lifespan=lifespan)

# AI 接口 (DeepSeek, Python 直连)
app.include_router(ai_router)

# CORS: 允许 GitHub Pages 前端跨域访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health():
    return {
        "status": "ok",
        "service": "jarvis-gold-backend",
        "time": __import__("datetime").datetime.now().isoformat(),
    }


@app.websocket("/ws/prices")
async def ws_prices(websocket: WebSocket):
    """WS 实时金价: 京东积存金双源, 每 1 分钟推送"""
    await ws_handler(websocket)


@app.get("/api/jd/prices")
def jd_prices():
    """京东积存金实时价 (最近抓取值, 立即返回)"""
    from .jd_ws import _last_push
    return {"code": 200, "message": "ok", "data": _last_push}


@app.get("/api/jd/snapshots")
def jd_snapshots(limit: int = 60):
    """京东价持久化快照 (回测/图表用)"""
    out = {}
    for key in ("jd_zheshang", "jd_minsheng"):
        snaps = store.get_snapshots(key, limit=limit)
        if snaps:
            out[key] = snaps
    return {"code": 200, "message": "ok", "data": out}


@app.get("/api/markets")
def markets():
    """支持的标的一览"""
    return {"markets": [
        {"key": key, "symbol": cfg["symbol"], "label": cfg["label"]}
        for key, cfg in SOURCE_MARKETS.items()
    ]}


@app.get("/api/prices")
def prices():
    """各标的最新实时价 (+最近一条已记录快照)"""
    from .price_source import fetch_realtime
    out = {}
    for key, cfg in SOURCE_MARKETS.items():
        r = fetch_realtime(cfg["symbol"])
        snaps = store.get_snapshots(key, limit=1)
        out[key] = {
            "label": cfg["label"],
            "symbol": cfg["symbol"],
            "realtime": r,
            "last_stored": snaps[0] if snaps else None,
        }
        if r and r.get("price"):
            store.record_snapshot(key, r["price"], r.get("change_pct"))
    return {"prices": out}


@app.get("/api/kline")
def kline(
    market: str = Query("gold_etf"),
    limit: int = Query(120, ge=1, le=5000),
    start: Optional[str] = None,
    end: Optional[str] = None,
):
    """历史K线
    - gold_etf: SQLite 持久化数据
    - london_gold: 新浪实时抓取 (伦敦金)
    """
    if market == "london_gold":
        from .price_source import fetch_kline
        data = fetch_kline("hf_XAU", count=limit)
        rng = {"min": data[0]["date"] if data else None,
               "max": data[-1]["date"] if data else None,
               "count": len(data)}
        return {"market": market, "range": rng, "count": len(data), "data": data}
    data = store.get_kline(market, limit=limit, start=start, end=end)
    rng = store.kline_date_range(market)
    return {"market": market, "range": rng, "count": len(data), "data": data}


@app.get("/api/backtest")
def backtest(
    market: str = Query("gold_etf"),
    short_ma: int = Query(5, ge=1),
    long_ma: int = Query(20, ge=2),
    initial_cash: float = Query(100000, gt=0),
    limit: int = Query(120, ge=1, le=5000),
):
    """双均线策略回测"""
    klines = store.get_kline(market, limit=limit)
    if klines:
        for k in klines:
            k["symbol"] = market
    return run_backtest(klines, short_ma=short_ma, long_ma=long_ma, initial_cash=initial_cash)


@app.get("/api/storage")
def storage():
    """存储概览"""
    return {"summary": store.summary()}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=8100, reload=True)
