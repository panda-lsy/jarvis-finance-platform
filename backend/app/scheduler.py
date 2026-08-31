#!/usr/bin/env python3
"""
调度器: 首次批量加载历史 + 周期增量抓取实时价/更新K线

用法:
  python -m app.scheduler once        # 只执行一次: 拉历史K线 + 记录快照
  python -m app.scheduler load        # 仅批量加载历史K线
  python -m app.scheduler server      # 常驻: 周期调度(默认每5分钟)
"""
import sys
import time
import logging
import threading
from datetime import datetime
from typing import Dict

from .price_source import fetch_realtime, fetch_kline, MARKETS
from .db import PriceStore

logger = logging.getLogger(__name__)

DEFAULT_INTERVAL = 300  # 秒, 5分钟


def load_historical(store: PriceStore, count: int = 120) -> Dict:
    """批量加载全部标的历史K线到 SQLite"""
    result = {}
    for key, cfg in MARKETS.items():
        rows = fetch_kline(cfg["symbol"], count=count)
        n = store.upsert_kline(key, rows)
        result[key] = {"symbol": cfg["symbol"], "label": cfg["label"], "loaded": n}
        logger.info("loaded %s: %d klines", key, n)
    return result


def snapshot_all(store: PriceStore):
    """记录全部标的最新实时价快照"""
    for key, cfg in MARKETS.items():
        r = fetch_realtime(cfg["symbol"])
        if r and r.get("price"):
            store.record_snapshot(key, r["price"], r.get("change_pct"))
            logger.info("snapshot %s: %.3f (%.2f%%)", key, r["price"], r.get("change_pct") or 0)
        else:
            logger.warning("snapshot %s failed", key)


def run_once():
    store = PriceStore()
    print("=== 加载历史K线 ===")
    print(load_historical(store))
    print("=== 记录实时快照 ===")
    snapshot_all(store)
    print("=== 存储概览 ===")
    print(store.summary())


def run_loop(interval: int = DEFAULT_INTERVAL):
    store = PriceStore()
    logger.info("启动周期调度, 每 %d 秒", interval)
    # 先执行一次
    load_historical(store)
    snapshot_all(store)
    while True:
        time.sleep(interval)
        try:
            load_historical(store, count=120)
            snapshot_all(store)
        except Exception as e:
            logger.exception("scheduled run failed: %s", e)


def start_background(interval: int = DEFAULT_INTERVAL) -> threading.Thread:
    """在服务内以守护线程方式启动调度"""
    t = threading.Thread(target=run_loop, args=(interval,), daemon=True)
    t.start()
    return t


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    mode = sys.argv[1] if len(sys.argv) > 1 else "once"
    if mode == "server":
        interval = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_INTERVAL
        run_loop(interval)
    else:
        run_once()
