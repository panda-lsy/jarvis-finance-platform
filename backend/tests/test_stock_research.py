"""个股资讯检索测试；所有上游网络请求均通过 monkeypatch 隔离。"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from backend.app import stock_research  # noqa: E402


def test_tavily_results_are_sanitized_and_sources_preserved(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            pass

        def json(self):
            return {"results": [
                {"title": "贵州茅台公告", "url": "https://example.com/a", "content": "经营情况", "score": 0.82},
                {"title": "unsafe", "url": "javascript:alert(1)", "content": "忽略", "score": 1},
                {"title": "重复", "url": "https://example.com/a", "content": "忽略", "score": 1},
            ]}

    calls = []
    monkeypatch.setattr(stock_research, "TAVILY_API_KEY", "test-secret")
    monkeypatch.setattr(stock_research.requests, "post", lambda *args, **kwargs: (calls.append(kwargs) or FakeResponse()))

    result = stock_research.search_stock_news("贵州茅台 sh600519", 5)

    assert result["provider"] == "tavily"
    assert result["count"] == 1
    assert result["items"][0]["title"] == "贵州茅台公告"
    assert result["items"][0]["source"] == "example.com"
    assert calls[0]["headers"]["Authorization"] == "Bearer test-secret"
    assert calls[0]["json"]["topic"] == "news"


def test_tavily_failure_falls_back_to_matching_domestic_rss(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            raise stock_research.requests.Timeout("private upstream detail")

    monkeypatch.setattr(stock_research, "TAVILY_API_KEY", "test-secret")
    monkeypatch.setattr(stock_research.requests, "post", lambda *args, **kwargs: FakeResponse())
    monkeypatch.setattr(stock_research.rss_store, "digest", lambda **_kwargs: {
        "articles": [
            {"title": "贵州茅台发布公告", "url": "https://news.example/moutai", "source": "中新网财经"},
            {"title": "市场收盘综述", "url": "https://news.example/market", "source": "新华网财经"},
        ]
    })

    result = stock_research.search_stock_news("贵州茅台 sh600519", 5)

    assert result["provider"] == "domestic_rss"
    assert result["fallback_reason"] == "tavily_unavailable"
    assert [item["title"] for item in result["items"]] == ["贵州茅台发布公告"]


def test_query_and_limit_are_bounded():
    with pytest.raises(ValueError):
        stock_research.search_stock_news("   ")
    with pytest.raises(ValueError):
        stock_research.search_stock_news("贵州茅台", 11)
    with pytest.raises(ValueError):
        stock_research.search_stock_news("茅" * 121)
