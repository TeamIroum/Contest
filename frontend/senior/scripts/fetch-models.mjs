// 가림 모델(번호판·차량)을 public/models/에 받아 둔다. 용량이 커서(약 15MB) Git에 넣지 않는다.
// 얼굴 모델(face_yunet_s_dynamic@1.onnx, 230KB)은 이미 public/models/에 들어 있다 (ai/contract/yunet_web 복사본).
// 출처는 팀 저장소 tools/ai-verify/pwa/fetch_models.py와 같다.
//
// 실행:  npm run models
import fs from 'node:fs'
import path from 'node:path'

const OUT = path.join(import.meta.dirname, '..', 'public', 'models')
const MODELS = [
  ['plate-t384@1.onnx', 'https://github.com/ankandrew/open-image-models/releases/download/assets/yolo-v9-t-384-license-plates-end2end.onnx'],
  ['efficientdet_lite0@1.tflite', 'https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite'],
]

fs.mkdirSync(OUT, { recursive: true })
for (const [name, url] of MODELS) {
  const dst = path.join(OUT, name)
  if (fs.existsSync(dst) && fs.statSync(dst).size > 0) {
    console.log(`있음    ${name} (${(fs.statSync(dst).size / 1e6).toFixed(1)}MB)`)
    continue
  }
  console.log(`받는 중 ${name}`)
  const res = await fetch(url)
  if (!res.ok) {
    console.error(`실패    ${name}: HTTP ${res.status}`)
    process.exit(1)
  }
  fs.writeFileSync(dst, Buffer.from(await res.arrayBuffer()))
  console.log(`완료    ${name} (${(fs.statSync(dst).size / 1e6).toFixed(1)}MB)`)
}
