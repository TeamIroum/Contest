"""검증 기록 저장소 (SQLite).

업무 백엔드가 할 일을 작게 흉내 낸다.
  - AI 결과는 스냅샷으로 쌓는다 (재판독으로 바뀌어도 이전 결과를 덮어쓰지 않음)
  - 가린 사진은 받아서 자기 저장소에 복사하고 버전(image_revision)을 함께 저장한다
  - GET /events 의 마지막 순번(cursor)을 저장한다
"""
from __future__ import annotations

import json
import shutil
import sqlite3
import threading
from datetime import datetime, timezone, timedelta
from pathlib import Path

KST = timezone(timedelta(hours=9))

SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,              -- analyze | verify | privacy | narrative
  photo_id TEXT,
  parent_run_id INTEGER,           -- verify: 처음 판독한 run
  created_at TEXT NOT NULL,
  scenario TEXT,
  label TEXT,                      -- 사용자가 붙인 메모·파일 이름
  request_json TEXT,
  expected_json TEXT,              -- 정답 라벨 {facility, issue, grade} 또는 {result}
  http_status INTEGER,
  latency_ms INTEGER,
  checks_json TEXT,
  image_path TEXT,                 -- 백엔드 쪽 복사본
  image_revision INTEGER,
  hidden INTEGER DEFAULT 0         -- image_hidden 알림 또는 410
);
CREATE INDEX IF NOT EXISTS runs_photo ON runs(photo_id);
CREATE TABLE IF NOT EXISTS snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  source TEXT NOT NULL,            -- response | refresh | event
  response_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS snapshots_run ON snapshots(run_id);
CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY,
  type TEXT NOT NULL,
  photo_id TEXT,
  payload_json TEXT NOT NULL,
  received_at TEXT NOT NULL,
  applied TEXT                     -- 백엔드가 한 처리
);
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT);
"""


def now() -> str:
    return datetime.now(KST).isoformat(timespec="seconds")


class Store:
    def __init__(self, data_dir: str | Path):
        self.dir = Path(data_dir)
        self.image_dir = self.dir / "images"
        self.image_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.db = sqlite3.connect(self.dir / "verify.db", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    # ---- runs ----
    def add_run(self, kind: str, *, photo_id=None, parent_run_id=None, scenario=None, label=None,
                request=None, expected=None, http_status=None, latency_ms=None, checks=None,
                response=None) -> int:
        with self._lock:
            cur = self.db.execute(
                "INSERT INTO runs(kind, photo_id, parent_run_id, created_at, scenario, label, request_json,"
                " expected_json, http_status, latency_ms, checks_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (kind, photo_id, parent_run_id, now(), scenario, label, _dump(request), _dump(expected),
                 http_status, latency_ms, _dump(checks)))
            run_id = cur.lastrowid
            if response is not None:
                self.db.execute("INSERT INTO snapshots(run_id, created_at, source, response_json) VALUES (?,?,?,?)",
                                (run_id, now(), "response", _dump(response)))
            self.db.commit()
            return run_id

    def update_run(self, run_id: int, **fields):
        if not fields:
            return
        cols = []
        vals = []
        for k, v in fields.items():
            if k in ("checks", "expected", "request"):
                k, v = f"{k}_json", _dump(v)
            cols.append(f"{k} = ?")
            vals.append(v)
        with self._lock:
            self.db.execute(f"UPDATE runs SET {', '.join(cols)} WHERE id = ?", (*vals, run_id))
            self.db.commit()

    def add_snapshot(self, run_id: int, source: str, response: dict):
        with self._lock:
            self.db.execute("INSERT INTO snapshots(run_id, created_at, source, response_json) VALUES (?,?,?,?)",
                            (run_id, now(), source, _dump(response)))
            self.db.commit()

    def get_run(self, run_id: int, with_snapshots: bool = True) -> dict | None:
        row = self.db.execute("SELECT * FROM runs WHERE id = ?", (run_id,)).fetchone()
        if not row:
            return None
        run = _run(row)
        snaps = self.db.execute("SELECT * FROM snapshots WHERE run_id = ? ORDER BY id", (run_id,)).fetchall()
        run["response"] = json.loads(snaps[-1]["response_json"]) if snaps else None
        if with_snapshots:
            run["snapshots"] = [{"id": s["id"], "created_at": s["created_at"], "source": s["source"],
                                 "response": json.loads(s["response_json"])} for s in snaps]
        return run

    def runs_by_photo(self, photo_id: str) -> list[int]:
        return [r["id"] for r in self.db.execute("SELECT id FROM runs WHERE photo_id = ? ORDER BY id", (photo_id,))]

    def list_runs(self, kind: str | None = None, limit: int = 200) -> list[dict]:
        q = "SELECT * FROM runs" + (" WHERE kind = ?" if kind else "") + " ORDER BY id DESC LIMIT ?"
        rows = self.db.execute(q, (kind, limit) if kind else (limit,)).fetchall()
        out = []
        for row in rows:
            run = _run(row)
            snap = self.db.execute("SELECT response_json, (SELECT COUNT(*) FROM snapshots WHERE run_id = ?) n"
                                   " FROM snapshots WHERE run_id = ? ORDER BY id DESC LIMIT 1",
                                   (row["id"], row["id"])).fetchone()
            run["response"] = json.loads(snap["response_json"]) if snap else None
            run["snapshot_count"] = snap["n"] if snap else 0
            out.append(run)
        return out

    def backup(self) -> Path:
        """기록(verify.db)과 가린 사진 복사본을 backups/<시각>/에 통째로 남긴다. 백업은 자동으로 지우지 않는다."""
        dest = self.dir / "backups" / datetime.now(KST).strftime("%Y%m%d-%H%M%S")
        (dest / "images").mkdir(parents=True, exist_ok=False)
        with self._lock:
            out = sqlite3.connect(dest / "verify.db")
            try:
                self.db.backup(out)   # 쓰는 중이어도 일관된 사본 (파일 복사보다 안전)
            finally:
                out.close()
        for f in self.image_dir.glob("*"):
            if f.is_file():
                shutil.copy2(f, dest / "images" / f.name)
        return dest

    def clear(self) -> Path:
        """기록을 모두 지운다. 지우기 전에 반드시 백업한다 (백업이 실패하면 지우지 않음). 돌려주는 것: 백업 위치"""
        dest = self.backup()
        with self._lock:
            self.db.executescript("DELETE FROM runs; DELETE FROM snapshots; DELETE FROM events; DELETE FROM kv;"
                                  " DELETE FROM sqlite_sequence;")
            self.db.commit()
        for f in self.image_dir.glob("*"):
            f.unlink(missing_ok=True)
        return dest

    # ---- events ----
    def cursor(self) -> int:
        row = self.db.execute("SELECT value FROM kv WHERE key = 'events_after'").fetchone()
        return int(row["value"]) if row else 0

    def set_cursor(self, after: int):
        with self._lock:
            self.db.execute("INSERT INTO kv(key, value) VALUES ('events_after', ?)"
                            " ON CONFLICT(key) DO UPDATE SET value = excluded.value", (str(after),))
            self.db.commit()

    def add_event(self, ev: dict, applied: str):
        with self._lock:
            self.db.execute("INSERT OR REPLACE INTO events(seq, type, photo_id, payload_json, received_at, applied)"
                            " VALUES (?,?,?,?,?,?)",
                            (ev.get("seq"), ev.get("type"), ev.get("photo_id"), _dump(ev), now(), applied))
            self.db.commit()

    def list_events(self, limit: int = 200) -> list[dict]:
        rows = self.db.execute("SELECT * FROM events ORDER BY seq DESC LIMIT ?", (limit,)).fetchall()
        return [{"seq": r["seq"], "type": r["type"], "photo_id": r["photo_id"], "received_at": r["received_at"],
                 "applied": r["applied"], "payload": json.loads(r["payload_json"])} for r in rows]


def _dump(v):
    return None if v is None else json.dumps(v, ensure_ascii=False)


def _run(row: sqlite3.Row) -> dict:
    d = dict(row)
    for k in ("request_json", "expected_json", "checks_json"):
        d[k[:-5]] = json.loads(d.pop(k)) if d.get(k) else None
    d["hidden"] = bool(d.get("hidden"))
    return d
