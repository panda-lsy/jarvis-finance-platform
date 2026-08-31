#!/usr/bin/env python3
"""
SQLite 持久化存储
存储历史K线和实时价格快照，供回测与前端展示。
"""
import os
import sqlite3
import logging
from datetime import datetime, date
from typing import List, Dict, Optional

logger = logging.getLogger(__name__)

DEFAULT_DB = os.environ.get(
    "GOLD_DB_PATH",
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "gold.db"),
)


class PriceStore:
    """黄金价格 SQLite 存储"""

    def __init__(self, db_path: str = DEFAULT_DB):
        self.db_path = db_path
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self._init_db()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._conn() as c:
            c.execute("""
                CREATE TABLE IF NOT EXISTS kline (
                    symbol TEXT NOT NULL,
                    date TEXT NOT NULL,
                    open REAL, close REAL, high REAL, low REAL, volume REAL,
                    PRIMARY KEY (symbol, date)
                )
            """)
            c.execute("""
                CREATE TABLE IF NOT EXISTS daily_snapshot (
                    symbol TEXT NOT NULL,
                    ts TEXT NOT NULL,
                    price REAL,
                    change_pct REAL,
                    PRIMARY KEY (symbol, ts)
                )
            """)
            c.execute("""
                CREATE INDEX IF NOT EXISTS idx_kline_symbol_date
                ON kline (symbol, date)
            """)

    # ---- K线 ----
    def upsert_kline(self, symbol: str, rows: List[Dict]):
        """批量写入K线; 已存在则更新"""
        if not rows:
            return 0
        with self._conn() as c:
            c.executemany("""
                INSERT INTO kline (symbol, date, open, close, high, low, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(symbol, date) DO UPDATE SET
                    open=excluded.open, close=excluded.close,
                    high=excluded.high, low=excluded.low, volume=excluded.volume
            """, [
                (symbol, r["date"], r["open"], r["close"], r["high"], r["low"], r["volume"])
                for r in rows
            ])
        return len(rows)

    def get_kline(self, symbol: str, limit: int = 120, start: str = None, end: str = None) -> List[Dict]:
        q = "SELECT date, open, close, high, low, volume FROM kline WHERE symbol=?"
        params: list = [symbol]
        if start:
            q += " AND date>=?"
            params.append(start)
        if end:
            q += " AND date<=?"
            params.append(end)
        q += " ORDER BY date ASC"
        if limit:
            # 取最近 limit 条
            q = f"SELECT * FROM ({q}) ORDER BY date DESC LIMIT ?"
            params.append(limit)
            inner_q = q
        with self._conn() as c:
            rows = c.execute(q, params).fetchall()
        rows.reverse()
        return [dict(r) for r in rows]

    def kline_date_range(self, symbol: str) -> Optional[Dict]:
        with self._conn() as c:
            row = c.execute(
                "SELECT MIN(date) AS mn, MAX(date) AS mx, COUNT(*) AS n FROM kline WHERE symbol=?",
                (symbol,),
            ).fetchone()
        if not row or row["n"] == 0:
            return None
        return {"min": row["mn"], "max": row["mx"], "count": row["n"]}

    # ---- 实时快照 ----
    def record_snapshot(self, symbol: str, price: float, change_pct: Optional[float]):
        ts = datetime.now().isoformat(timespec="seconds")
        with self._conn() as c:
            c.execute(
                "INSERT INTO daily_snapshot (symbol, ts, price, change_pct) VALUES (?,?,?,?)",
                (symbol, ts, price, change_pct),
            )

    def get_snapshots(self, symbol: str, limit: int = 500) -> List[Dict]:
        with self._conn() as c:
            rows = c.execute(
                "SELECT ts, price, change_pct FROM daily_snapshot WHERE symbol=? ORDER BY ts DESC LIMIT ?",
                (symbol, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def summary(self) -> Dict:
        with self._conn() as c:
            k = c.execute("SELECT symbol, COUNT(*) n FROM kline GROUP BY symbol").fetchall()
        return {
            "db_path": self.db_path,
            "kline": [dict(r) for r in k],
        }


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    st = PriceStore()
    print("summary:", st.summary())
