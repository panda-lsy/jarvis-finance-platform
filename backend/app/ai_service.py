#!/usr/bin/env python3
"""
贾维斯 AI 服务 (Python 直连 DeepSeek)
- 职责: AI 对话 / 智能报价 / 财报解析 / 研报情感 / 产业链分析
- 协议: OpenAI Chat Completions (deepseek-chat)
- API Key: 环境变量 DEEPSEEK_API_KEY
- endpoint: https://api.deepseek.com/v1/chat/completions

架构: 按项目约定, AI 接口统一归 Python 侧负责 (Java 专注数据存储)。
前端只面对本服务, 通过 /api/ai/* 调用。
"""
import os
import json
import logging
from typing import List, Dict, Optional, Any

import requests

logger = logging.getLogger(__name__)

DEEPSEEK_BASE = os.getenv("DEEPSEEK_BASE_URL", "https://ollama.com/v1")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash:0731")
API_KEY = os.getenv("DEEPSEEK_API_KEY", os.getenv("OLLAMA_API_KEY", ""))
TIMEOUT = int(os.getenv("DEEPSEEK_TIMEOUT", "60"))

# 系统提示词 - 金融投研助手
FIN_SYS_PROMPT = (
    "你是「库里帕酱」，贾维斯金融投研平台的 AI 投资助手。"
    "你擅长：实时金价解读、黄金ETF投资咨询、财报解析、产业链挖掘、研报情感分析、智能报价。"
    "回答专业、简洁、可执行，涉及持仓建议时提示风险，不承诺收益。"
)


def _key() -> str:
    """校验 API Key, 缺失时抛出明确异常"""
    if not API_KEY:
        raise RuntimeError(
            "DEEPSEEK_API_KEY 未配置: 请在环境变量中设置 DEEPSEEK_API_KEY"
        )
    return API_KEY


def _chat_request(messages: List[Dict[str, str]], temperature: float = 0.7,
                  max_tokens: Optional[int] = None) -> Dict[str, Any]:
    """调用 DeepSeek Chat Completions"""
    payload: Dict[str, Any] = {
        "model": DEEPSEEK_MODEL,
        "messages": messages,
        "temperature": temperature,
        "stream": False,
    }
    if max_tokens:
        payload["max_tokens"] = max_tokens

    resp = requests.post(
        f"{DEEPSEEK_BASE}/chat/completions",
        headers={
            "Authorization": f"Bearer {_key()}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=TIMEOUT,
    )
    if resp.status_code != 200:
        logger.error("DeepSeek HTTP %s: %s", resp.status_code, resp.text[:500])
        raise RuntimeError(f"DeepSeek 调用失败 (HTTP {resp.status_code}): {resp.text[:200]}")

    data = resp.json()
    try:
        return {
            "content": data["choices"][0]["message"]["content"],
            "role": data["choices"][0]["message"].get("role", "assistant"),
            "model": data.get("model", DEEPSEEK_MODEL),
            "usage": data.get("usage"),
        }
    except (KeyError, IndexError) as e:
        logger.error("DeepSeek 响应解析失败: %s", data)
        raise RuntimeError(f"DeepSeek 响应格式异常: {e}")


def chat(messages: List[Dict[str, str]], temperature: float = 0.7) -> Dict[str, Any]:
    """通用对话。messages: [{"role","content"}...]"""
    full = [{"role": "system", "content": FIN_SYS_PROMPT}] + messages
    return _chat_request(full, temperature=temperature)


def capabilities() -> Dict[str, Any]:
    """能力探测: 是否可用 + 协议/模型信息"""
    ok = bool(API_KEY)
    return {
        "available": ok,
        "provider": "deepseek",
        "protocol": "openai-chat",
        "model": DEEPSEEK_MODEL,
        "key_configured": ok,
        "message": "已配置" if ok else "缺少 DEEPSEEK_API_KEY 环境变量",
        "skills": [
            "金价实时解读",
            "黄金ETF投资咨询",
            "财报智能解析",
            "产业链挖掘",
            "研报情感分析",
            "智能报价",
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


def analyze_sentiment(reports: List[str]) -> Dict[str, Any]:
    """研报情感分析 (返回每篇情感倾向 + 汇总)"""
    joined = "\n\n---\n\n".join(f"[{i+1}] {t}" for i, t in enumerate(reports))
    prompt = (
        "请对以下各篇研报进行情感分析，逐篇给出 sentiment(看多/看空/中性)、置信度和关键论据，"
        "最后给出综合判断。\n\n"
        f"{joined}"
    )
    return _chat_request([{"role": "user", "content": prompt}], temperature=0.2, max_tokens=1500)


def analyze_chain(node: str, context: str = "") -> Dict[str, Any]:
    """产业链分析"""
    prompt = (
        f"请对【{node}】进行产业链分析：上下游、供需格局、关键厂商、景气度、投资逻辑。"
        f"{('行业/市场背景: ' + context) if context else '无额外背景'}"
    )
    return _chat_request([{"role": "user", "content": prompt}], temperature=0.4, max_tokens=1500)


def smart_quote(price_data: Dict[str, Any]) -> Dict[str, Any]:
    """智能报价解读 (结合实时行情)"""
    prompt = (
        "你是黄金投资助手，基于以下实时行情给出简洁的智能解读与操作参考：\n"
        f"{json.dumps(price_data, ensure_ascii=False, default=str)}\n"
        "要求: 3-5 条要点, 含趋势判断/风险提示, 200字内。"
    )
    return _chat_request([{"role": "user", "content": prompt}], temperature=0.5, max_tokens=600)
