"""JARVIS RSS 信息源模块（Phase 3 Information Agent 的 MVP 地基）。

职责边界（与三栈架构一致）：
  - 本模块只做「资讯源配置 / RSS 抓取 / 文章标准化 / 去重」。
  - 文章的业务持久化、权限、配额由 Java 主后端负责；本模块的文章缓存仍是**进程内存**，
    资讯源配置由 Java 主后端持久化后，在启动时同步到本模块。
  - AI 分析不在本模块，由后续内部 AI 服务接口完成。

对外错误约定（由 app.main 注册的异常处理器映射为 HTTP 状态码）：
  - RSSValidationError → 400
  - RSSSourceNotFound → 404
"""
from datetime import datetime
from email.utils import parsedate_to_datetime
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
import html
import math
import os
import re
from threading import RLock
import unicodedata
from typing import Dict, List, Optional
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import feedparser
import requests

#: 允许的资讯源协议。仅 http(s)，避免 file:// 等被当作抓取目标。
_ALLOWED_SCHEMES = ("http", "https")
_RSS_TIMEOUT = (
    float(os.getenv("RSS_CONNECT_TIMEOUT_SECONDS", "3")),
    float(os.getenv("RSS_READ_TIMEOUT_SECONDS", "6")),
)
_RSS_USER_AGENT = "JARVIS-Finance-Research/1.0 (+https://f.shengxia.me)"
_TRACKING_QUERY_KEYS = {
    "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid",
    "igshid", "vero_conv", "vero_id", "oly_anon_id", "oly_enc_id",
}
_NEAR_DUPLICATE_WINDOW_SECONDS = 72 * 3600
_NEAR_DUPLICATE_SCAN_LIMIT = 600


def _positive_int_env(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default
    return value if value > 0 else default

#: 预置财经资讯源。让"每日要闻"开箱可用，而不必先手工登记源。
#:
#: 抓取成败取决于**服务器的出网能力**：这些域名在受限网络下可能全部超时。
#: 因此 digest 逐源返回 error，绝不把单源失败升级为整体错误——前端按
#: ok_sources / error 降级展示。需要增删源时仍走 /internal/rss/source。
DEFAULT_SOURCES: List[Dict] = [
    {
        "id": "yahoo_finance",
        "name": "Yahoo Finance",
        "url": "https://finance.yahoo.com/news/rssindex",
        "category": "global",
        "credibility": 78,
    },
    {
        "id": "cnbc_finance",
        "name": "CNBC Finance",
        "url": (
            "https://search.cnbc.com/rs/search/combinedcms/view.xml"
            "?partnerId=wrss01&id=100003114"
        ),
        "category": "global",
        "credibility": 82,
    },
    {
        "id": "marketwatch_top",
        "name": "MarketWatch",
        "url": "https://feeds.content.dowjones.io/public/rss/mw_topstories",
        "category": "markets",
        "credibility": 76,
    },
    {
        "id": "investing_cn",
        "name": "英为财情",
        "url": "https://cn.investing.com/rss/news.rss",
        "category": "markets",
        "credibility": 70,
    },
    {
        "id": "wsj_markets",
        "name": "WSJ Markets",
        "url": "https://feeds.a.dj.com/rss/RSSMarketsMain.xml",
        "category": "markets",
        "credibility": 84,
    },
    {
        "id": "cointelegraph",
        "name": "Cointelegraph",
        "url": "https://cointelegraph.com/rss",
        "category": "crypto",
        "credibility": 68,
    },
    {
        "id": "coindesk",
        "name": "CoinDesk",
        "url": "https://www.coindesk.com/arc/outboundfeeds/rss/",
        "category": "crypto",
        "credibility": 72,
    },
    {
        "id": "ft_markets",
        "name": "Financial Times Markets",
        "url": "https://www.ft.com/markets?format=rss",
        "category": "global",
        "credibility": 88,
    },
    {
        "id": "marketwatch_topstories",
        "name": "MarketWatch Top Stories",
        "url": "https://www.marketwatch.com/rss/topstories",
        "category": "markets",
        "credibility": 76,
    },
    {
        "id": "nasdaq_market",
        "name": "Nasdaq Market News",
        "url": "https://www.nasdaq.com/feed/rssoutbound?category=Markets",
        "category": "markets",
        "credibility": 84,
    },
    {
        "id": "china_news_finance",
        "name": "中新网财经",
        "url": "https://www.chinanews.com.cn/rss/finance.xml",
        "category": "markets",
        "credibility": 86,
    },
    {
        "id": "xinhuanet_finance",
        "name": "新华网金融",
        "url": "http://www.xinhuanet.com/finance/news_finance.xml",
        "category": "markets",
        "credibility": 90,
    },
    {
        "id": "xinhuanet_economy",
        "name": "新华网财经",
        "url": "http://www.xinhuanet.com/fortune/news_fortune.xml",
        "category": "markets",
        "credibility": 90,
    },
]


class RSSValidationError(ValueError):
    """请求载荷不合法：缺 id/url、字段为空、URL 非法等。映射为 400。"""


class RSSSourceNotFound(LookupError):
    """指定 id 的资讯源不存在。映射为 404。"""


def _clean_text(value: object) -> str:
    """把任意输入规整为去首尾空白的字符串；None / 非字符串返回空串。"""
    if value is None:
        return ""
    return str(value).strip()


def _canonical_url(value: object) -> str:
    """生成用于去重的稳定 URL，不改变前端最终跳转使用的原始链接。

    只移除公认 tracking 参数和 fragment；业务 query 保留并排序，避免为了去重
    把真正不同的财经页面误合并。
    """
    text = _clean_text(value)
    if not text:
        return ""
    try:
        parsed = urlparse(text)
        scheme = parsed.scheme.lower()
        if scheme not in _ALLOWED_SCHEMES or not parsed.hostname:
            return text
        host = parsed.hostname.lower().rstrip(".")
        try:
            port = parsed.port
        except ValueError:
            return text
        if port and not ((scheme == "http" and port == 80) or (scheme == "https" and port == 443)):
            host = f"{host}:{port}"
        path = re.sub(r"/{2,}", "/", parsed.path or "/")
        query = []
        for key, item in parse_qsl(parsed.query, keep_blank_values=True):
            lowered = key.lower()
            if lowered.startswith("utm_") or lowered in _TRACKING_QUERY_KEYS:
                continue
            query.append((key, item))
        query.sort(key=lambda pair: (pair[0].lower(), pair[1]))
        return urlunparse((scheme, host, path, "", urlencode(query, doseq=True), ""))
    except (TypeError, ValueError):
        return text


def _plain_text(value: object) -> str:
    text = html.unescape(_clean_text(value))
    text = re.sub(r"<[^>]+>", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _normalize_title(value: object) -> str:
    text = unicodedata.normalize("NFKC", _plain_text(value)).lower()
    text = re.sub(r"[^\w\u4e00-\u9fff]+", " ", text, flags=re.UNICODE)
    return re.sub(r"\s+", " ", text).strip()


def _simhash_tokens(value: str) -> List[str]:
    normalized = unicodedata.normalize("NFKC", value).lower()
    tokens = re.findall(r"[a-z0-9]+", normalized)
    for run in re.findall(r"[\u4e00-\u9fff]+", normalized):
        if len(run) == 1:
            tokens.append(run)
        else:
            tokens.extend(run[index:index + 2] for index in range(len(run) - 1))
    return tokens[:2_000]


def _simhash64(value: str) -> str:
    vector = [0] * 64
    tokens = _simhash_tokens(value)
    if not tokens:
        return "0" * 16
    for token in tokens:
        bits = int.from_bytes(sha256(token.encode("utf-8")).digest()[:8], "big")
        for index in range(64):
            vector[index] += 1 if bits & (1 << index) else -1
    fingerprint = 0
    for index, weight in enumerate(vector):
        if weight >= 0:
            fingerprint |= 1 << index
    return f"{fingerprint:016x}"


def _hamming_distance(left: str, right: str) -> int:
    try:
        return (int(left, 16) ^ int(right, 16)).bit_count()
    except (TypeError, ValueError):
        return 64


def _fetch_feed(url: str):
    """在有限时限内下载并解析 RSS，避免单个外部源拖住整条新闻接口。"""
    response = requests.get(
        url,
        headers={"User-Agent": _RSS_USER_AGENT, "Accept": "application/rss+xml, application/atom+xml, text/xml"},
        timeout=_RSS_TIMEOUT,
    )
    response.raise_for_status()
    return feedparser.parse(response.content)


def _published_timestamp(value: object) -> Optional[float]:
    """把 RSS 的时间文本解析成时间戳；解析不出来返回 None（绝不抛异常）。

    RSS 常见 RFC822（`Wed, 17 Sep 2026 08:30:00 +0800`），本模块自己写入的
    created_at 是 ISO8601，所以两条路径都要试。feedparser 对畸形日期可能让
    parsedate_to_datetime 返回 None，此时 .timestamp() 会 AttributeError，
    一并按"解析失败"处理。
    """
    text = _clean_text(value)
    if not text:
        return None
    for parser in (parsedate_to_datetime, datetime.fromisoformat):
        try:
            parsed = parser(text)
        except (TypeError, ValueError, OverflowError):
            continue
        if parsed is None:
            continue
        try:
            return parsed.timestamp()
        except (AttributeError, OverflowError, OSError):
            continue
    return None


def _article_sort_key(article: Dict) -> tuple:
    """资讯排序键：优先发布时间，解析不出来时退回入库时间。

    两类时间不能混进同一个字段比较——把"解析失败"当成很新或很旧都是错的。
    这里用元组分开比较：能解析发布时间的按发布时间排，缺发布时间的整体排在其后
    （这类条目在真实 feed 里占比很低，且用入库时间在同类内部仍然有序）。
    """
    published = _published_timestamp(article.get("published"))
    created = _published_timestamp(article.get("created_at"))
    return (published or 0.0, created or 0.0)


def _entry_body(item) -> str:
    """优先取 RSS 正文片段，缺失时退回摘要；不把 HTML 当作可执行内容。"""
    summary = _clean_text(item.get("summary"))
    content = item.get("content")
    if isinstance(content, list):
        fragments = []
        for fragment in content:
            if isinstance(fragment, dict):
                value = _clean_text(fragment.get("value"))
                if value:
                    fragments.append(value)
        if fragments:
            return " ".join(fragments)[:8_000]
    return summary[:8_000]


def _entry_tags(item, category: str) -> List[str]:
    """保留 feed 自带标签，并补充来源分类；标签仅用于筛选展示。"""
    tags: List[str] = []
    raw_tags = item.get("tags")
    if isinstance(raw_tags, list):
        for raw in raw_tags:
            if isinstance(raw, dict):
                value = _clean_text(raw.get("term") or raw.get("label"))
            else:
                value = _clean_text(raw)
            if value and value not in tags:
                tags.append(value[:40])
    raw_category = _clean_text(item.get("category"))
    if raw_category and raw_category not in tags:
        tags.append(raw_category[:40])
    if category and category not in tags:
        tags.append(category)
    return tags[:8]


def _market_impact(title: str, body: str) -> Dict[str, str]:
    """低成本、可解释的第一层影响方向；明确标注为规则结果，不冒充投资建议。"""
    text = f"{title} {body}".lower()
    positive = len(re.findall(r"上涨|上调|增长|改善|突破|利好|降息|回购|beat|surge|rally|upgrade", text))
    negative = len(re.findall(r"下跌|下调|下降|恶化|跌破|利空|加息|违约|风险|miss|drop|selloff|downgrade", text))
    direction = "positive" if positive > negative else "negative" if negative > positive else "neutral"
    return {"direction": direction, "method": "keyword_rule", "disclaimer": "仅供资讯筛选，不构成投资建议"}


class RSSStore:
    """内存版资讯源仓库。

    文章仍不做业务持久化（进程重启后清空），但作为在线筛选工作集必须有明确
    retention/cap，避免长期运行后历史噪声持续占内存并拖慢近重复扫描和 rerank。
    真正的历史归档仍应由 Java 主后端接管。
    """

    #: 同一资讯源在 digest 中的最小抓取间隔（秒）。
    #: 前端每次打开行情页都会问一次要闻，不设间隔就会把外部 feed 打爆。
    DIGEST_MIN_INTERVAL_SECONDS = 300
    ARTICLE_RETENTION_DAYS = _positive_int_env("RSS_ARTICLE_RETENTION_DAYS", 30)
    MAX_ARTICLES = _positive_int_env("RSS_MAX_ARTICLES", 5_000)

    def __init__(self, seed_defaults: bool = True):
        self.sources: Dict[str, Dict] = {}
        self.articles: Dict[str, Dict] = {}
        self._last_crawled: Dict[str, datetime] = {}
        self._source_health: Dict[str, Dict] = {}
        self._state_lock = RLock()
        if seed_defaults:
            self._seed_default_sources()

    def _seed_default_sources(self) -> None:
        """补齐预置资讯源；**不覆盖**已登记的同 id 源（外部登记优先）。"""
        for source in DEFAULT_SOURCES:
            if source["id"] in self.sources:
                continue
            try:
                self.add_source(dict(source))
            except RSSValidationError:
                # 预置数据自身不合法时跳过即可，不该让进程起不来。
                continue

    # ---- 资讯源 ----

    def add_source(self, source: Dict) -> Dict:
        """登记/覆盖一个资讯源。

        必填 `id` 与 `url`；`name` 缺省回退为 `id`。同 id 重复登记视为覆盖更新。
        """
        if not isinstance(source, dict):
            raise RSSValidationError("source 必须是 JSON 对象")

        source_id = _clean_text(source.get("id"))
        url = _clean_text(source.get("url"))
        if not source_id:
            raise RSSValidationError("source.id 不能为空")
        if not url:
            raise RSSValidationError("source.url 不能为空")

        if len(source_id) > 80:
            raise RSSValidationError("source.id 不能超过80个字符")
        if len(url) > 500:
            raise RSSValidationError("source.url 不能超过500个字符")

        scheme = urlparse(url).scheme.lower()
        if scheme not in _ALLOWED_SCHEMES:
            raise RSSValidationError(
                f"source.url 仅支持 {'/'.join(_ALLOWED_SCHEMES)}，当前为 '{scheme or '空'}'"
            )

        name = _clean_text(source.get("name")) or source_id
        category = _clean_text(source.get("category")) or "general"
        try:
            credibility = int(source.get("credibility", 50))
        except (TypeError, ValueError):
            raise RSSValidationError("source.credibility 必须是整数")
        if len(name) > 120:
            raise RSSValidationError("source.name 不能超过120个字符")
        if len(category) > 40:
            raise RSSValidationError("source.category 不能超过40个字符")
        if not 0 <= credibility <= 100:
            raise RSSValidationError("source.credibility 必须在0-100之间")

        previous = self.sources.get(source_id)
        now = datetime.now().isoformat()
        stored = {
            "id": source_id,
            "url": url,
            "name": name,
            "category": category,
            "credibility": credibility,
            "enabled": bool(source.get("enabled", True)),
            "created_at": previous.get("created_at", now) if previous else now,
            "updated_at": now,
        }
        self.sources[source_id] = stored
        return stored

    def list_sources(self) -> List[Dict]:
        return list(self.sources.values())

    def get_source(self, source_id: str) -> Dict:
        source = self.sources.get(_clean_text(source_id))
        if source is None:
            raise RSSSourceNotFound(f"资讯源不存在: {source_id}")
        return source

    # ---- 抓取 ----

    def crawl(self, source_id: str) -> Dict:
        """抓取指定资讯源并标准化其中的新文章。

        返回结构化信封，**不再用空列表同时表示「成功但无新文章」和「抓取失败」**
        ——调用方必须能区分这两种情况。

            {"source_id", "ok", "fetched", "added", "skipped", "error"}
        """
        source = self.get_source(source_id)

        # 网络失败必须被收敛成单源错误；不能让一个 RSS 源阻塞 /daily。
        try:
            feed = _fetch_feed(source["url"])
        except Exception as exc:  # requests/解析库的异常都按单源降级
            self._record_source_health(source["id"], success_weight=0.0, fetched=0, accepted=0)
            return {
                "source_id": source["id"],
                "ok": False,
                "fetched": 0,
                "added": [],
                "skipped": 0,
                "error": f"抓取失败: {_clean_text(exc) or exc.__class__.__name__}",
            }
        entries = list(getattr(feed, "entries", []) or [])
        error = self._feed_error(feed)

        added: List[Dict] = []
        skipped = 0
        merged = 0
        for item in entries:
            article = self._normalize(source["id"], item)
            if article is None:
                skipped += 1
                continue
            # digest 会并行抓多个来源；查重与写入必须处于同一临界区，否则两个
            # 来源同时返回同一事件时可能都“未命中”后各写一份。
            with self._state_lock:
                existing = self.articles.get(article["id"]) or self._find_near_duplicate(article)
                if existing is not None:
                    self._merge_duplicate(existing, article)
                    skipped += 1
                    merged += 1
                    continue
                self.articles[article["id"]] = article
                added.append(article)

        partial = bool(error and error.startswith("部分解析告警"))
        success_weight = 1.0 if error is None else 0.5 if partial else 0.0
        self._record_source_health(
            source["id"], success_weight=success_weight,
            fetched=len(entries), accepted=len(added) + merged,
        )
        health = self._source_health_score(source["id"])
        for article in added:
            article["source_health_score"] = health

        return {
            "source_id": source["id"],
            "ok": error is None,
            "fetched": len(entries),
            "added": added,
            "skipped": skipped,
            "merged": merged,
            "error": error,
        }

    @staticmethod
    def _feed_error(feed) -> Optional[str]:
        """把 feedparser 的 bozo 状态转成可读错误；正常 feed 返回 None。"""
        if not getattr(feed, "bozo", 0):
            return None
        exc = getattr(feed, "bozo_exception", None)
        if exc is None:
            detail = "未知解析错误"
        else:
            detail = _clean_text(exc) or exc.__class__.__name__
        if not getattr(feed, "entries", None):
            return f"抓取失败: {detail}"
        # 有条目但 bozo：属于「部分可解析」，给出告警而非整体失败。
        return f"部分解析告警: {detail}"

    def _normalize(self, source_id: str, item) -> Optional[Dict]:
        """把一条 feed 条目标准化为文章；信息不足到无法去重时返回 None。

        去重键只由 `标题|链接` 决定，**不含 source_id**——因此同一篇文章若被多个
        feed 转载，只会入库一次（信息聚合的有意行为，避免同一新闻重复出现）。
        代价：文章只归属于首个抓到的源，`list_articles(source_id)` 不会在其他
        转载源下列出它。若后续需要「谁转载了这篇」，应改为记录多个来源，
        而不是放宽去重键。
        """
        source = self.get_source(source_id)
        title = _clean_text(item.get("title"))
        url = _clean_text(item.get("link"))
        if not title and not url:
            return None

        body = _entry_body(item)
        normalized_title = _normalize_title(title)
        canonical_url = _canonical_url(url)
        exact_material = f"{normalized_title}|{canonical_url}"
        key = sha256(exact_material.encode("utf-8")).hexdigest()
        near_hash = _simhash64(f"{normalized_title} {normalized_title} {_plain_text(body)[:1_200]}")
        event_material = normalized_title or canonical_url or key
        return {
            "id": key,
            "source_id": source_id,
            "source_ids": [source_id],
            "source_count": 1,
            "duplicate_count": 0,
            "title": title,
            "url": url,
            "canonical_url": canonical_url,
            "normalized_title": normalized_title,
            "exact_hash": sha256(exact_material.encode("utf-8")).hexdigest(),
            "near_duplicate_hash": near_hash,
            "event_cluster_id": "evt_" + sha256(event_material.encode("utf-8")).hexdigest()[:16],
            "summary": body,
            "published": _clean_text(item.get("published")),
            "category": source.get("category", "general"),
            "tags": _entry_tags(item, source.get("category", "general")),
            "analysis": _market_impact(title, body),
            "source_credibility": int(source.get("credibility", 50)),
            "source_health_score": self._source_health_score(source_id),
            "created_at": datetime.now().isoformat(),
        }

    def _find_near_duplicate(self, article: Dict) -> Optional[Dict]:
        candidates = list(self.articles.values())[-_NEAR_DUPLICATE_SCAN_LIMIT:]
        incoming_url = article.get("canonical_url") or ""
        incoming_title = article.get("normalized_title") or ""
        incoming_hash = article.get("near_duplicate_hash") or ""
        incoming_time = _published_timestamp(article.get("published")) or _published_timestamp(article.get("created_at"))
        for existing in reversed(candidates):
            existing_url = existing.get("canonical_url") or ""
            if incoming_url and existing_url and incoming_url == existing_url:
                return existing

            existing_time = _published_timestamp(existing.get("published")) or _published_timestamp(existing.get("created_at"))
            if incoming_time and existing_time and abs(incoming_time - existing_time) > _NEAR_DUPLICATE_WINDOW_SECONDS:
                continue

            existing_title = existing.get("normalized_title") or ""
            if incoming_title and incoming_title == existing_title:
                return existing

            if len(incoming_title) >= 18 and len(existing_title) >= 18:
                if _hamming_distance(incoming_hash, existing.get("near_duplicate_hash") or "") <= 3:
                    return existing
        return None

    def _merge_duplicate(self, existing: Dict, incoming: Dict) -> None:
        sources = list(existing.get("source_ids") or [existing.get("source_id")])
        source_id = incoming.get("source_id")
        new_source = bool(source_id and source_id not in sources)
        if source_id and source_id not in sources:
            sources.append(source_id)
        existing["source_ids"] = [item for item in sources if item]
        existing["source_count"] = len(existing["source_ids"])
        # duplicate_count 表示“其它独立来源的重复报道”，不能因为同一个 RSS
        # 每 5 分钟继续返回同一条文章就不断降低 novelty。
        if new_source:
            existing["duplicate_count"] = max(
                int(existing.get("duplicate_count") or 0) + 1,
                existing["source_count"] - 1,
            )
        existing["source_credibility"] = max(
            int(existing.get("source_credibility") or 0),
            int(incoming.get("source_credibility") or 0),
        )
        existing["source_health_score"] = max(
            float(existing.get("source_health_score") or 0),
            float(incoming.get("source_health_score") or 0),
        )

    def _record_source_health(self, source_id: str, success_weight: float, fetched: int, accepted: int) -> None:
        with self._state_lock:
            stats = self._source_health.setdefault(source_id, {
                "attempts": 0, "success_weight": 0.0, "fetched": 0, "accepted": 0,
            })
            stats["attempts"] += 1
            stats["success_weight"] += max(0.0, min(1.0, float(success_weight)))
            stats["fetched"] += max(0, int(fetched))
            stats["accepted"] += max(0, int(accepted))

    def _source_health_score(self, source_id: str) -> float:
        with self._state_lock:
            stats = self._source_health.get(source_id)
            if not stats:
                return 80.0
            # Beta-style smoothing：新来源先验约 80，避免第一次短暂失败就被永久打低。
            return round((stats["success_weight"] + 4.0) / (stats["attempts"] + 5.0) * 100.0, 2)

    def _score_article(self, article: Dict, now: datetime) -> None:
        credibility = max(0.0, min(100.0, float(article.get("source_credibility") or 50)))
        health = max(0.0, min(100.0, float(article.get("source_health_score") or 80)))

        timestamp = _published_timestamp(article.get("published")) or _published_timestamp(article.get("created_at"))
        if timestamp is None:
            freshness = 22.0
            age_hours = None
        else:
            age_hours = max(0.0, (now.timestamp() - timestamp) / 3600.0)
            freshness = max(0.0, min(100.0, (2 ** (-age_hours / 18.0)) * 100.0))
        article["recency_timestamp"] = round(float(timestamp or 0.0), 3)

        quality = 0.0
        quality += 30.0 if _clean_text(article.get("title")) else 0.0
        quality += 18.0 if _clean_text(article.get("canonical_url")) else 0.0
        summary_length = len(_plain_text(article.get("summary")))
        quality += min(27.0, summary_length / 320.0 * 27.0)
        quality += 15.0 if _published_timestamp(article.get("published")) is not None else 0.0
        quality += 10.0 if article.get("tags") else 0.0
        quality = max(0.0, min(100.0, quality))

        source_count = max(1, int(article.get("source_count") or 1))
        confirmation = 0.0 if source_count <= 1 else min(100.0, math.log2(source_count) / math.log2(5) * 100.0)
        duplicates = max(0, int(article.get("duplicate_count") or 0))
        novelty = max(55.0, 100.0 - min(45.0, duplicates * 7.0))

        rank = (
            credibility * 0.24
            + freshness * 0.26
            + quality * 0.14
            + confirmation * 0.14
            + novelty * 0.12
            + health * 0.10
        )

        reasons: List[str] = [f"来源可信度 {int(round(credibility))}"]
        if source_count > 1:
            reasons.append(f"{source_count} 个来源确认")
        if age_hours is not None and age_hours <= 6:
            reasons.append("6 小时内发布")
        elif age_hours is not None and age_hours <= 24:
            reasons.append("24 小时内发布")
        if quality >= 75:
            reasons.append("信息字段完整")

        article["content_quality_score"] = round(quality, 2)
        article["freshness_score"] = round(freshness, 2)
        article["novelty_score"] = round(novelty, 2)
        article["confirmation_score"] = round(confirmation, 2)
        article["rank_score"] = round(rank, 2)
        article["selection_reason"] = reasons[:4]

    def _prune_articles(self, now: datetime) -> None:
        cutoff = now.timestamp() - max(1, int(self.ARTICLE_RETENTION_DAYS)) * 86400
        expired = []
        for article_id, article in self.articles.items():
            timestamp = (
                _published_timestamp(article.get("published"))
                or _published_timestamp(article.get("created_at"))
            )
            if timestamp is not None and timestamp < cutoff:
                expired.append(article_id)
        for article_id in expired:
            self.articles.pop(article_id, None)

        cap = max(1, int(self.MAX_ARTICLES))
        if len(self.articles) <= cap:
            return
        ordered = sorted(
            self.articles.values(),
            key=lambda article: (
                _published_timestamp(article.get("published"))
                or _published_timestamp(article.get("created_at"))
                or 0.0
            ),
            reverse=True,
        )
        keep = {article["id"] for article in ordered[:cap] if article.get("id")}
        for article_id in list(self.articles):
            if article_id not in keep:
                self.articles.pop(article_id, None)

    # ---- 文章 ----

    def list_articles(self, source_id: Optional[str] = None) -> List[Dict]:
        with self._state_lock:
            articles = list(self.articles.values())
        if source_id is None:
            return articles
        wanted = _clean_text(source_id)
        return [a for a in articles if wanted in (a.get("source_ids") or [a.get("source_id")])]

    # ---- 每日要闻 ----

    def digest(self, refresh: bool = True, force: bool = False) -> Dict:
        """刷新（受间隔限制）并返回合并后的最新资讯。

        这是"每日要闻"的唯一入口：抓取归本模块，Java 主后端只做薄代理与降级。
        返回：

            {"generated_at", "refreshed", "ok_sources", "total_sources",
             "sources": [{source_id, name, ok, crawled, fetched, added, error}],
             "articles": [...]}

        单源失败不会让整个 digest 失败：error 逐源给出，articles 仍是已抓到的部分。
        """
        now = datetime.now()
        statuses: List[Dict] = []
        enabled_sources = [source for source in self.list_sources() if source.get("enabled", True)]
        due_sources: List[Dict] = []
        for source in enabled_sources:
            source_id = source["id"]
            name = source.get("name") or source_id
            last = self._last_crawled.get(source_id)
            due = bool(refresh) and (
                force
                or last is None
                or (now - last).total_seconds() >= self.DIGEST_MIN_INTERVAL_SECONDS
            )
            if not due:
                statuses.append({
                    "source_id": source_id, "name": name,
                    "category": source.get("category", "general"),
                    "ok": True, "crawled": False,
                    "fetched": 0, "added": 0, "error": None,
                    "health_score": self._source_health_score(source_id),
                })
                continue
            due_sources.append(source)

        # 外部源彼此独立，并行抓取；单源超时只影响该源，不把十个源的等待时间相加。
        if due_sources:
            workers = min(8, len(due_sources))
            with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="rss") as pool:
                results = list(pool.map(self.crawl, [source["id"] for source in due_sources]))
            for source, result in zip(due_sources, results):
                source_id = source["id"]
                self._last_crawled[source_id] = now
                statuses.append({
                    "source_id": source_id, "name": source.get("name") or source_id,
                    "category": source.get("category", "general"),
                    "ok": bool(result["ok"]), "crawled": True,
                    "fetched": result["fetched"], "added": len(result["added"]),
                    "merged": int(result.get("merged") or 0),
                    "error": result["error"],
                    "health_score": self._source_health_score(source_id),
                })

        self._prune_articles(now)
        articles = self.list_articles()
        for article in articles:
            self._score_article(article, now)
        articles.sort(key=lambda article: (float(article.get("rank_score") or 0), _article_sort_key(article)), reverse=True)
        source_ids = {
            source_id
            for article in articles
            for source_id in (article.get("source_ids") or [article.get("source_id")])
            if source_id
        }
        article_count = len(articles)
        quality_metrics = {
            "article_count": article_count,
            "confirmed_event_count": sum(
                1 for article in articles if int(article.get("source_count") or 1) > 1
            ),
            "duplicate_merge_count": sum(
                int(article.get("duplicate_count") or 0) for article in articles
            ),
            "source_diversity": len(source_ids),
            "average_rank_score": round(
                sum(float(article.get("rank_score") or 0) for article in articles) / article_count, 2
            ) if article_count else 0.0,
            "average_content_quality_score": round(
                sum(float(article.get("content_quality_score") or 0) for article in articles) / article_count, 2
            ) if article_count else 0.0,
        }
        return {
            "generated_at": now.isoformat(),
            "refreshed": sum(1 for item in statuses if item["crawled"]),
            "ok_sources": sum(1 for item in statuses if item["ok"]),
            "total_sources": len(statuses),
            "rank_mode": "intelligence_v1",
            "quality_metrics": quality_metrics,
            "sources": statuses,
            "articles": articles,
        }


rss_store = RSSStore()
