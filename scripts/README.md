# Legacy / Local Development Scripts

> 这些脚本属于旧版本地开发栈，会启动 dashboard / websocket / 旧 Python API 等历史组件。
> **生产环境不要使用本目录脚本。** 当前生产架构只运行 Java 主后端 + Python AI 服务，统一使用 `deploy/` 下的 systemd / release 脚本。

This folder is retained only for legacy/local workflows.

## Core Flow

- Start all: `powershell -ExecutionPolicy Bypass -File .\\scripts\\Start-All.ps1`
- Status: `powershell -ExecutionPolicy Bypass -File .\\scripts\\Status.ps1`
- Stop all: `powershell -ExecutionPolicy Bypass -File .\\scripts\\Stop-All.ps1`

## Component Control

- Dashboard stack: `Start-Dashboard.ps1` / `Stop-Dashboard.ps1`
- Monitor service: `Start-Service.ps1` / `Stop-Service.ps1`
- Static portal: `Start-Web.ps1` / `Stop-Web.ps1`

## Shell Scripts (Moved Here)

- Linux/macOS start all: `./scripts/start_all.sh`
- Linux/macOS status: `./scripts/status.sh`
- Linux/macOS stop all: `./scripts/stop_all.sh`
- Dashboard only: `./scripts/start_dashboard.sh` / `./scripts/stop_dashboard.sh`
- Service only: `./scripts/start_service.sh` / `./scripts/stop_service.sh`
- Static web only: `./scripts/start_web.sh` / `./scripts/stop_web.sh`

## Notes

- PID files are written to the project root (`.ws_pid`, `.web_pid`, `.api_pid`, `.service_pid`, `.portal_pid`).
- Logs are written to `logs/`.
- Runtime ports are persisted in `.service_ports.json` (PowerShell) or `.service_ports.env` (shell).
- Optional env vars for public access (cpolar or NATAPP):
  - Preferred generic vars: `PUBLIC_API_BASE`, `PUBLIC_DASHBOARD_BASE`
  - NATAPP-specific vars (fallback): `NATAPP_API_BASE`, `NATAPP_DASHBOARD_BASE`
- Optional preferred local ports: `WS_PORT`, `DASHBOARD_PORT`, `API_PORT`, `PORTAL_PORT` (scripts auto-fallback to free ports if occupied).
- If your environment blocks script execution, run once as admin:
  `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`
