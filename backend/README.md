# JARVIS 黄金 - 本机后端 (Python FastAPI)

本机持久化黄金价格数据，提供历史K线、实时价与回测 API。
数据源：腾讯财经（已验证可用）。

## 功能

- **历史K线抓取**：首次启动批量拉取约120个交易日日K → 落 SQLite
- **实时价格记录**：周期抓取实时价并追加快照
- **回测引擎**：双均线策略，返回收益/回撤/逐日净值曲线
- **REST API**：供 GitHub Pages 前端 / React Native 移动端跨域访问

## 目录

```
backend/
├── app/
│   ├── main.py           # FastAPI 入口 (端口 8100)
│   ├── price_source.py   # 腾讯数据抓取
│   ├── db.py             # SQLite 持久化
│   ├── scheduler.py      # 批量加载 + 周期抓取
│   └── backtest.py       # 双均线回测引擎
├── data/gold.db          # SQLite 数据库 (运行时生成)
└── requirements.txt
```

## 启动

```bash
cd backend
pip install -r requirements.txt

# 方式1: 仅执行一次 (拉历史 + 记快照)
python -m app.scheduler once

# 方式2: 常驻周期抓取 (仅抓取, 不启服务)
python -m app.scheduler server

# 方式3: 启动 API 服务 (自动后台周期抓取)
python -m uvicorn app.main:app --host 0.0.0.0 --port 8100
```

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/markets` | 可用的黄金标的 |
| GET | `/api/prices` | 各标的最新实时价 + 最近快照 |
| GET | `/api/kline?market=gold_etf&limit=120` | 本机持久化的历史K线 |
| GET | `/api/backtest?short_ma=5&long_ma=20` | 双均线回测 |
| GET | `/api/storage` | SQLite 存储概览 |

## 环境变量

- `GOLD_DB_PATH`：自定义 SQLite 路径（默认 `data/gold.db`）
