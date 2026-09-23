package com.jarvis.research.agent;

import com.jarvis.research.ai.DeterministicContext;
import com.jarvis.research.ai.KlineMetrics;
import com.jarvis.research.ai.RiskMetrics;
import com.jarvis.research.market.ExtendedMarketDataService;
import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.market.dto.DailyKlineDTO;
import com.jarvis.research.market.dto.KlineBarDTO;
import com.jarvis.research.market.dto.MinuteKlineDTO;
import com.jarvis.research.news.NewsDigest;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import com.jarvis.research.service.JdGoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 金融研究 Agent 的第一条真实工作流：行情快照 -> 日 K/指标 -> AI 汇总。
 * 工具均为只读操作，服务端生成上下文，浏览器不能伪造行情指标。
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final MarketDataService marketDataService;
    private final ExtendedMarketDataService extendedMarketDataService;
    private final JdGoldService jdGoldService;
    private final AiProxyService aiProxyService;
    private final AiRateLimitService aiRateLimitService;
    private final AgentToolRegistry toolRegistry;

    public void run(Long userId, String runId, String question, Consumer<AgentEvent> sink,
                    BooleanSupplier cancelled) {
        run(userId, runId, question, AgentResearchContext.DEFAULT, sink, cancelled);
    }

    public void run(Long userId, String runId, String question, AgentResearchContext researchContext,
                    Consumer<AgentEvent> sink, BooleanSupplier cancelled) {
        AgentResearchContext instrument = researchContext == null ? AgentResearchContext.DEFAULT : researchContext;
        try {
            checkCancelled(cancelled);
            emit(sink, AgentEvent.create(
                    "run_started", "running", "Agent 已开始执行研究工作流", null,
                    question, "已建立本次研究运行上下文", Map.of(
                            "workflow", "financial-research-v1", "instrument", instrument.toMap()),
                    Instant.now(), null, null, null));

            emit(sink, AgentEvent.create(
                    "plan_created", "completed", "研究计划已生成", null, question,
                    "新闻、财报、行情、K 线与指标、风险检查与模型汇总", Map.of(
                            "steps", List.of("个股资讯检索 / 市场新闻", "财报解析", "行情快照", "K 线与技术指标", "风险检查", "AI 研究结论"),
                            "readOnlyTools", toolRegistry.readOnlyTools(), "instrument", instrument.toMap()),
                    Instant.now(), Instant.now(), 0L, null));

            checkCancelled(cancelled);
            Map<String, Object> news = executeNewsTool(sink, instrument, cancelled);
            Map<String, Object> filing = executeFinancialReportTool(sink, question, userId, cancelled);
            Map<String, Object> prices = executeQuoteTool(sink, instrument, cancelled);

            checkCancelled(cancelled);
            Map<String, Object> kline = executeKlineTool(sink, instrument, cancelled);
            Map<String, Object> metrics = executeIndicatorTool(sink, instrument, kline, cancelled);
            Map<String, Object> risk = executeRiskTool(sink, instrument, kline, cancelled);

            checkCancelled(cancelled);
            executeSynthesis(userId, sink, question, instrument, news, filing, prices, kline, metrics, risk, cancelled);

            emit(sink, AgentEvent.create(
                    "run_completed", "completed", "研究工作流完成", null, null,
                    "已生成可继续追问的 Markdown 研究结论", Map.of("instrument", instrument.toMap()),
                    Instant.now(), Instant.now(), 0L, null));
        } catch (AgentCancelledException ignored) {
            // 取消事件由 AgentRunService 统一发布，避免重复发送 terminal event。
        } catch (Exception error) {
            String message = safeMessage(error);
            emit(sink, AgentEvent.create(
                    "run_failed", "failed", "研究工作流失败", null, null, message,
                    Map.of(), Instant.now(), Instant.now(), 0L, "AGENT_EXECUTION_FAILED"));
        }
    }

    private Map<String, Object> executeNewsTool(Consumer<AgentEvent> sink,
                                                AgentResearchContext instrument,
                                                BooleanSupplier cancelled) {
        Instant started = Instant.now();
        boolean stockResearch = instrument.isEquityMarket();
        String stepTitle = stockResearch ? "检索个股最新信息" : "读取市场新闻";
        String toolName = stockResearch ? "StockNewsSearchTool" : "MarketNewsTool";
        String query = stockResearch
                ? instrument.name() + " " + instrument.symbol()
                : "daily";
        String stepId = beginStep(sink, stepTitle, toolName, query, started);
        try {
            checkCancelled(cancelled);
            Map<String, Object> request = stockResearch
                    ? Map.of("query", query, "limit", 8, "instrument", instrument.toMap())
                    : Map.of("instrument", instrument.toMap());
            emitStepEvent(sink, AgentEvent.create("tool_call", "running", stepTitle, toolName,
                    query, stockResearch ? "按研究标的检索新闻和公告" : "读取已缓存的市场新闻摘要",
                    request, started, null, null, null), stepId);

            Map<String, Object> news;
            if (stockResearch) {
                try {
                    Map<String, Object> result = aiProxyService.post(
                            "/internal/research/stock-news", Map.of("query", query, "limit", 8));
                    if (result == null || !(result.get("items") instanceof List<?>)) {
                        throw new IllegalStateException("个股搜索服务返回格式不可用");
                    }
                    news = new LinkedHashMap<>(result);
                    news.putIfAbsent("available", true);
                } catch (Exception searchUnavailable) {
                    // Java 与 Python 可分批发布；旧版 Python 无此路由时仍可读取原 RSS digest。
                    Map<String, Object> raw = aiProxyService.post(
                            "/internal/rss/digest?refresh=false&force=false", Map.of());
                    news = NewsDigest.fromDigest(raw, 8);
                    news.put("provider", "rss_digest_compatibility_fallback");
                }
            } else {
                Map<String, Object> raw = aiProxyService.post(
                        "/internal/rss/digest?refresh=false&force=false", Map.of());
                news = NewsDigest.fromDigest(raw, 8);
                news.put("provider", "rss_digest");
            }
            Object rawItems = news.get("items");
            int count = rawItems instanceof List<?> items ? items.size() : 0;
            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("available", news.getOrDefault("available", false));
            resultPayload.put("provider", news.getOrDefault("provider", "unknown"));
            resultPayload.put("query", query);
            resultPayload.put("instrument", instrument.toMap());
            resultPayload.put("items", rawItems instanceof List<?> ? rawItems : List.of());
            resultPayload.put("generated_at", news.getOrDefault("generated_at", ""));
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", stepTitle + "完成", toolName,
                    query, "已取得 " + count + " 条相关资讯",
                    resultPayload,
                    started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, stepTitle + "步骤完成", toolName, "已完成新闻工具调用",
                    started, "completed", null);
            return news;
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, toolName, stepId, started, error);
            completeStep(sink, stepId, stepTitle + "步骤失败", toolName, safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            return NewsDigest.unavailable(NewsDigest.REASON_UNAVAILABLE);
        }
    }

    private Map<String, Object> executeFinancialReportTool(Consumer<AgentEvent> sink, String question,
                                                             Long userId, BooleanSupplier cancelled) {
        Instant started = Instant.now();
        String stepId = beginStep(sink, "解析财报材料", "FinancialReportTool", null, started);
        boolean hasFiling = isFinancialDocument(question);
        emitStepEvent(sink, AgentEvent.create("tool_call", hasFiling ? "running" : "completed",
                hasFiling ? "解析财报材料" : "财报工具待命", "FinancialReportTool", null,
                hasFiling ? "将用户提供的财报材料交给财务解析器" : "本轮未检测到财报原文，跳过额外解析",
                Map.of("available", hasFiling), started, hasFiling ? null : Instant.now(),
                hasFiling ? null : 0L, null), stepId);
        if (!hasFiling) {
            completeStep(sink, stepId, "财报步骤已跳过", "FinancialReportTool", "本轮未提供财报材料",
                    started, "completed", null);
            return Map.of("available", false, "reason", "no_filing_context");
        }
        try {
            checkCancelled(cancelled);
            Map<String, Object> response = aiProxyService.post("/api/ai/financial/report",
                    Map.of("content", question));
            aiRateLimitService.recordTokens(userId, response);
            String content = extractContent(response);
            Map<String, Object> filing = new LinkedHashMap<>();
            filing.put("available", true);
            filing.put("analysis", content);
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", "财报解析完成", "FinancialReportTool",
                    null, "已生成财报结构化摘要，原始模型文本仅在服务端传递给最终汇总步骤",
                    Map.of("available", true, "analysis_exposed", false),
                    started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, "财报步骤完成", "FinancialReportTool", "已生成财报结构化摘要",
                    started, "completed", null);
            return filing;
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, "FinancialReportTool", stepId, started, error);
            completeStep(sink, stepId, "财报步骤失败", "FinancialReportTool", safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            return Map.of("available", false, "reason", "financial_report_failed");
        }
    }

    private Map<String, Object> executeQuoteTool(Consumer<AgentEvent> sink,
                                                  AgentResearchContext instrument,
                                                  BooleanSupplier cancelled) {
        Instant started = Instant.now();
        String stepId = beginStep(sink, "读取实时行情", "MarketQuoteTool", instrument.key(), started);
        emitStepEvent(sink, AgentEvent.create("tool_call", "running", "读取实时行情", "MarketQuoteTool",
                instrument.key(), "正在读取服务端行情快照", Map.of("instrument", instrument.toMap()),
                started, null, null, null), stepId);
        try {
            checkCancelled(cancelled);
            Map<String, Object> quote;
            if (instrument.isCoreGoldMarket()) {
                Object value = marketDataService.getLatestPrices().get(instrument.market());
                quote = value instanceof Map<?, ?> map ? stringKeyMap(map)
                        : unavailable("quote_unavailable", instrument);
            } else if (instrument.isJdGoldMarket()) {
                quote = jdGoldService.latestQuote(instrument.jdGoldSourceSymbol());
            } else if (instrument.isSgeGoldMarket()) {
                quote = extendedMarketDataService.sgeGoldQuote();
            } else if (instrument.isExtendedMarket()) {
                quote = extendedMarketDataService.quote(instrument.market(), instrument.symbol());
            } else {
                quote = unavailable("unsupported_quote_market", instrument);
            }
            quote = new LinkedHashMap<>(quote);
            quote.putIfAbsent("market", instrument.market());
            quote.putIfAbsent("symbol", instrument.symbol());
            quote.putIfAbsent("name", instrument.name());
            boolean available = hasUsablePrice(quote) && !Boolean.FALSE.equals(quote.get("available"));
            quote.put("available", available);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("instrument", instrument.toMap());
            summary.put("available", available);
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", "行情读取完成", "MarketQuoteTool",
                    instrument.key(), available ? "已取得所选研究对象的服务端行情快照" : "所选研究对象暂无可用报价",
                    summary,
                    started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, "行情步骤完成", "MarketQuoteTool",
                    available ? "已取得服务端行情快照" : "报价不可用，后续分析必须披露此数据缺口",
                    started, "completed", null);
            return quote;
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, "MarketQuoteTool", stepId, started, error);
            completeStep(sink, stepId, "行情步骤失败", "MarketQuoteTool", safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            return unavailable("quote_failed", instrument);
        }
    }

    private Map<String, Object> executeKlineTool(Consumer<AgentEvent> sink,
                                                  AgentResearchContext instrument,
                                                  BooleanSupplier cancelled) {
        Instant started = Instant.now();
        boolean minuteKline = instrument.isJdGoldMarket();
        String interval = minuteKline ? "60m" : "1d";
        String stepTitle = minuteKline ? "读取 60 分钟 K 线" : "读取日 K 线";
        String stepId = beginStep(sink, stepTitle, "MarketKlineTool", instrument.key() + ":60", started);
        emitStepEvent(sink, AgentEvent.create("tool_call", "running", stepTitle, "MarketKlineTool",
                instrument.key() + ":60", "正在读取最近 60 根" + (minuteKline ? "60 分钟" : "日") + "K",
                Map.of("instrument", instrument.toMap(), "interval", interval),
                started, null, null, null), stepId);
        try {
            checkCancelled(cancelled);
            Map<String, Object> kline;
            if (instrument.isCoreGoldMarket()) {
                DailyKlineDTO dto = marketDataService.getDailyKline(instrument.market(), 60);
                kline = klineToMap(dto);
                kline.put("symbol", instrument.symbol());
            } else if (minuteKline) {
                MinuteKlineDTO dto = marketDataService.getMinuteKline(instrument.jdGoldSourceSymbol(), 60, 60);
                kline = minuteKlineToMap(dto);
            } else if (instrument.supportsExtendedKline()) {
                kline = extendedMarketDataService.kline(instrument.market(), instrument.symbol(), interval, 60);
            } else if (instrument.isSgeGoldMarket()) {
                kline = unavailable("historical_kline_unavailable", instrument);
            } else {
                kline = unavailable("unsupported_kline_market", instrument);
            }
            kline.putIfAbsent("market", instrument.market());
            kline.putIfAbsent("symbol", instrument.symbol());
            kline.putIfAbsent("name", instrument.name());
            kline.putIfAbsent("available", positiveCount(kline.get("count")));
            int count = kline.get("count") instanceof Number number ? number.intValue() : 0;
            boolean available = Boolean.TRUE.equals(kline.get("available"));
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", stepTitle + "读取完成", "MarketKlineTool",
                    instrument.key() + ":60", available ? "已取得 " + count + " 根" + (minuteKline ? "60 分钟" : "日") + "K"
                            : "该标的暂无可用" + (minuteKline ? "分钟" : "日") + "K历史数据",
                    Map.of("instrument", instrument.toMap(), "interval", interval,
                            "count", count, "available", available),
                    started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, stepTitle + "步骤完成", "MarketKlineTool",
                    available ? "已取得服务端K线数据" : "K线数据不可用，后续分析必须披露此数据缺口",
                    started, "completed", null);
            return kline;
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, "MarketKlineTool", stepId, started, error);
            completeStep(sink, stepId, "日 K 线步骤失败", "MarketKlineTool", safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            return unavailable("kline_failed", instrument);
        }
    }

    private Map<String, Object> executeIndicatorTool(Consumer<AgentEvent> sink,
                                                       AgentResearchContext instrument,
                                                       Map<String, Object> kline,
                                                       BooleanSupplier cancelled) {
        Instant started = Instant.now();
        String stepId = beginStep(sink, "计算技术指标", "TechnicalIndicatorTool", instrument.key(), started);
        emitStepEvent(sink, AgentEvent.create("tool_call", "running", "计算技术指标", "TechnicalIndicatorTool",
                instrument.key(), "计算 SMA、EMA、RSI 与支撑阻力", Map.of("instrument", instrument.toMap()),
                started, null, null, null), stepId);
        try {
            checkCancelled(cancelled);
            Map<String, Object> metrics = KlineMetrics.compute(kline);
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", "技术指标计算完成", "TechnicalIndicatorTool",
                    instrument.key(), "指标已准备给研究模型引用", metrics,
                    started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, "指标步骤完成", "TechnicalIndicatorTool", "指标已准备给研究模型引用",
                    started, "completed", null);
            return metrics;
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, "TechnicalIndicatorTool", stepId, started, error);
            completeStep(sink, stepId, "指标步骤失败", "TechnicalIndicatorTool", safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            return Map.of("available", false, "reason", "indicator_failed");
        }
    }

    private Map<String, Object> executeRiskTool(Consumer<AgentEvent> sink,
                                                 AgentResearchContext instrument,
                                                 Map<String, Object> kline,
                                                 BooleanSupplier cancelled) {
        Instant started = Instant.now();
        String stepId = beginStep(sink, "检查历史风险", "RiskCheckTool", instrument.key(), started);
        emitStepEvent(sink, AgentEvent.create("tool_call", "running", "检查历史风险", "RiskCheckTool",
                instrument.key(), "根据服务端K线计算 VaR、ES、波动率与最大回撤",
                Map.of("instrument", instrument.toMap()), started, null, null, null), stepId);
        try {
            checkCancelled(cancelled);
            List<Object> closes = new ArrayList<>();
            Object rows = kline.get("data");
            if (rows instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> map && map.get("close") != null) closes.add(map.get("close"));
                }
            }
            Map<String, Object> risk = RiskMetrics.compute(closes, 0.95, null, instrument.key()).toMap();
            boolean available = Boolean.TRUE.equals(risk.get("available"));
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", "历史风险检查完成", "RiskCheckTool",
                    instrument.key(), available ? "已取得 VaR、ES、波动率与回撤" : "风险样本不足，已返回降级结果",
                    risk, started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, "风险步骤完成", "RiskCheckTool", "已完成风险检查",
                    started, "completed", null);
            return risk;
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, "RiskCheckTool", stepId, started, error);
            completeStep(sink, stepId, "风险步骤失败", "RiskCheckTool", safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            return Map.of("available", false, "reason", "risk_check_failed");
        }
    }

    private void executeSynthesis(Long userId, Consumer<AgentEvent> sink, String question,
                                  AgentResearchContext instrument,
                                  Map<String, Object> news, Map<String, Object> filing,
                                  Map<String, Object> prices, Map<String, Object> kline,
                                  Map<String, Object> metrics, Map<String, Object> risk,
                                  BooleanSupplier cancelled) {
        Instant started = Instant.now();
        String stepId = beginStep(sink, "生成研究结论", "ResearchSynthesisTool", question, started);
        emitStepEvent(sink, AgentEvent.create("tool_call", "running", "生成研究结论", "ResearchSynthesisTool",
                question, "将服务端行情与指标交给 AI 生成结论", Map.of(), started, null, null, null), stepId);
        try {
            checkCancelled(cancelled);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("generated_at", Instant.now().toString());
            context.put("instrument", instrument.toMap());
            context.put("prices", Map.of(instrument.key(), prices));
            context.put("news", news);
            context.put("filing", filing);
            context.put("klines", Map.of(instrument.key(), kline));
            context.put("metrics", DeterministicContext.compute(context));
            context.put("risk", risk);

            Map<String, Object> body = new LinkedHashMap<>();
            String groundedQuestion = "当前研究对象是 " + instrument.name() + "（" + instrument.symbol()
                    + "，市场 " + instrument.market() + "）。回答必须明确写出该名称或代码；"
                    + "不得改答黄金、指数或其他标的。"
                    + "只分析 research_context.instrument 指定的单一研究对象；"
                    + "必须严格区分工具返回的 available 与数据缺口；报价、K线、指标或风险字段缺失/不可用时，"
                    + "不得编造当前价格、历史走势或指标数值，须明确说明缺失项并仅给出有来源依据的定性分析。"
                    + "\n\n用户问题：\n" + question;
            // Python API only accepts client-supplied user/assistant roles. Trusted system
            // instructions are injected by the Python service from research_context.
            body.put("messages", List.of(Map.of("role", "user", "content", groundedQuestion)));
            body.put("research_context", context);
            body.put("metrics", metrics);
            String safetyStepId = java.util.UUID.randomUUID().toString();
            emitStepEvent(sink, AgentEvent.create("safety_review", "running", "输出安全审查待执行", "OutputSafetyReviewer",
                    null, "候选结论将在服务端完成独立安全审查后再释放",
                    Map.of("safety", Map.of("status", "reviewing")), started, null, null, null), safetyStepId);
            Map<String, Object> response = aiProxyService.post("/api/ai/chat", body);
            aiRateLimitService.recordTokens(userId, response);
            String content = extractContent(response);
            Map<String, Object> safety = extractSafety(response);
            String safetyStatus = String.valueOf(safety.getOrDefault("status", "legacy_unreviewed"));
            if (!"retracted".equals(safetyStatus) && !instrument.matches(content)) {
                Map<String, Object> mismatch = new LinkedHashMap<>();
                mismatch.put("status", "retracted");
                mismatch.put("risk", "entity_mismatch");
                mismatch.put("reason_code", "research_context_mismatch");
                mismatch.put("reason", "候选结论未明确绑定所选研究对象");
                mismatch.put("instrument", instrument.toMap());
                safety = mismatch;
                safetyStatus = "retracted";
                content = "研究结论未能明确绑定当前研究对象 " + instrument.name()
                        + "（" + instrument.symbol() + "），已撤回。请重试。";
            }
            boolean retracted = "retracted".equals(safetyStatus);
            String safetyTitle = switch (safetyStatus) {
                case "approved" -> "输出安全审查通过";
                case "retracted" -> "输出已被安全审查撤回";
                case "review_error_open" -> "输出审查异常，已按配置降级放行";
                case "disabled" -> "输出安全审查未启用";
                default -> "兼容响应未携带安全审查元数据";
            };
            emitStepEvent(sink, AgentEvent.create("safety_review", retracted ? "retracted" : "completed",
                    safetyTitle, "OutputSafetyReviewer", null,
                    retracted ? "候选结论未越过服务端安全边界" : "候选结论安全状态已确认",
                    Map.of("safety", safety), started, Instant.now(), elapsed(started),
                    retracted ? "AI_OUTPUT_RETRACTED" : null), safetyStepId);
            if (retracted) {
                emitStepEvent(sink, AgentEvent.create("assistant_retracted", "retracted", "研究结论已撤回",
                        "OutputSafetyReviewer", null, "危险候选正文未下发，仅返回安全替代文案",
                        Map.of("content", content, "safety", safety), started,
                        Instant.now(), elapsed(started), "AI_OUTPUT_RETRACTED"), safetyStepId);
            } else {
                emitStepEvent(sink, AgentEvent.create("assistant_delta", "completed", "研究结论已生成", "ResearchSynthesisTool",
                        null, "Markdown 结论已通过输出边界", Map.of("content", content, "safety", safety), started,
                        Instant.now(), elapsed(started), null), stepId);
            }
            emitStepEvent(sink, AgentEvent.create("tool_result", "completed", "研究汇总完成", "ResearchSynthesisTool",
                    question, retracted ? "模型候选结论已被输出安全审查撤回" : "模型已引用服务端研究上下文并通过输出边界",
                    Map.of(), started, Instant.now(), elapsed(started), null), stepId);
            completeStep(sink, stepId, "研究结论步骤完成", "ResearchSynthesisTool",
                    retracted ? "候选结论已安全撤回" : "模型结论已通过输出边界",
                    started, "completed", null);
        } catch (AgentCancelledException cancelledException) {
            throw cancelledException;
        } catch (Exception error) {
            emitToolFailure(sink, "ResearchSynthesisTool", stepId, started, error);
            completeStep(sink, stepId, "研究结论步骤失败", "ResearchSynthesisTool", safeMessage(error),
                    started, "failed", "TOOL_FAILED");
            throw error;
        }
    }

    private static Map<String, Object> klineToMap(DailyKlineDTO dto) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dto == null) return result;
        result.put("market", dto.market());
        result.put("range", dto.range());
        result.put("as_of", dto.asOf());
        result.put("count", dto.count());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (dto.data() != null) {
            for (KlineBarDTO bar : dto.data()) {
                if (bar == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", bar.date());
                row.put("open", bar.open());
                row.put("close", bar.close());
                row.put("high", bar.high());
                row.put("low", bar.low());
                row.put("volume", bar.volume());
                rows.add(row);
            }
        }
        result.put("data", rows);
        return result;
    }

    private static Map<String, Object> minuteKlineToMap(MinuteKlineDTO dto) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dto == null) return result;
        result.put("market", dto.market());
        result.put("interval", dto.interval());
        result.put("count", dto.count());
        result.put("period_type", "intraday");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (dto.data() != null) {
            for (KlineBarDTO bar : dto.data()) {
                if (bar == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", bar.date());
                row.put("open", bar.open());
                row.put("close", bar.close());
                row.put("high", bar.high());
                row.put("low", bar.low());
                row.put("volume", bar.volume());
                rows.add(row);
            }
        }
        result.put("data", rows);
        result.put("available", !rows.isEmpty());
        return result;
    }

    private static boolean hasUsablePrice(Map<String, Object> quote) {
        Object value = quote.get("price");
        return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() > 0;
    }

    private static boolean positiveCount(Object value) {
        return value instanceof Number number && number.intValue() > 0;
    }

    private static String extractContent(Map<String, Object> response) {
        if (response == null) return "（模型未返回内容）";
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map) {
            Object nested = map.get("content");
            if (nested != null) return String.valueOf(nested);
            Object message = map.get("message");
            if (message instanceof Map<?, ?> messageMap && messageMap.get("content") != null) {
                return String.valueOf(messageMap.get("content"));
            }
        }
        Object content = response.get("content");
        return content == null ? "（模型未返回内容）" : String.valueOf(content);
    }

    private static Map<String, Object> extractSafety(Map<String, Object> response) {
        if (response != null) {
            Object data = response.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                Object safety = dataMap.get("safety");
                if (safety instanceof Map<?, ?> safetyMap) return stringKeyMap(safetyMap);
            }
            Object safety = response.get("safety");
            if (safety instanceof Map<?, ?> safetyMap) return stringKeyMap(safetyMap);
        }
        return Map.of(
                "status", "legacy_unreviewed",
                "risk", "unknown",
                "reason_code", "missing_safety_metadata"
        );
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) out.put(String.valueOf(key), value);
        });
        return out;
    }

    private static Map<String, Object> unavailable(String reason, AgentResearchContext instrument) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("reason", reason);
        out.put("market", instrument.market());
        out.put("symbol", instrument.symbol());
        out.put("name", instrument.name());
        out.put("data", List.of());
        out.put("count", 0);
        return out;
    }

    private static boolean isFinancialDocument(String question) {
        if (question == null || question.isBlank()) return false;
        if (question.length() >= 300) return true;
        return question.contains("财报") || question.contains("年报") || question.contains("季报")
                || question.contains("营收") || question.contains("现金流") || question.contains("净利润")
                || question.contains("10-K") || question.contains("10-Q");
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String beginStep(Consumer<AgentEvent> sink, String title, String tool,
                                    String inputSummary, Instant started) {
        AgentEvent event = AgentEvent.create("step_started", "running", title, tool,
                inputSummary, "步骤已开始", Map.of(), started, null, null, null);
        emit(sink, event);
        return event.stepId();
    }

    private static void emitStepEvent(Consumer<AgentEvent> sink, AgentEvent event, String stepId) {
        emit(sink, event.withStep(stepId));
    }

    private static void completeStep(Consumer<AgentEvent> sink, String stepId,
                                     String title, String tool, String outputSummary,
                                     Instant started, String status, String errorCode) {
        emitStepEvent(sink, AgentEvent.create("step_completed", status, title, tool,
                null, outputSummary, Map.of(), started, Instant.now(), elapsed(started), errorCode), stepId);
    }

    private static void emitToolFailure(Consumer<AgentEvent> sink, String tool, String stepId,
                                        Instant started, Exception error) {
        emitStepEvent(sink, AgentEvent.create("tool_result", "failed", tool + " 执行失败", tool,
                null, safeMessage(error), Map.of(), started, Instant.now(), elapsed(started), "TOOL_FAILED"),
                stepId);
    }

    private static long elapsed(Instant started) {
        return Math.max(0L, Duration.between(started, Instant.now()).toMillis());
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "未知错误" : error.getMessage();
        if (message == null || message.isBlank()) return "执行失败，请稍后重试";
        String sanitized = message.replaceAll("(?i)(authorization|token|api[-_]?key|cookie)\\s*[:=]\\s*\\S+", "$1=[已隐藏]");
        return sanitized.length() > 240 ? sanitized.substring(0, 240) : sanitized;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new AgentCancelledException();
        }
    }

    private static void emit(Consumer<AgentEvent> sink, AgentEvent event) {
        sink.accept(event);
    }

    private static final class AgentCancelledException extends RuntimeException {
    }
}
