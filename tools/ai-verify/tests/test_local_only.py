"""검증 콘솔 화면·콘솔 API는 이 PC에서만, 휴대폰 앱이 쓰는 곳(/api/v1, /pwa)은 터널 너머에도 열린다.

실행: python -m pytest -q tools/ai-verify/tests
"""
import os
import sys
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
os.environ.setdefault("VERIFY_DATA_DIR", tempfile.mkdtemp(prefix="verify-test-"))   # 실제 기록을 건드리지 않게

import app as console  # noqa: E402

LOCAL = TestClient(console.app, base_url="http://127.0.0.1:8100", client=("127.0.0.1", 50000))
# 터널은 이 PC 안에서 넘겨주므로 보낸 쪽 주소는 127.0.0.1로 보인다. 주소(Host)와 헤더로만 바깥임을 안다
FUNNEL = TestClient(console.app, base_url="https://desktop-test.tail0000.ts.net:8443", client=("127.0.0.1", 50001))
CONSOLE_PATHS = ["/", "/index.html", "/api/runs", "/api/health", "/docs", "/openapi.json", "/api/v1"]
PUBLIC_PATHS = ["/api/v1/me/patrols", "/pwa/", "/pwa/app.js"]


@pytest.mark.parametrize("path", CONSOLE_PATHS)
def test_콘솔은_터널_너머에서_막힘(path):
    r = FUNNEL.get(path)
    assert r.status_code == 403, (path, r.status_code)


@pytest.mark.parametrize("headers", [
    {"Tailscale-Funnel-Request": "?1"}, {"X-Forwarded-For": "203.0.113.5"}, {"Cf-Connecting-Ip": "203.0.113.5"},
])
def test_전달_헤더가_있으면_주소가_이_PC여도_막힘(headers):
    assert LOCAL.get("/api/runs", headers=headers).status_code == 403


@pytest.mark.parametrize("path", PUBLIC_PATHS)
def test_휴대폰_앱이_쓰는_곳은_열림(path):
    assert FUNNEL.get(path).status_code == 200, path


@pytest.mark.parametrize("path", ["/", "/api/runs", "/pwa/"])
def test_이_PC에서는_모두_열림(path):
    assert LOCAL.get(path).status_code == 200, path


def test_앱_실패_기록_조회는_이_PC에서만():
    assert FUNNEL.get("/api/v1/test-client-logs").status_code == 403
    assert LOCAL.get("/api/v1/test-client-logs").status_code == 200
