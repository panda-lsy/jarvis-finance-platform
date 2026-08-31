# 贾维斯 (JARVIS) - 积存金智能模拟交易助手

[![OpenVINO](https://img.shields.io/badge/OpenVINO-2026-blue)](https://docs.openvino.ai/)
[![ModelScope](https://img.shields.io/badge/ModelScope-魔搭社区-blue)](https://www.modelscope.cn/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> ModelScope x OpenVINO AI 应用实战参赛作品

## 项目简介

本项目是一个基于 OpenVINO 加速的积存金智能模拟交易系统，提供行情采集、策略分析、可视化大盘、OpenClaw 调度与多模态 AI 能力（ASR/TTS/VLM/图像生成）。

## 核心能力

| 能力       | 描述                           | 技术                 |
| ---------- | ------------------------------ | -------------------- |
| 语音交互   | 语音输入问价、问仓位、问策略   | Qwen3-ASR + OpenVINO |
| 智能分析   | 图文与行情联合分析             | Qwen3-VL + OpenVINO  |
| 语音播报   | AI 回复语音合成播报            | Qwen3-TTS + OpenVINO |
| 可视化     | Dashboard + WebSocket 实时推送 | Flask + ECharts      |
| 自动化运维 | 模板化定时任务和巡检           | OpenClaw + Python    |

## 目录结构

```text
gold-trading/
├── app/                      # Web/API 服务 (原)
├── ai_interface/             # ASR/TTS/VLM/图像生成接口 (原)
├── src/                      # 交易核心模块 (原)
├── ops/                      # 监控、通知、OpenClaw 模板管理 (原)
├── scripts/                  # Windows + Linux/macOS 运维脚本 (原)
├── web/                      # 统一工作台静态入口 (原)
├── config/                   # 配置与模板 (原)
├── skills/                   # Skill 文档 (原)
├── backend/                  # ★ Python 辅助微服务 (FastAPI + SQLite, 数据采集/AI预处理)
├── java-backend/             # ★ Java 主后端 (Spring Boot 3, 金融数据交互 + DeepSeek AI)
├── frontend/                 # ★ Vue.js 演示前端 (GitHub Pages)
├── mobile/                   # ★ React Native 移动端 (Expo)
└── .github/workflows/
    ├── ci.yml                # 原有 CI
    └── deploy-frontend.yml   # ★ 部署 Vue 前端到 GitHub Pages
```

## ★ 新增架构：Java 主后端 + Python 辅助微服务 + 前端分离

在原有 AI 全栈基础上，新增了轻量分离架构（暂不部署本地 AI 模块）：

- **Java 主后端** (`java-backend/`, Spring Boot 3, 端口 8200): 金融数据交互 + DeepSeek 大模型集成。负责业务 API / 行情 / AI 投研分析。
- **Python 辅助微服务** (`backend/`, FastAPI, 端口 8100): 仅保留数据采集 / AI 预处理，被 Java 回退调用。
- **DeepSeek 接入**: 兼容 OpenAI Chat / Responses / Anthropic Messages 三种协议，`AiGateway` 统一路由。
- **前端** (`frontend/` Vue 3 + `mobile/` React Native) + **数据源** (腾讯财经)。

### Java 主后端启动

```bash
cd java-backend
export DEEPSEEK_API_KEY=<Key>
mvn spring-boot:run        # 端口 8200
```

### Python 辅助微服务启动

```bash
cd backend && python -m uvicorn app.main:app --host 0.0.0.0 --port 8100
```

详细说明见 `java-backend/README.md` 与 `backend/README.md`。


### 本机后端启动

```bash
cd backend
pip install -r requirements.txt
export GOLD_DB_PATH=$(pwd)/data/gold.db   # 可选
python -m uvicorn app.main:app --host 0.0.0.0 --port 8100
```

### Vue 前端启动 (本地)

```bash
cd frontend
npm install
npm run dev   # http://localhost:5173/gold-trading/ (代理 -> :8100)
```

### GitHub Pages 部署

推送 `main` 且改动 `frontend/` 即自动构建部署；访问时用 `?api=` 指定本机后端：
`https://<user>.github.io/gold-trading/?api=http://<本机IP>:8100`

### React Native 移动端

```bash
cd mobile
npm install
npx expo start
```

详细说明见各子目录 README。


## 快速开始

### 环境要求

- 操作系统: Windows / Linux / macOS
- Python: 3.10+
- 内存: 8GB+（建议 16GB）
- 存储: 10GB+（模型文件）

### 安装依赖

```bash
pip install -r requirements.txt
```

### 首次下载模型（可选）

```bash
python tools/download_models.py
```

### 启动全部服务

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Start-All.ps1
```

Linux/macOS:

```bash
./scripts/start_all.sh
```

### 查看状态与停止

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Status.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\Stop-All.ps1
```

Linux/macOS:

```bash
./scripts/status.sh
./scripts/stop_all.sh
```

## 端口说明（已支持动态分配）

脚本会优先使用默认端口，如果端口被占用会自动寻找可用端口。

- 默认端口:
  - Dashboard: 5000
  - API: 8080
  - WebSocket: 8765
  - Portal: 8090
- 运行时端口记录:
  - Windows: .service_ports.json
  - Linux/macOS: .service_ports.env

可选端口环境变量（启动前设置）:

- WS_PORT
- DASHBOARD_PORT
- API_PORT
- PORTAL_PORT

## 统一工作台与 AI 页面

静态门户启动：

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Start-Web.ps1
```

Linux/macOS:

```bash
./scripts/start_web.sh
```

访问地址使用状态脚本输出的 Portal 实际端口，例如：

- 统一工作台: http://127.0.0.1:PORTAL/
- AI Playground: http://127.0.0.1:PORTAL/ai_playground.html

## 主要 AI API

- GET /api/ai/capabilities
- POST /api/ai/chat
- POST /api/ai/tts
- POST /api/ai/asr
- POST /api/ai/vlm/image
- POST /api/ai/vlm/kline
- POST /api/ai/vlm/market
- POST /api/ai/image/brief
- GET /api/ai/artifacts/`<filename>`

## 公网访问（cpolar / NATAPP）

当运行在 Copaw/受限环境，无法直接暴露 localhost 时，可使用隧道工具。

启动前可配置以下变量（两组任选其一）：

- 通用变量：
   - PUBLIC_API_BASE
   - PUBLIC_DASHBOARD_BASE
- NATAPP 变量（脚本已支持）：
   - NATAPP_API_BASE
   - NATAPP_DASHBOARD_BASE

Windows 示例：

```powershell
$env:PUBLIC_API_BASE="https://xxx.cpolar.top"
$env:PUBLIC_DASHBOARD_BASE="https://yyy.cpolar.top"
powershell -ExecutionPolicy Bypass -File .\scripts\Start-All.ps1
```

Linux/macOS 示例：

```bash
export PUBLIC_API_BASE="https://xxx.cpolar.top"
export PUBLIC_DASHBOARD_BASE="https://yyy.cpolar.top"
./scripts/start_all.sh
```

NATAPP 示例（PowerShell）：

```powershell
$env:NATAPP_API_BASE="https://api-xxxxx.natappfree.cc"
$env:NATAPP_DASHBOARD_BASE="https://dash-xxxxx.natappfree.cc"
powershell -ExecutionPolicy Bypass -File .\scripts\Start-All.ps1
```

NATAPP 示例（Linux/macOS）：

```bash
export NATAPP_API_BASE="https://api-xxxxx.natappfree.cc"
export NATAPP_DASHBOARD_BASE="https://dash-xxxxx.natappfree.cc"
./scripts/start_all.sh
```

脚本会自动生成 web/runtime-config.js，将统一工作台指向公网地址。

## OpenClaw 生产模板

```bash
python ops/setup_openclaw.py --mode production --apply
```

模板文件：

- config/openclaw_cron.production.json

## 补充文档

- 脚本说明: scripts/README.md
- 生产运维 Skill: skills/gold-trading-production-ops/SKILL.md

## 常见问题

1. 端口占用导致启动失败
   - 先运行停止脚本，再重新启动；当前脚本已支持自动换端口。
2. Linux/macOS 执行脚本被拒绝
   - 运行 chmod +x ./scripts/*.sh。
3. Python 不在 PATH
   - Windows 确认 py/python，Linux/macOS 确认 python3/python。
4. cpolar/NATAPP 域名可打开但 API 调用失败
   - 重新检查 PUBLIC_API_BASE / PUBLIC_DASHBOARD_BASE 或 NATAPP_API_BASE / NATAPP_DASHBOARD_BASE，并重启服务。

## 致谢

- [OpenVINO](https://github.com/openvinotoolkit/openvino)
- [ModelScope](https://www.modelscope.cn/)
- [Qwen3](https://github.com/QwenLM/Qwen3)
