"""AI 서버 처리 테스트: 중복 요청, 혼잡·오류 시 재판독, 인증."""
import importlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import jobs  # noqa: E402
import pipeline  # noqa: E402
import storage  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402

IMG = (Path(__file__).resolve().parent.parent / "eval" / "sample" / "s01.jpg").read_bytes()


class Counting(MockProvider):
    def __init__(self):
        super().__init__()
        self.calls = 0

    def analyze(self, *a, **k):
        self.calls += 1
        self.next_scenario = "sidewalk_danger"
        return super().analyze(*a, **k)


def test_same_photo_id_is_processed_once():
    p = Counting()
    r1 = pipeline.analyze_photo(IMG, {"photo_id": "p_dup", "location_tags": []}, p)
    r2 = pipeline.analyze_photo(IMG, {"photo_id": "p_dup", "location_tags": []}, p)
    assert p.calls == 1 and r2["meta"]["replayed"] and r2["grade"] == r1["grade"] == "긴급"


def test_ai_error_then_background_retry_succeeds():
    class FlakyOnce(MockProvider):
        failed = False

        def analyze(self, *a, **k):
            if not FlakyOnce.failed:
                FlakyOnce.failed = True
                raise TimeoutError("시간 초과")
            self.next_scenario = "fence_tilted"
            return super().analyze(*a, **k)

    p = FlakyOnce()
    pipeline.set_default_provider(p)
    r = pipeline.analyze_photo(IMG, {"photo_id": "p_flaky", "location_tags": []}, p)
    assert r["status"] == "needs_review" and r["retry"]["pending"]
    assert jobs.retry_queue.process_due(force=True) == 1
    after = storage.get_results().load("p_flaky")
    assert after["status"] == "ok" and after["grade"] == "주의" and after["retry"] is None
    assert after["meta"]["retried"] == 1


def test_busy_gate_returns_quickly():
    gate = jobs.AIGate(max_concurrent=1, wait_s=0.05)
    gate._sem.acquire()  # 자리가 꽉 찬 상태
    orig, jobs.ai_gate = jobs.ai_gate, gate
    try:
        r = pipeline.analyze_photo(IMG, {"photo_id": "p_busy"}, MockProvider())
    finally:
        jobs.ai_gate = orig
    assert r["status"] == "needs_review" and "AIBusy" in r["notes"][0]


def test_retry_gives_up_after_limit():
    class AlwaysFail(MockProvider):
        def analyze(self, *a, **k):
            raise ConnectionError("끊김")
    p = AlwaysFail()
    pipeline.set_default_provider(p)
    pipeline.analyze_photo(IMG, {"photo_id": "p_dead"}, p)
    for _ in range(5):
        jobs.retry_queue.process_due(force=True)
    r = storage.get_results().load("p_dead")
    assert r["status"] == "needs_review" and r["retry"]["gave_up"]


def test_api_key_required(monkeypatch):
    from fastapi.testclient import TestClient
    monkeypatch.setenv("API_KEYS", "k1,k2")
    import server
    importlib.reload(server)
    try:
        c = TestClient(server.app)
        assert c.get("/health").status_code == 200                       # 상태 확인은 열림
        assert c.get("/taxonomy").status_code == 401
        assert c.get("/taxonomy", headers={"X-API-Key": "wrong"}).status_code == 401
        assert c.get("/taxonomy", headers={"X-API-Key": "k2"}).status_code == 200
    finally:
        monkeypatch.delenv("API_KEYS")
        importlib.reload(server)


def test_results_endpoint():
    from fastapi.testclient import TestClient
    import server
    c = TestClient(server.app)
    c.post("/analyze", files={"image": ("a.jpg", IMG, "image/jpeg")}, data={"photo_id": "p_res"},
           headers={"X-Mock-Scenario": "drain_normal"})
    r = c.get("/results/p_res")
    assert r.status_code == 200 and r.json()["grade"] == "정상"
    assert c.get("/results/p_none").status_code == 404



def test_health_answers_while_analyze_waits_for_ai(monkeypatch):
    """판독이 AI 응답을 기다리는 동안에도 /health 같은 다른 요청은 바로 응답해야 한다 (이벤트 루프를 막지 않음).
    TestClient는 요청마다 이벤트 루프를 따로 만들어 이 문제를 재현하지 못하므로 실제 uvicorn 서버로 확인한다."""
    import socket
    import threading
    import time
    import httpx
    import uvicorn
    import server
    started, release = threading.Event(), threading.Event()

    def slow_analyze(*a, **k):
        started.set()
        release.wait(10)
        return {"photo_id": "slow"}

    monkeypatch.setattr(server.pipeline, "analyze_photo", slow_analyze)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    srv = uvicorn.Server(uvicorn.Config(server.app, host="127.0.0.1", port=port, log_level="warning", lifespan="off"))
    threading.Thread(target=srv.run, daemon=True).start()
    base = f"http://127.0.0.1:{port}"
    for _ in range(100):
        if srv.started:
            break
        time.sleep(0.05)
    img = (Path(__file__).resolve().parent.parent / "eval" / "sample" / "s01.jpg").read_bytes()
    t = threading.Thread(target=lambda: httpx.post(base + "/analyze", files={"image": ("a.jpg", img, "image/jpeg")}, timeout=15))
    t.start()
    try:
        assert started.wait(5)
        assert httpx.get(base + "/health", timeout=2).status_code == 200
    finally:
        release.set()
        t.join(10)
        srv.should_exit = True
