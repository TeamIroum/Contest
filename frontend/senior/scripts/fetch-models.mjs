// 가림 모델(번호판·차량)을 public/models/에 받고, 모델 목록(model-manifest.json)을 내용 해시와 함께 만든다.
// 번호판·차량 모델은 용량이 커서(약 15MB) Git에 넣지 않는다.
// 얼굴 모델(face_yunet_s_dynamic@1.onnx, 230KB)은 이미 public/models/에 들어 있다 (ai/contract/yunet_web 복사본).
//
// 모델 목록은 tools/ai-verify/pwa/make_model_manifest.py와 같은 방식으로 만든다
// (주소마다 ?v=<sha256 앞 12자리>, version = 주소들의 sha256 앞 8자리). masking.js는 해시 없는 목록을 거절한다.
//
// 실행:  npm run models   (모델 파일을 바꾼 뒤에도 다시 실행)
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const OUT = path.join(import.meta.dirname, '..', 'public', 'models')
const DOWNLOADS = [
  ['plate-t384@1.onnx', 'https://github.com/ankandrew/open-image-models/releases/download/assets/yolo-v9-t-384-license-plates-end2end.onnx'],
  ['efficientdet_lite0@1.tflite', 'https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite'],
]
// 키 → 파일 이름 (masking.js가 쓰는 모델. 이름의 @숫자는 모델 버전)
const MODELS = { face: 'face_yunet_s_dynamic@1.onnx', plate: 'plate-t384@1.onnx', vehicle: 'efficientdet_lite0@1.tflite' }

fs.mkdirSync(OUT, { recursive: true })
for (const [name, url] of DOWNLOADS) {
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

// 모델 목록 (make_model_manifest.py와 같은 결과)
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex')
const files = {}
for (const [key, name] of Object.entries(MODELS)) {
  const p = path.join(OUT, name)
  if (!fs.existsSync(p)) {
    console.error(`모델 파일 없음: ${name}`)
    process.exit(1)
  }
  files[key] = `./${name}?v=${sha(fs.readFileSync(p)).slice(0, 12)}`
}
const version = sha(Object.keys(files).sort().map((k) => files[k]).join('|')).slice(0, 8)
fs.writeFileSync(path.join(OUT, 'model-manifest.json'), JSON.stringify({ version, files }, null, 2) + '\n')
console.log(`목록    model-manifest.json  version ${version}`)
