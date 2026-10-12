// 어르신 앱 효과음과 진동.
// 파일 없이 브라우저(Web Audio)에서 짧은 소리를 합성한다. 오프라인에서도 동작한다.
// - 촬영음은 늘 재생한다 (촬영 사실을 알리는 장치라 끌 수 없음)
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

/** 짧은 잡음 조각: 한 대역만 남겨 날카롭지 않은 "딸깍" 소리를 만든다 */
function click(c: Ctx, at: number, dur: number, freq: number, q: number, vol: number) {
  const len = Math.max(1, Math.floor(c.sampleRate * dur))
  const buf = c.createBuffer(1, len, c.sampleRate)
  const d = buf.getChannelData(0)
  // 앞부분이 세고 빠르게 줄어드는 잡음
  for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3)
  const src = c.createBufferSource()
  const f = c.createBiquadFilter()
  const g = c.createGain()
  f.type = 'bandpass'
  f.frequency.value = freq
  f.Q.value = q
  g.gain.value = vol
  src.buffer = buf
  src.connect(f).connect(g).connect(c.destination)
  src.start(c.currentTime + at)
}

/** 낮게 떨어지는 짧은 음: 셔터 몸체가 "툭" 울리는 느낌 */
function thump(c: Ctx, at: number) {
  const t0 = c.currentTime + at
  const o = c.createOscillator()
  const g = c.createGain()
  o.type = 'sine'
  o.frequency.setValueAtTime(190, t0)
  o.frequency.exponentialRampToValueAtTime(80, t0 + 0.07)
  g.gain.setValueAtTime(0.0001, t0)
  g.gain.exponentialRampToValueAtTime(0.28, t0 + 0.004)
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.09)
  o.connect(g).connect(c.destination)
  o.start(t0)
  o.stop(t0 + 0.1)
}

/** 사진 찍을 때: 카메라 셔터 "찰-칵" (항상 재생)
 *  열릴 때 또렷한 딸깍 + 몸체 울림, 닫힐 때 조금 부드러운 딸깍 */
export function playShutter() {
  // 촬영음은 빼고 짧은 진동만 남긴다 (사용자 요청). 소리를 되살리려면 SHUTTER_SOUND를 true로.
  vibrate(30)
  if (!SHUTTER_SOUND) return
  const c = audio()
  if (!c) return
  click(c, 0, 0.018, 3200, 1.4, 0.55)
  thump(c, 0)
  click(c, 0.075, 0.03, 2200, 1.1, 0.4)
  thump(c, 0.075)
}

/** 촬영음 사용 여부. 팀 문서(frontend/senior/README.md)는 촬영음 재생을 요구하므로 팀 확인 필요 */
const SHUTTER_SOUND = false

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
