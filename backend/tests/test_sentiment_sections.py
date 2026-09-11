"""研报情感分析（FR-08）固定小节切分与争议焦点解析测试。

运行：在仓库根目录 `pytest backend/tests/test_sentiment_sections.py -v`。
"""
import pytest

from backend.app.ai_service import analyze_sentiment, parse_sentiment_sections

SAMPLE = """【情感摘要】
[1] 看多，置信度 0.75：央行购金延续 + 地缘避险支撑中长期金价。
[2] 看空，置信度 0.6：短期涨幅已透支，估值偏高。
综合判断：中长期偏多，短期存分歧。

【争议焦点】
短期金价是否高估 | 央行持续购金与降息预期提供支撑，回调即买入 | 年内涨幅已透支，目标价上限低于现价
- **降息节奏** | 通胀回落将推动年内两次降息 | 通胀黏性使降息推迟至明年
3. 美元走势 | 美元指数走弱利好金价 | 避险需求推升美元，压制金价

【趋势判断】
央行购金与地缘风险构成中长期底部支撑。

【评级与目标价】
6 家买入 / 3 家中性 / 1 家卖出；目标价区间 285-300 元。
"""


def test_parse_sentiment_sections_extracts_all_sections():
    parsed = parse_sentiment_sections(SAMPLE)

    assert set(parsed["sections"]) == {"情感摘要", "争议焦点", "趋势判断", "评级与目标价"}
    assert "央行购金延续" in parsed["sections"]["情感摘要"]
    assert parsed["sections"]["评级与目标价"].startswith("6 家买入")
    # 争议焦点小节正文不包含下一小节的标题
    assert "【趋势判断】" not in parsed["sections"]["争议焦点"]


def test_parse_sentiment_sections_parses_dispute_rows():
    disputes = parse_sentiment_sections(SAMPLE)["disputes"]

    assert [item["id"] for item in disputes] == ["dispute-1", "dispute-2", "dispute-3"]
    assert disputes[0] == {
        "id": "dispute-1",
        "topic": "短期金价是否高估",
        "bull": "央行持续购金与降息预期提供支撑，回调即买入",
        "bear": "年内涨幅已透支，目标价上限低于现价",
    }
    # 列表符号与加粗标记被清理
    assert disputes[1]["topic"] == "降息节奏"
    assert disputes[1]["bear"] == "通胀黏性使降息推迟至明年"
    # 有序列表前缀被清理
    assert disputes[2]["topic"] == "美元走势"


def test_parse_sentiment_sections_accepts_fullwidth_separator():
    parsed = parse_sentiment_sections("【争议焦点】\n主题｜多方说法｜空方说法\n")

    assert parsed["disputes"] == [{
        "id": "dispute-1",
        "topic": "主题",
        "bull": "多方说法",
        "bear": "空方说法",
    }]


def test_parse_sentiment_sections_strips_side_labels():
    parsed = parse_sentiment_sections(
        "【争议焦点】\n焦点：估值水平 | 多方：业绩兑现可消化估值 | 空方：增速下滑将杀估值\n"
    )

    assert parsed["disputes"][0] == {
        "id": "dispute-1",
        "topic": "估值水平",
        "bull": "业绩兑现可消化估值",
        "bear": "增速下滑将杀估值",
    }


def test_parse_sentiment_sections_treats_dash_as_no_opposing_view():
    parsed = parse_sentiment_sections("【争议焦点】\n无显著争议 | 各方结论一致 | -\n")

    assert parsed["disputes"][0]["bull"] == "各方结论一致"
    assert parsed["disputes"][0]["bear"] == ""


def test_parse_sentiment_sections_ignores_rows_without_two_parts():
    parsed = parse_sentiment_sections(
        "【争议焦点】\n单段说明没有分隔符\n\n另一个单段\n主题 | 多方\n"
    )

    assert [item["topic"] for item in parsed["disputes"]] == ["主题"]


def test_parse_sentiment_sections_caps_dispute_count():
    body = "\n".join(f"焦点{i} | 多方{i} | 空方{i}" for i in range(1, 11))
    parsed = parse_sentiment_sections(f"【争议焦点】\n{body}\n")

    assert len(parsed["disputes"]) == 6
    assert parsed["disputes"][-1]["topic"] == "焦点6"


def test_parse_sentiment_sections_truncates_long_fields():
    parsed = parse_sentiment_sections(f"【争议焦点】\n{'焦' * 500} | {'多' * 500} | {'空' * 500}\n")

    assert len(parsed["disputes"][0]["topic"]) == 300
    assert len(parsed["disputes"][0]["bull"]) == 300
    assert len(parsed["disputes"][0]["bear"]) == 300


@pytest.mark.parametrize("payload", ["", "   ", "没有小节的普通文本\n再看这一行", None, 123, {"content": "x"}])
def test_parse_sentiment_sections_returns_empty_for_non_section_text(payload):
    parsed = parse_sentiment_sections(payload)

    assert parsed == {"sections": {}, "disputes": []}


def test_parse_sentiment_sections_keeps_first_of_duplicate_sections():
    parsed = parse_sentiment_sections("【情感摘要】第一次\n【情感摘要】第二次\n")

    assert parsed["sections"]["情感摘要"] == "第一次"


def test_parse_sentiment_sections_is_deterministic():
    assert parse_sentiment_sections(SAMPLE) == parse_sentiment_sections(SAMPLE)


def test_analyze_sentiment_returns_sections_and_disputes(monkeypatch):
    monkeypatch.setattr(
        "backend.app.ai_service._chat_request",
        lambda *args, **kwargs: {"content": SAMPLE, "role": "assistant", "model": "test", "usage": None},
    )

    result = analyze_sentiment(["研报一", "研报二"])

    assert result["sections"]["情感摘要"]
    assert len(result["disputes"]) == 3
    # 向后兼容：content 仍是模型原文字符串
    assert result["content"] == SAMPLE


def test_analyze_sentiment_prompt_requires_fixed_sections(monkeypatch):
    calls = {}

    def fake_chat_request(messages, temperature=0.7, max_tokens=None):
        calls["prompt"] = messages[0]["content"]
        return {"content": SAMPLE, "role": "assistant", "model": "test", "usage": None}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)

    analyze_sentiment(["研报一", "研报二"])

    prompt = calls["prompt"]
    for section in ("【情感摘要】", "【争议焦点】", "【趋势判断】", "【评级与目标价】"):
        assert section in prompt
    assert "焦点 | 多方论据 | 空方论据" in prompt
    assert "[1] 研报一" in prompt and "[2] 研报二" in prompt
