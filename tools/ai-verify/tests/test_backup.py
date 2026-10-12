"""'기록 모두 지우기'는 지우기 전에 기록(verify.db)과 사진 복사본을 백업한다. 백업이 실패하면 지우지 않는다.

실행: python -m pytest -q tools/ai-verify/tests
"""
import sqlite3
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from store import Store  # noqa: E402


def make_store():
    s = Store(tempfile.mkdtemp(prefix="backup-test-"))
    s.add_run("analyze", label="수집 보도", expected={"facility": "sidewalk"})
    (s.image_dir / "run1_r1.jpg").write_bytes(b"\xff\xd8 masked")
    return s


def test_지우기_전에_기록과_사진을_백업():
    s = make_store()
    dest = s.clear()
    assert s.db.execute("SELECT COUNT(*) FROM runs").fetchone()[0] == 0
    assert not list(s.image_dir.glob("*"))
    assert dest.parent == s.dir / "backups"
    db = sqlite3.connect(dest / "verify.db")
    assert db.execute("SELECT label, expected_json FROM runs").fetchone() == ("수집 보도", '{"facility": "sidewalk"}')
    assert (dest / "images" / "run1_r1.jpg").read_bytes() == b"\xff\xd8 masked"


def test_백업이_실패하면_지우지_않음(monkeypatch):
    s = make_store()
    monkeypatch.setattr(s, "backup", lambda: (_ for _ in ()).throw(OSError("디스크 가득 참")))
    with pytest.raises(OSError):
        s.clear()
    assert s.db.execute("SELECT COUNT(*) FROM runs").fetchone()[0] == 1
    assert (s.image_dir / "run1_r1.jpg").exists()
