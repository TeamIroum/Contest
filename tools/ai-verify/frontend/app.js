// AI 판독 검증 콘솔 화면. 모든 호출은 검증용 백엔드(/api)로만 보냅니다. AI 서버를 직접 부르지 않습니다.
"use strict";

const ANALYZE_SCENARIOS = ["", "sidewalk_danger", "drain_normal", "drain_minor", "fence_tilted", "bench_broken",
  "exercise_part", "sidewalk_uncertain", "out_of_frame", "unknown_facility", "plate_leak", "issue_under_mask"];
const VERIFY_SCENARIOS = ["", "fixed", "not_fixed", "different_place", "worse"];
const VERIFY_RESULTS = ["fixed", "not_fixed", "unclear", "worse", "same", "better", "retake"];
const GRADES = ["정상", "관찰", "주의", "긴급"];
const KIND_LABEL = { analyze: "판독", verify: "전후 비교", privacy: "개인정보", narrative: "보고 문장" };
const STATUS_LABEL = { ok: "판독 완료", retake: "재촬영", needs_review: "담당자 확인", error: "처리 실패" };
const LEVEL_LABEL = { pass: "통과", fail: "위반", warn: "확인", info: "참고", skip: "건너뜀" };

const state = { health: null, taxonomy: null, selectedRun: null };
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

// ---------------------------------------------------------------- 공통
function esc(v) {
  return String(v ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function api(path, opts = {}) {
  const res = await fetch("/api" + path, opts);
  const text = await res.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { body = { detail: text }; }
  if (!res.ok) {
    const d = body && body.detail;
    throw new Error(`HTTP ${res.status}: ${typeof d === "string" ? d : JSON.stringify(d)}`);
  }
  return body;
}

let toastTimer;
function toast(msg, err = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast" + (err ? " err" : "");
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), err ? 6000 : 2500);
}

async function busy(btn, fn) {
  const label = btn.textContent;
  btn.disabled = true;
  try { return await fn(t => (btn.textContent = t)); }
  catch (e) { toast(e.message, true); }
  finally { btn.disabled = false; btn.textContent = label; }
}

function badge(text, cls = "") { return `<span class="badge ${esc(cls)}">${esc(text)}</span>`; }
function gradeTag(g) {
  if (!g) return '<span class="muted">—</span>';
  const i = GRADES.indexOf(g);
  return `<span class="grade g${i < 0 ? 0 : i}">${esc(g)}</span>`;
}
function facilityLabel(type) {
  const f = state.taxonomy && state.taxonomy.facilities && state.taxonomy.facilities[type];
  return f ? `${f.label} (${type})` : type || "—";
}
function issueLabel(ftype, itype) {
  const f = state.taxonomy && state.taxonomy.facilities && state.taxonomy.facilities[ftype];
  const l = f && f.issues && f.issues[itype];
  return l ? `${l} (${itype})` : itype;
}
function fmtTime(s) { return s ? s.replace("T", " ").slice(5, 19) : ""; }
function opt(value, label, sel) { return `<option value="${esc(value)}"${sel ? " selected" : ""}>${esc(label)}</option>`; }

// ---------------------------------------------------------------- 탭
function showTab(name) {
  $$("#tabs button").forEach(b => b.classList.toggle("active", b.dataset.tab === name));
  $$(".tab").forEach(s => s.classList.toggle("active", s.id === "tab-" + name));
  ({ overview: loadOverview, verify: loadBeforeRuns, runs: loadRuns, events: loadEvents })[name]?.();
}
$("#tabs").addEventListener("click", e => { if (e.target.dataset.tab) showTab(e.target.dataset.tab); });

// ---------------------------------------------------------------- 상태
async function loadHealth() {
  const el = $("#ai-status");
  try {
    const h = await api("/health");
    state.health = h;
    const ai = h.ai || {};
    el.className = "ai-status " + (h.reachable && h.contract_match && h.auth_ok ? "ok" : "bad");
    el.lastElementChild.textContent = h.reachable
      ? `${ai.provider} · ${ai.model} · 계약 ${ai.contract_version}${h.auth_ok ? "" : " · 인증 실패"}`
      : "AI 서버에 연결하지 못함";
    $$(".mock-only").forEach(x => (x.hidden = ai.provider !== "mock"));
    $("#health").innerHTML = [
      ["AI 서버", esc(h.console.ai_base_url)],
      ["연결", h.reachable ? badge(`HTTP ${h.http_status} · ${h.latency_ms}ms`, "pass") : badge(`연결 실패 (HTTP ${h.http_status})`, "fail")],
      ["공급자 / 모델", esc(`${ai.provider ?? "—"} / ${ai.model ?? "—"}`)],
      ["계약 버전", h.contract_match ? badge(ai.contract_version, "pass") : badge(`${ai.contract_version ?? "—"} (기대 ${h.expected_contract})`, "fail")],
      ["API 키 인증", h.auth_ok ? badge(h.console.has_api_key ? "키 사용 중" : "통과 (AI 서버 인증 꺼짐)", h.console.has_api_key ? "pass" : "warn")
        : badge(`실패 HTTP ${h.auth_status ?? "—"}`, "fail")],
      ["얼굴 검출기", esc(ai.face_detector ?? "—")],
      ["앱 마스킹 필수", ai.require_device_masking ? badge("켜짐", "pass") : badge("꺼짐 (운영은 1)", "warn")],
      ["모델 준비", !ai.warmup || ai.warmup.state === "skipped" ? '<span class="muted">해당 없음</span>'
        : ai.warmup.state === "ready" ? badge(`준비됨 (${ai.warmup.seconds}초)`, "pass")
        : ai.warmup.state === "loading" ? badge("모델 올리는 중", "warn")
        : badge("실패: " + (ai.warmup.error || ""), "fail")],
      ["재판독 대기", esc(ai.retry_queue ?? "—")],
      ["마지막 알림 순번", esc(ai.last_event_seq ?? "—")],
      ["검증 기록 위치", `<span class="muted small">${esc(h.console.data_dir)}</span>`],
    ].map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("");
  } catch (e) {
    el.className = "ai-status bad";
    el.lastElementChild.textContent = "검증 백엔드 오류";
    $("#health").innerHTML = `<dt>오류</dt><dd>${esc(e.message)}</dd>`;
  }
}
$("#health-refresh").onclick = () => { loadHealth(); loadOverview(); };

async function loadTaxonomy() {
  try { state.taxonomy = await api("/taxonomy"); } catch (e) { toast("taxonomy를 받지 못했습니다: " + e.message, true); return; }
  const t = state.taxonomy;
  $("#tag-boxes").innerHTML = Object.entries(t.location_tags || {}).map(([k, v]) =>
    `<label><input type="checkbox" name="tag" value="${esc(k)}">${esc(v)} <code>${esc(k)}</code></label>`).join("");
  $("#exp-facility").innerHTML = opt("", "—") + Object.entries(t.facilities).map(([k, v]) => opt(k, v.label)).join("") + opt("unknown", "목록 외 (unknown)");
  $("#exp-grade").innerHTML = opt("", "—") + GRADES.map(g => opt(g, g)).join("");
  fillIssues();
}
function fillIssues() {
  const f = $("#exp-facility").value;
  const facs = state.taxonomy ? state.taxonomy.facilities : {};
  const issues = f && facs[f] ? Object.entries(facs[f].issues) : [];
  $("#exp-issue").innerHTML = opt("", "—") + opt("none", "문제 없음") + issues.map(([k, v]) => opt(k, v)).join("");
}
$("#exp-facility").onchange = fillIssues;

// ---------------------------------------------------------------- 결과 카드
function overlay(run) {
  const resp = run.response || {};
  if (run.hidden) return `<div class="photo"><div class="noimg">가림 점검에서 보류된 사진 (410)</div></div>`;
  if (!run.image_path) {
    const why = resp.status === "error" ? "처리 실패라 저장되지 않음" : resp.status === "retake" ? "흐림·어두움 재촬영이라 저장되지 않음" : "저장된 사진 없음";
    return `<div class="photo"><div class="noimg">${why}</div></div>`;
  }
  const boxes = [];
  (resp.issues || []).forEach(i => {
    if (Array.isArray(i.bbox)) boxes.push({ b: i.bbox, cls: "", t: `${i.label || i.type} ${i.severity}/3` });
  });
  const priv = ((resp.meta || {}).privacy || resp.privacy || {}).boxes || [];
  priv.forEach(p => { if (Array.isArray(p.box)) boxes.push({ b: p.box, cls: "priv", t: `가림 ${p.kind || ""}${p.source === "ai" ? " (AI)" : ""}` }); });
  const html = boxes.map(({ b, cls, t }) => {
    const [l, tp, r, bt] = b;
    return `<div class="box ${cls}" style="left:${l * 100}%;top:${tp * 100}%;width:${(r - l) * 100}%;height:${(bt - tp) * 100}%"><span>${esc(t)}</span></div>`;
  }).join("");
  return `<div class="photo"><img src="/api/runs/${run.id}/image?r=${run.image_revision}" alt="가린 사진">${html}</div>
    <div class="legend">빨간 네모: 문제 위치 · 점선: 가린 위치 · 사진 버전 r${esc(run.image_revision)} (백엔드 복사본)</div>`;
}

function checksHtml(items) {
  if (!items || !items.length) return '<p class="muted">검사 없음</p>';
  const order = { fail: 0, warn: 1, info: 2, pass: 3 };
  const sorted = [...items].sort((a, b) => order[a.level] - order[b.level]);
  const count = l => items.filter(c => c.level === l).length;
  const li = c => `<li><span>${badge(LEVEL_LABEL[c.level], c.level)}</span><div>${esc(c.title)}${c.detail ? `<div class="detail">${esc(c.detail)}</div>` : ""}</div></li>`;
  const notPass = sorted.filter(c => c.level !== "pass"), pass = sorted.filter(c => c.level === "pass");
  return `<div class="check-summary">${badge(`통과 ${count("pass")}`, "pass")}${count("fail") ? badge(`위반 ${count("fail")}`, "fail") : ""}${count("warn") ? badge(`확인 ${count("warn")}`, "warn") : ""}${count("info") ? badge(`참고 ${count("info")}`, "info") : ""}</div>
  ${notPass.length ? `<ul class="checks">${notPass.map(li).join("")}</ul>` : ""}
  ${pass.length ? `<details><summary>통과한 항목 ${pass.length}개 보기</summary><ul class="checks">${pass.map(li).join("")}</ul></details>` : ""}`;
}

function expectedHtml(run) {
  const exp = run.expected;
  if (!exp) return "";
  const resp = run.response || {};
  const rows = [];
  const mark = ok => ok ? badge("일치", "pass") : badge("불일치", "fail");
  if (exp.facility) rows.push(["시설", `${esc(facilityLabel(exp.facility))} ${mark(((resp.facility || {}).type) === exp.facility)}`]);
  if (exp.issue) {
    const got = (resp.issues || []).map(i => i.type);
    rows.push(["문제", `${esc(exp.issue === "none" ? "문제 없음" : exp.issue)} ${mark(exp.issue === "none" ? !got.length : got.includes(exp.issue))}`]);
  }
  if (exp.grade) rows.push(["등급", `${gradeTag(exp.grade)} ${mark(resp.grade === exp.grade)}`]);
  if (exp.result) {
    const got = resp.status === "retake" ? "retake" : resp.result;
    rows.push(["결과", `${esc(exp.result)} ${mark(got === exp.result)}`]);
  }
  return `<h3>정답 라벨</h3><dl class="facts">${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("")}</dl>`;
}

function factsHtml(run) {
  const r = run.response || {};
  const rows = [];
  const add = (k, v) => { if (v !== undefined && v !== null && v !== "") rows.push([k, v]); };
  if (run.kind === "analyze") {
    add("상태", badge(STATUS_LABEL[r.status] || r.status || "—", r.status) + ` <code>${esc(r.status)}</code>`);
    add("등급", gradeTag(r.grade) + (r.status === "needs_review" && r.grade ? ' <span class="muted small">잠정</span>' : ""));
    add("처리 제안", `<code>${esc(r.action)}</code>`);
    if (r.facility) add("시설", `${esc(facilityLabel(r.facility.type))} <span class="muted small">확신 ${esc(r.facility.certainty)}</span>`
      + (r.facility.facility_id ? ` · AI 대장 ${esc(r.facility.facility_id)}` : "")
      + (r.facility.location_tags && r.facility.location_tags.length ? ` · 태그 ${esc(r.facility.location_tags.join(", "))}` : ""));
    if ((r.issues || []).length) add("문제", (r.issues || []).map(i => `${esc(issueLabel((r.facility || {}).type, i.type))} · 정도 ${esc(i.severity)}/3<div class="muted small">${esc(i.evidence)}</div>`).join(""));
    add("담당", esc(r.dept));
    if ((r.grade_reasons || []).length) add("판정 근거", `<ul class="reasons">${r.grade_reasons.map(x => `<li>${esc(x)}</li>`).join("")}</ul>`);
    add("중복", r.duplicate_of && `기존 건 <code>${esc(r.duplicate_of)}</code>`);
    if (r.quality && r.quality.problems && r.quality.problems.length) add("품질 문제", esc(r.quality.problems.join(", ")));
  } else if (run.kind === "verify") {
    add("상태", badge(STATUS_LABEL[r.status] || r.status || "—", r.status) + ` <code>${esc(r.status)}</code>`);
    add("모드", `<code>${esc(r.mode)}</code>`);
    add("결과", r.result && `<code>${esc(r.result)}</code>`);
    add("처리 제안", `<code>${esc(r.action)}</code>`);
    add("같은 시설", esc(r.same_facility));
    add("현재 정도", r.current_severity != null ? `${esc(r.current_severity)}/3` : null);
    add("제안 등급", r.new_grade && gradeTag(r.new_grade));
    add("근거", esc(r.evidence));
    const p = (run.request || {}).prior;
    if (p) add("prior", `<code>${esc(p.case_id)}</code> ${esc(facilityLabel(p.facility_type))} · ${esc(p.issue_type)} ${esc(p.severity)}/3`);
    if (run.parent_run_id) add("처음 판독", `<a href="#" data-open-run="${run.parent_run_id}">#${run.parent_run_id}</a>`);
  } else if (run.kind === "privacy") {
    add("상태", badge(r.status, r.status));
    add("purpose", `<code>${esc(r.purpose)}</code>`);
    add("media_id", `<code>${esc(r.media_id)}</code>`);
  } else if (run.kind === "narrative") {
    add("source", badge(r.source, r.source === "llm" ? "pass" : "warn"));
    add("문장", `<pre class="json">${esc(r.text)}</pre>`);
    if (r.note) add("참고", esc(r.note));
  }
  if (r.senior_message) add("어르신 안내", `${esc(r.senior_message.text)} <code>${esc(r.senior_message.key)}</code>`);
  if (r.retry) add("재판독", `<code>${esc(JSON.stringify(r.retry))}</code>`);
  if ((r.notes || []).length) add("메모", r.notes.map(esc).join("<br>"));
  const priv = (r.meta || {}).privacy || r.privacy;
  if (priv) add("개인정보", `mode <code>${esc(priv.mode)}</code> · 서버 확인 얼굴 ${esc(priv.faces)} 번호판 ${esc(priv.plates)} 차량 ${esc(priv.vehicles)}`
    + ` · server_added <b>${esc(priv.server_added)}</b>` + (priv.leaks_fixed ? ` · AI 누락 보정 ${esc(priv.leaks_fixed)}` : ""));
  const meta = r.meta || {};
  if (meta.provider) add("모델", `${esc(meta.provider)} / ${esc(meta.model)} · ${esc(meta.prompt_version || "")} · AI 처리 ${esc(meta.latency_ms)}ms${meta.replayed ? " · " + badge("재요청", "info") : ""}`);
  return `<dl class="facts">${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("")}</dl>`;
}

function labelEditor(run) {
  if (run.kind !== "analyze" && run.kind !== "verify") return "";
  const e = run.expected || {};
  if (run.kind === "verify") {
    return `<h3>정답 라벨 고치기</h3><div class="label-edit" data-run="${run.id}">
      <select name="result">${opt("", "—")}${VERIFY_RESULTS.map(v => opt(v, v, e.result === v)).join("")}</select>
      <button class="mini primary" data-save-label>저장</button></div>`;
  }
  const facs = state.taxonomy ? state.taxonomy.facilities : {};
  const issues = new Set();
  Object.values(facs).forEach(f => Object.keys(f.issues).forEach(k => issues.add(k)));
  return `<h3>정답 라벨 고치기</h3><div class="label-edit" data-run="${run.id}">
    <select name="facility">${opt("", "시설 —")}${Object.entries(facs).map(([k, v]) => opt(k, v.label, e.facility === k)).join("")}${opt("unknown", "목록 외", e.facility === "unknown")}</select>
    <select name="issue">${opt("", "문제 —")}${opt("none", "문제 없음", e.issue === "none")}${[...issues].map(k => opt(k, k, e.issue === k)).join("")}</select>
    <select name="grade">${opt("", "등급 —")}${GRADES.map(g => opt(g, g, e.grade === g)).join("")}</select>
    <button class="mini primary" data-save-label>저장</button></div>`;
}

function renderRun(run, { detail = false } = {}) {
  const r = run.response || {};
  const s = run.summary || summarize(run.checks);
  const el = document.createElement("div");
  el.className = "card";
  const snaps = run.snapshots || [];
  el.innerHTML = `
    <div class="run-head">
      <span class="title">#${run.id} · ${esc(KIND_LABEL[run.kind] || run.kind)} · ${esc(run.label || "")}</span>
      ${run.scenario ? badge("mock: " + run.scenario, "info") : ""}
      ${badge(`HTTP ${run.http_status}`, run.http_status === 200 ? "pass" : "fail")}
      ${s.fail ? badge(`계약 위반 ${s.fail}`, "fail") : badge("계약 통과", "pass")}
      ${s.warn ? badge(`확인 ${s.warn}`, "warn") : ""}
      <span class="muted small">${esc(run.latency_ms)}ms</span>
    </div>
    <div class="${run.kind === "narrative" ? "" : "run"}">
      ${run.kind === "narrative" ? "" : `<div>${overlay(run)}<div class="muted small" style="margin-top:6px">photo_id <code>${esc(run.photo_id)}</code></div></div>`}
      <div>
        ${r.retry && r.retry.pending ? `<p class="badge warn" style="white-space:normal;margin-bottom:8px">AI가 재판독하는 중입니다 (30초·2분·10분 간격). 결과가 나오면 자동으로 새 스냅샷이 추가됩니다.</p>` : ""}
        ${run.http_status === 200 ? factsHtml(run) :`<p class="warn-text">${esc(r.detail ? JSON.stringify(r.detail) : "요청 실패")}</p>`}
        ${expectedHtml(run)}
        ${detail ? labelEditor(run) : ""}
        <h3>계약 검사</h3>${checksHtml(run.checks)}
      </div>
    </div>
    ${detail && (run.kind === "analyze" || run.kind === "verify") ? `<h3>결과 스냅샷 ${snaps.length}개</h3>
      <div class="snap-list">${snaps.map((x, i) => `<button class="mini ${i === snaps.length - 1 ? "primary" : ""}" data-snap="${i}">${i + 1}. ${esc(x.source)} ${esc(fmtTime(x.created_at))}</button>`).join("")}
      <button class="mini ghost" data-refresh="${run.id}">GET /results 로 최신 결과 확인</button></div>` : ""}
    <details><summary>보낸 값 · 받은 JSON</summary>
      <pre class="json">${esc(JSON.stringify(run.request, null, 2))}</pre>
      <pre class="json" data-json>${esc(JSON.stringify(r, null, 2))}</pre></details>`;
  el.addEventListener("click", async ev => {
    const t = ev.target;
    if (t.dataset.openRun) { ev.preventDefault(); openRun(+t.dataset.openRun); }
    if (t.dataset.snap !== undefined) {
      $("[data-json]", el).textContent = JSON.stringify(snaps[+t.dataset.snap].response, null, 2);
      $$("[data-snap]", el).forEach(b => b.classList.toggle("primary", b === t));
      el.querySelector("details").open = true;
    }
    if (t.dataset.refresh) {
      await busy(t, async () => {
        const out = await api(`/runs/${t.dataset.refresh}/refresh`, { method: "POST" });
        toast(out.refresh_changed === null ? "조회할 수 없는 기록입니다" : out.refresh_changed ? "새 결과를 스냅샷으로 추가했습니다" : "결과 변화 없음");
        if (out.refresh_changed) openRun(run.id);
      });
    }
    if (t.dataset.saveLabel !== undefined) {
      const box = t.closest(".label-edit");
      const body = {};
      $$("select", box).forEach(s => (body[s.name] = s.value || null));
      await busy(t, async () => { await api(`/runs/${box.dataset.run}/expected`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }); toast("정답 라벨을 저장했습니다"); openRun(run.id); loadRuns(); });
    }
  });
  return el;
}

function summarize(items) {
  const o = { pass: 0, fail: 0, warn: 0, info: 0 };
  (items || []).forEach(c => (o[c.level] = (o[c.level] || 0) + 1));
  return o;
}

// ---------------------------------------------------------------- 판독 시험
function setupDrop(label, onFiles) {
  const input = $("input[type=file]", label);
  ["dragenter", "dragover"].forEach(n => label.addEventListener(n, e => { e.preventDefault(); label.classList.add("over"); }));
  ["dragleave", "drop"].forEach(n => label.addEventListener(n, e => { e.preventDefault(); label.classList.remove("over"); }));
  label.addEventListener("drop", e => { input.files = e.dataTransfer.files; onFiles(input.files); });
  input.addEventListener("change", () => onFiles(input.files));
}
setupDrop($("#analyze-drop"), files => ($("#analyze-files").textContent = [...files].map(f => `${f.name} (${Math.round(f.size / 1024)}KB)`).join(", ")));
$$("#tab-verify .drop, #tab-extra .drop").forEach(l => setupDrop(l, files => {
  const out = l.nextElementSibling;
  if (out && out.classList.contains("file-list")) out.textContent = [...files].map(f => f.name).join(", ");
}));

function deviceReport(form) {
  if (!form.device_masked.checked) return "";
  return JSON.stringify({ faces: +form.dev_faces.value || 0, plates: +form.dev_plates.value || 0, detector: "yunet+plate-t384+effdet0", appVersion: form.dev_app.value });
}

$("#analyze-form").addEventListener("submit", async ev => {
  ev.preventDefault();
  const form = ev.target;
  const files = [...form.image.files];
  if (!files.length) return toast("사진을 고르세요", true);
  const col = $("#analyze-result");
  if ($(".empty", col)) col.innerHTML = "";
  await busy($("#analyze-submit"), async setLabel => {
    for (const [i, file] of files.entries()) {
      setLabel(`판독 중… ${i + 1}/${files.length}`);
      const fd = new FormData();
      fd.append("image", file);
      fd.append("scenario", form.scenario.value);
      fd.append("location_tags", $$("input[name=tag]:checked", form).map(x => x.value).join(","));
      fd.append("device_masked", form.device_masked.checked);
      fd.append("device_report", deviceReport(form));
      for (const k of ["photo_id", "participant_id", "open_cases", "history", "lat", "lon", "expected_facility", "expected_issue", "expected_grade"]) {
        if (form[k].value.trim()) fd.append(k, form[k].value.trim());
      }
      const run = await api("/analyze", { method: "POST", body: fd });
      col.prepend(renderRun(run));
    }
    toast(`${files.length}장 판독 완료`);
  });
});

// ---------------------------------------------------------------- 전후 비교
let beforeRuns = [];
async function loadBeforeRuns() {
  try {
    beforeRuns = (await api("/runs?kind=analyze")).filter(r => r.image_path && !r.hidden && ((r.response || {}).issues || []).length);
  } catch (e) { return toast(e.message, true); }
  const sel = $("#verify-before");
  const cur = sel.value;
  sel.innerHTML = beforeRuns.length
    ? beforeRuns.map(r => {
      const x = r.response;
      const iss = x.issues.map(i => `${i.type} ${i.severity}`).join(", ");
      return opt(r.id, `#${r.id} ${x.facility ? x.facility.type : ""} · ${iss} · ${x.grade || ""} · ${r.label || ""}`, String(r.id) === cur);
    }).join("")
    : opt("", "문제가 판독된 기록이 없습니다. 판독 시험에서 먼저 사진을 올리세요");
  showBefore();
}
function showBefore() {
  const r = beforeRuns.find(x => String(x.id) === $("#verify-before").value);
  $("#verify-before-preview").innerHTML = r ? overlay(r) : "";
}
$("#verify-before").onchange = showBefore;
$("#exp-result").innerHTML = opt("", "—") + VERIFY_RESULTS.map(v => opt(v, v)).join("");

$("#verify-form").addEventListener("submit", async ev => {
  ev.preventDefault();
  const form = ev.target;
  const fd = new FormData();
  fd.append("after", form.after.files[0]);
  if (form.before_run_id.value) fd.append("before_run_id", form.before_run_id.value);
  fd.append("mode", form.mode.value);
  fd.append("scenario", form.scenario.value);
  fd.append("device_masked", form.device_masked.checked);
  if (form.device_masked.checked) fd.append("device_report", JSON.stringify({ faces: 0, plates: 0, detector: "yunet+plate-t384+effdet0", appVersion: "1.0.0" }));
  if (form.prior_json.value.trim()) fd.append("prior_json", form.prior_json.value.trim());
  if (form.expected_result.value) fd.append("expected_result", form.expected_result.value);
  const col = $("#verify-result");
  await busy($("button[type=submit]", form), async () => {
    const run = await api("/verify", { method: "POST", body: fd });
    if ($(".empty", col)) col.innerHTML = "";
    col.prepend(renderRun(run));
  });
});

// ---------------------------------------------------------------- 시나리오 자동 점검
$("#suite-run").onclick = ev => busy(ev.target, async setLabel => {
  setLabel("점검 중…");
  const out = await api("/suite", { method: "POST" });
  const box = $("#suite-result");
  if (out.error) { box.innerHTML = `<p class="warn-text">${esc(out.error)}</p>`; return; }
  const groups = {};
  out.cases.forEach(c => (groups[c.group] = groups[c.group] || []).push(c));
  box.innerHTML = `
    <div class="stats" style="margin-top:16px">
      <div class="stat"><div class="label">결과</div><div class="value">${out.ok ? badge("통과", "pass") : badge("실패", "fail")}</div><div class="sub">${esc(out.provider)} / ${esc(out.model)} · ${esc(out.elapsed_ms)}ms</div></div>
      <div class="stat"><div class="label">통과</div><div class="value">${out.counts.pass}</div></div>
      <div class="stat"><div class="label">실패</div><div class="value">${out.counts.fail}</div></div>
      <div class="stat"><div class="label">확인 필요</div><div class="value">${out.counts.warn}</div></div>
      <div class="stat"><div class="label">건너뜀</div><div class="value">${out.counts.skip}</div></div>
    </div>
    ${Object.entries(groups).map(([g, cs]) => `<div class="suite-group"><h3>${esc(g)}</h3><div class="table-wrap"><table class="table">
      <tr><th>결과</th><th>항목</th><th>내용</th><th></th></tr>
      ${cs.map(c => `<tr><td>${badge(LEVEL_LABEL[c.level], c.level)}</td><td>${esc(c.title)}</td><td class="muted small">${esc(c.detail)}</td>
        <td>${c.run_id ? `<a href="#" data-open-run="${c.run_id}">#${c.run_id}</a>` : ""}</td></tr>`).join("")}
    </table></div></div>`).join("")}`;
  toast(out.ok ? "자동 점검 통과" : `실패 ${out.counts.fail}건`, !out.ok);
});
$("#suite-result").addEventListener("click", e => { if (e.target.dataset.openRun) { e.preventDefault(); openRun(+e.target.dataset.openRun); } });

// ---------------------------------------------------------------- 기록
async function loadRuns() {
  let runs;
  try { runs = await api("/runs" + ($("#runs-kind").value ? `?kind=${$("#runs-kind").value}` : "")); } catch (e) { return toast(e.message, true); }
  if ($("#runs-hide-auto").checked) runs = runs.filter(r => !(r.label || "").startsWith("[자동]"));
  const t = $("#runs-table");
  if (!runs.length) { t.innerHTML = '<tr><td class="muted">기록이 없습니다.</td></tr>'; return; }
  t.innerHTML = `<tr><th>#</th><th>시각</th><th>종류</th><th>사진·메모</th><th>상태</th><th>결과</th><th>처리 제안</th><th>계약 검사</th><th>정답</th><th class="num">응답</th></tr>` +
    runs.map(r => {
      const x = r.response || {};
      const s = r.summary;
      const res = r.kind === "analyze" ? gradeTag(x.grade) : r.kind === "verify" ? esc(x.result || "—") : r.kind === "narrative" ? esc(x.source) : "";
      let exp = "";
      if (r.expected) {
        const ok = r.kind === "verify" ? ((x.status === "retake" ? "retake" : x.result) === r.expected.result)
          : (!r.expected.grade || x.grade === r.expected.grade) && (!r.expected.facility || (x.facility || {}).type === r.expected.facility);
        exp = ok ? badge("일치", "pass") : badge("불일치", "fail");
      }
      return `<tr class="click${state.selectedRun === r.id ? " sel" : ""}" data-id="${r.id}"><td>${r.id}</td><td class="muted">${esc(fmtTime(r.created_at))}</td><td>${esc(KIND_LABEL[r.kind])}</td>
        <td>${esc(r.label || "")}${r.hidden ? " " + badge("보류", "fail") : ""}${r.snapshot_count > 1 ? " " + badge(`스냅샷 ${r.snapshot_count}`, "info") : ""}</td>
        <td>${x.status ? badge(STATUS_LABEL[x.status] || x.status, x.status) : r.http_status !== 200 ? badge("HTTP " + r.http_status, "fail") : ""}</td>
        <td>${res}</td><td>${x.action ? `<code>${esc(x.action)}</code>` : ""}</td>
        <td>${s.fail ? badge(`위반 ${s.fail}`, "fail") : badge("통과", "pass")} ${s.warn ? badge(`확인 ${s.warn}`, "warn") : ""}</td>
        <td>${exp}</td><td class="num muted">${esc(r.latency_ms)}ms</td></tr>`;
    }).join("");
}
$("#runs-table").addEventListener("click", e => { const tr = e.target.closest("tr[data-id]"); if (tr) openRun(+tr.dataset.id, false); });
$("#runs-kind").onchange = loadRuns;
$("#runs-hide-auto").onchange = loadRuns;
$("#runs-refresh").onclick = loadRuns;
$("#runs-clear").onclick = async () => {
  if (!confirm("검증 콘솔의 기록과 사진 복사본을 모두 지웁니다. AI 서버의 기록은 그대로입니다. 계속할까요?")) return;
  await api("/runs", { method: "DELETE" });
  $("#run-detail").innerHTML = "";
  loadRuns();
  toast("기록을 지웠습니다");
};

async function openRun(id, switchTab = true) {
  try {
    const run = await api(`/runs/${id}`);
    state.selectedRun = id;
    if (switchTab) showTab("runs"); else $$("#runs-table tr[data-id]").forEach(tr => tr.classList.toggle("sel", +tr.dataset.id === id));
    const box = $("#run-detail");
    box.innerHTML = "";
    box.appendChild(renderRun(run, { detail: true }));
    box.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (e) { toast(e.message, true); }
}

// ---------------------------------------------------------------- 알림·가림 점검
async function loadEvents() {
  try {
    const out = await api("/events");
    $("#ev-cursor").textContent = out.cursor;
    const t = $("#ev-table");
    t.innerHTML = out.events.length
      ? `<tr><th>seq</th><th>종류</th><th>photo_id</th><th>내용</th><th>백엔드 처리</th><th>받은 시각</th></tr>` + out.events.map(e => {
        const p = { ...e.payload };
        ["seq", "type", "photo_id"].forEach(k => delete p[k]);
        return `<tr><td>${e.seq}</td><td><code>${esc(e.type)}</code></td><td><code>${esc(e.photo_id)}</code></td><td class="muted small">${esc(JSON.stringify(p))}</td><td>${esc(e.applied)}</td><td class="muted">${esc(fmtTime(e.received_at))}</td></tr>`;
      }).join("")
      : '<tr><td class="muted">받은 알림이 없습니다. "알림 받기·처리"를 누르세요.</td></tr>';
  } catch (e) { toast(e.message, true); }
}
$("#ev-poll").onclick = ev => busy(ev.target, async () => {
  const out = await api("/events/poll", { method: "POST" });
  toast(`알림 ${out.handled.length}건 처리 (순번 ${out.cursor})`);
  loadEvents();
});
$("#ev-reset").onclick = async () => { await api("/events/reset", { method: "POST" }); loadEvents(); toast("다음에 처음부터 다시 받습니다"); };

$("#audit-load").onclick = ev => busy(ev.target, async () => {
  const list = await api(`/audit/masking?n=${$("#audit-n").value || 12}`);
  const grid = $("#audit-grid");
  grid.innerHTML = list.length ? list.map(x => `
    <div class="audit-item" data-pid="${esc(x.photo_id)}">
      <img src="/api/ai-image/${encodeURIComponent(x.photo_id)}" alt="" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'noimg muted small',style:'padding:30px;text-align:center',textContent:'사진 없음 또는 보류(410)'}))">
      <div class="body"><code>${esc(x.photo_id)}</code><br>${esc(x.reason)} · ${esc(x.mode)}<br>
        얼굴 ${esc(x.faces)} · 번호판 ${esc(x.plates)} · server_added ${esc(x.server_added)}${x.leaks_fixed ? ` · AI 보정 ${esc(x.leaks_fixed)}` : ""}
        <div class="actions"><button class="mini" data-audit="1">가림 충분</button><button class="mini danger" data-audit="0">가림 부족</button></div></div>
    </div>`).join("") : '<p class="muted">표본이 없습니다.</p>';
});
$("#audit-grid").addEventListener("click", async e => {
  const b = e.target.closest("[data-audit]");
  if (!b) return;
  const pid = b.closest("[data-pid]").dataset.pid;
  const ok = b.dataset.audit === "1";
  const note = ok ? "" : prompt("부족한 점 (예: 번호판 일부가 보임)", "") ?? null;
  if (note === null) return;
  await busy(b, async () => {
    await api(`/audit/masking/${encodeURIComponent(pid)}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ok, reviewer: "검증 콘솔", note }) });
    toast(ok ? "가림 충분으로 기록" : "가림 부족으로 기록 (사진 보류). 알림 받기로 반영을 확인하세요");
  });
});

// ---------------------------------------------------------------- 보고 문장
const NAR_DEFAULT = [
  { key: "photos", label: "점검 사진", value: 1240, unit: "장" },
  { key: "facilities", label: "점검한 고유 시설", value: 312, unit: "곳" },
  { key: "urgent_alerts", label: "긴급 알림", value: 2, unit: "건" },
  { key: "review_pending", label: "담당자 검토 대기", value: 14, unit: "건" },
  { key: "repair_close_suggested", label: "수리 확인 제안(담당자 확정 전)", value: 5, unit: "건" },
];
function narRows(items) {
  $("#nar-items").innerHTML = `<tr><th>key</th><th>label</th><th>value</th><th>unit</th><th></th></tr>` + items.map(i => `
    <tr><td><input name="key" value="${esc(i.key)}"></td><td><input name="label" value="${esc(i.label)}"></td>
    <td><input name="value" type="number" min="0" step="1" value="${esc(i.value)}"></td><td><input name="unit" value="${esc(i.unit)}" class="narrow"></td>
    <td><button class="mini danger" data-del>삭제</button></td></tr>`).join("");
}
function narItems() {
  return $$("#nar-items tr").slice(1).map(tr => {
    const o = {};
    $$("input", tr).forEach(i => (o[i.name] = i.name === "value" ? Number(i.value) : i.value));
    if (!o.unit) delete o.unit;
    return o;
  });
}
narRows(NAR_DEFAULT);
$("#nar-items").addEventListener("click", e => { if (e.target.dataset.del !== undefined) { e.target.closest("tr").remove(); } });
$("#nar-add").onclick = () => narRows([...narItems(), { key: "item" + Date.now() % 1000, label: "", value: 0, unit: "건" }]);
$("#nar-run").onclick = ev => busy(ev.target, async () => {
  const run = await api("/narrative", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ items: narItems(), audience: $("#nar-audience").value }) });
  const box = $("#nar-result");
  box.innerHTML = "";
  box.appendChild(renderRun(run));
});

// ---------------------------------------------------------------- 개인정보 단독 검사
$("#privacy-form").addEventListener("submit", async ev => {
  ev.preventDefault();
  const form = ev.target;
  const fd = new FormData();
  fd.append("image", form.image.files[0]);
  fd.append("purpose", form.purpose.value);
  if (form.media_id.value.trim()) fd.append("media_id", form.media_id.value.trim());
  fd.append("device_masked", form.device_masked.checked);
  await busy($("button[type=submit]", form), async () => {
    const run = await api("/privacy", { method: "POST", body: fd });
    const box = $("#privacy-result");
    box.innerHTML = "";
    box.appendChild(renderRun(run));
  });
});

// ---------------------------------------------------------------- AI 쪽 집계
$("#agg-run").onclick = ev => busy(ev.target, async () => {
  const q = new URLSearchParams();
  if ($("#agg-start").value) q.set("start", $("#agg-start").value);
  if ($("#agg-end").value) q.set("end", $("#agg-end").value);
  const out = await api(`/ai/${$("#agg-path").value}?${q}`);
  $("#agg-result").textContent = `HTTP ${out.http_status} · ${out.latency_ms}ms\n` + JSON.stringify(out.body, null, 2);
});
{
  const d = new Date(), day = (d.getDay() + 6) % 7;
  const mon = new Date(d - day * 864e5), sun = new Date(+mon + 6 * 864e5);
  $("#agg-start").value = mon.toISOString().slice(0, 10);
  $("#agg-end").value = sun.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- 개요
function stat(label, value, sub = "", hl = false) {
  return `<div class="stat${hl ? " hl" : ""}"><div class="label">${esc(label)}</div><div class="value">${value ?? "—"}</div><div class="sub">${esc(sub)}</div></div>`;
}
const pct = v => (v === null || v === undefined ? "—" : v + "%");

async function loadOverview() {
  let s;
  try { s = await api("/stats"); } catch (e) { return toast(e.message, true); }
  const a = s.analyze, v = s.verify, c = s.contract;
  $("#contract-stats").innerHTML = `<div class="stats">${stat("호출", c.runs)}${stat("계약 통과", c.clean, c.runs ? `${Math.round(100 * c.clean / c.runs)}%` : "", true)}${stat("위반 있음", c.runs - c.clean)}</div>` +
    (c.fail_by_check.length ? `<h3>자주 위반한 항목</h3><ul class="checks">${c.fail_by_check.slice(0, 8).map(([t, n]) => `<li><span>${badge(n, "fail")}</span><div>${esc(t)}</div></li>`).join("")}</ul>` : '<p class="muted small" style="margin-top:12px">위반 기록이 없습니다.</p>');
  const st = a.status || {};
  $("#analyze-stats").innerHTML = `<div class="stats">
    ${stat("판독 호출", a.total, `라벨 붙은 사진 ${a.labeled}`)}
    ${stat("긴급 재현율", pct(a.urgent_recall), `실제 긴급 ${a.urgent_n}장 중`, true)}
    ${stat("등급 일치율", pct(a.grade_accuracy), `±1 일치 ${pct(a.grade_within1)} · ${a.grade_n}장`)}
    ${stat("시설 분류 정확도", pct(a.facility_accuracy), `${a.facility_n}장`)}
    ${stat("문제 유형 정확도", pct(a.issue_accuracy), `${a.issue_n}장`)}
    ${stat("담당자 확인 비율", pct(a.needs_review_rate), `needs_review ${st.needs_review || 0}`)}
    ${stat("재촬영 비율", pct(a.retake_rate), `retake ${st.retake || 0} · error ${st.error || 0}`)}
    ${stat("응답 시간", a.latency ? a.latency.mean + "ms" : "—", a.latency ? `p95 ${a.latency.p95}ms · 최대 ${a.latency.max}ms` : "")}
  </div>` + (a.urgent_missed.length ? `<h3>놓친 긴급</h3><ul class="checks">${a.urgent_missed.map(m => `<li><span>${badge("놓침", "fail")}</span><div><a href="#" data-open-run="${m.run_id}">#${m.run_id}</a> ${esc(m.label)} → AI ${esc(m.grade || "등급 없음")} (${esc(m.status)})</div></li>`).join("")}</ul>` : "");
  const head = [...GRADES, "없음"];
  $("#confusion").innerHTML = a.grade_n ? `<table class="table confusion"><tr><th>정답 \\ AI</th>${head.map(h => `<th class="num">${h}</th>`).join("")}</tr>` +
    GRADES.map(e => `<tr><th>${gradeTag(e)}</th>${head.map(p => {
      const n = a.confusion[e][p];
      const cls = p === e ? "diag" : (e === "긴급" && p !== "긴급" && n) ? "miss" : "";
      return `<td class="num ${cls}">${n || '<span class="muted">·</span>'}</td>`;
    }).join("")}</tr>`).join("") + "</table>" : '<p class="muted">등급 정답 라벨을 붙인 판독이 없습니다.</p>';
  $("#verify-stats").innerHTML = `<div class="stats">
    ${stat("비교 호출", v.total, `라벨 ${v.labeled}`)}
    ${stat("결과 정확도", pct(v.result_accuracy), "", true)}
    ${stat("잘못된 재요청", v.wrong_rerequest, "고쳐졌거나 다른 장소인데 re_request")}
    ${stat("안 고쳐졌는데 종료", v.wrong_close, "not_fixed인데 close_case")}
  </div>`;
}
$("#tab-overview").addEventListener("click", e => { if (e.target.dataset.openRun) { e.preventDefault(); openRun(+e.target.dataset.openRun); } });

// ---------------------------------------------------------------- 알림 자동 받기
// 재판독(result_updated)·다시 가림·보류 알림을 15초마다 받아 반영합니다. 업무 백엔드의 알림 수신 작업에 해당합니다.
async function autoPoll() {
  try {
    const out = await api("/events/poll", { method: "POST" });
    const updated = out.handled.filter(h => /스냅샷 추가|교체|차단|다시 표시/.test(h.applied));
    if (!updated.length) return;
    toast(`AI 알림 반영: ${updated.map(h => h.applied).join(" / ")}`);
    const active = ($(".tab.active") || {}).id;
    if (active === "tab-runs") { loadRuns(); if (state.selectedRun) openRun(state.selectedRun, false); }
    if (active === "tab-events") loadEvents();
    if (active === "tab-overview") loadOverview();
    // 판독·비교 탭에 떠 있는 결과 카드도 새 결과로 바꿈
    for (const card of $$("#analyze-result > .card, #verify-result > .card")) {
      const id = +((card.querySelector(".run-head .title") || {}).textContent || "").match(/#(\d+)/)?.[1];
      if (id && updated.some(h => h.applied.includes(`#${id} `))) card.replaceWith(renderRun(await api(`/runs/${id}`)));
    }
  } catch { /* AI 서버가 잠시 꺼져 있어도 화면은 계속 씀 */ }
}
setInterval(autoPoll, 15000);

// ---------------------------------------------------------------- 시작
$("#analyze-scenario").innerHTML = ANALYZE_SCENARIOS.map(s => opt(s, s || "(지정 안 함 · 아무 시나리오나 골라짐)")).join("");
$("#verify-scenario").innerHTML = VERIFY_SCENARIOS.map(s => opt(s, s || "(지정 안 함)")).join("");
loadHealth();
loadTaxonomy().then(() => {
  // 주소 뒤에 #analyze, #runs, #run-12 처럼 붙이면 그 화면으로 바로 엽니다
  const h = location.hash.slice(1);
  if (/^run-\d+$/.test(h)) openRun(+h.slice(4));
  else if (h && $("#tab-" + h)) showTab(h);
  else loadOverview();
});
