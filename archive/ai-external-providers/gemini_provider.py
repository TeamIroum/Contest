"""Google Gemini API로 판독하는 provider (무료 등급 사용 가능).

준비: Google AI Studio(aistudio.google.com)에서 구글 계정으로 API 키 발급. 결제 수단 등록 없이 무료 등급으로 동작한다.

환경 변수
  GEMINI_API_KEY       API 키 (필수)
  GEMINI_MODEL         모델 이름 (기본 gemini-3.5-flash). AI Studio에서 무료로 쓸 수 있는 Flash 계열 이름으로 바꾼다
  AI_MIN_INTERVAL_S    호출 사이 최소 간격(초). 무료 등급의 분당 호출 제한에 맞춘다 (기본 6초 = 분당 10회)

주의
  - 무료 등급은 분당·하루 호출 수 제한이 있다. 제한에 걸리면(429) 5초 이내로 기다리면 될 때만 다시 시도하고,
    아니면 바로 실패로 돌린다 (재판독 대기열이 나중에 다시 판독).
  - 무료 등급에서는 보낸 내용이 Google 서비스 개선에 쓰일 수 있다. 이 시스템은 가린 사진만 보내지만,
    실제 운영에서는 유료 등급이나 국내·자체 모델로 바꾼다.
"""
import base64
import json
import os
import threading
import time

import httpx

from providers.base import Provider

API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
DEFAULT_MODEL = "gemini-3.5-flash"  # 2.5 계열은 2026-10 종료(404)
MAX_429_WAIT_S = 5  # 호출 한도(429)일 때 요청 안에서 기다리는 최대 시간
_ALLOWED = {"type", "properties", "required", "enum", "items", "description",
            "minimum", "maximum", "minItems", "maxItems", "nullable"}


def to_gemini_schema(s):
    """JSON Schema를 Gemini가 받는 형식(OpenAPI 부분집합, 대문자 type)으로 바꾼다."""
    if isinstance(s, list):
        return [to_gemini_schema(x) for x in s]
    if not isinstance(s, dict):
        return s
    out = {}
    for k, v in s.items():
        if k not in _ALLOWED:
            continue
        if k == "type":
            out[k] = str(v).upper()
        elif k == "properties":
            out[k] = {pk: to_gemini_schema(pv) for pk, pv in v.items()}
        elif k == "items":
            out[k] = to_gemini_schema(v)
        else:
            out[k] = v
    return out


class RateLimited(Exception):
    pass


class GeminiError(RuntimeError):
    """Gemini가 오류를 돌려줬을 때. 상태 코드와 Google의 설명을 그대로 담는다 (API 키는 담지 않음)."""


class GeminiProvider(Provider):
    name = "gemini"

    def __init__(self, model: str | None = None, api_key: str | None = None, transport=None):
        self.model = model or os.environ.get("GEMINI_MODEL", DEFAULT_MODEL)
        self.api_key = api_key or os.environ.get("GEMINI_API_KEY", "")
        if not self.api_key:
            raise RuntimeError("GEMINI_API_KEY가 없습니다. Google AI Studio에서 무료 키를 발급하세요.")
        self.interval = float(os.environ.get("AI_MIN_INTERVAL_S", "6"))
        self.client = httpx.Client(timeout=float(os.environ.get("AI_TIMEOUT_S", "12")), transport=transport)
        self._lock = threading.Lock()
        self._last = 0.0

    def _wait_turn(self):
        with self._lock:
            gap = self.interval - (time.time() - self._last)
            if gap > 0:
                time.sleep(gap)
            self._last = time.time()

    def _post(self, body: dict) -> dict:
        url = API.format(model=self.model)
        for attempt in range(3):
            self._wait_turn()
            r = self.client.post(url, headers={"x-goog-api-key": self.api_key}, json=body)
            if r.status_code == 429 and attempt < 2:
                # 잠깐 기다리면 되는 경우만 여기서 다시 시도한다. 오래 기다려야 하면(하루 한도 등)
                # 바로 실패로 돌려 담당자 확인으로 넘기고, 재판독은 jobs.RetryQueue가 30초·2분·10분 뒤에 한다.
                wait = float(r.headers.get("retry-after", 0) or 0)
                if 0 < wait <= MAX_429_WAIT_S:
                    time.sleep(wait)
                    continue
            if r.status_code == 429:
                raise RateLimited("무료 등급 호출 한도 초과 (429)")
            if r.status_code >= 400:
                try:
                    msg = r.json().get("error", {}).get("message", "")
                except ValueError:
                    msg = r.text
                raise GeminiError(f"Gemini API {r.status_code} (모델 {self.model}): {msg[:300]}")
            return r.json()
        raise RateLimited("무료 등급 호출 한도 초과 (429)")

    @staticmethod
    def _text(resp: dict) -> str:
        try:
            parts = resp["candidates"][0]["content"]["parts"]
        except (KeyError, IndexError):
            raise RuntimeError(f"Gemini 응답에 내용이 없습니다: {json.dumps(resp, ensure_ascii=False)[:300]}")
        return "".join(p.get("text", "") for p in parts if not p.get("thought"))

    def _json_call(self, system: str, parts: list, schema: dict) -> dict:
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
                                 "responseSchema": to_gemini_schema(schema)},
        }
        text = self._text(self._post(body))
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            raise RuntimeError(f"Gemini가 JSON이 아닌 답을 보냄: {text[:200]}")
        return _fill_defaults(data, schema)

    @staticmethod
    def _img(jpeg: bytes) -> dict:
        return {"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(jpeg).decode()}}

    def analyze(self, image_jpeg, prompt, schema):
        # Gemini는 위치를 [ymin, xmin, ymax, xmax](0~1000)로 내도록 학습되어 있어, 계약 형식
        # [왼쪽, 위, 오른쪽, 아래](0~1)를 요구해도 세로·가로를 바꿔 내는 일이 잦다.
        # 그래서 Gemini에는 익숙한 형식으로 받고 여기서 계약 형식으로 바꾼다.
        data = self._json_call(prompt + BBOX_NOTE, [self._img(image_jpeg), {"text": "이 점검 사진을 판독해 형식에 맞춰 보고해 주세요."}],
                               _bbox_1000(schema))
        for key in ("issues", "privacy_leaks"):
            for item in data.get(key) or []:
                if isinstance(item, dict):
                    item["bbox"] = from_gemini_box(item.get("bbox"))
        return data

    def verify(self, before_jpeg, after_jpeg, prompt, schema):
        parts = [{"text": "이전 사진:"}, self._img(before_jpeg), {"text": "이번에 다시 찍은 사진:"},
                 self._img(after_jpeg), {"text": "두 사진을 비교해 형식에 맞춰 보고해 주세요."}]
        return self._json_call(prompt, parts, schema)

    def generate_text(self, prompt):
        body = {"contents": [{"role": "user", "parts": [{"text": prompt}]}], "generationConfig": {"temperature": 0.3}}
        return self._text(self._post(body)).strip()


BBOX_NOTE = """

[위치 형식 (Gemini 전용, 8번·11번의 좌표 형식보다 우선)]
bbox는 [ymin, xmin, ymax, xmax] 순서로, 사진 전체를 0~1000으로 본 정수로 적는다.
ymin·ymax는 세로(위→아래), xmin·xmax는 가로(왼쪽→오른쪽) 위치다. 문제 부위만 감싼다."""


def _bbox_1000(schema: dict) -> dict:
    """판독 스키마의 bbox 범위를 0~1 대신 0~1000으로 바꾼 사본."""
    s = json.loads(json.dumps(schema))
    for key in ("issues", "privacy_leaks"):
        item = s.get("properties", {}).get(key, {}).get("items", {}).get("properties", {}).get("bbox")
        if item:
            item["items"] = {"type": "number", "minimum": 0, "maximum": 1000}
    return s


def from_gemini_box(b):
    """Gemini [ymin, xmin, ymax, xmax] (0~1000) → 계약 [왼쪽, 위, 오른쪽, 아래] (0~1).
    값이 모두 1 이하면 이미 비율로 낸 것으로 보고 1000으로 나누지 않는다. 형식이 틀리면 그대로 둔다."""
    if not (isinstance(b, list) and len(b) == 4 and all(isinstance(v, (int, float)) for v in b)):
        return b
    scale = 1000.0 if max(b) > 1 else 1.0
    y0, x0, y1, x1 = (min(max(v / scale, 0.0), 1.0) for v in b)
    l, r = sorted((x0, x1))
    t, btm = sorted((y0, y1))
    return [round(l, 4), round(t, 4), round(r, 4), round(btm, 4)]


def _fill_defaults(data: dict, schema: dict) -> dict:
    """모델이 빠뜨린 필드를 안전한 기본값으로 채우고, 범위 밖 값을 바로잡는다."""
    props = schema.get("properties", {})
    for k, spec in props.items():
        if k in data:
            continue
        t = spec.get("type")
        data[k] = [] if t == "array" else False if t == "boolean" else 0 if t == "integer" else (
            "low" if k == "certainty" else "unclear" if spec.get("enum") and "unclear" in spec["enum"] else
            (spec.get("enum") or [""])[-1] if spec.get("enum") else "")
    if "certainty" in props and data.get("certainty") not in ("high", "medium", "low"):
        data["certainty"] = "low"
    return data
