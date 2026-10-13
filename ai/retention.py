"""가린 사진 보관 기간이 지나면 지우기.

원칙: 사진은 기간이 지나면 지우고, 판독 결과 JSON은 남긴다. 활동 실적·주간 보고·시설 이력과
위치·악화 판단(직전 결과 비교)은 사진 없이 결과만으로 계속 돌아가야 하기 때문이다.

  정상 사진  RETENTION_NORMAL_DAYS (기본 180일, 약 6개월)
             판독 결과가 ok이고 이상(issues)이 하나도 없는 사진
  처리 건    RETENTION_CASE_DAYS (기본 1095일, 약 3년)
             이상이 있었거나 담당자 확인으로 간 사진, 수리 확인(전후 비교) 사진, 보수 증거 사진, 수리 전 사진
  지우지 않음  참고 사진(reference). 장소 안내에 계속 쓰므로 새 참고 사진으로 바꿀 때 백엔드가 정리한다.
             재판독을 기다리는 사진. 재판독에 사진이 필요하다.

기간은 촬영 시각(captured_at)부터 센다. 없으면 기록 시각, 그것도 없으면 사진 파일 시각을 쓴다.
기간을 0 이하로 두면 그 종류는 지우지 않는다.

AI 서버는 담당자 판정을 모른다. 그래서 '정상'은 AI 판독 결과 기준이고, 담당자가 이상으로 바꾼 사진은
백엔드가 자기 보관 정책으로 더 오래 둔다. AI 서버 사본은 판독·재판독용이라 이 기준으로 충분하다.

지우면 결과에 masked_image_url=None과 image_deleted를 남기고 GET /events에 image_deleted를 쌓는다.
백엔드는 이 알림을 받아 자기 사본도 같은 기준으로 정리할지 정한다.
RETENTION_SWEEP_HOURS(기본 24시간)마다, 그리고 서버를 켤 때 한 번 돈다.
"""
import logging
import os
import threading
from datetime import datetime, timedelta, timezone

import storage

log = logging.getLogger("ai.retention")

KST = timezone(timedelta(hours=9))
NORMAL, CASE, KEEP = "normal", "case", "keep"


def _days(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        log.warning("%s 값이 숫자가 아니라 기본값 %d일을 씁니다", name, default)
        return default


def policy_days() -> dict:
    return {NORMAL: _days("RETENTION_NORMAL_DAYS", 180), CASE: _days("RETENTION_CASE_DAYS", 1095)}


def classify(rec: dict, media: bool = False) -> str:
    """보관 기준 종류. media=True면 참고·보수·수리 전 사진 기록이다."""
    if media:
        return KEEP if rec.get("purpose") == "reference" else CASE
    if (rec.get("retry") or {}).get("pending"):
        return KEEP
    if rec.get("mode"):  # 수리 확인(전후 비교) 결과
        return CASE
    if rec.get("status") == "ok" and not rec.get("issues"):
        return NORMAL
    return CASE


def _parse(ts) -> datetime | None:
    if not isinstance(ts, str) or not ts:
        return None
    try:
        dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=KST)


def _taken_at(rec: dict, photo_id: str) -> datetime | None:
    dt = _parse(rec.get("captured_at")) or _parse((rec.get("meta") or {}).get("created_at"))
    if dt:
        return dt
    path = storage.get_store().path_for(photo_id + storage.SUFFIX)
    return datetime.fromtimestamp(path.stat().st_mtime, KST) if path else None


def _expire(records, media: bool, photo_id: str, kind: str, days: int, now: datetime) -> dict | None:
    """기간이 지났으면 사진을 지우고 기록을 남긴다. 지운 경우에만 알림을 돌려준다."""
    with storage.photo_lock(photo_id):  # 재판독·가림 점검 기록과 겹치지 않게
        rec = records.load(photo_id)
        if rec is None or not rec.get("masked_image_url") or classify(rec, media) != kind:
            return None
        taken = _taken_at(rec, photo_id)
        if taken is None or now - taken < timedelta(days=days):
            return None
        rev = storage.get_store().revision(photo_id)
        storage.get_store().delete_masked(photo_id)
        rec["masked_image_url"] = None
        rec["image_deleted"] = {"at": now.isoformat(timespec="seconds"), "policy": kind, "days": days}
        records.save(rec)
        return storage.get_events().add("image_deleted", photo_id, image_revision=rev, policy=kind, days=days)


def sweep(now: datetime | None = None) -> dict:
    """보관 기간이 지난 가린 사진을 지운다. 지운 수를 종류별로 돌려준다."""
    now = now or datetime.now(KST)
    days = policy_days()
    out = {NORMAL: 0, CASE: 0}
    for records, media in ((storage.get_results(), False), (storage.get_media(), True)):
        for rec in list(records.all()):
            pid = rec.get("photo_id")
            if not pid or not rec.get("masked_image_url"):
                continue
            kind = classify(rec, media)
            if kind == KEEP or days[kind] <= 0:
                continue
            try:
                if _expire(records, media, pid, kind, days[kind], now):
                    out[kind] += 1
            except Exception:
                log.exception("보관 기간 정리 실패 photo_id=%s", pid)
    if out[NORMAL] or out[CASE]:
        log.info("보관 기간이 지난 사진을 지웠습니다: 정상 %d장, 처리 건 %d장", out[NORMAL], out[CASE])
    return out


class RetentionJob:
    def __init__(self):
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        interval_s = max(1.0, float(os.environ.get("RETENTION_SWEEP_HOURS", "24"))) * 3600
        self._stop.clear()

        def loop():
            while not self._stop.is_set():
                try:
                    sweep()
                except Exception:
                    log.exception("보관 기간 정리 중 오류")
                self._stop.wait(interval_s)

        self._thread = threading.Thread(target=loop, name="ai-retention", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()


retention_job = RetentionJob()
