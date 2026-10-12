// 관리자 웹 알림음. 기본은 꺼져 있고, 담당자가 설정에서 켠 항목만 소리를 낸다.
// 파일 없이 브라우저(Web Audio)에서 짧은 소리를 합성한다.

export type AlertKind = 'attendance' | 'return'
export type AlertSettings = Record<AlertKind, boolean>

export const ALERT_LABEL: Record<AlertKind, { title: string; desc: string }> = {
  attendance: { title: '출석 요청', desc: '어르신이 앱에서 출석을 요청했을 때' },
  return: { title: '복귀 요청', desc: '조가 활동을 마치고 센터로 돌아오기 시작했을 때' },
}

const KEY = 'neighborhood-admin.alert-sounds'
const OFF: AlertSettings = { attendance: false, return: false }

/** 이 브라우저에 저장한 설정 (저장소를 못 쓰면 모두 꺼짐) */
export function loadAlertSettings(): AlertSettings {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? { ...OFF, ...JSON.parse(raw) } : OFF
  } catch {
    return OFF
  }
}

export function saveAlertSettings(s: AlertSettings) {
  try {
    localStorage.setItem(KEY, JSON.stringify(s))
  } catch {
    /* 저장하지 못해도 이번 화면에서는 동작 */
  }
}

let ctx: AudioContext | null = null
function audio() {
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

/** 브라우저는 사용자가 한 번 누른 뒤에야 소리를 허용하므로, 첫 클릭 때 소리 장치를 깨워 둔다 */
export function unlockAlertAudio() {
  const once = () => {
    audio()
    window.removeEventListener('pointerdown', once)
  }
  window.addEventListener('pointerdown', once)
}

function tone(freq: number, start: number, dur: number) {
  const c = audio()
  if (!c) return
  const t0 = c.currentTime + start
  const o = c.createOscillator()
  const g = c.createGain()
  o.type = 'sine'
  o.frequency.setValueAtTime(freq, t0)
  g.gain.setValueAtTime(0.0001, t0)
  g.gain.exponentialRampToValueAtTime(0.18, t0 + 0.015)
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  o.connect(g).connect(c.destination)
  o.start(t0)
  o.stop(t0 + dur + 0.02)
}

/** 사무실에서 거슬리지 않는 짧은 두 음. 항목마다 음을 달리해 귀로도 구분되게 한다 */
export function playAlert(kind: AlertKind) {
  if (kind === 'attendance') {
    tone(784, 0, 0.18)
    tone(1047, 0.12, 0.3)
  } else {
    tone(659, 0, 0.18)
    tone(523, 0.12, 0.3)
  }
}
