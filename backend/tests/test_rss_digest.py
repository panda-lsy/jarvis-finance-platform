"""每日要闻（RSS digest）测试。

全部用例都不触网：`rss._fetch_feed` 一律被替换为假实现。
覆盖四条容易出错的语义：
  1. 预置资讯源开箱可用，且不覆盖已登记的同 id 源；
  2. digest 合并多源、按发布时间倒序，单源失败不拖垮整体；
  3. 最小抓取间隔生效，force 可绕过（否则前端每次开页面都会打爆外部 feed）；
  4. 发布时间解析失败时的排序行为是**刻意**的，不是偶然。
"""
import os
import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from backend.app import rss as rss_module  # noqa: E402
from backend.app.rss import (  # noqa: E402
    DEFAULT_SOURCES,
    RSSStore,
    _article_sort_key,
    _published_timestamp,
)


def _fake_feed(entries, bozo=0, exc=None):
    """构造最小 feedparser 返回对象。"""
    return types.SimpleNamespace(entries=entries, bozo=bozo, bozo_exception=exc)


def _entry(title, link, published=""):
    # feedparser 的条目是 dict 式（FeedParserDict），_normalize 走 item.get(...)，
    # 所以假条目必须是 dict，SimpleNamespace 会直接 AttributeError。
    return {"title": title, "link": link, "summary": "", "published": published}


def _patch_feed(monkeypatch, feed):
    """把 RSS 下载器换成固定返回，并记录每次请求的 URL。"""
    seen = []

    def fake_parse(url):
        seen.append(url)
        return feed

    monkeypatch.setattr(rss_module, "_fetch_feed", fake_parse)
    return seen


# ---- 预置资讯源 ----

def test_default_sources_are_seeded():
    store = RSSStore()
    ids = [source["id"] for source in store.list_sources()]

    assert ids == [source["id"] for source in DEFAULT_SOURCES]
    assert len(ids) == len(set(ids)), "预置源 id 不能重复"
    for source in store.list_sources():
        assert source["url"].startswith("http"), f"{source['id']} 必须是 http(s) 源"
        assert source["name"], f"{source['id']} 必须有可读名称"


def test_domestic_finance_sources_are_part_of_defaults():
    ids = {source["id"] for source in RSSStore().list_sources()}

    assert {"china_news_finance", "xinhuanet_finance", "xinhuanet_economy"} <= ids


def test_seeding_is_idempotent_and_never_overrides():
    store = RSSStore()
    store.add_source({
        "id": "yahoo_finance", "url": "https://internal.example.com/rss", "name": "自建源",
    })
    before = store.get_source("yahoo_finance")["url"]

    # 再补一次预置：已存在的 id 必须原样保留（外部登记优先）
    store._seed_default_sources()

    assert store.get_source("yahoo_finance")["url"] == before
    assert len(store.list_sources()) == len(DEFAULT_SOURCES)


def test_seed_defaults_can_be_disabled():
    assert RSSStore(seed_defaults=False).list_sources() == []


# ---- digest 合并与排序 ----

def test_digest_merges_sources_and_intelligence_ranking_keeps_freshness_signal(monkeypatch):
    store = RSSStore()
    # 只留两个源，避免用例与预置源数量耦合
    store.sources = {
        "a": {"id": "a", "url": "https://a.example.com/rss", "name": "A 源", "enabled": True},
        "b": {"id": "b", "url": "https://b.example.com/rss", "name": "B 源", "enabled": True},
    }
    feeds = {
        "https://a.example.com/rss": _fake_feed([
            _entry("A 早", "https://a.example.com/1", "Wed, 17 Sep 2026 08:00:00 +0800"),
            _entry("A 晚", "https://a.example.com/2", "Wed, 17 Sep 2026 20:00:00 +0800"),
        ]),
        "https://b.example.com/rss": _fake_feed([
            _entry("B 中", "https://b.example.com/1", "Wed, 17 Sep 2026 12:00:00 +0800"),
        ]),
    }

    def fake_parse(url):
        return feeds[url]

    monkeypatch.setattr(rss_module, "_fetch_feed", fake_parse)

    digest = store.digest(force=True)

    assert digest["total_sources"] == 2
    assert digest["refreshed"] == 2
    assert digest["ok_sources"] == 2
    assert [item["title"] for item in digest["articles"]] == ["A 晚", "B 中", "A 早"]
    assert digest["rank_mode"] == "intelligence_v1"
    assert all("rank_score" in item for item in digest["articles"])
    assert all("selection_reason" in item for item in digest["articles"])
    assert all("recency_timestamp" in item for item in digest["articles"])
    # 文章带链接，前端据此判断是否可跳转
    assert all(item["url"].startswith("https://") for item in digest["articles"])
    assert all(status["crawled"] is True for status in digest["sources"])


def test_digest_survives_single_source_failure(monkeypatch):
    store = RSSStore()
    store.sources = {
        "ok": {"id": "ok", "url": "https://ok.example.com/rss", "name": "正常源", "enabled": True},
        "bad": {"id": "bad", "url": "https://bad.example.com/rss", "name": "坏源", "enabled": True},
    }

    def fake_parse(url):
        if url.startswith("https://bad"):
            return _fake_feed([], bozo=1, exc=ValueError("connection reset"))
        return _fake_feed([_entry("正常新闻", "https://ok.example.com/1", "Wed, 17 Sep 2026 09:00:00 +0800")])

    monkeypatch.setattr(rss_module, "_fetch_feed", fake_parse)

    digest = store.digest(force=True)

    # 关键：坏源不升级为整体失败，好源的文章照样返回
    assert digest["ok_sources"] == 1
    assert len(digest["articles"]) == 1
    bad = next(status for status in digest["sources"] if status["source_id"] == "bad")
    assert bad["ok"] is False
    assert "connection reset" in bad["error"]


def test_digest_respects_min_interval_and_force(monkeypatch):
    store = RSSStore()
    store.sources = {
        "a": {"id": "a", "url": "https://a.example.com/rss", "name": "A", "enabled": True},
    }
    seen = _patch_feed(monkeypatch, _fake_feed([
        _entry("一次", "https://a.example.com/1", "Wed, 17 Sep 2026 08:00:00 +0800"),
    ]))

    first = store.digest()
    assert first["refreshed"] == 1
    assert len(seen) == 1

    second = store.digest()
    # 间隔内不再打外部源，但文章仍然返回
    assert second["refreshed"] == 0
    assert second["sources"][0]["crawled"] is False
    assert len(seen) == 1
    assert len(second["articles"]) == 1

    forced = store.digest(force=True)
    assert forced["refreshed"] == 1
    assert len(seen) == 2

    offline = store.digest(refresh=False)
    assert offline["refreshed"] == 0
    assert len(seen) == 2


def test_digest_deduplicates_across_sources(monkeypatch):
    store = RSSStore()
    store.sources = {
        "a": {"id": "a", "url": "https://a.example.com/rss", "name": "A", "enabled": True},
        "b": {"id": "b", "url": "https://b.example.com/rss", "name": "B", "enabled": True},
    }
    same = _entry("同一篇", "https://news.example.com/1", "Wed, 17 Sep 2026 08:00:00 +0800")
    feeds = {
        "https://a.example.com/rss": _fake_feed([same]),
        "https://b.example.com/rss": _fake_feed([same]),
    }
    monkeypatch.setattr(rss_module, "_fetch_feed", lambda url: feeds[url])

    digest = store.digest(force=True)

    assert [item["title"] for item in digest["articles"]] == ["同一篇"]
    article = digest["articles"][0]
    assert article["source_ids"] == ["a", "b"]
    assert article["source_count"] == 2
    assert article["confirmation_score"] > 0
    assert any("来源确认" in reason for reason in article["selection_reason"])
    metrics = digest["quality_metrics"]
    assert metrics["article_count"] == 1
    assert metrics["confirmed_event_count"] == 1
    assert metrics["duplicate_merge_count"] == 1
    assert metrics["source_diversity"] == 2


def test_source_health_uses_smoothed_score_and_exposes_it(monkeypatch):
    store = RSSStore(seed_defaults=False)
    store.add_source({"id": "a", "url": "https://a.example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed([
        _entry("健康源", "https://a.example.com/1", "2026-09-21T08:00:00"),
    ]))

    digest = store.digest(force=True)

    status = digest["sources"][0]
    assert 80 < status["health_score"] < 100
    assert digest["articles"][0]["source_health_score"] == status["health_score"]


def test_digest_prunes_expired_articles_and_enforces_working_set_cap(monkeypatch):
    store = RSSStore(seed_defaults=False)
    store.ARTICLE_RETENTION_DAYS = 30
    store.MAX_ARTICLES = 2
    store.add_source({"id": "a", "url": "https://a.example.com/rss"})
    _patch_feed(monkeypatch, _fake_feed([
        _entry("过期", "https://a.example.com/old", "2025-01-01T00:00:00"),
        _entry("较新", "https://a.example.com/newer", "2026-09-20T08:00:00"),
        _entry("最新", "https://a.example.com/newest", "2026-09-21T08:00:00"),
        _entry("次新", "https://a.example.com/mid", "2026-09-20T20:00:00"),
    ]))

    digest = store.digest(force=True)

    titles = [article["title"] for article in digest["articles"]]
    assert "过期" not in titles
    assert len(titles) == 2
    assert set(titles) == {"最新", "次新"}


# ---- 时间解析与排序 ----

@pytest.mark.parametrize("value,expected", [
    ("Wed, 17 Sep 2026 08:30:00 +0800", True),
    ("2026-09-17T08:30:00", True),
    ("", False),
    ("not-a-date", False),
    (None, False),
])
def test_published_timestamp_handles_both_formats(value, expected):
    parsed = _published_timestamp(value)
    assert (parsed is not None) is expected


def test_articles_without_parseable_date_sort_last():
    """刻意行为：缺发布时间的条目排在有序日期之后，而不是被当成"很新"。"""
    dated = {"published": "Wed, 17 Sep 2026 08:00:00 +0800", "created_at": "2026-09-17T08:00:00"}
    undated = {"published": "", "created_at": "2026-09-18T08:00:00"}

    assert _article_sort_key(dated) > _article_sort_key(undated)
    assert _article_sort_key(undated)[0] == 0.0
