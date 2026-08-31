#!/usr/bin/env python3
"""
腾讯财经数据抓取服务
- 实时价格: qt.gtimg.cn
- 历史日K: web.ifzq.gtimg.cn/appstock/app/fqkline/get
已验证可用 (2026-08):
  * 实时价 黄金ETF 518850 ✅
  * 历史日K 黄金ETF 518850 ✅ (最多约120根, 字段 [日期,开,收,高,低,量])
"""
import logging
import requests
from typing import List, Dict, Optional

logger = logging.getLogger(__name__)

REALTIME_URL = "https://qt.gtimg.cn/q={symbol}"
KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"

# 支持的标的
#  symbol 为腾讯格式代码 (sh/sz 前缀)
MARKETS = {
    "gold_etf": {
        "label": "黄金ETF华夏",
        "symbol": "sh518850",
    },
}


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    return s


def fetch_realtime(symbol: str = "sh518850") -> Optional[Dict]:
    """获取实时报价。返回 dict 或 None。"""
    try:
        s = _session()
        resp = s.get(REALTIME_URL.format(symbol=symbol), timeout=8)
        resp.encoding = "gb2312"
        text = resp.text
        if "~" not in text:
            return None
        v = text.split("~")
        # 字段: 1名称, 3现价, 4昨收, 5今开, 6成交量(手), 31涨跌, 32涨跌%
        name = v[1]
        price = float(v[3]) if len(v) > 3 else None
        prev_close = float(v[4]) if len(v) > 4 else None
        open_p = float(v[5]) if len(v) > 5 else None
        volume = float(v[6]) if len(v) > 6 else None
        change = float(v[31]) if len(v) > 31 else None
        change_pct = float(v[32]) if len(v) > 32 else None
        return {
            "symbol": symbol,
            "name": name,
            "price": price,
            "prev_close": prev_close,
            "open": open_p,
            "volume": volume,
            "change": change,
            "change_pct": change_pct,
        }
    except Exception as e:
        logger.warning("realtime fetch failed %s: %s", symbol, e)
        return None


def fetch_kline(symbol: str = "sh518850", count: int = 120, fq: str = "qfq") -> List[Dict]:
    """
    获取历史日K线。
    返回字段: date, open, close, high, low, volume
    """
    try:
        s = _session()
        param = f"{symbol},day,,,{count},{fq}"
        resp = s.get(KLINE_URL, params={"param": param}, timeout=12)
        data = resp.json()
        node = data.get("data", {}).get(symbol, {})
        raw = node.get("day") or node.get("qfqday") or []
        out = []
        for item in raw:
            # [日期, 开, 收, 高, 低, 成交量]
            out.append({
                "date": item[0],
                "open": float(item[1]),
                "close": float(item[2]),
                "high": float(item[3]),
                "low": float(item[4]),
                "volume": float(item[5]) if len(item) > 5 else 0.0,
            })
        return out
    except Exception as e:
        logger.warning("kline fetch failed %s: %s", symbol, e)
        return []


def fetch_all_markets_kline(count: int = 120) -> Dict[str, List[Dict]]:
    """抓取全部标的历史K线"""
    result = {}
    for key, cfg in MARKETS.items():
        result[key] = fetch_kline(cfg["symbol"], count=count)
    return result


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    print("实时: ", fetch_realtime())
    k = fetch_kline(count=120)
    print("历史K线根数:", len(k))
    if k:
        print("最新一条:", k[-1])
