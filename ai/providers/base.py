"""모든 판독 모델이 지켜야 할 공통 인터페이스.

provider는 '사진을 보고 판단한 원자료'만 돌려준다.
등급 계산, 담당자 배정, 어르신 안내 문구는 provider가 아니라 rules.py / pipeline.py가 맡는다.
"""
from abc import ABC, abstractmethod
import io

from PIL import Image

MAX_SIDE = 1568  # 너무 큰 사진은 줄여서 보낸다 (속도·비용)


def prepare_image(image_bytes: bytes) -> bytes:
    """사진을 RGB JPEG로 바꾸고 긴 변을 MAX_SIDE 이하로 줄인다."""
    img = Image.open(io.BytesIO(image_bytes))
    img = img.convert("RGB")
    img.thumbnail((MAX_SIDE, MAX_SIDE))
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=88)
    return out.getvalue()


class Provider(ABC):
    name: str = "base"
    model: str = ""

    @abstractmethod
    def analyze(self, image_jpeg: bytes, prompt: str, schema: dict) -> dict:
        """사진 한 장 판독. schema(prompts.analyze_schema)에 맞는 dict를 돌려준다."""

    @abstractmethod
    def verify(self, before_jpeg: bytes, after_jpeg: bytes, prompt: str, schema: dict) -> dict:
        """전후 사진 비교. schema(prompts.verify_schema)에 맞는 dict를 돌려준다."""

    @abstractmethod
    def generate_text(self, prompt: str) -> str:
        """담당자 알림 문구, 주간 보고 같은 문장 생성."""
