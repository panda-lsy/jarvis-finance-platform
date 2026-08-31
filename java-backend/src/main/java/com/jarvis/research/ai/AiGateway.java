package com.jarvis.research.ai;

import com.jarvis.research.ai.AiModels.ChatMessage;
import com.jarvis.research.ai.AiModels.ChatResult;
import com.jarvis.research.ai.config.AiProperties;
import com.jarvis.research.ai.provider.AnthropicMessagesProvider;
import com.jarvis.research.ai.provider.OpenAiChatProvider;
import com.jarvis.research.ai.provider.OpenAiResponsesProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 门面 - 统一入口
 * 根据 ai.protocol 配置路由到对应协议实现
 */
@Slf4j
@Service
public class AiGateway {

    private final AiProperties props;
    private final Map<String, AiProvider> providers = new ConcurrentHashMap<>();

    public AiGateway(AiProperties props,
                     OpenAiChatProvider chat,
                     OpenAiResponsesProvider responses,
                     AnthropicMessagesProvider anthropic) {
        this.props = props;
        providers.put(chat.protocol(), chat);
        providers.put(responses.protocol(), responses);
        providers.put(anthropic.protocol(), anthropic);
    }

    /** 获取当前配置的默认 provider */
    public AiProvider defaultProvider() {
        String proto = props.getProtocol();
        AiProvider p = providers.get(proto);
        if (p == null) {
            log.warn("未注册协议 {}, 回退 openai-chat", proto);
            p = providers.get("openai-chat");
        }
        return p;
    }

    /** 当前协议名 */
    public String activeProtocol() {
        return props.getProtocol();
    }

    /** 通用对话 */
    public ChatResult chat(List<ChatMessage> messages) {
        return defaultProvider().chat(new com.jarvis.research.ai.AiModels.ChatRequest(
                props.getProtocolModel(), messages));
    }

    public ChatResult chat(String system, String user) {
        return chat(List.of(
                ChatMessage.builder().role("system").content(system).build(),
                ChatMessage.builder().role("user").content(user).build()
        ));
    }

    // ============ 金融场景便捷方法 ============

    /** 财报智能解析 (结构化提取 JSON) */
    public ChatResult analyzeFinancialReport(String reportText) {
        String system = """
            你是资深金融分析师。请解析上市公司财报文本，提取结构化信息并以纯JSON返回:
            {
              "company": 公司名,
              "period": 报告期,
              "revenue": 营收(亿元),
              "net_profit": 净利润(亿元),
              "revenue_yoy": 营收同比(%),
              "profit_yoy": 净利润同比(%),
              "gross_margin": 毛利率(%),
              "strategic_adjustment": 战略调整摘要,
              "risk_points": [风险点],
              "highlights": [亮点]
            }
            只返回JSON, 不要额外说明。
            """;
        return chat(system, "请分析以下财报文本:\n" + reportText);
    }

    /** 智能报价 / 价格走势预测 */
    public ChatResult predictPrice(String marketContext, String historySummary) {
        String system = """
            你是黄金及贵金属市场分析师。基于提供的行情数据与宏观背景，给出:
            1) 当前价格走势判断
            2) 关键支撑/压力位
            3) 未来1-2周价格区间预测(给出概率)
            4) 影响因素与风险提示
            结构化、简洁、专业。
            """;
        return chat(system,
                "行情与历史概况:\n" + historySummary + "\n\n宏观/市场动态:\n" + marketContext);
    }

    /** 研报情感分析 + 争议点梳理 */
    public ChatResult analyzeSentiment(List<String> reports) {
        String joined = String.join("\n---\n", reports);
        String system = """
            你是研报分析专家。对以下多篇研报进行:
            1) 整体情感倾向(看多/中性/看空)及强度
            2) 观点聚类(按主题归类)
            3) 争议点梳理(不同机构分歧)
            4) 共识预期
            请结构化输出。
            """;
        return chat(system, "研报内容:\n" + joined);
    }

    /** 产业链上下游分析 */
    public ChatResult analyzeChain(String industryNode, String context) {
        String system = """
            你是产业链研究专家。针对指定行业/节点, 梳理:
            1) 上游供应商 / 下游需求方
            2) 关键传导逻辑与影响路径
            3) 动态影响预测(价格/供给/需求变化对上下游影响)
            4) 风险预警(原材料中断、政策变动、需求下滑)
            结构化输出。
            """;
        return chat(system, "分析产业节点: " + industryNode + "\n背景信息:\n" + context);
    }
}
