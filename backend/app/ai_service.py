#!/usr/bin/env python3
"""
JARVIS AI provider adapter.

Python 只负责 AI 调用；浏览器不直连本服务，所有请求由 Java 主后端通过内部令牌转发。
上游采用 OpenAI Chat Completions 兼容协议，可配置 DeepSeek、Ollama Cloud 或其他兼容服务。
"""
import os
import json
import logging
import re
from typing import Iterator, List, Dict, Optional, Any

import requests

from .research_tools import deterministic_context, quote_metrics, risk_metrics, strategy_profile

logger = logging.getLogger(__name__)

AI_BASE_URL = os.getenv(
    "AI_BASE_URL",
    os.getenv("DEEPSEEK_BASE_URL", "https://ollama.com/v1"),
).rstrip("/")
AI_MODEL = os.getenv(
    "AI_MODEL",
    os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash:0731"),
)
AI_MODEL_DISPLAY_NAME = os.getenv("AI_MODEL_DISPLAY_NAME", AI_MODEL)
AI_API_KEY = os.getenv(
    "AI_API_KEY",
    os.getenv("DEEPSEEK_API_KEY", os.getenv("OLLAMA_API_KEY", "")),
)
AI_PROVIDER = os.getenv(
    "AI_PROVIDER",
    "ollama-cloud" if "ollama.com" in AI_BASE_URL else "deepseek",
)
AI_TIMEOUT = int(os.getenv("AI_TIMEOUT", os.getenv("DEEPSEEK_TIMEOUT", "60")))

# 系统提示词 - 金融投研助手
FIN_SYS_PROMPT = (
    "你是「库里帕酱」，贾维斯金融投研平台的 AI 投资助手。"
    "你擅长：实时金价解读、黄金ETF投资咨询、财报解析、产业链挖掘、研报情感分析、智能报价。"
    "回答专业、简洁、可执行，涉及持仓建议时提示风险，不承诺收益。"
    "当系统提供确定性研究上下文时，其中的价格和量化指标由程序计算，是唯一可信数值口径；"
    "不得擅自修改、重算或编造这些数值。上下文缺少所需数据时必须明确说明数据不足。"
)


def _research_context_message(raw_context: Optional[Dict[str, Any]]) -> Optional[Dict[str, str]]:
    calculated = deterministic_context(raw_context)
    if not calculated:
        return None
    return {
        "role": "system",
        "content": (
            "以下是 JARVIS 确定性金融计算层生成的只读研究上下文。"
            "请直接引用这些数值进行解释，不要自行改写数值口径：\n"
            + json.dumps(calculated, ensure_ascii=False, separators=(",", ":"))
        ),
    }


def _key() -> str:
    """校验 API Key, 缺失时抛出明确异常"""
    if not AI_API_KEY:
        raise RuntimeError("AI_API_KEY 未配置")
    return AI_API_KEY


def _chat_request(messages: List[Dict[str, str]], temperature: float = 0.7,
                  max_tokens: Optional[int] = None) -> Dict[str, Any]:
    """调用 OpenAI Chat Completions 兼容上游。"""
    payload: Dict[str, Any] = {
        "model": AI_MODEL,
        "messages": messages,
        "temperature": temperature,
        "stream": False,
    }
    if max_tokens:
        payload["max_tokens"] = max_tokens

    try:
        resp = requests.post(
            f"{AI_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {_key()}",
                "Content-Type": "application/json",
            },
            json=payload,
            timeout=AI_TIMEOUT,
        )
    except requests.RequestException as e:
        logger.error("AI upstream connection failed: %s", e)
        raise RuntimeError("AI 上游连接失败") from e
    if resp.status_code != 200:
        logger.error("AI upstream HTTP %s: %s", resp.status_code, resp.text[:500])
        raise RuntimeError(f"AI 上游调用失败 (HTTP {resp.status_code})")

    data = resp.json()
    try:
        return {
            "content": data["choices"][0]["message"]["content"],
            "role": data["choices"][0]["message"].get("role", "assistant"),
            "model": data.get("model", AI_MODEL),
            "usage": data.get("usage"),
        }
    except (KeyError, IndexError) as e:
        logger.error("AI 上游响应解析失败: %s", data)
        raise RuntimeError(f"AI 上游响应格式异常: {e}")


def open_chat_stream(messages: List[Dict[str, str]], temperature: float = 0.7,
                     research_context: Optional[Dict[str, Any]] = None) -> Iterator[Dict[str, Any]]:
    """打开 OpenAI-compatible 流式对话并返回增量事件迭代器。

    上游连接和 HTTP 状态会在本函数返回前完成校验，因此 FastAPI 可以在开始
    SSE 响应之前把连接/鉴权等错误映射为 502，而不是先返回 200 再失败。
    """
    full = [{"role": "system", "content": FIN_SYS_PROMPT}]
    context_message = _research_context_message(research_context)
    if context_message:
        full.append(context_message)
    full.extend(messages)
    payload: Dict[str, Any] = {
        "model": AI_MODEL,
        "messages": full,
        "temperature": temperature,
        "stream": True,
    }
    try:
        resp = requests.post(
            f"{AI_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {_key()}",
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            json=payload,
            timeout=(10, AI_TIMEOUT),
            stream=True,
        )
    except requests.RequestException as e:
        raise RuntimeError(f"AI 上游连接失败: {e}") from e

    if resp.status_code != 200:
        body = resp.text[:500]
        resp.close()
        logger.error("AI upstream streaming HTTP %s: %s", resp.status_code, body)
        raise RuntimeError(f"AI 上游调用失败 (HTTP {resp.status_code})")
    if not getattr(resp, "encoding", None):
        resp.encoding = "utf-8"

    def events() -> Iterator[Dict[str, Any]]:
        model = AI_MODEL
        try:
            for raw_line in resp.iter_lines(decode_unicode=True):
                if not raw_line:
                    continue
                line = raw_line.strip()
                if not line.startswith("data:"):
                    continue
                payload_text = line[5:].strip()
                if payload_text == "[DONE]":
                    yield {"type": "done", "model": model}
                    return
                try:
                    chunk = json.loads(payload_text)
                except json.JSONDecodeError:
                    logger.warning("忽略无法解析的 AI SSE 行: %s", payload_text[:200])
                    continue
                model = chunk.get("model") or model
                choices = chunk.get("choices") or []
                if not choices:
                    continue
                delta = choices[0].get("delta") or {}
                content = delta.get("content")
                if content:
                    yield {"type": "delta", "content": content}
                finish_reason = choices[0].get("finish_reason")
                if finish_reason:
                    yield {"type": "done", "model": model, "finish_reason": finish_reason}
                    return
            yield {"type": "done", "model": model}
        except requests.RequestException as e:
            logger.error("AI upstream streaming interrupted: %s", e)
            yield {"type": "error", "message": "AI 上游流式连接中断"}
        finally:
            resp.close()

    return events()


def chat(messages: List[Dict[str, str]], temperature: float = 0.7,
         research_context: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """通用对话。量化上下文先由 Python 确定性计算，再交给模型解释。"""
    full = [{"role": "system", "content": FIN_SYS_PROMPT}]
    context_message = _research_context_message(research_context)
    if context_message:
        full.append(context_message)
    full.extend(messages)
    return _chat_request(full, temperature=temperature)


def capabilities() -> Dict[str, Any]:
    """能力探测: 是否可用 + 协议/模型信息"""
    ok = bool(AI_API_KEY)
    return {
        "available": ok,
        "provider": AI_PROVIDER,
        "protocol": "openai-chat",
        "streaming": True,
        "model": AI_MODEL,
        "display_name": AI_MODEL_DISPLAY_NAME,
        "key_configured": ok,
        "message": "已配置" if ok else "缺少 AI_API_KEY 环境变量",
        "skills": [
            "金价实时解读",
            "黄金ETF投资咨询",
            "财报智能解析",
            "产业链挖掘",
            "研报情感分析",
            "智能报价",
            "风险预警（VaR/ES）",
            "模拟盘持仓与杠杆风险分析",
            "个性化策略生成（风险偏好问卷）",
        ],
    }


def financial_report(content: str) -> Dict[str, Any]:
    """财报智能解析"""
    prompt = (
        "请作为金融分析师解析以下财报内容，输出结构化的分析："
        "营收/利润变动、毛利率、资产负债、现金流、风险点、投资建议（稳健）。\n\n"
        f"财报内容:\n{content}"
    )
    return _chat_request([{"role": "user", "content": prompt}], temperature=0.3, max_tokens=1500)


# ---- 研报情感分析：固定小节切分（FR-08）----
# 只做确定性切分与字段清理，不推断语义、不做词频统计——避免把模型自由文本
# 伪装成结构化信号。模型没有按小节输出时 sections 为空，由前端回退展示全文。
_SENTIMENT_SECTION_PATTERN = re.compile(r"【\s*([^】]{1,16}?)\s*】")
_SENTIMENT_DISPUTE_SEPARATOR = re.compile(r"\s*[|｜]\s*")
_SENTIMENT_LIST_MARKER = re.compile(r"^\s*(?:[-*•·]|\d+[.、)])\s*")
_SENTIMENT_TOPIC_LABEL = re.compile(r"^(?:争议)?(?:焦点|议题|分歧)[:：]\s*")
_SENTIMENT_BULL_LABEL = re.compile(r"^(?:多方|看多|正面|多头)[:：]\s*")
_SENTIMENT_BEAR_LABEL = re.compile(r"^(?:空方|看空|负面|空头)[:：]\s*")
_SENTIMENT_MAX_DISPUTES = 6            # 卡片上限，避免模型输出过长列表撑爆页面
_SENTIMENT_MAX_FIELD_CHARS = 300       # 单个字段上限，超出截断


def _clean_inline(text: str) -> str:
    """去掉 Markdown 列表符号与加粗标记，得到可直接放进卡片的纯文本。"""
    cleaned = _SENTIMENT_LIST_MARKER.sub("", str(text).strip())
    return cleaned.replace("**", "").replace("__", "").strip()


def _strip_label(text: str, pattern: "re.Pattern[str]") -> str:
    return pattern.sub("", text).strip()


def parse_sentiment_sections(text: Any) -> Dict[str, Any]:
    """把模型按【小节】输出的文本切成 {sections, disputes}（确定性，不触网）。

    - sections：小节名 → 正文；未命中任何小节时返回空 dict
    - disputes：争议焦点小节内 `焦点 | 多方论据 | 空方论据` 的逐行解析结果，
      形如 [{"id": "dispute-1", "topic": ..., "bull": ..., "bear": ...}]，
      不足两段的行（凑不成对比）直接忽略。
    """
    raw = text if isinstance(text, str) else ""
    if not raw.strip():
        return {"sections": {}, "disputes": []}

    matches = list(_SENTIMENT_SECTION_PATTERN.finditer(raw))
    sections: Dict[str, str] = {}
    for index, match in enumerate(matches):
        name = match.group(1)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(raw)
        if name in sections:
            continue
        sections[name] = raw[match.end():end].strip()

    disputes: List[Dict[str, str]] = []
    for line in sections.get("争议焦点", "").splitlines():
        parts = [part for part in (
            _clean_inline(part) for part in _SENTIMENT_DISPUTE_SEPARATOR.split(line)
        ) if part]
        if len(parts) < 2:
            continue
        topic = _strip_label(parts[0], _SENTIMENT_TOPIC_LABEL)
        if not topic:
            continue
        bull = _strip_label(parts[1], _SENTIMENT_BULL_LABEL) if len(parts) > 1 else ""
        bear = _strip_label(parts[2], _SENTIMENT_BEAR_LABEL) if len(parts) > 2 else ""
        if bear in ("-", "—", "无"):
            bear = ""
        disputes.append({
            "id": f"dispute-{len(disputes) + 1}",
            "topic": topic[:_SENTIMENT_MAX_FIELD_CHARS],
            "bull": bull[:_SENTIMENT_MAX_FIELD_CHARS],
            "bear": bear[:_SENTIMENT_MAX_FIELD_CHARS],
        })
        if len(disputes) >= _SENTIMENT_MAX_DISPUTES:
            break

    return {"sections": sections, "disputes": disputes}


def analyze_sentiment(reports: List[str]) -> Dict[str, Any]:
    """研报情感分析（FR-08）：要求模型按固定小节输出，服务端确定性切分成可渲染结构。

    返回在模型原始响应上追加：
      - sections: 小节名 → 正文（未命中任何小节时为空，前端回退展示全文）
      - disputes: 争议焦点 [{id, topic, bull, bear}]，供「争议点对比卡片」渲染
    """
    joined = "\n\n---\n\n".join(f"[{i+1}] {t}" for i, t in enumerate(reports))
    prompt = (
        "请对以下各篇研报进行情感分析，并**严格按下列四个小节、按此顺序**输出，不要增加或改名小节：\n"
        "【情感摘要】逐篇给出 sentiment(看多/看空/中性)、置信度与关键论据，最后给出综合判断。\n"
        "【争议焦点】逐行列出观点相左的焦点，每行格式必须是："
        "焦点 | 多方论据 | 空方论据（三段用半角竖线 | 分隔，每段一句话）；"
        "若确实无分歧，输出唯一一行：无显著争议 | 各方结论一致 | -\n"
        "【趋势判断】行业趋势与关键变量的判断（2-4 条）。\n"
        "【评级与目标价】评级分布与目标价区间；研报未提及时写“未提及”。\n\n"
        f"{joined}"
    )
    result = _chat_request([{"role": "user", "content": prompt}], temperature=0.2, max_tokens=1800)
    parsed = parse_sentiment_sections(result.get("content"))
    # 兼容既有消费方：content 仍是模型原文，新增两个字段供页面渲染卡片。
    return {**result, "sections": parsed["sections"], "disputes": parsed["disputes"]}


def analyze_chain(node: str, context: str = "") -> Dict[str, Any]:
    """产业链分析"""
    prompt = (
        f"请对【{node}】进行产业链分析：上下游、供需格局、关键厂商、景气度、投资逻辑。"
        f"{('行业/市场背景: ' + context) if context else '无额外背景'}"
    )
    return _chat_request([{"role": "user", "content": prompt}], temperature=0.4, max_tokens=1500)


def smart_quote(price_data: Dict[str, Any]) -> Dict[str, Any]:
    """智能报价解读：派生数值先由 Python 计算，LLM 只负责文字解释。"""
    metrics = quote_metrics(price_data)
    prompt = (
        "你是黄金投资助手。以下【确定性计算结果】由程序生成，禁止自行修改其中数值；"
        "请基于这些结果给出简洁的行情解读与操作参考。\n"
        f"确定性计算结果: {json.dumps(metrics, ensure_ascii=False, default=str)}\n"
        f"原始行情: {json.dumps(price_data, ensure_ascii=False, default=str)}\n"
        "要求: 3-5 条要点, 含趋势判断/风险提示, 200字内。"
    )
    return _chat_request([{"role": "user", "content": prompt}], temperature=0.5, max_tokens=600)


def analyze_risk(closes: List[Any], confidence: float = 0.95,
                 portfolio_value: Optional[float] = None,
                 symbol: Optional[str] = None) -> Dict[str, Any]:
    """风险预警（FR-10）：数值（VaR/ES/波动率/最大回撤）由确定性层计算，LLM 只写风险报告。

    返回 {available, metrics, alerts, content}；样本不足时 available=False。
    """
    metrics = risk_metrics(closes, confidence=str(confidence),
                           portfolio_value=portfolio_value, symbol=symbol)
    if not metrics.get("available"):
        return {"available": False, "reason": metrics.get("reason"), "bars": metrics.get("bars")}

    alerts = metrics.pop("alerts", [])
    confidence_value = metrics.get("confidence")
    try:
        confidence_pct = format(float(confidence_value) * 100, ".2f").rstrip("0").rstrip(".")
        confidence_label = f"{confidence_pct}%"
    except (TypeError, ValueError):
        confidence_label = "当前置信度"
    readable = {
        "symbol": metrics.get("symbol"),
        "样本根数": metrics.get("bars"),
        "最新收盘价": metrics.get("last_close"),
        "置信度": metrics.get("confidence"),
        f"单日VaR({confidence_label})": metrics.get("var_pct"),
        "尾部风险ES": metrics.get("es_pct"),
        "年化波动率": metrics.get("vol_annual_pct"),
        "历史最大回撤": metrics.get("max_drawdown_pct"),
        "账户单日潜在亏损": metrics.get("var_amount"),
    }
    prompt = (
        "你是金融风控分析师。以下【确定性计算结果】由程序基于历史收盘价计算，禁止自行修改或重算其中数值，"
        "数值缺失时如实说明数据不足。\n"
        f"确定性计算结果: {json.dumps(readable, ensure_ascii=False, default=str)}\n"
        f"命中预警: {json.dumps(alerts, ensure_ascii=False, default=str)}\n"
        "请输出结构化风险报告：1) 风险来源与指标解读 2) 影响范围（对单标的/账户的含义）"
        "3) 应对建议（仓位、止损、分散化等，可执行）4) 一句总体风险评级（低/中/高）。"
        "要求简洁专业，正文不超过 300 字，数值一律引用上面的口径。"
    )
    content = _chat_request([{"role": "user", "content": prompt}], temperature=0.3, max_tokens=900)
    return {
        "available": True,
        "metrics": {key: value for key, value in metrics.items() if key != "alerts"},
        "alerts": alerts,
        "content": content,
    }


def generate_strategy(horizon_years: Any = None,
                      max_drawdown_pct: Any = None,
                      target_return_pct: Any = None,
                      capital: Any = None,
                      experience: Any = None) -> Dict[str, Any]:
    """个性化策略生成（FR-11）：风险等级与配置比例由确定性层计算，LLM 只写策略说明。

    返回 {available, profile, content}；问卷非法时 available=False。
    """
    profile = strategy_profile(
        horizon_years=horizon_years,
        max_drawdown_pct=max_drawdown_pct,
        target_return_pct=target_return_pct,
        capital=capital,
        experience=experience,
    )
    if not profile.get("available"):
        return {"available": False, "reason": profile.get("reason")}

    allocation_map = {item["label"]: item["pct"] for item in profile.get("allocation", [])}
    readable = {
        "风险等级": f"{profile.get('level_label')}（{profile.get('level')}）",
        "综合得分": profile.get("score"),
        "投资期限(年)": profile.get("answers", {}).get("horizon_years"),
        "可承受最大回撤(%)": profile.get("answers", {}).get("max_drawdown_pct"),
        "目标年化收益(%)": profile.get("answers", {}).get("target_return_pct"),
        "投资经验": profile.get("answers", {}).get("experience_label"),
        "建议配置比例(%,合计100)": allocation_map,
        "资金规模(元)": profile.get("capital"),
        "黄金ETF建议金额(元)": profile.get("gold_amount"),
    }
    prompt = (
        "你是资产配置顾问。以下【确定性计算结果】由程序依据风险偏好问卷计算，"
        "禁止自行修改或重算其中的等级、得分与配置比例，数值缺失时如实说明数据不足。\n"
        f"确定性计算结果: {json.dumps(readable, ensure_ascii=False, default=str)}\n"
        "请输出个性化投资策略说明：1) 风险等级解读（为什么是该等级）"
        "2) 配置比例含义（各类资产的作用与黄金ETF的角色）"
        "3) 执行建议（分批建仓、再平衡频率、止损/止盈纪律，可执行）"
        "4) 与该等级匹配的注意事项与风险提示。"
        "要求简洁专业，正文不超过 300 字，配置比例一律引用上面的口径且必须合计 100%。"
    )
    content = _chat_request([{"role": "user", "content": prompt}], temperature=0.4, max_tokens=900)
    return {
        "available": True,
        "profile": profile,
        "content": content,
    }
