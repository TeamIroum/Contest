"""평가 스크립트는 실행마다 새로 판독해야 한다 (서버 저장소의 이전 결과를 재사용하지 않음)."""
import csv
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

import run_eval  # noqa: E402
from providers.mock_provider import MockProvider  # noqa: E402


def test_second_run_reanalyzes_instead_of_replaying(monkeypatch, tmp_path):
    labels = tmp_path / "labels.csv"
    shutil.copy(ROOT / "eval" / "sample" / "s01.jpg", tmp_path / "s01.jpg")
    labels.write_text("photo_id,image_path,facility_type,issue_type,severity,quality_ok,location_tags,capture_hour,note\n"
                      "same_id,s01.jpg,drain,,0,1,,,\n", encoding="utf-8")
    outs = []
    for scenario in ("drain_normal", "sidewalk_danger"):          # 두 번째 실행은 판독 결과가 달라지게 함
        p = MockProvider()
        p.next_scenario = scenario
        monkeypatch.setattr(run_eval, "get_provider", lambda name, p=p: p)
        monkeypatch.setattr(sys, "argv", ["run_eval.py", "analyze", "--labels", str(labels), "--provider", "mock"])
        before = set((ROOT / "eval" / "results").glob("*"))
        run_eval.main()
        out = (set((ROOT / "eval" / "results").glob("*")) - before).pop()
        outs.append(out)
        time.sleep(1.1)                                              # 결과 폴더 이름이 초 단위라 겹치지 않게
    try:
        grades = [list(csv.DictReader((o / "predictions.csv").open(encoding="utf-8")))[0]["pred_grade"] for o in outs]
        assert grades == ["정상", "긴급"]
    finally:
        for o in outs:
            shutil.rmtree(o, ignore_errors=True)
