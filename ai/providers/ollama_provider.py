"""자체 서버에서 돌리는 공개 비전 모델로 판독하는 provider (Ollama). 운영 판독 경로이며, 사진이 서버 밖으로 나가지 않음.

준비
  1) ollama.com에서 Ollama 설치
  2) 비전 모델 내려받기 (예: `ollama pull qwen3-vl:8b-instruct`)
  3) Ollama 실행 상태에서 AI_PROVIDER=ollama 로 서버나 평가 스크립트 실행

환경 변수
  OLLAMA_MODEL   모델 이름 (기본 qwen3-vl:8b-instruct)
  OLLAMA_URL     기본 http://localhost:11434
  OLLAMA_NUM_CTX 한 번에 다룰 토큰 수 (기본 16384). Ollama 기본값 4096으로는 긴 변 1568px 사진이 들어가지 않는다(400)
  OLLAMA_KEEP_ALIVE 마지막 요청 뒤 모델을 GPU에 유지하는 시간 (기본 3h, Ollama 기본값은 5m). 활동 시간 길이에 맞춘다
  OLLAMA_BBOX    모델이 내는 위치 형식 auto | pixel | norm1000 (기본 auto: qwen3 계열은 norm1000, 그 밖은 pixel)
                 qwen2.5vl은 사진 픽셀 [x1, y1, x2, y2], qwen3-vl은 0~1000 비율 [x1, y1, x2, y2]로 답한다

주의: 사진 한 장 판독에 서버 사양에 따라 수 초~수십 초가 걸린다. 어떤 공개 모델을 쓸지는
      같은 라벨 사진으로 평가 스크립트(eval/run_eval.py)를 돌려 정한다.
"""
import base64
import json
import math
import os

import cv2
import httpx

from providers.base import Provider

DEFAULT_MODEL = "qwen3-vl:8b-instruct"
# Qwen2.5-VL은 사진을 28px 단위로 맞추고, 약 80만 화소보다 작으면 키워서 본다. 위치(bbox)는 그렇게 바꾼
# 크기 기준 픽셀로 낸다. 그래서 처음부터 모델이 바꾸지 않을 크기로 맞춰 보내고, 받은 픽셀을 그 크기로 나눈다.
PATCH = 28
MIN_PIXELS = 1024 * PATCH * PATCH


def fit_for_model(jpeg: bytes) -> tuple[bytes, int, int]:
    """사진을 28px 배수이면서 MIN_PIXELS 이상인 크기로 맞춘다. (JPEG, 가로, 세로)"""
    import images
    img = images.decode_jpeg(jpeg)
    h, w = img.shape[:2]
    k = max(1.0, math.sqrt(MIN_PIXELS / (w * h)))
    W, H = (max(PATCH, round(v * k / PATCH) * PATCH) for v in (w, h))
    while W * H < MIN_PIXELS:
        W, H = W + PATCH, H + PATCH
    if (W, H) != (w, h):
        img = cv2.resize(img, (W, H), interpolation=cv2.INTER_CUBIC if W > w else cv2.INTER_AREA)
        jpeg = images.encode_jpeg(img)
    return jpeg, W, H


def bbox_mode(model: str) -> str:
    mode = os.environ.get("OLLAMA_BBOX", "auto")
    if mode != "auto":
        return mode
    return "norm1000" if model.startswith("qwen3") else "pixel"


def to_ratio_box(b, W: int, H: int, mode: str = "pixel"):
    """모델 좌표 [x1, y1, x2, y2] → 계약 [왼쪽, 위, 오른쪽, 아래] (0~1).
    mode=pixel이면 사진 크기(W, H)로, norm1000이면 1000으로 나눈다. 프롬프트대로 정수로 답하면 값이 작아도
    ([0, 0, 1, 1] 같은 구석의 작은 상자) 그 형식으로 읽는다. 소수가 섞여 있고 모두 1 이하일 때만 이미 비율로 본다.
    형식이 틀린 값은 그대로 돌려주고, pipeline의 응답 형식 확인에서 걸러진다."""
    if not (isinstance(b, list) and len(b) == 4
            and all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in b)):
        return b
    already_ratio = max(b) <= 1 and any(float(v) != int(v) for v in b)
    if not already_ratio:
        b = [v / 1000 for v in b] if mode == "norm1000" else [b[0] / W, b[1] / H, b[2] / W, b[3] / H]
    x0, y0, x1, y1 = (min(max(float(v), 0.0), 1.0) for v in b)
    l, r = sorted((x0, x1))
    t, btm = sorted((y0, y1))
    return [round(l, 4), round(t, 4), round(r, 4), round(btm, 4)]


def _pixel_bbox_schema(schema: dict) -> dict:
    """bbox 값 범위(0~1) 제약을 뺀 사본. 모델은 픽셀로 답한다."""
    s = json.loads(json.dumps(schema))
    for key in ("issues", "privacy_leaks"):
        item = s.get("properties", {}).get(key, {}).get("items", {}).get("properties", {}).get("bbox")
        if item:
            item["items"] = {"type": "number"}
    return s


class OllamaProvider(Provider):
    name = "ollama"

    def __init__(self, model: str | None = None, url: str | None = None, transport=None):
        self.model = model or os.environ.get("OLLAMA_MODEL", DEFAULT_MODEL)
        self.url = (url or os.environ.get("OLLAMA_URL", "http://localhost:11434")).rstrip("/")
        self.client = httpx.Client(timeout=float(os.environ.get("AI_TIMEOUT_S", "12")), transport=transport)
        self.num_ctx = int(os.environ.get("OLLAMA_NUM_CTX", "16384"))
        # Ollama는 기본으로 5분 동안 요청이 없으면 모델을 GPU에서 내린다. 활동 중 장소 이동으로 5분 넘게
        # 사진이 없으면 다음 판독이 모델을 다시 올리느라 1분 가까이 걸리므로, 활동 시간 동안 유지한다.
        self.keep_alive = os.environ.get("OLLAMA_KEEP_ALIVE", "3h")
        # 판독 1회 제한(AI_TIMEOUT_S, 운영 12초)과 별개로, 모델을 GPU에 올리는 데는 1분 가까이 걸린다.
        self.warmup_timeout = float(os.environ.get("OLLAMA_WARMUP_TIMEOUT_S", "300"))

    def warm_up(self) -> None:
        """모델을 GPU에 미리 올린다 (빈 대화를 보내면 Ollama가 모델만 올린다).
        판독 요청과 num_ctx가 같아야 판독 때 다시 올리지 않는다."""
        r = self.client.post(f"{self.url}/api/chat", json={
            "model": self.model, "messages": [], "keep_alive": self.keep_alive,
            "options": {"num_ctx": self.num_ctx}}, timeout=self.warmup_timeout)
        r.raise_for_status()

    def _chat(self, system: str, text: str, images: list[bytes], schema: dict | None) -> str:
        body = {
            "model": self.model,
            "stream": False,
            "options": {"temperature": 0, "num_ctx": self.num_ctx},
            "keep_alive": self.keep_alive,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": text, "images": [base64.b64encode(i).decode() for i in images]},
            ],
        }
        if schema:
            body["format"] = schema  # Ollama는 JSON Schema로 출력 형식을 강제할 수 있다
        r = self.client.post(f"{self.url}/api/chat", json=body)
        r.raise_for_status()
        return r.json()["message"]["content"]

    def _json(self, system, text, images, schema):
        raw = self._chat(system, text, images, schema)
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            raise RuntimeError(f"모델이 JSON이 아닌 답을 보냄: {raw[:200]}")
        return _fill_defaults(data, schema)

    def analyze(self, image_jpeg, prompt, schema):
        jpeg, W, H = fit_for_model(image_jpeg)
        mode = bbox_mode(self.model)
        how = ("사진 전체를 0~1000으로 본 정수로 적는다" if mode == "norm1000"
               else f"사진 크기(가로 {W}, 세로 {H})의 픽셀 정수로 적는다")
        note = ("\n\n[위치 형식 (이 모델 전용, 8번·11번의 좌표 형식보다 우선)]\n"
                f"bbox는 [왼쪽 x, 위 y, 오른쪽 x, 아래 y] 순서로, {how}.")
        data = self._json(prompt + note, "이 점검 사진을 판독해 형식에 맞춰 JSON으로 보고해 주세요.", [jpeg],
                          _pixel_bbox_schema(schema))
        for key in ("issues", "privacy_leaks"):
            for item in data.get(key) or []:
                if isinstance(item, dict):
                    item["bbox"] = to_ratio_box(item.get("bbox"), W, H, mode)
        return data

    def verify(self, before_jpeg, after_jpeg, prompt, schema):
        return self._json(prompt, "첫 번째 이미지는 이전 사진, 두 번째 이미지는 이번에 다시 찍은 사진입니다. "
                                  "두 사진을 비교해 형식에 맞춰 JSON으로 보고해 주세요.",
                          [fit_for_model(before_jpeg)[0], fit_for_model(after_jpeg)[0]], schema)

    def generate_text(self, prompt):
        return self._chat("", prompt, [], None).strip()


def _fill_defaults(data, schema: dict):
    """뜻이 분명한 기본값만 채운다: 빠진 목록은 빈 목록(issues·privacy_leaks: 보고할 것 없음),
    certainty가 없거나 틀리면 low(담당자 확인으로 감). framing_ok·facility_visible 같은 판단 값은 지어내지 않는다.
    빠진 채로 두면 pipeline이 형식 오류로 보고 담당자 확인 + 나중에 다시 판독한다.
    (예전에는 framing_ok를 False로 채워, 잘 찍은 사진도 어르신께 다시 찍으라고 안내했다)"""
    if not isinstance(data, dict):
        return data
    props = schema.get("properties", {})
    for k, spec in props.items():
        if k not in data and spec.get("type") == "array":
            data[k] = []
    if "certainty" in props and data.get("certainty") not in ("high", "medium", "low"):
        data["certainty"] = "low"
    return data
