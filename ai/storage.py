"""가린 사진 저장소.

원칙: 서버 디스크에는 가린(마스킹된) 사진만 저장한다. 원본은 메모리에서만 다루고 어디에도 쓰지 않는다.
- 저장 위치: 환경 변수 IMAGE_DIR (기본 data/images/)
- 접근 주소: /images/<photo_id>_masked.jpg  (server.py가 이 주소로 내려준다)

실제 운영에서 시 서버나 클라우드 저장소로 옮길 때는 LocalImageStore와 같은 메서드를 가진 클래스를 만들어
get_store()만 바꾸면 된다. 파이프라인 코드는 바꾸지 않는다.
"""
import os
import re
import threading
import uuid
from contextlib import contextmanager
from pathlib import Path

from config import BASE_DIR

URL_PREFIX = "/images/"
SUFFIX = "_masked.jpg"
REV_SUFFIX = "_masked.rev"
_rev_lock = threading.Lock()
_SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")


def safe_photo_id(photo_id: str | None) -> str:
    """파일 이름으로 써도 안전한 ID만 허용한다. 아니면 새로 만든다."""
    if photo_id and _SAFE_ID.match(photo_id):
        return photo_id
    return f"p_{uuid.uuid4().hex[:12]}"


def is_client_photo_id(photo_id: str) -> bool:
    """앱·백엔드가 정해 보내는 photo_id로 쓸 수 있는지. m_로 시작하는 ID는 서버 내부용
    (참고·보수 사진, 수리 전 사진 사본)이라 받지 않는다. 받으면 서로의 사진을 덮어쓸 수 있다."""
    return bool(photo_id) and bool(_SAFE_ID.match(photo_id)) and not photo_id.startswith(MEDIA_PREFIX)


def _tmp(path: Path) -> Path:
    """같은 파일을 두 곳에서 동시에 써도 임시 파일이 겹치지 않게 이름을 따로 붙인다."""
    return path.with_name(f"{path.name}.{uuid.uuid4().hex[:8]}.tmp")


_photo_locks: dict[str, list] = {}   # photo_id → [잠금, 쓰는 수]
_photo_locks_guard = threading.Lock()


@contextmanager
def photo_lock(photo_id: str):
    """같은 photo_id의 판독·재판독·가림 점검 기록이 동시에 진행되지 않게 한다.
    (먼저 시작한 쪽이 결과를 저장한 뒤에 다음 쪽이 그 결과를 읽는다) 한 프로세스 기준이다."""
    with _photo_locks_guard:
        entry = _photo_locks.setdefault(photo_id, [threading.RLock(), 0])
        entry[1] += 1
    try:
        with entry[0]:
            yield
    finally:
        with _photo_locks_guard:
            entry[1] -= 1
            if entry[1] == 0:
                _photo_locks.pop(photo_id, None)


class LocalImageStore:
    def __init__(self, root: str | Path | None = None):
        self.root = Path(root or os.environ.get("IMAGE_DIR") or BASE_DIR / "data" / "images")
        self.root.mkdir(parents=True, exist_ok=True)
        # 사진 버전 기록은 사진 폴더 밖에 둔다 (사진 폴더에는 가린 사진만)
        self.rev_root = self.root.parent / (self.root.name + "_revisions")
        self.rev_root.mkdir(parents=True, exist_ok=True)

    def exists(self, photo_id: str) -> bool:
        return (self.root / (safe_photo_id(photo_id) + SUFFIX)).is_file()

    def save_masked(self, photo_id: str, masked_jpeg: bytes) -> str:
        """가린 사진을 저장하고 접근 주소를 돌려준다. 원본을 넘기지 않도록 호출하는 쪽에서 주의한다.
        저장할 때마다 사진 버전(revision)이 1씩 오른다. 처음 저장이 1이다."""
        name = safe_photo_id(photo_id) + SUFFIX
        with _rev_lock:
            rev = self.revision(photo_id) + 1  # 사진을 쓰기 전에 읽어야 첫 저장이 1이 된다
            tmp = _tmp(self.root / name)
            tmp.write_bytes(masked_jpeg)
            tmp.replace(self.root / name)  # 쓰는 도중에 읽히지 않도록 다 쓴 뒤 이름을 바꾼다
            rp = self.rev_root / (safe_photo_id(photo_id) + REV_SUFFIX)
            rtmp = _tmp(rp)
            rtmp.write_text(str(rev), encoding="utf-8")
            rtmp.replace(rp)
        return URL_PREFIX + name

    def revision(self, photo_id: str) -> int:
        """저장된 가린 사진의 버전. 사진이 없으면 0."""
        key = photo_id[len(URL_PREFIX):] if photo_id.startswith(URL_PREFIX) else photo_id
        key = key.removesuffix(SUFFIX)
        if not _SAFE_ID.match(key):
            return 0
        rp = self.rev_root / (key + REV_SUFFIX)
        if rp.is_file():
            try:
                return int(rp.read_text(encoding="utf-8").strip() or 0)
            except ValueError:
                return 0
        return 1 if (self.root / (key + SUFFIX)).is_file() else 0  # 버전 기록 이전에 저장된 사진

    def delete_masked(self, photo_id: str) -> bool:
        """보관 기간이 지난 가린 사진과 버전 기록을 지운다. 지웠으면 True."""
        key = safe_photo_id(photo_id)
        if key != photo_id:
            return False
        with _rev_lock:
            found = False
            for p in (self.root / (key + SUFFIX), self.rev_root / (key + REV_SUFFIX)):
                if p.is_file():
                    p.unlink()
                    found = True
        return found

    def path_for(self, file_name: str) -> Path | None:
        """주소의 파일 이름으로 실제 경로를 찾는다. 가린 사진 이름 형식이 아니면 None (경로 조작 방지)."""
        if not file_name.endswith(SUFFIX) or not _SAFE_ID.match(file_name[: -len(SUFFIX)]):
            return None
        p = self.root / file_name
        return p if p.is_file() else None

    def load_masked(self, url_or_photo_id: str) -> bytes | None:
        """주소(/images/..._masked.jpg) 또는 photo_id로 가린 사진을 읽는다."""
        key = url_or_photo_id
        if key.startswith(URL_PREFIX):
            key = key[len(URL_PREFIX):]
        if not key.endswith(SUFFIX):
            key = key + SUFFIX
        p = self.path_for(key)
        return p.read_bytes() if p else None


_store: LocalImageStore | None = None


def get_store() -> LocalImageStore:
    global _store
    if _store is None:
        _store = LocalImageStore()
    return _store


def set_store(store: LocalImageStore) -> None:
    """테스트나 다른 저장소로 바꿀 때 쓴다."""
    global _store
    _store = store


# =====================================================================
# 판독 결과 저장소
# =====================================================================
import json  # noqa: E402


class LocalResultStore:
    """판독 결과 JSON과 재판독에 필요한 맥락을 photo_id별로 저장한다.

    - 같은 photo_id로 다시 올라오면(앱의 네트워크 재시도) 저장된 결과를 그대로 돌려줘 중복 알림을 막는다.
    - AI 오류로 담당자 확인이 된 건은 저장된 가린 사진과 맥락으로 나중에 다시 판독한다.
    - 환경 변수 RESULT_DIR (기본 data/results/)
    """

    def __init__(self, root: str | Path | None = None):
        self.root = Path(root or os.environ.get("RESULT_DIR") or BASE_DIR / "data" / "results")
        self.root.mkdir(parents=True, exist_ok=True)

    def _p(self, photo_id: str, suffix: str) -> Path:
        return self.root / f"{safe_photo_id(photo_id)}{suffix}"

    def _write(self, path: Path, obj) -> None:
        tmp = _tmp(path)
        tmp.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)

    def save(self, result: dict, ctx: dict | None = None) -> None:
        self._write(self._p(result["photo_id"], ".json"), result)
        if ctx is not None:
            self._write(self._p(result["photo_id"], ".ctx.json"), ctx)

    def load(self, photo_id: str) -> dict | None:
        p = self._p(photo_id, ".json")
        return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None

    def load_ctx(self, photo_id: str) -> dict | None:
        p = self._p(photo_id, ".ctx.json")
        return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None

    def all(self):
        """저장된 결과를 모두 돌려준다. 시범 규모(수천 건)용 단순 구현이며, 운영에서는 DB 조회로 바꾼다."""
        for p in sorted(self.root.glob("*.json")):
            if p.name.endswith(".ctx.json"):
                continue
            try:
                yield json.loads(p.read_text(encoding="utf-8"))
            except Exception:
                continue

    def update(self, photo_id: str, **fields) -> dict | None:
        r = self.load(photo_id)
        if r is None:
            return None
        r.update(fields)
        self.save(r)
        return r

    def pending_retries(self) -> list[str]:
        out = []
        for p in self.root.glob("*.json"):
            if p.name.endswith(".ctx.json"):
                continue
            try:
                r = json.loads(p.read_text(encoding="utf-8"))
            except Exception:
                continue
            if (r.get("retry") or {}).get("pending"):
                out.append(r["photo_id"])
        return out


_results: LocalResultStore | None = None


def get_results() -> LocalResultStore:
    global _results
    if _results is None:
        _results = LocalResultStore()
    return _results


def set_results(store: LocalResultStore) -> None:
    global _results
    _results = store


# =====================================================================
# 변경 알림 기록 (백엔드가 GET /events로 가져간다)
# =====================================================================
class EventLog:
    """응답을 돌려준 뒤에 바뀐 것을 순번(seq)과 함께 쌓는다.

    type
      image_remasked  저장된 가린 사진을 다시 가림 (image_revision이 오름). 백엔드 복사본도 새 버전으로 바꿔야 함
      image_hidden    담당자 가림 점검에서 부족 판정. 백엔드도 이 사진의 조회·판독 사용을 막아야 함
      image_unhidden  가림 점검에서 다시 괜찮다고 기록됨 (재승인 여부는 백엔드 정책)
      result_updated  재판독·재비교가 끝나 결과가 바뀜. GET /results/{photo_id}로 다시 받음
      image_deleted   보관 기간이 지나 가린 사진을 지움 (판독 결과는 남음). retention.py 참고
    한 프로세스(작업자 1개) 기준 구현이다. 작업자를 늘리면 DB나 Redis로 옮긴다.
    """

    TYPES = {"image_remasked", "image_hidden", "image_unhidden", "result_updated", "image_deleted"}

    def __init__(self, path: str | Path | None = None):
        self.path = Path(path or os.environ.get("EVENT_LOG")
                         or (Path(os.environ.get("RESULT_DIR") or BASE_DIR / "data" / "results") / "_events.jsonl"))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._seq = 0
        if self.path.is_file():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                try:
                    self._seq = max(self._seq, int(json.loads(line)["seq"]))
                except Exception:
                    continue

    def add(self, type_: str, photo_id: str, **fields) -> dict:
        assert type_ in self.TYPES, type_
        from datetime import datetime, timedelta, timezone
        with self._lock:
            self._seq += 1
            ev = {"seq": self._seq, "type": type_, "photo_id": photo_id,
                  "at": datetime.now(timezone(timedelta(hours=9))).isoformat(timespec="seconds"), **fields}
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(ev, ensure_ascii=False) + "\n")
                f.flush()
                os.fsync(f.fileno())
        return ev

    def since(self, after: int = 0, limit: int = 100) -> dict:
        out = []
        if self.path.is_file():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                try:
                    ev = json.loads(line)
                except Exception:
                    continue
                if ev["seq"] > after:
                    out.append(ev)
                    if len(out) >= limit:
                        break
        return {"events": out, "next_after": out[-1]["seq"] if out else after, "last_seq": self._seq}


_events: EventLog | None = None


def get_events() -> EventLog:
    global _events
    if _events is None:
        _events = EventLog()
    return _events


def set_events(log: EventLog) -> None:
    global _events
    _events = log


# =====================================================================
# 개인정보 단독 검사 기록 (참고 사진·보수 증거 사진)
# =====================================================================
MEDIA_PREFIX = "m_"


def media_photo_id(media_id: str) -> str | None:
    """참고·보수 사진의 저장 이름. 판독 사진과 섞이지 않게 앞에 m_를 붙인다."""
    if not media_id or not _SAFE_ID.match(media_id) or len(media_id) > 78:
        return None
    return MEDIA_PREFIX + media_id


class LocalMediaStore(LocalResultStore):
    """판독 결과와 따로 둔다. 그래서 활동 실적·주간 보고·판독 이력에 섞이지 않는다."""

    def __init__(self, root: str | Path | None = None):
        super().__init__(root or Path(os.environ.get("RESULT_DIR") or BASE_DIR / "data" / "results") / "media")


_media: LocalMediaStore | None = None


def get_media() -> LocalMediaStore:
    global _media
    if _media is None:
        _media = LocalMediaStore()
    return _media


def set_media(store: LocalMediaStore) -> None:
    global _media
    _media = store
