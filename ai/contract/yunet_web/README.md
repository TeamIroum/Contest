# 시니어 PWA 얼굴 검출(YuNet) 구현 자료

시니어 PWA가 브라우저에서 얼굴을 찾을 때 **서버와 같은 결과**를 내도록 맞추는 자료입니다. 앱 마스킹 전체 기준은 [../APP_MASKING_GUIDE.md](../APP_MASKING_GUIDE.md)에 있습니다.

## 들어 있는 것

| 파일 | 내용 | 누가 쓰나 |
|---|---|---|
| `face_yunet_s_dynamic.onnx` | 서버와 같은 YuNet 모델. 입력 크기만 가변으로 바꿈 (233KB) | 앱 (PWA에 넣어 배포) |
| `yunet.mjs` | 브라우저용 검출 코드: 입력 만들기 → 모델 실행 → 상자로 바꾸기 → 겹친 상자 정리 | 앱 (그대로 쓰거나 옮겨 씀) |
| `images/`, `expected.json` | 시험 사진 5장과 서버(OpenCV)가 찾은 얼굴 | 앱 (구현 확인) |
| `verify.mjs`, `package.json` | `yunet.mjs`가 서버와 같은 얼굴을 찾는지 확인 | 앱·CI |
| `yunet_reference.py` | 같은 과정을 Python으로 적은 기준 구현 (서버와 완전히 일치함을 확인) | AI |
| `make_test_images.py`, `make_expected.py` | 시험 사진·가변 모델·기대 결과 다시 만들기 | AI |

## 왜 서버 모델을 그대로 쓰지 않고 가변 모델을 쓰나

서버는 OpenCV로 YuNet을 **사진 해상도 그대로** 돌립니다. 모델 파일은 입력이 640×640으로 선언되어 있지만 OpenCV는 크기를 바꿔 돌릴 수 있습니다. 브라우저의 ONNX Runtime Web은 선언된 크기를 지키므로, 서버 모델을 그대로 쓰면 사진을 640으로 줄여야 하고 **멀리 있는 작은 얼굴을 놓칩니다.** 그래서 가중치는 그대로 두고 입력 크기 선언만 가변으로 바꾼 파일을 넘깁니다.

## 서버와 같은 결과를 내는 조건

1. **사진을 줄이지 않고 넣습니다.** 카메라에서 받은 해상도 그대로(긴 변 2,400px 초과면 그 크기로 줄이고 좌표를 되돌림).
2. **입력은 BGR 순서, 0~255 값 그대로입니다.** 캔버스의 `ImageData`는 RGBA이므로 순서를 바꿉니다. 0~1로 나누지 않습니다.
3. **크기를 32의 배수로 맞출 때 늘리지 않고 오른쪽·아래를 0으로 채웁니다.**
4. **점수는 `sqrt(cls × obj)`**, 기준 0.55, 겹친 상자 정리(NMS) 기준 0.3입니다. 서버 설정(`FACE_SCORE`)과 같습니다.
5. **출력 상자는 중심이 아니라 왼쪽 위 좌표**로 바꿔서 씁니다.

이 다섯 가지는 `yunet.mjs`에 이미 들어 있습니다. 직접 옮겨 쓸 때 가장 많이 틀리는 곳이라, `verify.mjs`는 2·4·5번을 틀리면 실패하도록 확인해 두었습니다.

## 사용법 (브라우저)

```js
import * as ort from "onnxruntime-web";
import { loadYuNet, detectFaces } from "./yunet.mjs";

const session = await loadYuNet(ort, "/models/face_yunet_s_dynamic@1.onnx");
const ctx = canvas.getContext("2d");                 // 촬영한 순간을 그린 캔버스
const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
const faces = await detectFaces(ort, session, img.data, img.width, img.height);
// faces: [{ x, y, w, h, landmarks, score }]  원본 픽셀 좌표
```

찾은 상자는 APP_MASKING_GUIDE.md 4장대로 사방 25% 넓혀 가립니다.

## 구현 확인

```bash
cd ai/contract/yunet_web
npm install
node verify.mjs
```

Node.js에서 `onnxruntime-node`로 같은 코드를 돌려 `expected.json`과 비교합니다. 기준은 다음과 같습니다.

- 서버가 찾은 얼굴마다 상자 겹침(IoU) 0.9 이상, 점수 차이 0.03 이하
- 서버에 없는 얼굴을 찾으면 실패
- 점수가 기준(0.55)에 0.03 이내로 가까운 얼굴은 있어도 없어도 통과. 사진 해독(JPEG → 픽셀) 방식이 조금만 달라도 기준선을 넘나들기 때문입니다. `t01`의 두 번째 얼굴(점수 0.55)이 그런 경우입니다.

브라우저에서 직접 확인하려면 같은 시험 사진을 캔버스에 그려 `detectFaces` 결과를 `expected.json`과 같은 기준으로 비교하면 됩니다. 브라우저마다 JPEG 해독이 조금씩 달라 Node.js와 점수가 미세하게 다를 수 있으나 위 허용 범위 안이어야 합니다.

## 처리 시간 참고

Node.js(서버 CPU 1개)에서 1920×1080 사진 한 장이 약 0.2~0.3초였습니다. 휴대폰 브라우저(WASM)는 이보다 느리므로, 시범 기종에서 직접 재고 APP_MASKING_GUIDE.md 7장의 3초 목표(얼굴·번호판·차량 합계)에 맞는지 봅니다.

## 다시 만들 때 (AI 담당)

모델, 서버 점수 기준, OpenCV 버전이 바뀌면 다시 만들고 앱 담당에게 알립니다.

```bash
pip install onnx                 # 가변 모델 변환에만 필요
python make_test_images.py       # 시험 사진 (보통 다시 만들 필요 없음)
python make_expected.py          # 가변 모델 + 기대 결과, 기준 구현이 서버와 같은지 확인
node verify.mjs
```

시험 사진의 얼굴은 OpenCV 공식 저장소의 공개 시험 사진(`samples/data/messi5.jpg`)에서 가져왔고, 배경은 `ai/eval/sample`의 합성 이미지입니다. 실제 점검 사진은 넣지 않습니다.
