#!/usr/bin/env python3
"""
京东积存金价格源 (旧京东源: 浙商积存金 + 民生积存金)

- 数据来源: api.jdjygold.com HTTP 接口
  (旧代码 src/websocket_server.py 中的京东 WebSocket 地址已下线, DNS NXDOMAIN;
   该 WS 地址当时也从未真正使用, 实际数据来自以下两个 HTTP 接口)
- 用途: 每 1 分钟抓取一次实时金价, 持久化 + WebSocket 推送
"""
import logging
import requests
from datetime import datetime
from typing import Dict, Optional

logger = logging.getLogger(__name__)

ZHESHANG_API = "https://api.jdjygold.com/gw2/generic/jrm/h5/m/stdLatestPrice"
MINSHENG_API = "https://api.jdjygold.com/gw/generic/hj/h5/m/latestPrice"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)",
    "Accept": "application/json",
    "Origin": "https://www.jdjygold.com",
    "Referer": "https://www.jdjygold.com/",
}

SOURCES = {
    "zheshang": {
        "url": ZHESHANG_API,
        "params": {"productSku": "1961543816"},
        "label": "浙商积存金",
    },
    "minsheng": {
        "url": MINSHENG_API,
        "params": {"productSku": "P005"},
        "label": "民生积存金",
    },
}


def _parse(data: Dict) -> Optional[Dict]:
    """解析京东接口返回结构"""
    if not data.get("success"):
        return None
    datas = data.get("resultData", {}).get("datas")
    if not datas:
        return None
    try:
        rate_raw = datas.get("upAndDownRate", "0")
        ts_ms = int(datas.get("time", 0))
        return {
            "price": float(datas["price"]),
            "yesterday_price": float(datas.get("yesterdayPrice", datas["price"])),
            "change": float(datas.get("upAndDownAmt", 0)),
            "change_pct": float(str(rate_raw).replace("%", "")),
            "ts_ms": ts_ms,
            "time": datetime.fromtimestamp(ts_ms / 1000).strftime("%Y-%m-%d %H:%M:%S")
            if ts_ms else None,
        }
    except (KeyError, ValueError, TypeError) as e:
        logger.warning("jd price parse fail: %s", e)
        return None


def fetch_one(key: str) -> Optional[Dict]:
    """抓取单个源 (zheshang / minsheng)"""
    cfg = SOURCES.get(key)
    if not cfg:
        return None
    try:
        resp = requests.get(cfg["url"], params=cfg["params"], headers=HEADERS, timeout=10)
        p = _parse(resp.json())
        if p:
            p["source"] = key
            p["label"] = cfg["label"]
            return p
    except Exception as e:
        logger.warning("jd fetch fail (%s): %s", key, e)
    return None


def fetch_all() -> Dict[str, Dict]:
    """抓取全部京东源"""
    out = {}
    for key in SOURCES:
        p = fetch_one(key)
        if p:
            out[key] = p
    return out


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    import json
    print(json.dumps(fetch_all(), ensure_ascii=False, indent=2))
