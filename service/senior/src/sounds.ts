// 어르신 앱 효과음과 진동.
// 파일 없이 브라우저(Web Audio)에서 짧은 소리를 합성한다. 오프라인에서도 동작한다.
// - 촬영음("찰칵")은 늘 재생한다 (공공장소 촬영 사실을 알리는 장치라 끌 수 없음)
// - 나머지 효과음은 [소리] 버튼으로 끌 수 있다
// - 진동은 지원하는 휴대폰에서만 동작한다

type Ctx = AudioContext
let ctx: Ctx | null = null
let enabled = true

/** 효과음 켜기/끄기 (촬영음은 제외) */
export function setSoundEnabled(on: boolean) {
  enabled = on
}

function audio(): Ctx | null {
  try {
    if (!ctx) {
      const C = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
      ctx = new C()
    }
    if (ctx.state === 'suspended') void ctx.resume()
    return ctx
  } catch {
    return null
  }
}

/** 브라우저는 사용자가 한 번 누른 뒤에야 소리를 허용하므로, 첫 터치 때 소리 장치를 깨워 둔다 */
export function unlockAudio() {
  const once = () => {
    audio()
    primeShutter()
    window.removeEventListener('pointerdown', once)
  }
  window.addEventListener('pointerdown', once)
}

/** 중간 크기 기준 음량. 갑자기 크게 나지 않도록 부드럽게 올렸다가 내린다 */
const VOLUME = 0.22

function tone(freq: number, start: number, dur: number, type: OscillatorType = 'sine', vol = VOLUME) {
  const c = audio()
  if (!c) return
  const t0 = c.currentTime + start
  const o = c.createOscillator()
  const g = c.createGain()
  o.type = type
  o.frequency.setValueAtTime(freq, t0)
  g.gain.setValueAtTime(0.0001, t0)
  g.gain.exponentialRampToValueAtTime(vol, t0 + 0.015)
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  o.connect(g).connect(c.destination)
  o.start(t0)
  o.stop(t0 + dur + 0.02)
}

function vibrate(pattern: number | number[]) {
  try {
    navigator.vibrate?.(pattern)
  } catch {
    /* 진동을 지원하지 않는 기기 */
  }
}

/**
 * 사진 찍을 때: 휴대폰 카메라에서 흔히 듣는 "찰칵" (항상 재생, 소리 끄기와 상관없음 — 공공장소 촬영 사실 알림).
 * Web Audio는 아이폰 무음 스위치를 따라 꺼지므로, 소리를 WAV로 만들어 <audio>로 재생한다 (미디어 재생으로 취급돼 들림).
 */
export function playShutter() {
  vibrate(30)
  try {
    const a = shutterAudio()
    a.currentTime = 0
    void a.play().catch(() => {})
  } catch {
    /* 소리 없이 진행 */
  }
}

let shutterEl: HTMLAudioElement | null = null
function shutterAudio() {
  if (!shutterEl) {
    shutterEl = new Audio(URL.createObjectURL(shutterWav()))
    shutterEl.preload = 'auto'
  }
  return shutterEl
}
/** 첫 터치 때 촬영음을 미리 불러 둔다 (셔터를 누른 순간 바로 나도록) */
function primeShutter() {
  try {
    shutterAudio().load()
  } catch {
    /* 지원 안 함 */
  }
}

/**
 * 카메라 셔터 "찰칵"을 코드로 만든다 (파일을 따로 두지 않음, 매번 같은 소리).
 *   찰: 셔터막이 열리는 날카롭고 짧은 금속 딸깍
 *   칵: 약 55ms 뒤 닫히는, 조금 낮고 단단한 딸깍
 * 딸깍 하나 = 높은 대역 잡음(금속 마찰, 4ms) + 짧은 금속 울림 + 아주 짧은 몸체 울림
 */
function shutterWav() {
  const rate = 44100
  const n = Math.floor(rate * 0.16)
  const s = new Float32Array(n)
  let seed = 11
  const rand = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x3fffffff) - 1
  function snap(at: number, amp: number, hi: number, ring: number, body: number) {
    // 높은 대역만 남긴 잡음 (1차 고역 통과 두 번)
    const k = Math.exp((-2 * Math.PI * hi) / rate)
    let x1 = 0,
      y1 = 0,
      y2p = 0,
      z1 = 0
    const start = Math.floor(at * rate)
    for (let i = start; i < n; i++) {
      const t = (i - start) / rate
      if (t > 0.05) break
      const x = rand()
      const y = k * (y1 + x - x1)
      x1 = x
      y1 = y
      const z = k * (z1 + y - y2p)
      y2p = y
      z1 = z
      const attack = Math.min(1, t / 0.0002)
      s[i] +=
        amp *
        attack *
        (1.6 * z * Math.exp(-t / 0.004) + // 금속 마찰 (아주 짧게)
          0.45 * Math.sin(2 * Math.PI * ring * t) * Math.exp(-t / 0.009) + // 금속 울림
          0.35 * Math.sin(2 * Math.PI * body * t) * Math.exp(-t / 0.006)) // 몸체
    }
  }
  snap(0, 1, 5200, 3400, 520) // 찰
  snap(0.055, 0.8, 3800, 2600, 380) // 칵
  let peak = 0
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(s[i]))
  const view = new DataView(new ArrayBuffer(44 + n * 2))
  const str = (o: number, t: string) => {
    for (let i = 0; i < t.length; i++) view.setUint8(o + i, t.charCodeAt(i))
  }
  str(0, 'RIFF')
  view.setUint32(4, 36 + n * 2, true)
  str(8, 'WAVE')
  str(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, rate, true)
  view.setUint32(28, rate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  str(36, 'data')
  view.setUint32(40, n * 2, true)
  for (let i = 0; i < n; i++) view.setInt16(44 + i * 2, (s[i] / peak) * 0.9 * 0x7fff, true)
  return new Blob([view], { type: 'audio/wav' })
}

/** 사진 전송 완료·다음으로: 짧고 밝은 "띵" */
export function playSuccess() {
  vibrate(70)
  if (!enabled) return
  tone(880, 0, 0.18)
  tone(1320, 0.09, 0.32)
}

/** 다시 찍어야 할 때: 낮은 "뚜둥" (성공음과 확실히 구분) */
export function playRetake() {
  vibrate([90, 70, 90])
  if (!enabled) return
  tone(330, 0, 0.2, 'triangle', VOLUME * 1.1)
  tone(247, 0.2, 0.32, 'triangle', VOLUME * 1.1)
}

/** 출석 확인됨·출발해도 좋아요 등 기다리던 일이 끝났을 때: 부드러운 알림음 */
export function playNotify() {
  vibrate([60, 50, 60])
  if (!enabled) return
  tone(659, 0, 0.22)
  tone(784, 0.13, 0.22)
  tone(988, 0.26, 0.38)
}
