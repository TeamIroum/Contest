"""시험 사진 만들기 (한 번만 실행하면 됨, 결과는 images/에 들어 있음).

얼굴은 OpenCV 공식 저장소의 공개 시험 사진(samples/data/messi5.jpg)에서 가져오고,
배경은 ai/eval/sample의 합성 이미지를 씁니다. 실제 점검 사진은 넣지 않습니다.
작은 얼굴(32~64px), 세로 사진, 32의 배수가 아닌 크기, 얼굴 없는 사진을 일부러 포함합니다.
"""
import urllib.request
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
AI = HERE.parents[1]
SRC = "https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/messi5.jpg"


def main():
    out = HERE / "images"
    out.mkdir(exist_ok=True)
    messi = cv2.imdecode(np.frombuffer(urllib.request.urlopen(SRC).read(), np.uint8), cv2.IMREAD_COLOR)
    det = cv2.FaceDetectorYN.create(str(AI / "models" / "face_yunet_s.onnx"), "", (320, 320), 0.8, 0.3, 50)
    det.setInputSize((messi.shape[1], messi.shape[0]))
    x, y, w, h = (int(v) for v in det.detect(messi)[1][0][:4])
    m = int(0.35 * w)
    face = messi[max(0, y - m):y + h + m, max(0, x - m):x + w + m]
    flipped = cv2.flip(face, 1)

    def paste(base, crop, x0, y0, width):
        k = width / crop.shape[1]
        c = cv2.resize(crop, (width, int(crop.shape[0] * k)), interpolation=cv2.INTER_AREA)
        base[y0:y0 + c.shape[0], x0:x0 + c.shape[1]] = c

    bg1 = cv2.imread(str(AI / "eval" / "sample" / "s01.jpg"))
    bg2 = cv2.imread(str(AI / "eval" / "sample" / "s02.jpg"))
    street = cv2.resize(bg1, (1920, 1080))
    for x0, y0, wd, c in [(120, 120, 40, face), (400, 600, 64, flipped), (800, 300, 96, face),
                          (1200, 500, 160, flipped), (1600, 150, 260, face), (1500, 800, 56, flipped)]:
        paste(street, c, x0, y0, wd)
    portrait = cv2.resize(bg2, (1080, 1920))
    for x0, y0, wd, c in [(100, 200, 72, flipped), (600, 700, 180, face), (300, 1400, 48, face), (800, 1600, 120, flipped)]:
        paste(portrait, c, x0, y0, wd)
    odd = cv2.resize(bg1, (1001, 777))           # 32의 배수가 아닌 크기 (채우기 확인)
    paste(odd, face, 450, 300, 110)
    paste(odd, flipped, 80, 600, 44)
    images = {
        "t01_messi5_original.jpg": messi,
        "t02_street_1920x1080.jpg": street,
        "t03_portrait_1080x1920.jpg": portrait,
        "t04_odd_size_1001x777.jpg": odd,
        "t05_no_face_1600x900.jpg": cv2.resize(bg2, (1600, 900)),
    }
    for name, img in images.items():
        cv2.imwrite(str(out / name), img, [cv2.IMWRITE_JPEG_QUALITY, 92])
    print(f"{len(images)}장 생성: {out}")


if __name__ == "__main__":
    main()
