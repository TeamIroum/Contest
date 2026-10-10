"""업로드 사진 읽기·검증·변환.

- 형식 확인: JPEG/PNG만 받는다 (앱은 앱 안 카메라로 JPEG을 보낸다)
- 크기 제한: 파일 15MB, 4,000만 화소 (압축 폭탄 방지)
- 방향 보정: 휴대폰 사진의 EXIF 회전 정보를 픽셀에 반영한 뒤 버린다.
  보정하지 않으면 옆으로 누운 사진으로 얼굴을 찾게 되어 검출률이 크게 떨어진다.
- 메타데이터 제거: 픽셀 배열로 바꾸는 순간 EXIF(GPS, 기기 정보)가 모두 사라진다.
  다시 JPEG로 저장할 때도 메타데이터를 넣지 않는다.
"""
import io
import os

import cv2
import numpy as np
from PIL import Image, ImageOps

MAX_UPLOAD_BYTES = int(os.environ.get("MAX_UPLOAD_MB", "15")) * 1024 * 1024
MAX_PIXELS = 40_000_000
AI_MAX_SIDE = 1568      # AI에 보내고 저장하는 사진의 긴 변
JPEG_QUALITY = 88

Image.MAX_IMAGE_PIXELS = MAX_PIXELS


class ImageRejected(ValueError):
    """사진을 받을 수 없을 때. code는 앱에 돌려줄 사유."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def sniff(data: bytes) -> str | None:
    if data[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    return None


def decode_upload(data: bytes) -> np.ndarray:
    """업로드 바이트 → 방향 보정된 BGR 배열 (메타데이터 없음)."""
    if len(data) > MAX_UPLOAD_BYTES:
        raise ImageRejected("too_large", f"사진 파일이 {MAX_UPLOAD_BYTES // 1024 // 1024}MB를 넘습니다")
    if sniff(data) is None:
        raise ImageRejected("unsupported_type", "JPEG 또는 PNG 사진만 받습니다")
    try:
        with Image.open(io.BytesIO(data)) as im:
            if im.width * im.height > MAX_PIXELS:
                raise ImageRejected("too_many_pixels", "사진 해상도가 너무 큽니다")
            im = ImageOps.exif_transpose(im)  # 회전 정보 반영
            rgb = np.asarray(im.convert("RGB"))
    except ImageRejected:
        raise
    except Exception as e:  # 깨진 파일, 압축 폭탄 등
        raise ImageRejected("unreadable", f"사진을 읽을 수 없습니다: {type(e).__name__}") from e
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)


def resize_max(img: np.ndarray, max_side: int) -> np.ndarray:
    h, w = img.shape[:2]
    s = max(h, w)
    if s <= max_side:
        return img
    k = max_side / s
    return cv2.resize(img, (round(w * k), round(h * k)), interpolation=cv2.INTER_AREA)


def encode_jpeg(img: np.ndarray, quality: int = JPEG_QUALITY) -> bytes:
    """메타데이터 없는 JPEG. OpenCV는 EXIF를 쓰지 않는다."""
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise RuntimeError("JPEG 인코딩 실패")
    return buf.tobytes()


def decode_jpeg(data: bytes) -> np.ndarray:
    """서버가 저장한(이미 가린) JPEG을 다시 읽을 때."""
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("저장된 사진을 읽을 수 없습니다")
    return img
