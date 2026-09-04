#!/usr/bin/env python3
"""
JARVIS AI provider adapter.

Python 只负责 AI 调用；浏览器不直连本服务，所有请求由 Java 主后端通过内部令牌转发。
上游采用 OpenAI Chat Completions 兼容协议，可配置 DeepSeek、Ollama Cloud 或其他兼容服务。
"""
import os
import json
import logging
from typing import Iterator, List, Dict, Optional, Any

import requests

logger = logging.getLogger(__name__)

AI_BASE_URL = os.getenv(
    "AI_BASE_URL",
    os.getenv("DEEPSEEK_BASE_URL", "https://ollama.com/v1"),
).rstrip("/")
AI_MODEL = os.getenv(
    "AI_MODEL",
    os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash:0731"),
)
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
)


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


def open_chat_stream(messages: List[Dict[str, str]], temperature: float = 0.7) -> Iterator[Dict[str, Any]]:
    """打开 OpenAI-compatible 流式对话并返回增量事件迭代器。

    上游连接和 HTTP 状态会在本函数返回前完成校验，因此 FastAPI 可以在开始
    SSE 响应之前把连接/鉴权等错误映射为 502，而不是先返回 200 再失败。
    """
    full = [{"role": "system", "content": FIN_SYS_PROMPT}] + messages
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


def chat(messages: List[Dict[str, str]], temperature: float = 0.7) -> Dict[str, Any]:
    """通用对话。messages: [{"role","content"}...]"""
    full = [{"role": "system", "content": FIN_SYS_PROMPT}] + messages
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
        "key_configured": ok,
        "message": "已配置" if ok else "缺少 AI_API_KEY 环境变量",
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
