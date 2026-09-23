package com.jarvis.research.agent;

import org.springframework.stereotype.Component;

import java.util.List;

/** 首期只读工具白名单；注册表是 UI 展示与后续策略路由的单一来源。 */
@Component
public class AgentToolRegistry {

    public List<String> readOnlyTools() {
        return List.of(
                "MarketNewsTool",
                "StockNewsSearchTool",
                "FinancialReportTool",
                "MarketQuoteTool",
                "MarketKlineTool",
                "TechnicalIndicatorTool",
                "RiskCheckTool",
                "ResearchSynthesisTool"
        );
    }
}
