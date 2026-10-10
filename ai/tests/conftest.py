"""모든 테스트는 임시 폴더를 저장소로 쓰고, 재판독 대기열을 비운 상태에서 시작한다."""
import os
import sys
from pathlib import Path

# 테스트는 빠르게: 사전 학습 번호판·차량 모델은 기본으로 끄고, 그 모델을 직접 시험하는 테스트에서만 켠다
os.environ.setdefault("PLATE_PRETRAINED", "")
os.environ.setdefault("VEHICLE_FALLBACK", "")

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import jobs  # noqa: E402
import storage  # noqa: E402


@pytest.fixture(autouse=True)
def temp_store(tmp_path):
    store = storage.LocalImageStore(tmp_path / "images")
    storage.set_store(store)
    storage.set_results(storage.LocalResultStore(tmp_path / "results"))
    storage.set_media(storage.LocalMediaStore(tmp_path / "results" / "media"))
    storage.set_events(storage.EventLog(tmp_path / "results" / "_events.jsonl"))
    jobs.retry_queue = jobs.RetryQueue(delays=[0, 0, 0])
    yield store
