"""촬영 직후 사진 품질 확인 (AI 없이 OpenCV로).

흐림과 어두움은 규칙으로 빠르게 잡는다. 기준값은 테스트셋을 보고 조정한다.
'대상이 틀 밖', '시설이 안 보임'은 AI 판독 결과(framing_ok, facility_visible)로 판단한다.
"""
import cv2
import numpy as np

BLUR_THRESHOLD = 60.0   # 라플라시안 분산이 이보다 낮으면 흐림
DARK_THRESHOLD = 45.0   # 평균 밝기(0~255)가 이보다 낮으면 어두움
CHECK_WIDTH = 800       # 사진 크기에 따라 값이 달라지지 않도록 같은 폭으로 줄여서 잰다
MIN_SIDE = 32           # 짧은 변이 이보다 작으면 점검 사진으로 쓸 수 없다


def check(image_bytes: bytes) -> dict:
    arr = np.frombuffer(image_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE)
    if img is None:
        return {"ok": False, "problems": ["unreadable"], "metrics": {}}
    return check_array(img)


def check_array(img: np.ndarray) -> dict:
    """이미 읽은 사진(BGR 또는 흑백 배열)의 흐림·어두움 확인."""
    if img.ndim == 3:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    h, w = img.shape
    if min(h, w) < MIN_SIDE:  # 아주 좁고 긴 사진: 줄이면 높이가 0이 되어 오류가 나므로 판독 불가로 본다
        return {"ok": False, "problems": ["unreadable"], "metrics": {}}
    if w > CHECK_WIDTH:
        img = cv2.resize(img, (CHECK_WIDTH, max(1, int(h * CHECK_WIDTH / w))))

    sharpness = float(cv2.Laplacian(img, cv2.CV_64F).var())
    brightness = float(img.mean())

    problems = []
    if brightness < DARK_THRESHOLD:
        problems.append("dark")
    elif sharpness < BLUR_THRESHOLD:  # 어두운 사진은 원래 분산이 낮으니 어두움을 먼저 알린다
        problems.append("blur")

    return {
        "ok": not problems,
        "problems": problems,
        "metrics": {"sharpness": round(sharpness, 1), "brightness": round(brightness, 1)},
    }
