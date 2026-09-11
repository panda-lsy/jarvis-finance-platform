"""个性化策略生成（FR-11）确定性打分/配置映射与端点测试。

运行：在仓库根目录 `pytest backend/tests/test_strategy.py -v`（或 cd backend 后 pytest tests/test_strategy.py）。
"""
from decimal import Decimal

import pytest
from pydantic import ValidationError

from backend.app.ai_routes import StrategyReq
from backend.app.ai_service import generate_strategy
from backend.app.research_tools import strategy_profile


def _balanced_answers():
    """期限 5 年 / 回撤 20% / 目标 7.5% / 无经验 → 四项得分均落在中位。"""
    return dict(horizon_years=5, max_drawdown_pct=20, target_return_pct=7.5, experience="none")


def test_strategy_profile_deterministic_on_balanced_answers():
    profile = strategy_profile(**_balanced_answers())

    assert profile["available"] is True
    # 50*0.3 + 50*0.3 + 50*0.2 + 0*0.2 = 40.0000，恰好落在稳健型下边界
    assert profile["score"] == "40.0000"
    assert profile["level"] == "balanced"
    assert profile["level_label"] == "稳健型"
    assert [item["pct"] for item in profile["allocation"]] == ["40.0000", "30.0000", "15.0000", "15.0000"]
    assert [item["id"] for item in profile["allocation"]] == ["gold_etf", "bond", "cash", "equity"]
    assert profile["answers"]["experience_label"] == "无经验"
    assert len(profile["sub_scores"]) == 4
    assert profile["sub_scores"][0]["weight_pct"] == "30.0000"


def test_strategy_profile_score_is_stable_across_repeated_calls():
    first = strategy_profile(horizon_years="3", max_drawdown_pct=10, target_return_pct=6, experience="basic")
    second = strategy_profile(horizon_years=3.0, max_drawdown_pct="10.0", target_return_pct="6", experience="basic")

    assert first == second


def test_strategy_profile_extreme_conservative_questionnaire():
    profile = strategy_profile(horizon_years=0.5, max_drawdown_pct=1, target_return_pct=0, experience="none")

    # 5*0.3 + 2.5*0.3 + 0*0.2 + 0*0.2 = 2.2500
    assert profile["score"] == "2.2500"
    assert profile["level"] == "conservative"
    assert profile["level_label"] == "保守型"
    assert [item["pct"] for item in profile["allocation"]] == ["20.0000", "40.0000", "30.0000", "10.0000"]


def test_strategy_profile_extreme_aggressive_questionnaire():
    profile = strategy_profile(horizon_years=30, max_drawdown_pct=60, target_return_pct=50, experience="rich")

    # 四项均被裁剪到 100 分 → 综合 100.0000
    assert profile["score"] == "100.0000"
    assert profile["level"] == "aggressive"
    assert profile["level_label"] == "积极型"
    assert [item["pct"] for item in profile["allocation"]] == ["55.0000", "15.0000", "10.0000", "20.0000"]
    assert all(item["score"] == "100.0000" for item in profile["sub_scores"])


def test_strategy_profile_level_boundary_just_below_balanced():
    answers = _balanced_answers()
    answers["target_return_pct"] = 7.4
    profile = strategy_profile(**answers)

    # 0.2 * 49.3333... → 综合 39.8667，刚好跌破 40 分门槛
    assert profile["score"] == "39.8667"
    assert profile["level"] == "conservative"


@pytest.mark.parametrize("level", ["conservative", "balanced", "aggressive"])
def test_strategy_profile_allocation_always_sums_to_100(level):
    presets = {
        "conservative": dict(horizon_years=1, max_drawdown_pct=5, target_return_pct=1, experience="none"),
        "balanced": dict(horizon_years=5, max_drawdown_pct=16, target_return_pct=7.5, experience="basic"),
        "aggressive": dict(horizon_years=10, max_drawdown_pct=40, target_return_pct=15, experience="rich"),
    }
    profile = strategy_profile(**presets[level])

    assert profile["level"] == level
    assert sum(Decimal(item["pct"]) for item in profile["allocation"]) == Decimal("100")


def test_strategy_profile_unknown_experience_falls_back_to_basic():
    profile = strategy_profile(horizon_years=5, max_drawdown_pct=20, target_return_pct=7.5, experience="vip")

    assert profile["available"] is True
    assert profile["answers"]["experience"] == "basic"
    assert profile["answers"]["experience_label"] == "有一定经验"


def test_strategy_profile_capital_conversion():
    with_capital = strategy_profile(**_balanced_answers(), capital=100000)
    assert with_capital["capital"] == "100000.00"
    # 稳健型黄金 ETF 40% → 40000.00 元
    assert with_capital["gold_amount"] == "40000.00"

    without_capital = strategy_profile(**_balanced_answers())
    assert "capital" not in without_capital
    assert "gold_amount" not in without_capital


def test_strategy_profile_rejects_invalid_questionnaire():
    assert strategy_profile()["reason"] == "invalid_questionnaire"
    assert strategy_profile(horizon_years="abc", max_drawdown_pct=10, target_return_pct=5)["available"] is False
    assert strategy_profile(horizon_years=0, max_drawdown_pct=10, target_return_pct=5)["available"] is False
    assert strategy_profile(horizon_years=5, max_drawdown_pct=-1, target_return_pct=5)["available"] is False
    assert strategy_profile(horizon_years=5, max_drawdown_pct=10, target_return_pct=-1)["available"] is False


def test_strategy_profile_reasons_are_keyed_objects():
    profile = strategy_profile(**_balanced_answers())

    # 前端动态列表统一用 {id, text} 对象做 key
    assert {item["id"] for item in profile["reasons"]} == {
        "horizon", "drawdown", "target_return", "experience", "overall",
    }
    assert all(isinstance(item["text"], str) and item["text"] for item in profile["reasons"])


def test_strategy_request_accepts_valid_ranges():
    request = StrategyReq(horizon_years=5, max_drawdown_pct=20, target_return_pct=7.5, capital=100000)

    assert request.experience == "basic"
    assert request.capital == 100000
    assert StrategyReq(horizon_years=5, max_drawdown_pct=20, target_return_pct=0).target_return_pct == 0


@pytest.mark.parametrize("payload", [
    {"horizon_years": 0.1, "max_drawdown_pct": 20, "target_return_pct": 7.5},
    {"horizon_years": 5, "max_drawdown_pct": 0, "target_return_pct": 7.5},
    {"horizon_years": 5, "max_drawdown_pct": 20, "target_return_pct": 99},
    {"horizon_years": 5, "max_drawdown_pct": 20, "target_return_pct": 7.5, "capital": 0},
    {"horizon_years": 5, "max_drawdown_pct": 20, "target_return_pct": 7.5, "experience": "vip"},
])
def test_strategy_request_rejects_out_of_range_answers(payload):
    with pytest.raises(ValidationError):
        StrategyReq(**payload)


def test_generate_strategy_skips_llm_when_questionnaire_invalid(monkeypatch):
    def fail_chat_request(*args, **kwargs):  # pragma: no cover - 不应被调用
        raise AssertionError("问卷非法时不得调用 LLM")

    monkeypatch.setattr("backend.app.ai_service._chat_request", fail_chat_request)
    result = generate_strategy(None, 20, 7.5)

    assert result["available"] is False
    assert result["reason"] == "invalid_questionnaire"
    assert "content" not in result


def test_generate_strategy_calls_llm_with_deterministic_profile(monkeypatch):
    calls = {}

    def fake_chat_request(messages, temperature=0.7, max_tokens=None):
        calls["messages"] = messages
        calls["temperature"] = temperature
        return {"content": "策略说明占位", "role": "assistant", "model": "test", "usage": None}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)
    result = generate_strategy(5, 20, 7.5, capital=100000, experience="none")

    assert result["available"] is True
    assert result["content"]["content"] == "策略说明占位"
    assert result["profile"]["level"] == "balanced"
    assert result["profile"]["score"] == "40.0000"
    assert calls["temperature"] == 0.4
    prompt = calls["messages"][0]["content"]
    assert "确定性计算结果" in prompt
    assert "稳健型" in prompt
    assert "40000.00" in prompt  # 黄金 ETF 建议金额已注入 prompt
