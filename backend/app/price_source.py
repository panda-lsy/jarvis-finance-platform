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
#  symbol 为腾讯格式代码 (sh/sz 前缀; hf_ 为国际/伦敦金)
MARKETS = {
    "gold_etf": {
        "label": "黄金ETF华夏",
        "symbol": "sh518850",
    },
    "london_gold": {
        "label": "伦敦金(现货黄金)",
        "symbol": "hf_XAU",
    },
}


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    return s


def fetch_realtime(symbol: str = "sh518850") -> Optional[Dict]:
    """获取实时报价。返回 dict 或 None。
    兼容两种格式:
      - A股/ETF: ~ 分隔 (qt.gtimg.cn)
      - 国际金/伦敦金: , 分隔 (hf_XAU)
    """
    try:
        s = _session()
        resp = s.get(REALTIME_URL.format(symbol=symbol), timeout=8)
        resp.encoding = "gb2312"
        text = resp.text
        if "=" not in text:
            return None
        payload = text.split("=", 1)[1].strip().strip(';').strip('"')
        if not payload:
            return None

        # 判断分隔符: 国际金用逗号, A股用波浪号
        if "~" in payload:
            v = payload.split("~")
            name = v[1]
            price = float(v[3]) if len(v) > 3 else None
            prev_close = float(v[4]) if len(v) > 4 else None
            open_p = float(v[5]) if len(v) > 5 else None
            volume = float(v[6]) if len(v) > 6 else None
            change = float(v[31]) if len(v) > 31 else None
            change_pct = float(v[32]) if len(v) > 32 else None
        else:
            # 国际金/伦敦金: 逗号分隔
            v = payload.split(",")
            # [0]现价 [1]涨跌 [2]今开 [3]昨收 [4]最高 [5]最低 [6]时间 [7]昨收2 [8]均价 ... [13]名称
            name = v[13] if len(v) > 13 else symbol
            price = float(v[0]) if len(v) > 0 else None
            change = float(v[1]) if len(v) > 1 else None
            open_p = float(v[2]) if len(v) > 2 else None
            prev_close = float(v[3]) if len(v) > 3 else None
            high = float(v[4]) if len(v) > 4 else None
            low = float(v[5]) if len(v) > 5 else None
            volume = None
            change_pct = (change / prev_close * 100) if (change is not None and prev_close) else None

        return {
            "symbol": symbol,
            "name": name,
            "price": price,
            "prev_close": prev_close,
            "open": open_p,
            "high": high if "~" not in payload else None,
            "low": low if "~" not in payload else None,
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
    兼容: A股/ETF(腾讯) 与 伦敦金/国际金(新浪)
    """
    # 伦敦金/国际金: 用新浪接口
    if symbol.startswith("hf_") or symbol.upper() in ("XAU", "XAUUSD"):
        return _fetch_london_kline(symbol, count)
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


def _fetch_london_kline(symbol: str, count: int) -> List[Dict]:
    """伦敦金/国际金日K (新浪 GlobalFuturesService)"""
    try:
        s = _session()
        s.headers.update({"Referer": "https://finance.sina.com.cn"})
        url = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesDailyKLine"
        resp = s.get(url, params={"symbol": "XAU"}, timeout=12)
        import re, json as _json
        m = re.search(r"\(\[(.*)\]\)", resp.text, re.S)
        if not m:
            return []
        data = _json.loads("[" + m.group(1) + "]")
        out = []
        for k in data[-count:]:
            out.append({
                "date": k["date"],
                "open": float(k["open"]),
                "close": float(k["close"]),
                "high": float(k["high"]),
                "low": float(k["low"]),
                "volume": float(k.get("volume", 0) or 0),
            })
        return out
    except Exception as e:
        logger.warning("london kline fetch failed %s: %s", symbol, e)
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
