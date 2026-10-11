// 설정과 전송 대기함. 대기함에는 가린 사진만 넣는다 (원본은 어떤 경로로도 오지 않음).
//
// 업무 API 명세 6·7절
//   - 먼저 대기함(앱 문서 폴더)에 저장한 뒤 보낸다. 서버가 RECEIVED 또는 NO_MEDIA로 답하기 전에는 "전송 대기"로 둔다.
//   - 503·연결 끊김은 접수가 아니다. 같은 clientSubmissionId/attemptId·같은 파일로 다시 보낸다.
//   - 앱이 다시 열릴 때, 연결이 돌아올 때 대기 사진을 보낸다.
import { Directory, File, Paths } from "expo-file-system";

// 시험 서버 주소 기본값. 코드에 넣지 않고 .env.local(Git 제외)의 EXPO_PUBLIC_SERVER_URL에서 받는다.
// 터널 주소가 바뀌면 .env.local을 고치고 앱을 다시 불러오거나, 앱 처음 화면 "시험 설정"에서 고친다
export const DEFAULT_SERVER = (process.env.EXPO_PUBLIC_SERVER_URL || "").trim();

const configFile = new File(Paths.document, "config.json");
const outboxDir = new Directory(Paths.document, "outbox");
const resultsFile = new File(Paths.document, "results.json");

// ---------------------------------------------------------------- 설정
export function loadConfig() {
  const base = { server: DEFAULT_SERVER, preview: true, boxes: true };
  try {
    return configFile.exists ? { ...base, ...JSON.parse(configFile.textSync()) } : base;
  } catch {
    return base;
  }
}

export function saveConfig(cfg) {
  try { configFile.write(JSON.stringify(cfg)); } catch { /* 저장 못 해도 동작 */ }
}

export const apiBase = (cfg) => `${cfg.server.replace(/\/+$/, "")}/api/v1`;

// ---------------------------------------------------------------- 대기함
function ensureDir() {
  outboxDir.create({ intermediates: true, idempotent: true });
}

// item: 제출 정보(JSON으로 남길 것), b64: 가린 JPEG
export function putNew(item, b64) {
  ensureDir();
  const photo = new File(outboxDir, `${item.clientSubmissionId}.jpg`);
  photo.write(b64, { encoding: "base64" });
  writeMeta({ ...item, photoUri: photo.uri });
  return { ...item, photoUri: photo.uri };
}

function writeMeta(item) {
  new File(outboxDir, `${item.clientSubmissionId}.json`).write(JSON.stringify(item));
}

export function all() {
  if (!outboxDir.exists) return [];
  const out = [];
  for (const f of outboxDir.list()) {
    if (f instanceof File && f.name.endsWith(".json")) {
      try { out.push(JSON.parse(f.textSync())); } catch { /* 깨진 항목은 건너뜀 */ }
    }
  }
  return out.sort((a, b) => a.capturedAt.localeCompare(b.capturedAt));
}

export function remove(id) {
  for (const name of [`${id}.json`, `${id}.jpg`]) {
    const f = new File(outboxDir, name);
    if (f.exists) f.delete();
  }
}

export function results() {
  try { return resultsFile.exists ? JSON.parse(resultsFile.textSync()) : []; } catch { return []; }
}

function logResult(r) {
  const list = [r, ...results()].slice(0, 50);
  resultsFile.write(JSON.stringify(list));
}

export function clearResults() {
  if (resultsFile.exists) resultsFile.delete();
}

// ---------------------------------------------------------------- 전송
// 한 건 보내기. 돌려주는 것: { done: 대기함에서 뺐는지, http, body, offline }
// 같은 사진을 동시에 두 번 보내지 않는다: 이미 보내는 중이면 그 결과를 함께 기다린다 (전송 버튼과 자동 재전송이 겹칠 때)
const inflight = new Map();
export function send(item, cfg) {
  const id = item.clientSubmissionId;
  if (!inflight.has(id)) inflight.set(id, sendOnce(item, cfg).finally(() => inflight.delete(id)));
  return inflight.get(id);
}

async function sendOnce(item, cfg) {
  const fd = new FormData();
  // SDK 57의 fetch(expo/fetch)는 RN 방식 {uri, name, type}을 받지 않는다 ("Unsupported FormDataPart implementation").
  // expo-file-system의 File은 bytes()와 파일 이름을 갖고 있어 그대로 넣으면 된다
  fd.append("file", new File(item.photoUri));
  for (const k of ["clientSubmissionId", "captureSlotId", "attemptId", "capturedAt", "maskingVersion"]) fd.append(k, item[k]);
  fd.append("deviceMasked", "true");
  fd.append("deviceMaskReport", JSON.stringify(item.deviceMaskReport));
  let resp;
  try {
    resp = await fetch(`${apiBase(cfg)}/patrols/${item.patrolId}/stops/${item.stopId}/inspections`, { method: "POST", body: fd });
  } catch (e) {
    const why = `${e?.name || "Error"}: ${e?.message || e}`;
    keep(item, { lastError: `연결 안 됨 (${why})` });
    // 사진이 서버에 닿지 못한 이유를 서버 기록에 남긴다 (사진 내용은 넣지 않음)
    fetch(`${apiBase(cfg)}/test-client-logs`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ stage: "upload", name: e?.name || null, message: String(e?.message || e),
        userAgent: "rn-test (React Native)", detail: { exists: new File(item.photoUri).exists, size: new File(item.photoUri).size } }),
    }).catch(() => {});
    return { done: false, offline: true, error: why };
  }
  const body = await resp.json().catch(() => ({}));
  const receipt = body.receiptStatus;
  // 접수(RECEIVED)·사진 없는 품질 실패(NO_MEDIA)·이미 끝난 구도(SLOT_RESOLVED)는 최종 답이므로 대기함에서 뺀다
  const final = (resp.ok && (receipt === "RECEIVED" || receipt === "NO_MEDIA")) || body.code === "SLOT_RESOLVED";
  if (final) {
    logResult({ clientSubmissionId: item.clientSubmissionId, stopName: item.stopName, capturedAt: item.capturedAt,
      http: resp.status, body, at: new Date().toISOString() });
    remove(item.clientSubmissionId);
    return { done: true, http: resp.status, body };
  }
  // 그 밖(503, 409 처리 중, 4xx 형식 오류)은 남겨 두고 이유를 적는다. 4xx 형식 오류는 자동으로 다시 보내지 않음(지우기 가능)
  const permanent = resp.status >= 400 && resp.status < 500 && body.code !== "SUBMISSION_PROCESSING";
  keep(item, { lastError: `${resp.status} ${body.code || ""} ${body.detail || ""}`.trim(), permanent });
  return { done: false, http: resp.status, body };
}

// 실패 이유를 적어 다시 저장. 그사이 다른 경로가 이미 지웠으면 되살리지 않는다
function keep(item, patch) {
  const meta = new File(outboxDir, `${item.clientSubmissionId}.json`);
  if (!meta.exists) return;
  let cur = item;
  try { cur = JSON.parse(meta.textSync()); } catch { /* 저장된 값이 깨졌으면 받은 값으로 */ }
  writeMeta({ ...cur, ...patch, tries: (cur.tries || 0) + 1 });
}

// 대기 사진을 차례로 보낸다. 돌려주는 것: [{ item, done, http, body, offline }]
let flushing = null;
export function flush(cfg) {
  if (!flushing) {
    flushing = (async () => {
      const out = [];
      for (const item of all()) {
        if (item.permanent) continue;
        const r = await send(item, cfg);
        out.push({ item, ...r });
        if (r.offline) break;   // 연결이 없으면 나머지도 실패하므로 멈춤
      }
      return out;
    })().finally(() => { flushing = null; });
  }
  return flushing;
}

// ---------------------------------------------------------------- 임시 파일 정리
// 카메라·사진 고르기·크기 조정이 앱 캐시 폴더에 만든 원본 사본을 지운다. 앱 캐시 밖(갤러리 원본)은 건드리지 않는다
export function deleteTemp(uri) {
  if (!uri || !uri.startsWith(Paths.cache.uri)) return;
  try {
    const f = new File(uri);
    if (f.exists) f.delete();
  } catch { /* 이미 없음 */ }
}
