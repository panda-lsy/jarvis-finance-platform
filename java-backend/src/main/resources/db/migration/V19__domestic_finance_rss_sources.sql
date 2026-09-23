-- 国内权威财经来源。链接取自来源网站公开的 RSS 订阅目录。
INSERT INTO news_source
    (source_key, name, url, category, credibility, enabled, created_at, updated_at)
VALUES
    ('china_news_finance', '中新网财经', 'https://www.chinanews.com.cn/rss/finance.xml', 'markets', 86, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('xinhuanet_finance', '新华网金融', 'http://www.xinhuanet.com/finance/news_finance.xml', 'markets', 90, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('xinhuanet_economy', '新华网财经', 'http://www.xinhuanet.com/fortune/news_fortune.xml', 'markets', 90, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
