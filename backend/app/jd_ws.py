#!/usr/bin/env python3
"""
WebSocket 实时金价推送 (FastAPI 原生)
- 每 1 分钟抓取京东积存金双源 (浙商 + 民生), 持久化到 SQLite
- 通过 /ws/prices 推送给前端
"""
import asyncio
import json
import logging
import threading
from datetime import datetime

from fastapi import WebSocket, WebSocketDisconnect

from .jd_source import fetch_all, SOURCES

logger = logging.getLogger(__name__)

PUSH_INTERVAL = 60  # 秒, 每分钟推一次

_clients: set = set()
_last_push: dict = {}
_loop_ref: asyncio.AbstractEventLoop = None


async def _broadcast(payload: dict):
    """广播给所有 WS 客户端"""
    if not _clients:
        return
    text = json.dumps(payload, ensure_ascii=False)
    dead = []
    for ws in list(_clients):
        try:
            await ws.send_text(text)
        except Exception:
            dead.append(ws)
    for ws in dead:
        _clients.discard(ws)


async def ws_handler(websocket: WebSocket):
    """/ws/prices 连接处理"""
    await websocket.accept()
    _clients.add(websocket)
    logger.info("WS 客户端接入, 当前 %d 个", len(_clients))
    try:
        # 连接即推送最近一次价格
        await websocket.send_text(json.dumps(_last_push, ensure_ascii=False))
        while True:
            msg = await websocket.receive_text()
            if msg.strip() == "ping":
                await websocket.send_text('{"type":"pong"}')
    except WebSocketDisconnect:
        pass
    finally:
        _clients.discard(websocket)
        logger.info("WS 客户端断开, 剩余 %d 个", len(_clients))


def _poll_once(store):
    """抓取京东双源 + 持久化 + 更新最新值 + 广播"""
    global _last_push
    prices = fetch_all()
    if not prices:
        return
    normalized = {}
    for key, p in prices.items():
        normalized[key] = {
            "source": key,
            "label": p["label"],
            "price": p["price"],
            "change": p["change"],
            "change_pct": p["change_pct"],
            "time": p["time"],
        }
        try:
            # 持久化: jd_zheshang / jd_minsheng 与现有标的不冲突
            store.record_snapshot("jd_" + key, p["price"], p["change_pct"])
        except Exception as e:
            logger.warning("jd snapshot save fail: %s", e)
    _last_push = {
        "type": "prices",
        "prices": normalized,
        "sources": [SOURCES[k]["label"] for k in SOURCES],
        "timestamp": datetime.now().isoformat(timespec="seconds"),
    }
    # 广播到 WS 客户端 (跨线程调度到事件循环)
    if _loop_ref and _clients:
        try:
            asyncio.run_coroutine_threadsafe(_broadcast(_last_push), _loop_ref)
        except Exception as e:
            logger.warning("ws broadcast schedule fail: %s", e)


def _loop(store, stop_event: threading.Event):
    while not stop_event.is_set():
        try:
            _poll_once(store)
        except Exception as e:
            logger.exception("jd poll fail: %s", e)
        stop_event.wait(PUSH_INTERVAL)


def start_poller(store, loop: asyncio.AbstractEventLoop) -> threading.Event:
    """启动后台轮询线程 (每 1 分钟); 返回停止事件"""
    global _loop_ref
    _loop_ref = loop
    stop_event = threading.Event()
    t = threading.Thread(target=_loop, args=(store, stop_event), daemon=True)
    t.start()
    logger.info("京东金价轮询已启动, 每 %d 秒", PUSH_INTERVAL)
    return stop_event
