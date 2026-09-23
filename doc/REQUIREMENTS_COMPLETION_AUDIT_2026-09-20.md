# JARVIS 需求完成度审计（2026-09-23）

本次审计基于 `doc/01_JARVIS金融投研平台_Software Requirement Specification_V1.0.md`、
`specs/financial-agent-workflow/`、`specs/market-chart-financial-import/` 以及部署文档。
文档中的视觉稿人工检查项与产品功能验收项分开统计，避免把视觉草图的 checklist 当成后端功能缺陷。

## 2026-09-23 补充审计

- FR-14 管理员账户管理补齐“重置登录状态”：管理员可提交必填原因撤销目标账户所有已签发 JWT；服务端递增 `credential_version`，之后旧 JWT 会被认证过滤器拒绝，且密码不变。管理员不能撤销自己的会话。
- 管理员账户启停、角色、个人/用户组配额与功能权限、用户组资料/成员/删除操作现在均校验必填操作原因；审计详情记录变更前后摘要。组名/描述、功能和成员摘要均有长度/数量边界。
- 修复目标用户审计页漏项：除了展示该用户作为操作者的事件，也会展示 `target=user:<id>` 指向该用户的管理员操作；分页上限、去重、时间倒序均保留。
- 验证：Java 全量 Maven 测试 631 tests / 0 failures / 0 errors / 5 skipped；新增 H2 持久化集成测试覆盖旧 JWT 在撤销前后有效性、凭证版本落库、审计落库及目标用户审计可见性。前端 `npm run test:p0` 为 204 passed，`npm run build` 通过；E2E 类型检查通过，管理员工作区 Mock 浏览器用例 2/2 通过；Python 测试 265 passed。
- GitHub PR#25（`fix(agent): preserve Java-Python chat role contract`）已于 2026-09-23 合并为 `afa1f8e`；确认 Gitee `main` 原提交 `fc9a965` 是其祖先后，已快进同步 Gitee `main` 至 `afa1f8e`。当前管理端改动分支也已基于该共同基线。
- 2026-09-23 紧急修复生产 Java→Python 聊天消息角色契约：旧服务把 `role=system` 作为客户端消息发送，违反 Python 仅接受 `user/assistant` 的 schema。PR#25 已让 Java 只发送 `user` 消息，并由 Python 基于可信 `research_context` 注入系统约束；生产原子发布为 `20260923-afa1f8e-agent-fix`（提交 `afa1f8e`），旧 release `20260922-pr24-fc9a965` 保留用于回滚。本次无数据库迁移，Flyway schema 不变。
- 该生产发布的 Java/Python readiness、公开 smoke（含行情、SSE、回测）通过；随后使用低权限 smoke 账号执行 `CHECK_AGENT_STREAM=1`，真实 Agent SSE 与 PostgreSQL 事件回放均通过，覆盖本次受影响链路。仅后端有改动，前端无需发布。
- 管理端会话撤销/审计实现已通过 GitHub PR#26 合并，合并提交 `7ef809f`；但尚未部署生产。Gitee PR#20 对应变更仍待指定测试人 `mc_shengxia` 接受，因此不绕过该门禁发布管理端改动。真实管理员 OAuth/审计浏览器验收仍缺生产管理员凭据，不以 Mock E2E 代替。
- GitHub 当前无开放 PR。Gitee PR#19（修正旧日报任务编辑时 `analyze` 默认行为及相应文档/验收用例）代码差异已审查，暂未发现需返修问题；PR#19、PR#20 均无冲突，但指定测试人 `mc_shengxia` 尚未接受，平台 `can_merge_check=false`，暂不能合并。不能把未完成的测试人验收当作合并授权。

## 本次已补齐

- 管理员用户目录返回最近登录时间、GitHub/OAuth 登录来源和登录名。
- 增加管理员按用户查看审计事件的 API 与前端审计面板。
- 登录和 GitHub OAuth 成功后记录 `users.last_login_at`，新增 Flyway V13 迁移。
- 首页深度行情接入 Java 统一技术指标摘要：SMA5、SMA20、EMA12、RSI14、20 根支撑位和压力位。
- 回测返回夏普比率、已闭合交易胜率、盈亏比、平均持仓天数、逐点回撤曲线；前端增加指标卡和回撤图。
- 财报解析返回 `financial-report-v1` 结构化 JSON，固定包含核心结论、营收利润、盈利质量、资产负债、现金流、风险点、投资观点和待核验事项；缺失小节会标记 `needs_review`，不伪造数据。
- 前端财报页展示结构化摘要，并保留原始 Markdown 渲染结果。
- Agent 运行服务补齐 pending/running 孤儿运行恢复、终态运行内存清理、SSE 重连终态竞态保护和超大 payload 有界降级；新增生命周期/恢复边界回归测试。每个真实工具步骤现在按同一 `stepId` 发出 `step_started → tool_call → tool_result → step_completed`，并保留开始/结束时间与耗时，前端 Trace 已增加生命周期标签。
- 管理员按用户的额度、权限和审计能力已完成；新增 V14 用户组、成员关系、组级 AI 配额与组级功能权限，组策略作为无用户级覆盖时的共享默认策略，并提供管理员 API、审计事件和前端管理工作区。
- 流式 AI 请求会请求上游返回 usage chunk，并由 Java 解析 `total_tokens` 后计入用户或用户组月度 Token 配额；未返回 usage 的兼容上游仍保持响应可用。
- RSS 信息中心已补齐：Java/PostgreSQL 持久化 10 个预置来源、管理员新增/编辑/启停/可信度维护、用户来源/主题订阅和服务端订阅过滤；Python 抓取结果补充正文片段、来源分类、标签和可解释的规则影响方向，前端新增 RSS 资讯工作区与管理后台来源面板。
- RSS AI 分析闭环已补齐：Java 统一鉴权/配额/usage，Python 返回有界且不含思维链的摘要、关键词、情绪、风险等级、影响方向和关联市场；日报任务支持 `analyze` 参数并把分析写入 PostgreSQL 执行产物，模型失败时保留原 RSS/规则结果；中高风险资讯通过站内通知提醒，前端可跳转多市场。已在生产发布并通过每日资讯接口验收。
- RSS 抓取链路已补齐外部网络超时与并行抓取：单源连接/读取超时不会阻塞整条资讯接口，生产刷新新闻已从烟测超时恢复为通过。
- RSS 全局缓存自动刷新器已补齐：Java 服务启动后首次刷新，之后默认每 24 小时刷新一次；不绑定用户、不创建任务、不消耗用户 AI 配额。用户 DAILY_DIGEST 任务仍负责个性化执行历史与重要资讯通知。
- 测试 Agent 已补齐：`tools/test-agent/test-agent.mjs` 可读取 PRD 生成结构化验收用例，支持调用配置中的任意 Playwright project；使用真实生产账号执行 `--run --project agent` 通过，3 条 Agent 浏览器用例无失败并输出脱敏报告。
- 测试 Agent 的需求映射已从仅覆盖登录/财报/视觉的宽泛启发式扩展为 Agent、定时任务、权限、资讯/风险、文件识别等显式 Playwright project 映射，并去重映射结果。
- 品牌图标已补齐同源 `32/64/192/512px` PNG，接入 favicon、Apple Touch Icon 与 PWA manifest。
- GitHub PR#21（资讯中心排序、质量指标、来源可信度、订阅交互、检索/密度/无障碍与动效）已完成审核并合并；合并提交为 `fbc6271`，全部 CI 通过。
- Grafana 服务健康面板和 Java 5xx/Hikari/目标存活告警模板已在生产导入；Prometheus、Grafana 已运行，Java 目标为 `up`，9 条行情规则、3 条运行时规则和 2 条模拟盘规则已加载，2 个 JARVIS dashboard 已可查询。

## 自动化验证结果

- Java：全量 Maven 测试通过，611 tests / 0 failures / 0 errors / 5 skipped；包含 RSS AI 分析/日报产物/重要资讯提醒测试，以及既有 Flyway/Hibernate schema contract、用户组配额/权限继承、回测、交易回滚故障注入、PostgreSQL 锁策略、Agent 工具步骤生命周期、运行恢复、SSE 断线取消竞态等测试。PostgreSQL 未配置时仅保留既有跳过项。
- Python：`PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest -q`，278 passed。普通 `pytest` 仍受本机 `pytest-asyncio` 与当前 pytest 版本兼容问题影响，代码测试本身不受影响。
- 前端：`npm run test:p0`，200 passed；`npm run build` 通过。
- 浏览器：Playwright `financial-import` 项目通过，验证财报 Markdown 文件导入替换/追加、失败导入保留原文，以及选择文件不会提前请求分析接口；预览模式下夜间多市场图表和财报输入面板也已实际检查。2026-09-21 使用真实生产账号执行 Agent 专项浏览器用例 3 条全部通过：真实 SSE 的工具生命周期、Markdown 结论、历史运行抽屉，以及停止状态和失败提示回归。
- 测试 Agent：`node tools/test-agent/test-agent.mjs --run --project smoke` 在本地 managed Vite 环境通过，6 条 smoke 浏览器用例无失败；未把 smoke 结果冒充真实登录、真实 AI 或生产验收。

## Gitee PR 审核（2026-09-21）

- Gitee 当前没有 open PR；最新已合并记录为 PR#16（`feat: add ops report, uptime and account overview panels`）、PR#18（定时任务权限 UI）和 PR#17（定时任务 E2E）。本轮通过 Gitee MCP 复核开放列表为空，没有可再次修改或合并的待审 PR。
- GitHub PR#21（`feat(news-ui): refine news intelligence interactions`）已审核并合并到 `main`，CI 的 Frontend、Java、Python、Flyway/Prod Startup、Deploy Scripts 均通过。
- Agent 生命周期修复提交 `0ffc751`、生产烟测门禁提交 `f344f0d`、前端取消状态与浏览器验收提交 `3b35893` 已同步到 Gitee `main` 和 GitHub `main`；本次复核仍没有 open PR。后续新增 PR 仍应先做 diff/测试审查，再合并到 `main`。

## 生产发布验收（2026-09-21 更新）

- GitHub Pages 已发布前端；本轮 `Deploy Frontend to GitHub Pages` 成功（run `35624275984`），发布后的 `https://f.shengxia.me/version.json` 已复核为 `f0ffa0b`；四种 favicon PNG 与 `manifest.webmanifest` 均可正常返回。
- 后端已通过原子发布切换到 `20260921-235536-f0ffa0bd66fe`；旧版本 `20260921-230800-84a6feaec689` 保留用于回滚。发布包 SHA-256 校验通过，Java/Python/前端构建均通过；监控栈在 release 外独立运行。
- 远端 `jarvis-ai.service`、`jarvis-java.service`、`postgresql` 均为 active；Java readiness、Python 内部 token readiness、Flyway v17 和公网 Java readiness 均返回成功。
- 公网数据库健康接口返回 401（该接口受认证保护），属于预期安全行为。
- 使用真实生产烟测账号完成：登录、数据库详情、行情、1Hz 行情 SSE、日 K、模拟盘、AI capabilities、Agent SSE、Agent PostgreSQL 事件回放、可复现回测和退出登录均通过；Agent SSE 的代理连接关闭码已按事件终态校验处理，不影响业务事件完整性。
- 2026-09-21 05:35 运行升级后的 `CHECK_AGENT_STREAM=1 CHECK_AGENT_RECOVERY=1` 专项烟测：除 Agent 真实工作流、`tool_call`、PostgreSQL 事件回放、断线取消/重订阅和回测登出外，烟测脚本还强制验证每个运行中工具步骤的 `step_started → tool_call → tool_result → step_completed` 顺序、共享 `stepId` 与终态事件；全部通过，此前可复现的断线取消 409 已不再出现。
- 使用真实生产会话对 `/api/news/analyze` 提交一条资讯联调通过，返回 `code=200`、1 条结构化分析并带有模型标识；未输出模型正文或任何凭据。
- 使用生产 smoke 账号执行 Agent SSE 后复核 `ai_quota`：月度 Token usage 从 `20218` 增加到 `20926`（+708），确认本次上游 usage 事件已由 Java 计入 PostgreSQL；日请求计数按新周期重置为 1。
- 使用生产 smoke 账号执行 `CHECK_RSS_NOTIFICATION=1`：临时 `DAILY_DIGEST(analyze=true)` 任务创建、立即执行、执行历史落库、站内通知读取和任务清理均通过；本次抓取没有 medium/high 风险资讯，因此没有触发 `NEWS_ALERT`，未将“没有命中”误报为通知成功。
- 使用真实生产账号执行 Agent 浏览器验收 3/3 通过：真实 SSE/工具生命周期/Markdown 结论/历史运行通过；受控长连接验证 `run_cancelled → STOPPED` 和 `run_failed → FAILED`，生产实际取消接口与失败事件由后端专项烟测覆盖。
- 远端监控已启用 Prometheus/Grafana Docker Compose：Prometheus 监听本机 `127.0.0.1:9090`，Grafana 监听本机 `127.0.0.1:3000`，Java Actuator 监听 `127.0.0.1:8201`；目标、告警组和 2 个 JARVIS dashboard 均已通过 HTTP API 复核。当前已有 stale quote / 风险跳过等真实指标告警状态，需继续按业务窗口观察，不把告警状态本身误判为代码故障。

## 仍未完成或需要真实环境验收

### P0 发布门禁

1. Agent Run 的真实 SSE、JWT、工具调用、步骤生命周期、主动取消、客户端断线后的重新订阅以及 PostgreSQL 事件回放已通过生产专项烟测；真实生产浏览器的 Trace、Markdown、历史运行、停止状态和失败提示均已通过。后端实际取消/失败与前端状态分别有烟测和浏览器证据。
2. 首页技术指标、回测高级指标和财报结构化返回已用真实登录会话完成 API 级生产联调；管理员 OAuth/审计查询仍缺真实管理员凭据下的端到端验收。
3. Agent 中心的真实 Trace、停止状态、失败提示和 Markdown 结论已有真实登录浏览器验收；不再列为未完成项。

### P1/P2

- 流式 AI 响应的精确月度 Token usage 已在本次生产 Agent smoke 中观察到真实 usage 事件并完成计入；后续仍需持续监控不同上游模型的兼容性。
- PRD V1.2 的 RSS 信息源管理、10 源配置、用户订阅、AI 分析、重要事件提醒和多市场入口已发布；全局每日自动刷新已由 Java 调度器负责。生产日报执行链已通过 smoke，但当前业务窗口没有 medium/high 资讯，`NEWS_ALERT` 实际触发仍需等待真实高/中风险资讯或用户验收窗口。
- 测试 Agent 已支持真实账号下的 Agent 自动编排并执行通过；当前已补齐主要业务域的显式 project 映射，后续新增 PRD 仍需补充对应浏览器用例与映射规则。
- Grafana/Prometheus 的仓库模板已补齐并完成生产导入；仍需在真实业务流量下继续验证 5xx、429/502、Hikari、行情源熔断等告警是否按预期 firing/恢复。
- 视觉规范文档中关于档案海景深、玻璃层次、长时间循环、移动端逐页像素审阅的 checklist 仍属于人工设计验收，不能用单元测试代替。
- 真实用户 Edge 的 GPU/无障碍最终人工验收仍需产品确认。

## 结论

核心业务功能已持续补齐，RSS AI/资讯链路和 Agent 的真实工具调用、取消、断线重订阅成功流已发布并完成生产烟测，Agent 的真实登录浏览器成功/失败/停止流也已验收；当前仍不能宣称“全部需求已生产验收完成”。剩余工作集中在管理员 OAuth/审计完整验收、RSS 通知与 usage 的真实业务触发、监控告警在真实业务流量下的 firing/恢复验证以及视觉人工确认，不应通过伪造数据或跳过认证来标记完成。
