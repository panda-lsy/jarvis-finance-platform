"""个股新闻检索工具：Tavily 实时搜索优先，国内 RSS 作为无密钥/故障回退。"""
from __future__ import annotations

import html
import logging
import math
import os
import re
from typing import Any, Dict, List
from urllib.parse import urlparse

import requests

from .rss import rss_store

logger = logging.getLogger(__name__)
TAVILY_SEARCH_URL = "https://api.tavily.com/search"
TAVILY_API_KEY = os.getenv("TAVILY_API_KEY", "").strip()
TAVILY_TIMEOUT_SECONDS = 8
MAX_QUERY_LENGTH = 120
MAX_RESULTS = 10
_IGNORED_TERMS = {"股票", "个股", "公司", "新闻", "资讯", "信息", "近期", "最新", "公告", "财报"}


def _clean_text(value: Any, limit: int) -> str:
    text = html.unescape(str(value or ""))
    text = re.sub(r"<[^>]*>", " ", text)
    return re.sub(r"\s+", " ", text).strip()[:limit]


def _normalize_query(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("query 必须是文本")
    query = re.sub(r"\s+", " ", value).strip()
    if not query:
        raise ValueError("请输入股票名称或代码")
    if len(query) > MAX_QUERY_LENGTH:
        raise ValueError(f"query 不能超过{MAX_QUERY_LENGTH}个字符")
    if any(ord(char) < 32 for char in query):
        raise ValueError("query 含有不支持的控制字符")
    return query


def _result_url(value: Any) -> str:
    url = _clean_text(value, 1_000)
    try:
        parsed = urlparse(url)
    except ValueError:
        return ""
    return url if parsed.scheme in {"http", "https"} and parsed.hostname else ""


def _search_tavily(query: str, limit: int) -> List[Dict[str, Any]]:
    response = requests.post(
        TAVILY_SEARCH_URL,
        headers={"Authorization": f"Bearer {TAVILY_API_KEY}", "Content-Type": "application/json"},
        json={
            "query": f"{query} 股票公司近期新闻公告财报",
            "topic": "news",
            "search_depth": "basic",
            "max_results": limit,
            "include_answer": False,
            "include_raw_content": False,
        },
        timeout=(3, TAVILY_TIMEOUT_SECONDS),
    )
    response.raise_for_status()
    payload = response.json()
    raw_results = payload.get("results") if isinstance(payload, dict) else None
    if not isinstance(raw_results, list):
        raise ValueError("Tavily 响应缺少 results")

    items: List[Dict[str, Any]] = []
    seen = set()
    for raw in raw_results:
        if not isinstance(raw, dict):
            continue
        url = _result_url(raw.get("url"))
        title = _clean_text(raw.get("title"), 300)
        if not url or not title or url in seen:
            continue
        seen.add(url)
        try:
            score = float(raw.get("score", 0))
        except (TypeError, ValueError):
            score = 0.0
        items.append({
            "title": title,
            "url": url,
            "source": urlparse(url).hostname or "web",
            "summary": _clean_text(raw.get("content"), 1_600),
            "published": _clean_text(raw.get("published_date"), 80),
            "score": round(max(0.0, min(1.0, score if math.isfinite(score) else 0.0)), 4),
            "provider": "tavily",
        })
        if len(items) >= limit:
            break
    return items


def _rss_fallback(query: str, limit: int) -> List[Dict[str, Any]]:
    digest = rss_store.digest(refresh=True, force=False)
    raw_articles = digest.get("articles") if isinstance(digest, dict) else None
    if not isinstance(raw_articles, list):
        return []

    terms = [term.casefold() for term in re.split(r"\s+", query) if term]
    terms = [term for term in terms if term not in _IGNORED_TERMS and (len(term) >= 2 or term.isdigit())]
    if not terms:
        terms = [query.casefold()]

    matches: List[Dict[str, Any]] = []
    seen = set()
    for article in raw_articles:
        if not isinstance(article, dict):
            continue
        haystack = " ".join(str(article.get(key) or "") for key in (
            "title", "summary", "source", "source_id", "category"))
        if not any(term in haystack.casefold() for term in terms):
            continue
        url = _result_url(article.get("url"))
        title = _clean_text(article.get("title"), 300)
        if not url or not title or url in seen:
            continue
        seen.add(url)
        matches.append({
            "title": title,
            "url": url,
            "source": _clean_text(article.get("source") or article.get("source_id"), 120),
            "summary": _clean_text(article.get("summary"), 1_600),
            "published": _clean_text(article.get("published"), 80),
            "provider": "domestic_rss",
        })
        if len(matches) >= limit:
            break
    return matches


def search_stock_news(query: Any, limit: Any = 8) -> Dict[str, Any]:
    """返回有来源链接的个股资讯；Tavily 不可用时稳定回退到 RSS。"""
    cleaned_query = _normalize_query(query)
    try:
        result_limit = int(limit)
    except (TypeError, ValueError):
        raise ValueError("limit 必须是整数")
    if result_limit < 1 or result_limit > MAX_RESULTS:
        raise ValueError(f"limit 必须在1-{MAX_RESULTS}之间")

    if TAVILY_API_KEY:
        try:
            items = _search_tavily(cleaned_query, result_limit)
            return {
                "available": True,
                "provider": "tavily",
                "query": cleaned_query,
                "items": items,
                "count": len(items),
            }
        except (requests.RequestException, ValueError, TypeError) as exc:
            # 不把上游响应/请求细节回传到浏览器，避免泄漏凭证或服务内部信息。
            logger.warning("Tavily 个股搜索失败，降级至国内 RSS：%s", type(exc).__name__)
            fallback_reason = "tavily_unavailable"
    else:
        fallback_reason = "tavily_not_configured"

    items = _rss_fallback(cleaned_query, result_limit)
    return {
        "available": True,
        "provider": "domestic_rss",
        "fallback_reason": fallback_reason,
        "query": cleaned_query,
        "items": items,
        "count": len(items),
    }
