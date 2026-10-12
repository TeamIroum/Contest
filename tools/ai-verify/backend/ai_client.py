"""AI 판독 모듈 호출. 업무 백엔드의 analysis.client 패키지가 할 일을 그대로 한다.

계약서 "백엔드 연동 시 꼭 맞출 것"을 여기서 지킨다.
  1. 위치는 location_tags로 보내고 lat/lon은 보내지 않는다 (시험용으로 일부러 보낼 때만 예외)
  2. 앱의 마스킹 보고(camelCase)를 snake_case로 바꿔 보낸다
"""
from __future__ import annotations

import json
import re
import time

import httpx


def to_snake(obj):
    """{"appVersion": "1.0"} → {"app_version": "1.0"} (중첩 포함)"""
    if isinstance(obj, dict):
        return {re.sub(r"(?<!^)(?=[A-Z])", "_", k).lower(): to_snake(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [to_snake(v) for v in obj]
    return obj


class AIResponse:
    def __init__(self, status: int, body, latency_ms: int, headers=None, content: bytes | None = None):
        self.status, self.body, self.latency_ms = status, body, latency_ms
        self.headers = headers or {}
        self.content = content

    def as_dict(self):
        return {"http_status": self.status, "latency_ms": self.latency_ms, "body": self.body}


class AIClient:
    def __init__(self, base_url: str, api_key: str | None, timeout_s: float = 60):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key or None
        self.http = httpx.Client(base_url=self.base_url, timeout=timeout_s)
        self._taxonomy = None

    def _headers(self, scenario: str | None = None, api_key: str | None = None) -> dict:
        h = {}
        key = api_key if api_key is not None else self.api_key
        if key:
            h["X-API-Key"] = key
        if scenario:
            h["X-Mock-Scenario"] = scenario
        return h

    def _call(self, method: str, path: str, *, scenario=None, api_key=None, raw=False, **kw) -> AIResponse:
        t0 = time.perf_counter()
        try:
            r = self.http.request(method, path, headers=self._headers(scenario, api_key), **kw)
        except httpx.HTTPError as e:
            return AIResponse(0, {"detail": f"AI 서버에 연결하지 못함: {type(e).__name__}: {e}"},
                              int((time.perf_counter() - t0) * 1000))
        ms = int((time.perf_counter() - t0) * 1000)
        if raw and r.status_code == 200:
            return AIResponse(r.status_code, None, ms, dict(r.headers), r.content)
        try:
            body = r.json()
        except ValueError:
            body = {"detail": r.text[:500]}
        return AIResponse(r.status_code, body, ms, dict(r.headers))

    # ---- 조회 ----
    def health(self) -> AIResponse:
        return self._call("GET", "/health")

    def taxonomy(self, refresh: bool = False) -> dict | None:
        if self._taxonomy is None or refresh:
            r = self._call("GET", "/taxonomy")
            self._taxonomy = r.body if r.status == 200 else None
        return self._taxonomy

    def result(self, photo_id: str) -> AIResponse:
        return self._call("GET", f"/results/{photo_id}")

    def image(self, url_or_photo_id: str) -> AIResponse:
        path = url_or_photo_id if url_or_photo_id.startswith("/images/") else f"/images/{url_or_photo_id}_masked.jpg"
        return self._call("GET", path, raw=True)

    def events(self, after: int, limit: int = 500) -> AIResponse:
        return self._call("GET", "/events", params={"after": after, "limit": limit})

    def audit_sample(self, **params) -> AIResponse:
        return self._call("GET", "/audit/masking", params={k: v for k, v in params.items() if v not in (None, "")})

    def audit_record(self, photo_id: str, body: dict) -> AIResponse:
        return self._call("POST", f"/audit/masking/{photo_id}", json=body)

    def get(self, path: str, **params) -> AIResponse:
        return self._call("GET", path, params={k: v for k, v in params.items() if v not in (None, "")})

    # ---- 판독 ----
    def analyze(self, image: bytes, filename: str, *, photo_id: str, participant_id: str | None,
                captured_at: str | None, location_tags: list[str] | None, device_masked: bool,
                device_report: dict | None, context: dict | None = None, latlon: tuple | None = None,
                scenario: str | None = None, extra_form: dict | None = None, api_key: str | None = None) -> AIResponse:
        data = {"photo_id": photo_id, "device_masked": "true" if device_masked else "false"}
        if location_tags is not None:  # None이면 필드를 빼서 AI 쪽 대장 값을 쓰게 함 (계약 예시 재현용)
            data["location_tags"] = ",".join(location_tags)
        if participant_id:
            data["participant_id"] = participant_id
        if captured_at:
            data["captured_at"] = captured_at
        if device_report:
            data["device_mask_report"] = json.dumps(to_snake(device_report), ensure_ascii=False)
        if context:
            data["context_json"] = json.dumps(context, ensure_ascii=False)
        if latlon:
            data["lat"], data["lon"] = str(latlon[0]), str(latlon[1])
        data.update(extra_form or {})
        return self._call("POST", "/analyze", scenario=scenario, api_key=api_key, data=data,
                          files={"image": (filename, image, _mime(filename))})

    def verify(self, after: bytes, filename: str, *, prior: dict, mode: str, photo_id: str,
               before_photo_id: str | None, before: bytes | None, participant_id: str | None,
               captured_at: str | None, device_masked: bool, device_report: dict | None,
               scenario: str | None = None) -> AIResponse:
        data = {"prior_json": json.dumps(prior, ensure_ascii=False), "mode": mode, "photo_id": photo_id,
                "device_masked": "true" if device_masked else "false"}
        if before_photo_id:
            data["before_photo_id"] = before_photo_id
        if participant_id:
            data["participant_id"] = participant_id
        if captured_at:
            data["captured_at"] = captured_at
        if device_report:
            data["device_mask_report"] = json.dumps(to_snake(device_report), ensure_ascii=False)
        files = {"after": (filename, after, _mime(filename))}
        if before is not None:
            files["before"] = ("before.jpg", before, "image/jpeg")
        return self._call("POST", "/verify", scenario=scenario, data=data, files=files)

    def privacy_mask(self, image: bytes, filename: str, *, media_id: str, purpose: str,
                     device_masked: bool, device_report: dict | None) -> AIResponse:
        data = {"media_id": media_id, "purpose": purpose, "device_masked": "true" if device_masked else "false"}
        if device_report:
            data["device_mask_report"] = json.dumps(to_snake(device_report), ensure_ascii=False)
        return self._call("POST", "/privacy/mask", data=data, files={"image": (filename, image, _mime(filename))})

    def narrative(self, items: list[dict], audience: str | None) -> AIResponse:
        body = {"items": items}
        if audience:
            body["audience"] = audience
        return self._call("POST", "/report/narrative", json=body)


def _mime(filename: str) -> str:
    f = filename.lower()
    if f.endswith(".png"):
        return "image/png"
    if f.endswith((".jpg", ".jpeg")):
        return "image/jpeg"
    return "application/octet-stream"
