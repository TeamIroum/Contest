"""AI 호출 관리: 동시 호출 제한과 나중에 다시 판독하기.

AIGate
  외부 AI API는 호출 수 제한(rate limit)이 있고, 한꺼번에 많이 부르면 모두 느려진다.
  동시에 부르는 수를 MAX_CONCURRENT_AI(기본 4)로 묶고, 자리가 나기를 AI_QUEUE_WAIT_S(기본 5초)
  넘게 기다려야 하면 바로 AIBusy를 낸다. 어르신이 현장에서 오래 기다리지 않게 하기 위해서다.

RetryQueue
  AI 오류·시간 초과·혼잡으로 '담당자 확인'이 된 사진은 저장된 가린 사진으로 나중에 다시 판독한다.
  원본이 없어도 되므로 개인정보 원칙과 충돌하지 않는다.
  30초, 2분, 10분 간격으로 세 번까지 다시 시도하고, 그래도 안 되면 담당자 확인으로 둔다.
  서버가 다시 켜지면 결과 저장소에서 대기 중인 건을 찾아 원래 예정 시각대로 이어서 처리한다.
  재판독할 사진이 사라졌거나 재판독 중 예외가 반복되면 대기를 끝내고 담당자 확인(gave_up)으로 둔다.
  결과가 바뀔 때마다 GET /events에 result_updated가 남으므로 백엔드는 계속 조회하지 않아도 된다.
"""
import heapq
import logging
import os
import threading
import time

log = logging.getLogger("ai.jobs")

MAX_CONCURRENT_AI = int(os.environ.get("MAX_CONCURRENT_AI", "4"))
AI_QUEUE_WAIT_S = float(os.environ.get("AI_QUEUE_WAIT_S", "5"))
RETRY_DELAYS_S = [30, 120, 600]


class AIBusy(Exception):
    """동시 호출 자리가 나지 않음."""


class AIGate:
    def __init__(self, max_concurrent: int = MAX_CONCURRENT_AI, wait_s: float = AI_QUEUE_WAIT_S):
        self._sem = threading.BoundedSemaphore(max_concurrent)
        self.wait_s = wait_s

    def run(self, fn, *args, **kwargs):
        if not self._sem.acquire(timeout=self.wait_s):
            raise AIBusy(f"AI 동시 호출이 가득 차 {self.wait_s:.0f}초 안에 자리가 나지 않음")
        try:
            return fn(*args, **kwargs)
        finally:
            self._sem.release()


ai_gate = AIGate()


class RetryQueue:
    def __init__(self, delays=None):
        self.delays = delays or RETRY_DELAYS_S
        self._heap: list[tuple[float, str, str, int]] = []  # (실행 시각, kind, photo_id, 시도 횟수)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._queued: dict[str, tuple[float, int]] = {}  # photo_id → (예정 시각, 시도 횟수). 두 번 넣지 않게

    def enqueue(self, kind: str, photo_id: str, attempt: int = 0) -> dict:
        """kind: analyze | verify. 돌려주는 값은 결과 JSON의 retry 필드에 넣는다."""
        if attempt >= len(self.delays):
            return {"pending": False, "attempts": attempt, "gave_up": True, "reason": "AI 판독 실패 반복"}
        due = time.time() + self.delays[attempt]
        with self._lock:
            if photo_id in self._queued:  # 이미 대기 중이면 실제로 잡혀 있는 예정 시각을 알려 준다
                due, attempt = self._queued[photo_id]
            else:
                heapq.heappush(self._heap, (due, kind, photo_id, attempt))
                self._queued[photo_id] = (due, attempt)
        return {"pending": True, "attempts": attempt, "next_at": int(due)}

    def process_due(self, force: bool = False) -> int:
        """때가 된 작업을 처리한다. force=True면 시각과 관계없이 모두 (테스트용).

        재판독할 수 없는 경우(가린 사진·맥락이 사라짐)와 재판독 중 예외가 난 경우도 끝을 낸다.
        예외는 한 번의 실패로 세어 다음 간격으로 미루고, 횟수를 다 쓰면 담당자 확인으로 둔다.
        그러지 않으면 결과가 '재판독 중'으로 영원히 남고 서버를 켤 때마다 다시 집어 든다."""
        import pipeline  # 순환 import 방지
        done = 0
        while True:
            with self._lock:
                if not self._heap or (not force and self._heap[0][0] > time.time()):
                    break
                _, kind, photo_id, attempt = heapq.heappop(self._heap)
                self._queued.pop(photo_id, None)
            try:
                fn = pipeline.reanalyze if kind == "analyze" else pipeline.reverify
                if fn(photo_id, attempt=attempt + 1) is None:
                    pipeline.give_up_retry(photo_id, attempt + 1, "재판독에 필요한 사진이나 맥락이 없음")
            except Exception as e:
                log.exception("재판독 중 예외 photo_id=%s", photo_id)
                self._after_crash(kind, photo_id, attempt + 1, e)
            done += 1
        return done

    def _after_crash(self, kind: str, photo_id: str, attempt: int, err: Exception) -> None:
        import pipeline
        import storage
        retry = self.enqueue(kind, photo_id, attempt)
        if retry.get("gave_up"):
            pipeline.give_up_retry(photo_id, attempt, f"재판독 중 오류 반복: {type(err).__name__}")
            return
        with storage.photo_lock(photo_id):
            r = storage.get_results().load(photo_id)
            if r is not None:
                r["retry"] = retry
                storage.get_results().save(r)

    def resume_from_store(self) -> int:
        """서버가 다시 켜졌을 때 결과 저장소에서 '재판독 중'인 건을 찾아 이어 간다.
        원래 예정 시각(next_at)을 지키고, 이미 지났으면 5초 뒤에 처리한다."""
        import storage
        ids = storage.get_results().pending_retries()
        n = 0
        for pid in ids:
            r = storage.get_results().load(pid) or {}
            retry = r.get("retry") or {}
            kind = "verify" if r.get("mode") else "analyze"
            due = max(float(retry.get("next_at") or 0), time.time() + 5)
            attempts = int(retry.get("attempts") or 0)
            with self._lock:
                if pid in self._queued:
                    continue
                heapq.heappush(self._heap, (due, kind, pid, attempts))
                self._queued[pid] = (due, attempts)
            n += 1
        return n

    def pending(self) -> list[dict]:
        with self._lock:
            return [{"photo_id": pid, "kind": kind, "attempt": att, "due_at": int(due)}
                    for due, kind, pid, att in sorted(self._heap)]

    def start(self, interval_s: float = 2.0) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()

        def loop():
            while not self._stop.is_set():
                self.process_due()
                self._stop.wait(interval_s)

        self._thread = threading.Thread(target=loop, name="ai-retry", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def __len__(self):
        return len(self._heap)


retry_queue = RetryQueue()
