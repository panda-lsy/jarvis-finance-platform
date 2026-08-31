#!/usr/bin/env python3
"""
回测引擎（基于历史K线）
策略: 双均线(短期/长期)交叉 - 金叉买入, 死叉卖出
输出: 策略净值曲线 + 买入持有收益 + 统计指标, 供前端回测可视化
"""
from typing import List, Dict


def moving_average(values, window):
    """返回与输入等长的MA数组, 前 window-1 位为 None"""
    n = len(values)
    out = [None] * n
    if n == 0 or window <= 0:
        return out
    s = 0.0
    for i in range(n):
        s += values[i]
        if i >= window:
            s -= values[i - window]
        if i >= window - 1:
            out[i] = s / window
    return out


def run_backtest(
    klines: List[Dict],
    short_ma: int = 5,
    long_ma: int = 20,
    initial_cash: float = 100000.0,
    transaction_cost: float = 0.001,  # 0.1% 手续费
) -> Dict:
    """
    在日K序列上跑双均线策略。
    klines 需按日期升序。
    """
    if len(klines) < long_ma:
        return {"error": f"数据不足, 需要至少 {long_ma} 根K线, 当前 {len(klines)} 根"}

    closes = [k["close"] for k in klines]
    dates = [k["date"] for k in klines]
    ma_s = moving_average(closes, short_ma)
    ma_l = moving_average(closes, long_ma)

    cash = initial_cash
    shares = 0.0
    equity_curve = []
    trades = []
    in_position = False

    for i in range(len(klines)):
        date = dates[i]
        close = closes[i]
        ms = ma_s[i]
        ml = ma_l[i]

        # 信号判断 (仅在两条MA都有值时)
        if ms is not None and ml is not None:
            if ms > ml and not in_position:
                # 金叉买入
                buy_price = close * (1 + transaction_cost)
                shares = cash / buy_price
                cash = 0.0
                in_position = True
                trades.append({"date": date, "type": "BUY", "price": round(close, 3), "qty": round(shares, 2)})
            elif ms < ml and in_position:
                # 死叉卖出
                sell_price = close * (1 - transaction_cost)
                cash = shares * sell_price
                shares = 0.0
                in_position = False
                trades.append({"date": date, "type": "SELL", "price": round(close, 3), "qty": round(shares, 2)})

        # 每日资产 = 现金 + 持仓市值
        equity = cash + shares * close
        equity_curve.append({"date": date, "equity": round(equity, 2), "close": close})

    # 结果
    final_equity = equity_curve[-1]["equity"]
    # 买入持有: 期初全仓买入一直持有
    bh_equity = initial_cash / closes[0] * closes[-1] / (1 + transaction_cost)

    total_return = (final_equity / initial_cash - 1) * 100
    bh_return = (bh_equity / initial_cash - 1) * 100

    # 最大回撤
    peak = equity_curve[0]["equity"]
    max_dd = 0.0
    for p in equity_curve:
        if p["equity"] > peak:
            peak = p["equity"]
        dd = (peak - p["equity"]) / peak if peak > 0 else 0
        if dd > max_dd:
            max_dd = dd

    days = max(len(klines), 1)
    annual_return = ((final_equity / initial_cash) ** (365 / days) - 1) * 100 if final_equity > 0 else 0

    return {
        "symbol": klines[0].get("symbol", ""),
        "range": {"start": dates[0], "end": dates[-1], "bars": len(dates)},
        "params": {"short_ma": short_ma, "long_ma": long_ma, "transaction_cost": transaction_cost},
        "initial_cash": initial_cash,
        "final_equity": round(final_equity, 2),
        "total_return_pct": round(total_return, 2),
        "annual_return_pct": round(annual_return, 2),
        "buy_hold_return_pct": round(bh_return, 2),
        "max_drawdown_pct": round(max_dd * 100, 2),
        "num_trades": len(trades),
        "trades": trades[-20:],
        "equity_curve": equity_curve,
    }
