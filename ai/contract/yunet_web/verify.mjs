// yunet.mjs가 서버(OpenCV)와 같은 얼굴을 찾는지 확인합니다.
// 실행: npm install && node verify.mjs
// 브라우저에서는 사진 해독(JPEG → 픽셀) 방식이 조금 달라 점수가 미세하게 다를 수 있으므로 허용 범위를 둡니다.
import fs from "node:fs";
import * as ort from "onnxruntime-node";
import jpeg from "jpeg-js";
import { loadYuNet, detectFaces, iou, SCORE_THRESHOLD } from "./yunet.mjs";

const MIN_IOU = 0.9;          // 상자 겹침
const MAX_SCORE_DIFF = 0.03;  // 점수 차이
const BORDERLINE = 0.03;      // 기준(0.55)에 이만큼 가까운 얼굴은 있어도 없어도 통과

const expected = JSON.parse(fs.readFileSync(new URL("./expected.json", import.meta.url)));
const session = await loadYuNet(ort, new URL("./face_yunet_s_dynamic.onnx", import.meta.url).pathname);
let failed = 0;

for (const [name, exp] of Object.entries(expected.images)) {
  const img = jpeg.decode(fs.readFileSync(new URL(`./images/${name}`, import.meta.url)), { useTArray: true, formatAsRGBA: true });
  const t0 = performance.now();
  const got = await detectFaces(ort, session, img.data, img.width, img.height);
  const ms = Math.round(performance.now() - t0);
  const problems = [];
  const used = new Set();
  for (const e of exp.faces) {
    let best = -1, bestIou = 0;
    got.forEach((g, i) => { const v = iou(e, g); if (v > bestIou) { bestIou = v; best = i; } });
    const borderline = e.score < SCORE_THRESHOLD + BORDERLINE;
    if (best < 0 || bestIou < MIN_IOU) { if (!borderline) problems.push(`놓침 score=${e.score}`); continue; }
    used.add(best);
    const d = Math.abs(got[best].score - e.score);
    if (d > MAX_SCORE_DIFF) problems.push(`점수 차이 ${d.toFixed(3)}`);
  }
  got.forEach((g, i) => { if (!used.has(i) && g.score >= SCORE_THRESHOLD + BORDERLINE) problems.push(`없는 얼굴 score=${g.score.toFixed(3)}`); });
  console.log(`${problems.length ? "실패" : "통과"}  ${name}  기대 ${exp.faces.length}개 / 찾음 ${got.length}개  ${ms}ms  ${problems.join(", ")}`);
  if (problems.length) failed++;
}
console.log(failed ? `\n${failed}장 실패` : "\n모두 통과");
process.exit(failed ? 1 : 0);
