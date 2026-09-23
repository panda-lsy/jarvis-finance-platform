"""RSS 信息源模块（Phase 3 Information Agent 地基）测试。

运行：仓库根目录 `pytest backend/tests/test_rss.py -v`。

覆盖要点：
  - 资讯源登记校验：缺 id/url、空值、非 http(s) 协议、非对象载荷
  - 同 id 覆盖更新；未知 id 取用/抓取抛 RSSSourceNotFound
  - 抓取：条目标准化、去重、信息不足条目跳过
  - **失败可区分**：抓取失败不再返回空列表，而是 ok=False + error
  - HTTP 语义：领域异常映射为 400 / 404，不泄漏成 500
  - 安全：所有 /internal/rss/* 端点都必须挂着内部服务令牌依赖

全部用例均不触网：`rss._fetch_feed` 一律被替换为假实现。
"""
import asyncio
import os
import sys
import types
from pathlib import Path

import pytest

os.environ["PYTHON_SERVICE_TOKEN"] = "unit-test-token"
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from backend.app import main as ai_main  # noqa: E402
from backend.app import rss as rss_module  # noqa: E402
from backend.app.ai_routes import require_internal_service  # noqa: E402
from backend.app.rss import (  # noqa: E402
    RSSSourceNotFound,
    RSSStore,
    RSSValidationError,
    _canonical_url,
)


def _fake_feed(entries=(), bozo=0, bozo_exception=None):
    """构造一个最小 feedparser 返回对象。"""
    return types.SimpleNamespace(
        entries=list(entries),
        bozo=bozo,
        bozo_exception=bozo_exception,
    )


def _patch_feed(monkeypatch, feed):
    """把 RSS 下载器替换为固定返回，并记录被请求的 URL。"""
    requested = []

    def fake_parse(url):
        requested.append(url)
        return feed

    monkeypatch.setattr(rss_module, "_fetch_feed", fake_parse)
    return requested


def _entry(title="标题", link="https://example.com/a", summary="摘要", published="2026-09-16"):
    return {"title": title, "link": link, "summary": summary, "published": published}


# ---- 资讯源登记与校验 ----

def test_add_source_applies_defaults_and_trims():
    # seed_defaults=False：这条只验证登记与规整，不该被预置资讯源干扰
    store = RSSStore(seed_defaults=False)
    stored = store.add_source({"id": "  gold-news  ", "url": "  https://example.com/rss  "})

    assert stored["id"] == "gold-news"
    assert stored["url"] == "https://example.com/rss"
    assert stored["name"] == "gold-news"      # name 缺省回退为 id
    assert stored["enabled"] is True
    assert store.list_sources() == [stored]


def test_add_source_keeps_explicit_name_and_enabled():
    store = RSSStore()
    stored = store.add_source({
        "id": "s1", "url": "https://example.com/rss", "name": "黄金资讯", "enabled": False,
    })
    assert stored["name"] == "黄金资讯"
    assert stored["enabled"] is False


def test_defaults_cover_ten_sources_and_keep_category_metadata():
    store = RSSStore()
    sources = store.list_sources()

    assert len(sources) >= 10
    assert all(source["category"] for source in sources)
    assert all(0 <= source["credibility"] <= 100 for source in sources)


def test_disabled_sources_are_not_crawled_by_digest(monkeypatch):
    store = RSSStore(seed_defaults=False)
    store.add_source({"id": "on", "url": "https://example.com/on", "category": "markets"})
    store.add_source({"id": "off", "url": "https://example.com/off", "enabled": False})
    requested = _patch_feed(monkeypatch, _fake_feed(entries=[_entry()]))

    digest = store.digest(refresh=True, force=True)

    assert requested == ["https://example.com/on"]
    assert digest["total_sources"] == 1
    assert digest["sources"][0]["source_id"] == "on"


@pytest.mark.parametrize("payload,reason", [
    ({}, "缺 id"),
    ({"id": "s1"}, "缺 url"),
    ({"id": "", "url": "https://example.com/rss"}, "id 为空"),
    ({"id": "s1", "url": "   "}, "url 为空"),
    ({"id": "s1", "url": "file:///etc/passwd"}, "file 协议"),
    ({"id": "s1", "url": "ftp://example.com/rss"}, "ftp 协议"),
    ({"id": "s1", "url": "example.com/rss"}, "无协议"),
    ("not-a-dict", "非对象"),
])
def test_add_source_rejects_invalid_payload(payload, reason):
    with pytest.raises(RSSValidationError):
        RSSStore().add_source(payload)


def test_add_source_same_id_overwrites():
    store = RSSStore(seed_defaults=False)
    store.add_source({"id": "s1", "url": "https://example.com/old"})
    store.add_source({"id": "s1", "url": "https://example.com/new"})

    assert len(store.list_sources()) == 1
    assert store.get_source("s1")["url"] == "https://example.com/new"


def test_get_source_unknown_id_raises_not_found():
    with pytest.raises(RSSSourceNotFound):
        RSSStore().get_source("missing")


# ---- 抓取 ----

def test_crawl_unknown_source_raises_not_found():
    with pytest.raises(RSSSourceNotFound):
        RSSStore().crawl("missing")


def test_crawl_normalizes_entries(monkeypatch):
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})
    requested = _patch_feed(monkeypatch, _fake_feed(entries=[_entry()]))

    result = store.crawl("s1")

    assert requested == ["https://example.com/rss"]      # 用的是登记的 URL
    assert result["ok"] is True
    assert result["error"] is None
    assert result["fetched"] == 1
    assert result["skipped"] == 0
    assert len(result["added"]) == 1

    article = result["added"][0]
    assert article["source_id"] == "s1"
    assert article["title"] == "标题"
    assert article["url"] == "https://example.com/a"
    assert article["summary"] == "摘要"
    assert article["tags"] == ["general"]
    assert article["analysis"]["direction"] == "neutral"
    assert article["published"] == "2026-09-16"
    assert len(article["id"]) == 64                      # sha256 十六进制
    assert article["canonical_url"] == "https://example.com/a"
    assert article["source_ids"] == ["s1"]
    assert article["source_count"] == 1
    assert len(article["near_duplicate_hash"]) == 16
    assert article["created_at"]


def test_canonical_url_removes_tracking_but_keeps_business_query():
    url = "HTTPS://Example.COM:443/news?id=7&utm_source=rss&b=2&a=1#top"
    assert _canonical_url(url) == "https://example.com/news?a=1&b=2&id=7"


def test_crawl_uses_content_tags_and_explainable_impact(monkeypatch):
    store = RSSStore(seed_defaults=False)
    store.add_source({"id": "s1", "url": "https://example.com/rss", "category": "gold"})
    _patch_feed(monkeypatch, _fake_feed(entries=[{
        "title": "Gold surges after rate cut",
        "link": "https://example.com/gold",
        "content": [{"value": "Gold rises on the policy signal."}],
        "tags": [{"term": "黄金"}, {"term": "宏观"}],
    }]))

    article = store.crawl("s1")["added"][0]

    assert article["summary"] == "Gold rises on the policy signal."
    assert article["tags"] == ["黄金", "宏观", "gold"]
    assert article["analysis"]["direction"] == "positive"
    assert article["analysis"]["method"] == "keyword_rule"


def test_crawl_deduplicates_across_runs(monkeypatch):
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry()]))

    first = store.crawl("s1")
    second = store.crawl("s1")

    assert len(first["added"]) == 1
    assert len(second["added"]) == 0
    assert second["skipped"] == 1
    assert second["fetched"] == 1
    assert len(store.list_articles()) == 1               # 未重复入库
    assert store.list_articles()[0]["duplicate_count"] == 0  # 同源轮询不惩罚 novelty


def test_crawl_skips_entries_without_title_and_link(monkeypatch):
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed(entries=[
        _entry(),
        {"title": "", "link": ""},
        {"summary": "只有摘要"},
    ]))

    result = store.crawl("s1")

    assert result["fetched"] == 3
    assert len(result["added"]) == 1
    assert result["skipped"] == 2


def test_crawl_reports_failure_instead_of_empty_list(monkeypatch):
    """抓取失败必须可区分于「成功但无新文章」——旧实现两者都返回 []。"""
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed(
        entries=[], bozo=1, bozo_exception=OSError("connection refused"),
    ))

    result = store.crawl("s1")

    assert result["ok"] is False
    assert "connection refused" in result["error"]
    assert result["fetched"] == 0
    assert result["added"] == []
    assert store.list_articles() == []


def test_crawl_converts_network_timeout_to_source_error(monkeypatch):
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})

    def fail_fetch(_url):
        raise TimeoutError("read timeout")

    monkeypatch.setattr(rss_module, "_fetch_feed", fail_fetch)

    result = store.crawl("s1")

    assert result["ok"] is False
    assert result["fetched"] == 0
    assert result["added"] == []
    assert "read timeout" in result["error"]


def test_crawl_marks_ok_false_but_keeps_articles_on_partial_parse(monkeypatch):
    """部分可解析：条目照常入库，同时给出告警，不整体丢弃。"""
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed(
        entries=[_entry()], bozo=1, bozo_exception=ValueError("malformed date"),
    ))

    result = store.crawl("s1")

    assert result["ok"] is False
    assert "malformed date" in result["error"]
    assert len(result["added"]) == 1
    assert len(store.list_articles()) == 1


def test_crawl_success_has_no_error(monkeypatch):
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry()]))

    result = store.crawl("s1")

    assert result["ok"] is True
    assert result["error"] is None


# ---- 文章列表 ----

def test_list_articles_can_filter_by_source(monkeypatch):
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/one"})
    store.add_source({"id": "s2", "url": "https://example.com/two"})

    # 两个源给出各自不同的文章，才能验证过滤本身。
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry(title="A", link="https://example.com/a")]))
    store.crawl("s1")
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry(title="B", link="https://example.com/b")]))
    store.crawl("s2")

    assert len(store.list_articles()) == 2
    assert len(store.list_articles("s1")) == 1
    assert store.list_articles("s1")[0]["title"] == "A"
    assert store.list_articles("s2")[0]["title"] == "B"
    assert store.list_articles("unknown") == []


def test_crawl_deduplicates_same_article_across_sources(monkeypatch):
    """跨源去重：同一篇文章被多个 feed 转载时只入库一次。

    这是信息聚合的**有意行为**（避免同一篇新闻重复出现），不是缺陷。
    V1 intelligence pipeline 会保留所有确认来源，因此聚合事件在两个来源
    的 source 过滤下都应可见。
    """
    store = RSSStore()
    store.add_source({"id": "s1", "url": "https://example.com/one"})
    store.add_source({"id": "s2", "url": "https://example.com/two"})
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry()]))

    first = store.crawl("s1")
    second = store.crawl("s2")

    assert len(first["added"]) == 1
    assert len(second["added"]) == 0              # 同标题+同链接 → 命中已有去重键
    assert second["skipped"] == 1
    assert second["merged"] == 1
    assert store.list_articles("s1") != []
    assert store.list_articles("s2") != []
    article = store.list_articles()[0]
    assert article["source_ids"] == ["s1", "s2"]
    assert article["source_count"] == 2
    assert article["duplicate_count"] == 1


def test_crawl_merges_tracking_variants_and_title_punctuation(monkeypatch):
    store = RSSStore(seed_defaults=False)
    store.add_source({"id": "s1", "url": "https://example.com/one"})
    store.add_source({"id": "s2", "url": "https://example.com/two"})

    _patch_feed(monkeypatch, _fake_feed(entries=[_entry(
        title="Fed keeps rates unchanged",
        link="https://news.example.com/story?id=7&utm_source=wire",
    )]))
    store.crawl("s1")
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry(
        title="Fed keeps rates unchanged!",
        link="https://news.example.com/story?utm_medium=rss&id=7#latest",
    )]))
    result = store.crawl("s2")

    assert result["merged"] == 1
    assert len(store.list_articles()) == 1
    assert store.list_articles()[0]["source_count"] == 2


# ---- HTTP 语义 ----

async def _call(handler, exc):
    """直接驱动异常处理器协程——不依赖 anyio/pytest-asyncio 插件。

    CI 以 `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1` 运行，异步插件不会自动加载，
    因此这里显式驱动协程，保证用例在任何 pytest 配置下都能跑。
    """
    return await handler(None, exc)


def test_validation_error_maps_to_400():
    response = asyncio.run(_call(ai_main._rss_validation_handler, RSSValidationError("source.url 不能为空")))
    assert response.status_code == 400


def test_source_not_found_maps_to_404():
    response = asyncio.run(_call(ai_main._rss_not_found_handler, RSSSourceNotFound("资讯源不存在: missing")))
    assert response.status_code == 404


def test_rss_endpoints_are_registered_and_token_protected():
    """每个 /internal/rss/* 端点都必须挂着内部服务令牌依赖。

    缺了它，Python AI 服务就会对未认证调用者暴露抓取能力。
    """
    rss_paths = {
        route.path: route
        for route in ai_main.app.routes
        if getattr(route, "path", "").startswith("/internal/rss/")
    }

    assert set(rss_paths) == {
        "/internal/rss/source",
        "/internal/rss/fetch/{source_id}",
        "/internal/rss/articles",
        "/internal/rss/digest",
        "/internal/rss/rerank",
    }

    for path, route in rss_paths.items():
        dependencies = [d.call for d in route.dependant.dependencies]
        assert require_internal_service in dependencies, f"{path} 缺少内部令牌依赖"

    stock_search = next(route for route in ai_main.app.routes
                        if getattr(route, "path", "") == "/internal/research/stock-news")
    assert require_internal_service in [d.call for d in stock_search.dependant.dependencies]


def test_add_and_list_sources_through_endpoints():
    """端点函数直接调用：登记后能立刻列出（走模块级单例）。"""
    ai_main.rss_store.sources.clear()
    ai_main.rss_store.articles.clear()

    stored = ai_main.add_rss_source({"id": "s1", "url": "https://example.com/rss", "name": "资讯"})

    assert stored["id"] == "s1"
    assert [s["id"] for s in ai_main.list_rss_sources()] == ["s1"]

    ai_main.rss_store.sources.clear()
    ai_main.rss_store.articles.clear()


def test_fetch_endpoint_returns_structured_result(monkeypatch):
    ai_main.rss_store.sources.clear()
    ai_main.rss_store.articles.clear()
    ai_main.add_rss_source({"id": "s1", "url": "https://example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed(entries=[_entry()]))

    result = ai_main.fetch_rss("s1")

    assert result["ok"] is True
    assert len(result["added"]) == 1
    assert len(ai_main.list_rss_articles("s1")) == 1

    ai_main.rss_store.sources.clear()
    ai_main.rss_store.articles.clear()
