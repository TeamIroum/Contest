// 시험용 시니어 앱 (React Native + Expo, iOS·Android).
// 장소 안내 → 앱 안 카메라 촬영 → 기기에서 가림(보이지 않는 WebView 엔진) → 대기함 저장 → 업무 API 전송 → 진행 안내.
// 앱은 AI 모듈을 부르지 않고 업무 API(/api/v1)만 부른다. 판독 결과는 어르신 화면에 보이지 않는다.
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator, Alert, AppState, Image, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View,
} from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { StatusBar } from "expo-status-bar";
import { CameraView, useCameraPermissions } from "expo-camera";
import * as ImagePicker from "expo-image-picker";
import { ImageManipulator, SaveFormat } from "expo-image-manipulator";
import * as Crypto from "expo-crypto";
import NetInfo from "@react-native-community/netinfo";
import MaskEngine from "./src/MaskEngine";
import * as store from "./src/storage";

const APP_VERSION = "rn-test-0.1.0";
const DETECT_MAX_SIDE = 2400;   // 가리기 엔진에 넘기는 최대 해상도 (안내서 3장)
const MODEL_LABELS = { face: "얼굴", plate: "번호판", vehicle: "차량" };

export default function App() {
  return (
    <SafeAreaProvider>
      <Main />
    </SafeAreaProvider>
  );
}

function Main() {
  const [cfg, setCfgState] = useState(store.loadConfig);
  const [serverDraft, setServerDraft] = useState(cfg.server);
  const [engineState, setEngineState] = useState({ ready: false, status: {} });
  const [screen, setScreen] = useState("home");
  const [patrol, setPatrol] = useState(null);
  const [stopIdx, setStopIdx] = useState(0);
  const [pending, setPending] = useState(null);       // 확인 화면의 가린 사진 (원본 아님)
  const [result, setResult] = useState(null);
  const [workingText, setWorkingText] = useState("");
  const [queue, setQueue] = useState([]);
  const [online, setOnline] = useState(true);
  const engine = useRef(null);
  const camera = useRef(null);
  const [camReady, setCamReady] = useState(false);
  const [permission, requestPermission] = useCameraPermissions();

  const setCfg = (patch) => setCfgState((c) => { const n = { ...c, ...patch }; store.saveConfig(n); return n; });
  const refreshQueue = useCallback(() => setQueue(store.all()), []);

  const activeSubmission = useRef(null);   // 지금 화면에서 보내는 사진 (자동 재전송 알림에서 뺌)
  const [notices, setNotices] = useState([]);   // 다시 찍어야 할 곳 알림

  const flush = useCallback(async () => {
    if (!cfg.server) return;
    const results = await store.flush(cfg).catch(() => []);
    refreshQueue();
    // 전송 대기로 넘어갔던 사진이 나중에 '다시 찍기'로 판정되면 어르신에게 알린다
    const retakes = (results || []).filter((r) => r.done && r.body?.nextAction === "RETAKE"
      && r.item.clientSubmissionId !== activeSubmission.current)
      .map((r) => ({ stopId: r.item.stopId, stopName: r.item.stopName, message: r.body.message }));
    if (retakes.length) setNotices((n) => [...n, ...retakes]);
  }, [cfg, refreshQueue]);

  // 앱을 다시 열 때, 연결이 돌아올 때 대기 사진을 보낸다 (백그라운드 전송에 기대지 않음)
  useEffect(() => {
    refreshQueue();
    flush();
    const sub = AppState.addEventListener("change", (s) => { if (s === "active") flush(); });
    const unsub = NetInfo.addEventListener((s) => {
      const on = Boolean(s.isConnected);
      setOnline(on);
      if (on) flush();
    });
    return () => { sub.remove(); unsub(); };
  }, [flush, refreshQueue]);

  useEffect(() => {
    if (!notices.length || (screen !== "home" && screen !== "stop")) return;
    const [n, ...rest] = notices;
    setNotices(rest);
    showResult("다시 찍어야 할 곳이 있어요", `${n.stopName}: ${n.message || "한 번 더 찍어 주세요."}`, "그 장소로 가기",
      () => goToStop(n.stopId));
  }, [notices, screen]);

  async function goToStop(stopId) {
    try {
      const p = await api("GET", `/patrols/${patrol?.id || (await loadPatrol()).p.id}`);
      setPatrol(p);
      const i = p.stops.findIndex((st) => st.id === stopId);
      if (i >= 0) setStopIdx(i);
      setScreen("stop");
    } catch (e) {
      Alert.alert("순회 정보를 받지 못했어요", e.message);
    }
  }

  // ------------------------------------------------------------ 서버 호출
  async function api(method, path) {
    const r = await fetch(`${store.apiBase(cfg)}${path}`, { method });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(`${r.status} ${body.code || ""} ${body.detail || ""}`.trim());
    return body;
  }

  // 서버까지 가지 못한 실패를 서버에서 볼 수 있게 알린다. 사진 내용은 넣지 않는다
  function reportClientError(stage, e, detail = null) {
    fetch(`${store.apiBase(cfg)}/test-client-logs`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ stage, name: e?.name || null, message: String(e?.message || e),
        userAgent: `${APP_VERSION} (React Native)`, detail: { ...detail, appVersion: APP_VERSION } }),
    }).catch(() => {});
  }

  async function loadPatrol() {
    const list = await api("GET", "/me/patrols");
    if (!list.items?.length) throw new Error("오늘 배정된 순회가 없어요.");
    const p = await api("GET", `/patrols/${list.items[0].id}`);
    const first = p.stops.findIndex((s) => s.captureSlots.some((c) => !isResolved(c)));
    const idx = first < 0 ? 0 : first;
    setPatrol(p);
    setStopIdx(idx);
    return { p, idx };
  }

  async function refreshPatrol() {
    if (!patrol) return;
    try { setPatrol(await api("GET", `/patrols/${patrol.id}`)); } catch { /* 연결이 없으면 지금 정보로 계속 */ }
  }

  // ------------------------------------------------------------ 화면 전환
  function showResult(text, sub, actionLabel, action, debug = null) {
    setResult({ text, sub, actionLabel, action, debug });
    setScreen("result");
  }

  function goNext(nextStopId) {
    if (!patrol) return setScreen("home");
    const i = nextStopId ? patrol.stops.findIndex((s) => s.id === nextStopId) : stopIdx + 1;
    if (i < 0 || i >= patrol.stops.length) {
      return showResult("모두 찍었어요", "센터로 돌아가 주세요.", "처음으로", () => setScreen("home"));
    }
    setStopIdx(i);
    setScreen("stop");
  }

  async function start() {
    try {
      await loadPatrol();
      setScreen("stop");
    } catch (e) {
      Alert.alert("순회 정보를 받지 못했어요", e.message);
    }
  }

  // ------------------------------------------------------------ 촬영 (앱 안 카메라만. 기본 카메라 앱·갤러리 저장 없음)
  async function openCamera() {
    if (!permission?.granted) {
      const p = await requestPermission();
      if (!p.granted) {
        reportClientError("camera", new Error("permission denied"));
        return showResult("카메라 사용을 허락하지 않았어요", "담당자에게 연락해 주세요.", "장소 안내로", () => setScreen("stop"));
      }
    }
    setCamReady(false);
    setScreen("camera");
  }

  async function shoot() {
    if (!camReady || !camera.current) return;
    const capturedAt = new Date().toISOString();
    let photo;
    try {
      // 촬영음은 카메라가 낸다 (공개 장소 촬영 사실 알림). 사진은 앱 캐시 임시 파일로만 생기고 곧 지운다
      photo = await camera.current.takePictureAsync({ quality: 1, shutterSound: true });
    } catch (e) {
      reportClientError("camera", e);
      return showResult("한 번 더 찍어 주세요", "사진을 찍지 못했어요.", "다시 찍기", openCamera);
    }
    processPhoto(photo, capturedAt, "camera", { p: patrol, idx: stopIdx });
  }

  // 시험용: 저장된 사진으로 앱 마스킹 확인. 실제 시니어 앱에는 두지 않는 기능 (안내서 2장)
  async function pickPhoto() {
    if (!engineState.ready) return Alert.alert("가림 모델이 아직 준비되지 않았어요.");
    let at = patrol ? { p: patrol, idx: stopIdx } : null;
    if (!at) {
      try { at = await loadPatrol(); } catch (e) { return Alert.alert("순회 정보를 받지 못했어요", e.message); }
    }
    const r = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ["images"], quality: 1, exif: false });
    if (r.canceled || !r.assets?.length) return;
    processPhoto(r.assets[0], new Date().toISOString(), "file", at);
  }

  // 원본 → (긴 변 2,400px 이하로) → 엔진에서 가림 → 확인 화면 또는 전송. 원본 임시 파일은 끝나면 지운다
  async function processPhoto(photo, capturedAt, source, at) {
    const fromFile = source === "file";
    const again = fromFile ? pickPhoto : openCamera;
    setWorkingText("얼굴과 번호판을 가리고 있어요");
    setScreen("working");
    let resized = null;
    try {
      const ctx = ImageManipulator.manipulate(photo.uri);
      const long = Math.max(photo.width || 0, photo.height || 0);
      if (long > DETECT_MAX_SIDE) ctx.resize(photo.width >= photo.height ? { width: DETECT_MAX_SIDE } : { height: DETECT_MAX_SIDE });
      const ref = await ctx.renderAsync();
      resized = await ref.saveAsync({ format: SaveFormat.JPEG, compress: 0.95, base64: true });
      ref.release?.();
      const masked = await engine.current.mask(resized.base64, fromFile ? `${APP_VERSION}+file` : APP_VERSION);
      const stop = at.p.stops[at.idx];
      const slot = currentSlot(stop);
      const item = {
        clientSubmissionId: Crypto.randomUUID(), attemptId: Crypto.randomUUID(),
        patrolId: at.p.id, stopId: stop.id, stopName: stop.name, captureSlotId: slot.id,
        capturedAt, maskingVersion: masked.maskingVersion, deviceMaskReport: masked.report,
      };
      const view = { item, b64: masked.b64, boxes: masked.boxes, size: masked.size, timings: masked.timings,
        blur: masked.blur, source, bytes: Math.round((masked.b64.length * 3) / 4) };
      if (cfg.preview || fromFile) {
        setPending(view);
        setScreen("preview");
      } else {
        submit(view);
      }
    } catch (e) {
      reportClientError("mask", e, { source });
      showResult("한 번 더 찍어 주세요", "사진을 정리하지 못했어요. 계속 안 되면 담당자에게 연락해 주세요.",
        fromFile ? "다른 사진 고르기" : "다시 찍기", again, { error: `${e.name}: ${e.message}` });
    } finally {
      store.deleteTemp(photo.uri);       // 카메라 임시 파일 / 사진 고르기가 만든 캐시 사본
      store.deleteTemp(resized?.uri);    // 크기 조정 사본
    }
  }

  // ------------------------------------------------------------ 전송
  async function submit(view) {
    setPending(null);
    setWorkingText("사진을 보내고 있어요");
    setScreen("working");
    let item;
    try {
      item = store.putNew(view.item, view.b64);   // 먼저 기기에 저장한 뒤 보낸다
    } catch (e) {
      reportClientError("outbox-save", e, { bytes: view.bytes });
      return showResult("저장하지 못했어요", "휴대폰 저장 공간을 확인하고 한 번 더 찍어 주세요.", "다시 찍기", openCamera,
        { error: `${e.name}: ${e.message}` });
    }
    activeSubmission.current = item.clientSubmissionId;
    const r = await store.send(item, cfg);   // 자동 재전송과 겹치면 같은 전송 결과를 함께 받음
    activeSubmission.current = null;
    refreshQueue();
    handleResponse(r, view);
  }

  function handleResponse(r, view) {
    const b = r.body || {};
    const debug = { http: r.http ?? "연결 안 됨", error: r.error, ...b, maskTimings: view?.timings };
    const consoleUrl = b.testConsoleRunId ? `${cfg.server.replace(/\/+$/, "")}/#run-${b.testConsoleRunId}` : null;
    if (!r.done) {
      if (r.offline || r.http >= 500 || b.code === "SUBMISSION_PROCESSING") {
        return showResult("전송 대기", "연결되면 자동으로 보낼게요. 다음 장소로 가셔도 돼요.", "다음 장소로", () => goNext(null), debug);
      }
      return showResult("보내지 못했어요", `담당자에게 알려 주세요. (${b.code || r.http})`, "장소 안내로", () => setScreen("stop"), debug);
    }
    if (b.code === "SLOT_RESOLVED") {
      return showResult("이미 끝난 곳이에요", "다음 장소로 가 주세요.", "다음 장소로", () => goNext(null), debug);
    }
    refreshPatrol();
    debug.console = consoleUrl;
    switch (b.nextAction) {
      case "RETAKE":
        return showResult("한 번 더 찍어 주세요", b.message || "", view?.source === "file" ? "다른 사진 고르기" : "다시 찍기",
          view?.source === "file" ? pickPhoto : openCamera, debug);
      case "RETURN_TO_CENTER":
        return showResult("모두 찍었어요", b.message || "센터로 돌아가 주세요.", "처음으로", () => setScreen("home"), debug);
      default:
        return showResult("잘 받았어요", b.message || "", "다음 장소로", () => goNext(b.nextStopId), debug);
    }
  }

  // ------------------------------------------------------------ 화면
  const stop = patrol?.stops[stopIdx];
  const slot = stop && currentSlot(stop);
  let body;

  if (screen === "home") {
    body = (
      <ScrollView contentContainerStyle={s.page}>
        <Text style={s.h1}>오늘 순회</Text>
        <View style={s.card}>
          <Text style={s.h2}>사진 가림 준비</Text>
          {Object.entries(MODEL_LABELS).map(([k, label]) => {
            const st = engineState.status?.[k] || "대기";
            return (
              <View key={k} style={s.statusRow}>
                <Text style={s.text}>{label}</Text>
                <Text style={[s.text, st === "준비" && s.ok, st === "실패" && s.bad]}>{st}</Text>
              </View>
            );
          })}
          <Text style={s.muted}>
            {!cfg.server ? "아래 시험 설정에서 서버 주소를 입력해 주세요."
              : engineState.ready ? "준비 완료" : engineState.error ? `준비가 안 됐어요: ${engineState.error}` : "처음에는 모델을 내려받느라 시간이 걸려요 (약 15MB)."}
          </Text>
          {!engineState.ready && engineState.error ? (
            <Btn kind="secondary" label="다시 준비하기" onPress={() => engine.current?.reload()} />
          ) : null}
        </View>
        <Btn kind="primary" big label="순회 시작" disabled={!engineState.ready} onPress={start} />

        {queue.length ? (
          <View style={s.card}>
            <Text style={s.h2}>전송 대기 {queue.length}건</Text>
            <Text style={s.muted}>아직 서버가 받지 않은 사진입니다. 연결되면 자동으로 보냅니다.</Text>
            {queue.map((i) => (
              <View key={i.clientSubmissionId} style={s.queueRow}>
                <Text style={[s.small, { flex: 1 }]}>
                  · {i.stopName} {new Date(i.capturedAt).toLocaleTimeString()} {i.lastError ? `(${i.lastError}${i.permanent ? ", 자동 재전송 안 함" : ""})` : ""}
                </Text>
                {i.permanent ? (
                  <Pressable style={s.smallBtn} onPress={() => Alert.alert("이 사진을 지울까요?", "서버로 보내지 않습니다.", [
                    { text: "취소", style: "cancel" },
                    { text: "지우기", style: "destructive", onPress: () => { store.remove(i.clientSubmissionId); refreshQueue(); } },
                  ])}>
                    <Text style={s.smallBtnText}>지우기</Text>
                  </Pressable>
                ) : null}
              </View>
            ))}
            <Btn kind="secondary" label="지금 보내기" onPress={flush} />
          </View>
        ) : null}

        <View style={s.card}>
          <Text style={s.h2}>시험 설정 (담당자용)</Text>
          <Text style={s.label}>서버 주소</Text>
          <TextInput style={s.input} value={serverDraft} onChangeText={setServerDraft} autoCapitalize="none"
            autoCorrect={false} keyboardType="url"
            onEndEditing={() => { const v = serverDraft.trim() || store.DEFAULT_SERVER; setServerDraft(v); setCfg({ server: v }); }} />
          <Toggle label="보내기 전에 가린 사진 확인" value={cfg.preview} onChange={(v) => setCfg({ preview: v })} />
          <Toggle label="가린 위치 표시 (확인 화면에만)" value={cfg.boxes} onChange={(v) => setCfg({ boxes: v })} />
          <View style={s.row}>
            <Btn kind="secondary" small label="구도 상태 초기화" onPress={async () => {
              try { const l = await api("GET", "/me/patrols"); await api("POST", `/patrols/${l.items[0].id}/test-reset`); Alert.alert("초기화했어요"); }
              catch (e) { Alert.alert("초기화 못 함", e.message); }
            }} />
            <Btn kind="secondary" small label="보낸 기록 지우기" onPress={() => { store.clearResults(); refreshQueue(); }} />
          </View>
          <Text style={s.h3}>보낸 기록</Text>
          {store.results().slice(0, 15).map((d) => (
            <Text key={d.clientSubmissionId} style={s.small}>
              · {d.stopName} · {d.body.receiptStatus}/{d.body.captureStatus}{d.body.testConsoleRunId ? ` · 콘솔 기록 ${d.body.testConsoleRunId}` : ""}
            </Text>
          ))}
          <Text style={s.muted}>판독 결과는 어르신 화면에 나오지 않습니다. 검증 콘솔 기록에서 확인하세요.</Text>
        </View>
      </ScrollView>
    );
  } else if (screen === "stop" && stop) {
    const left = slot.retakesRemaining ?? 2;
    body = (
      <ScrollView contentContainerStyle={s.page}>
        <Text style={s.muted}>{stopIdx + 1} / {patrol.stops.length} 번째 장소</Text>
        <Text style={s.h1}>{stop.name}</Text>
        <Text style={s.instruction}>{stop.instruction}</Text>
        <Text style={[s.muted, { marginBottom: 24 }]}>
          찍을 모습: {slot.label}
          {isResolved(slot) ? " · 이미 끝났어요" : slot.captureStatus === "RETAKE_REQUIRED" ? ` · 다시 찍기 ${left}번 남음` : ""}
        </Text>
        <Btn kind="primary" big label="사진 찍기" disabled={!engineState.ready} onPress={openCamera} />
        <Btn kind="outline" big label="저장된 사진 고르기" disabled={!engineState.ready} onPress={pickPhoto} />
        <View style={s.row}>
          <Btn kind="secondary" label="다음 장소로" onPress={() => goNext(null)} />
          <Btn kind="secondary" label="처음으로" onPress={() => setScreen("home")} />
        </View>
      </ScrollView>
    );
  } else if (screen === "working") {
    body = (
      <View style={s.center}>
        <ActivityIndicator size="large" color={C.accent} />
        <Text style={s.bigText}>{workingText}</Text>
      </View>
    );
  } else if (screen === "preview" && pending) {
    const [w, h] = pending.size.sent;
    const r = pending.item.deviceMaskReport, t = pending.timings;
    body = (
      <ScrollView contentContainerStyle={s.page}>
        <Text style={s.h1}>가린 사진</Text>
        <View style={[s.photo, { aspectRatio: w / h }]}>
          <Image source={{ uri: `data:image/jpeg;base64,${pending.b64}` }} style={StyleSheet.absoluteFill} resizeMode="stretch" />
          {cfg.boxes && pending.boxes.map(({ kind, box: [x0, y0, x1, y1] }, i) => (
            <View key={i} style={[s.bx, { borderColor: BOX_COLORS[kind], left: `${x0 * 100}%`, top: `${y0 * 100}%`,
              width: `${(x1 - x0) * 100}%`, height: `${(y1 - y0) * 100}%` }]} />
          ))}
        </View>
        <Text style={s.mono}>
          얼굴 {r.faces} · 번호판 {r.plates} · 차량 {r.vehicles}  (노랑 얼굴, 하늘 번호판, 분홍 차량){"\n"}
          원본 {pending.size.captured.join("×")} → 전송 {w}×{h}, {Math.round(pending.bytes / 1024)}KB{pending.blur ? "" : ", 흐림 미지원(모자이크 4칸)"}{"\n"}
          처리 {t.total}ms = 얼굴 {t.face} + 번호판 {t.plate} + 차량 {t.vehicle} + 가림 {t.mask} + JPEG {t.encode}{"\n"}
          보낼 곳: {pending.item.stopName}{pending.source === "file" ? " (저장된 사진 시험)" : ""}
        </Text>
        <View style={s.row}>
          <Btn kind="primary" label="보내기" onPress={() => submit(pending)} />
          <Btn kind="secondary" label={pending.source === "file" ? "다른 사진 고르기" : "다시 찍기"}
            onPress={() => { const f = pending.source === "file"; setPending(null); f ? pickPhoto() : openCamera(); }} />
        </View>
      </ScrollView>
    );
  } else if (screen === "result" && result) {
    body = (
      <ScrollView contentContainerStyle={[s.page, { paddingTop: 48 }]}>
        <Text style={[s.bigText, { textAlign: "center" }]}>{result.text}</Text>
        <Text style={[s.muted, { textAlign: "center", marginBottom: 24 }]}>{result.sub}</Text>
        <Btn kind="primary" big label={result.actionLabel} onPress={result.action} />
        <Btn kind="secondary" label="처음으로" onPress={() => setScreen("home")} />
        {result.debug ? (
          <View style={[s.card, { marginTop: 16 }]}>
            <Text style={s.h3}>시험 정보</Text>
            <Text style={s.mono} selectable>{JSON.stringify(result.debug, null, 2)}</Text>
          </View>
        ) : null}
      </ScrollView>
    );
  } else {
    body = <View style={s.center}><Btn kind="primary" label="처음으로" onPress={() => setScreen("home")} /></View>;
  }

  // 마스킹 엔진(WebView)은 화면이 바뀌어도 계속 같은 자리에 둔다. 다시 만들어지면 모델을 다시 불러와야 함
  return (
    <View style={{ flex: 1 }}>
      {screen === "camera" ? (
        <View style={s.cameraWrap}>
          <StatusBar style="light" />
          <CameraView ref={camera} style={StyleSheet.absoluteFill} facing="back" onCameraReady={() => setCamReady(true)} />
          <SafeAreaView edges={["bottom"]} style={s.cameraBar}>
            <Pressable onPress={() => setScreen("stop")} style={s.ghost}><Text style={s.ghostText}>취소</Text></Pressable>
            <Pressable onPress={shoot} disabled={!camReady} style={({ pressed }) => [s.shutter, pressed && { backgroundColor: "#fff" }]}
              accessibilityLabel="찍기" />
            <View style={{ width: 72 }} />
          </SafeAreaView>
        </View>
      ) : (
        <SafeAreaView style={s.root} edges={["top", "bottom"]}>
          <StatusBar style="dark" />
          <View style={s.bar}>
            <Text style={s.barTitle}>점검 사진 시험</Text>
            <Text style={[s.pill, online ? s.pillOk : s.pillWarn]}>{online ? "연결됨" : "연결 안 됨"}</Text>
            {queue.length ? <Text style={[s.pill, s.pillWarn]}>전송 대기 {queue.length}</Text> : null}
          </View>
          {body}
        </SafeAreaView>
      )}
      {cfg.server ? <MaskEngine key="mask-engine" ref={engine} server={cfg.server} onStatus={setEngineState} /> : null}
    </View>
  );
}

const isResolved = (slot) => ["ACCEPTED", "REVIEW_REQUESTED"].includes(slot.captureStatus);
const currentSlot = (stop) => stop.captureSlots.find((c) => !isResolved(c)) || stop.captureSlots[0];

function Btn({ label, onPress, kind = "secondary", big, small, disabled }) {
  return (
    <Pressable onPress={onPress} disabled={disabled}
      style={({ pressed }) => [s.btn, s[`btn_${kind}`], big && s.btnBig, small && s.btnSmall,
        disabled && { opacity: 0.45 }, pressed && { opacity: 0.75 }]}>
      <Text style={[s.btnText, s[`btnText_${kind}`], big && { fontSize: 24 }, small && { fontSize: 15 }]}>{label}</Text>
    </Pressable>
  );
}

function Toggle({ label, value, onChange }) {
  return (
    <View style={s.toggle}>
      <Text style={[s.text, { flex: 1, fontSize: 16 }]}>{label}</Text>
      <Switch value={value} onValueChange={onChange} trackColor={{ true: C.accent }} />
    </View>
  );
}

const C = { bg: "#f6f5f1", card: "#ffffff", ink: "#1d2320", muted: "#5d6762", line: "#d9dcd6", accent: "#1f5f4a",
  soft: "#e6f1ec", warn: "#9a5b00", warnBg: "#fff1d6", bad: "#a1271b" };
const BOX_COLORS = { face: "#ffd400", plate: "#00e0ff", vehicle: "#ff6ad5" };

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  bar: { flexDirection: "row", alignItems: "center", gap: 8, paddingHorizontal: 16, paddingVertical: 10,
    backgroundColor: C.card, borderBottomWidth: 1, borderBottomColor: C.line },
  barTitle: { flex: 1, fontSize: 18, fontWeight: "700", color: C.ink },
  pill: { fontSize: 13, paddingHorizontal: 10, paddingVertical: 2, borderRadius: 99, borderWidth: 1, overflow: "hidden" },
  pillOk: { color: C.accent, borderColor: C.accent },
  pillWarn: { color: C.warn, backgroundColor: C.warnBg, borderColor: "transparent" },
  page: { padding: 16, paddingBottom: 40 },
  h1: { fontSize: 28, fontWeight: "700", color: C.ink, marginVertical: 8 },
  h2: { fontSize: 20, fontWeight: "700", color: C.ink, marginBottom: 8 },
  h3: { fontSize: 17, fontWeight: "700", color: C.ink, marginTop: 12, marginBottom: 4 },
  text: { fontSize: 19, color: C.ink },
  muted: { fontSize: 17, color: C.muted, marginVertical: 6 },
  small: { fontSize: 14, color: C.ink, marginVertical: 2 },
  mono: { fontSize: 13, color: C.ink, fontFamily: "Menlo", marginVertical: 8 },
  instruction: { fontSize: 22, color: C.ink, marginBottom: 8 },
  bigText: { fontSize: 26, fontWeight: "700", color: C.ink, marginVertical: 16 },
  ok: { color: C.accent, fontWeight: "700" },
  bad: { color: C.bad, fontWeight: "700" },
  card: { backgroundColor: C.card, borderWidth: 1, borderColor: C.line, borderRadius: 14, padding: 16, marginBottom: 16 },
  statusRow: { flexDirection: "row", justifyContent: "space-between", paddingVertical: 6, borderBottomWidth: 1,
    borderBottomColor: C.line, borderStyle: "dashed" },
  row: { flexDirection: "row", gap: 10, marginVertical: 8 },
  center: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24 },
  label: { fontSize: 15, color: C.muted, marginTop: 4 },
  input: { borderWidth: 1, borderColor: C.line, borderRadius: 10, padding: 10, fontSize: 14, color: C.ink,
    backgroundColor: C.bg, marginVertical: 6 },
  toggle: { flexDirection: "row", alignItems: "center", marginVertical: 6 },
  btn: { flex: 1, minHeight: 56, borderRadius: 14, borderWidth: 1, borderColor: C.line, alignItems: "center",
    justifyContent: "center", paddingHorizontal: 14, marginBottom: 12 },
  btnBig: { flex: 0, minHeight: 76, marginBottom: 16 },
  btnSmall: { minHeight: 44 },
  btn_primary: { backgroundColor: C.accent, borderColor: C.accent },
  btn_outline: { backgroundColor: C.soft, borderColor: C.accent, borderWidth: 2 },
  btn_secondary: { backgroundColor: "transparent" },
  btnText: { fontSize: 19, color: C.ink, textAlign: "center" },
  btnText_primary: { color: "#fff", fontWeight: "700" },
  btnText_outline: { color: C.accent, fontWeight: "700" },
  photo: { width: "100%", borderRadius: 12, overflow: "hidden", backgroundColor: "#000" },
  bx: { position: "absolute", borderWidth: 2, borderStyle: "dashed" },
  cameraWrap: { flex: 1, backgroundColor: "#000" },
  cameraBar: { position: "absolute", left: 0, right: 0, bottom: 0, flexDirection: "row", alignItems: "center",
    justifyContent: "space-between", paddingHorizontal: 24, paddingTop: 16, paddingBottom: 24, backgroundColor: "rgba(0,0,0,0.45)" },
  shutter: { width: 84, height: 84, borderRadius: 42, borderWidth: 6, borderColor: "#fff", backgroundColor: "rgba(255,255,255,0.25)" },
  ghost: { width: 72, paddingVertical: 10, borderRadius: 12, borderWidth: 1, borderColor: "rgba(255,255,255,0.5)", alignItems: "center" },
  ghostText: { color: "#fff", fontSize: 17 },
  queueRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  smallBtn: { borderWidth: 1, borderColor: C.line, borderRadius: 10, paddingHorizontal: 10, paddingVertical: 6 },
  smallBtnText: { fontSize: 14, color: C.bad },
});
