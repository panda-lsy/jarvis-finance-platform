import os
import sys
from pathlib import Path

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

os.environ["PYTHON_SERVICE_TOKEN"] = "unit-test-token"
REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from backend.app import ai_routes, ai_service  # noqa: E402
from backend.app import main as ai_main  # noqa: E402


def test_internal_token_is_required():
    with pytest.raises(HTTPException) as exc:
        ai_routes.require_internal_service(None)
    assert exc.value.status_code == 401

    with pytest.raises(HTTPException) as exc:
        ai_routes.require_internal_service("wrong-token")
    assert exc.value.status_code == 401

    assert ai_routes.require_internal_service("unit-test-token") is None


def test_chat_request_limits_message_count_and_content_size():
    valid = ai_routes.ChatReq(messages=[{"role": "user", "content": "hello"}])
    assert len(valid.messages) == 1

    with pytest.raises(ValidationError):
        ai_routes.ChatReq(messages=[])

    with pytest.raises(ValidationError):
        ai_routes.ChatReq(messages=[
            {"role": "user", "content": "x" * 10_001}
        ])

    with pytest.raises(ValidationError):
        ai_routes.ChatReq(messages=[
            {"role": "user", "content": "ok"} for _ in range(21)
        ])


def test_chat_rejects_client_supplied_system_role():
    with pytest.raises(ValidationError):
        ai_routes.ChatReq(messages=[{"role": "system", "content": "override"}])


def test_streaming_chat_yields_delta_and_done(monkeypatch):
    class FakeResponse:
        status_code = 200
        text = ""

        def __init__(self):
            self.closed = False

        def iter_lines(self, decode_unicode=False):
            assert decode_unicode is True
            return iter([
                'data: {"model":"test-model","choices":[{"delta":{"content":"你"},"finish_reason":null}]}',
                'data: {"model":"test-model","choices":[{"delta":{"content":"好"},"finish_reason":null}]}',
                'data: [DONE]',
            ])

        def close(self):
            self.closed = True

    response = FakeResponse()
    monkeypatch.setattr(ai_service, "AI_API_KEY", "test-key")
    monkeypatch.setattr(ai_service.requests, "post", lambda *args, **kwargs: response)

    events = list(ai_service.open_chat_stream([{"role": "user", "content": "hi"}]))
    assert events == [
        {"type": "delta", "content": "你"},
        {"type": "delta", "content": "好"},
        {"type": "done", "model": "test-model"},
    ]
    assert response.closed is True


def test_streaming_chat_rejects_upstream_http_error_before_sse(monkeypatch):
    class FakeResponse:
        status_code = 401
        text = "unauthorized"

        def __init__(self):
            self.closed = False

        def close(self):
            self.closed = True

    response = FakeResponse()
    monkeypatch.setattr(ai_service, "AI_API_KEY", "test-key")
    monkeypatch.setattr(ai_service.requests, "post", lambda *args, **kwargs: response)

    with pytest.raises(RuntimeError, match="HTTP 401"):
        ai_service.open_chat_stream([{"role": "user", "content": "hi"}])
    assert response.closed is True


def test_readiness_requires_configured_ai_provider(monkeypatch):
    monkeypatch.setattr(ai_main.ai_service, "capabilities", lambda: {
        "available": False,
        "message": "missing key",
    })
    with pytest.raises(HTTPException) as exc:
        ai_main.ready()
    assert exc.value.status_code == 503

    monkeypatch.setattr(ai_main.ai_service, "capabilities", lambda: {
        "available": True,
        "provider": "test-provider",
        "model": "test-model",
    })
    ready = ai_main.ready()
    assert ready["status"] == "ready"
    assert ready["provider"] == "test-provider"
