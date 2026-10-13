// 시간에 맞춘 하늘: 새벽 · 오전 · 한낮 · 오후 · 해 질 녘 · 밤 6단계.
// 기준 시각마다 색을 정해 두고, 그 사이 시각은 두 색을 섞어 서서히 바뀌게 한다.

type Key = {
  h: number
  /** 하늘 위 → 아래 4색 */
  sky: [string, string, string, string]
  /** 해 주변 빛 */
  glow: string
  glowOp: number
  /** 땅을 덮는 색과 진하기 (밤일수록 진함) */
  tint: string
  tintOp: number
  /** 구름 밝은 쪽 / 그늘 쪽 */
  cloud: [string, string]
  cloudOp: number
  /** 위쪽 막대 색 */
  bar: string
  /** 창문 불빛 · 별 · 달 (0~1) */
  lit: number
  stars: number
  moon: number
}

const KEYS: Key[] = [
  { h: 0, sky: ['#0b1634', '#16244d', '#253764', '#344a7a'], glow: '#8fa8ff', glowOp: 0.12, tint: '#0a1430', tintOp: 0.55, cloud: ['#4c5c85', '#2f3c66'], cloudOp: 0.55, bar: '#152449', lit: 1, stars: 1, moon: 1 },
  { h: 5, sky: ['#0b1634', '#16244d', '#253764', '#344a7a'], glow: '#8fa8ff', glowOp: 0.12, tint: '#0a1430', tintOp: 0.55, cloud: ['#4c5c85', '#2f3c66'], cloudOp: 0.55, bar: '#152449', lit: 1, stars: 1, moon: 1 },
  // 새벽: 연보라 → 분홍
  { h: 6.2, sky: ['#4f63a6', '#a690c6', '#f1b6b3', '#ffd9b3'], glow: '#ffc89a', glowOp: 0.75, tint: '#4a3a6a', tintOp: 0.26, cloud: ['#ffe4e6', '#d8b5cf'], cloudOp: 0.95, bar: '#8f90c6', lit: 0.45, stars: 0.25, moon: 0.2 },
  // 오전: 맑은 하늘색
  { h: 7.6, sky: ['#7fbff2', '#a8d6f7', '#d3ecf8', '#ebf7f1'], glow: '#fff7d6', glowOp: 0.9, tint: '#000000', tintOp: 0, cloud: ['#ffffff', '#d4e6f6'], cloudOp: 1, bar: '#94ccf4', lit: 0, stars: 0, moon: 0 },
  { h: 10.5, sky: ['#7fbff2', '#a8d6f7', '#d3ecf8', '#ebf7f1'], glow: '#fff7d6', glowOp: 0.9, tint: '#000000', tintOp: 0, cloud: ['#ffffff', '#d4e6f6'], cloudOp: 1, bar: '#94ccf4', lit: 0, stars: 0, moon: 0 },
  // 한낮: 가장 밝은 파랑
  { h: 12.5, sky: ['#5aa8ee', '#93ccf6', '#cfe9f9', '#eef8f4'], glow: '#ffffff', glowOp: 0.65, tint: '#000000', tintOp: 0, cloud: ['#ffffff', '#d9eaf8'], cloudOp: 1, bar: '#84c2f2', lit: 0, stars: 0, moon: 0 },
  // 오후: 살짝 노란빛
  { h: 15.6, sky: ['#76b3e8', '#aad2f0', '#e3ecea', '#f8eed6'], glow: '#ffe6a6', glowOp: 0.85, tint: '#b07a20', tintOp: 0.05, cloud: ['#fffaf0', '#e1dccc'], cloudOp: 1, bar: '#9cc8e8', lit: 0, stars: 0, moon: 0 },
  // 해 질 녘: 주황 → 보라
  { h: 17.9, sky: ['#3d5799', '#c27fa0', '#f5a46e', '#ffd18c'], glow: '#ffae6e', glowOp: 0.95, tint: '#5a2d3a', tintOp: 0.24, cloud: ['#ffc8a6', '#c58ca0'], cloudOp: 1, bar: '#7c6fa0', lit: 0.35, stars: 0, moon: 0 },
  { h: 19.4, sky: ['#18244f', '#373c76', '#6a578a', '#9c7790'], glow: '#c48aa0', glowOp: 0.35, tint: '#14183a', tintOp: 0.45, cloud: ['#6a6a95', '#454a78'], cloudOp: 0.7, bar: '#22285a', lit: 0.85, stars: 0.6, moon: 0.75 },
  { h: 20.6, sky: ['#0b1634', '#16244d', '#253764', '#344a7a'], glow: '#8fa8ff', glowOp: 0.12, tint: '#0a1430', tintOp: 0.55, cloud: ['#4c5c85', '#2f3c66'], cloudOp: 0.55, bar: '#152449', lit: 1, stars: 1, moon: 1 },
  { h: 24, sky: ['#0b1634', '#16244d', '#253764', '#344a7a'], glow: '#8fa8ff', glowOp: 0.12, tint: '#0a1430', tintOp: 0.55, cloud: ['#4c5c85', '#2f3c66'], cloudOp: 0.55, bar: '#152449', lit: 1, stars: 1, moon: 1 },
]

const hex = (c: string) => [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16))
const mix = (a: string, b: string, t: number) =>
  '#' +
  hex(a)
    .map((v, i) => Math.round(v + (hex(b)[i] - v) * t).toString(16).padStart(2, '0'))
    .join('')
const num = (a: number, b: number, t: number) => a + (b - a) * t

/** 해가 떠 있는 시간 (해 뜸 ~ 해 짐) */
const RISE = 6
const SET = 18.6

export type SkyLook = {
  sky: [string, string, string, string]
  glow: string
  glowOp: number
  tint: string
  tintOp: number
  cloud: [string, string]
  cloudOp: number
  bar: string
  lit: number
  stars: number
  moon: number
  /** 해 위치 (하늘 층 좌표 440×140) · 보이는 정도 */
  sunX: number
  sunY: number
  sunOp: number
  /** 해가 왼쪽에 있으면 산·나무의 밝은 면을 왼쪽으로 */
  fromLeft: boolean
  /** 하늘이 어두워 풍경 위 글씨를 밝게 써야 하는지 */
  dark: boolean
}

export function lookAt(hour: number): SkyLook {
  const h = ((hour % 24) + 24) % 24
  const i = KEYS.findIndex((k, n) => n < KEYS.length - 1 && h >= k.h && h < KEYS[n + 1].h)
  const a = KEYS[i]
  const b = KEYS[i + 1]
  const t = (h - a.h) / (b.h - a.h)
  // 해: 왼쪽 아래에서 떠서 가운데 위를 지나 오른쪽 아래로 진다
  const p = Math.min(1, Math.max(0, (h - RISE) / (SET - RISE)))
  const sunX = 40 + p * 360
  const sunY = 118 - Math.sin(Math.PI * p) * 86
  const sunOp = h < RISE || h > SET ? 0 : Math.min(1, Math.min(h - RISE, SET - h) / 0.5)
  return {
    sky: a.sky.map((c, n) => mix(c, b.sky[n], t)) as SkyLook['sky'],
    glow: mix(a.glow, b.glow, t),
    glowOp: num(a.glowOp, b.glowOp, t),
    tint: mix(a.tint, b.tint, t),
    tintOp: num(a.tintOp, b.tintOp, t),
    cloud: [mix(a.cloud[0], b.cloud[0], t), mix(a.cloud[1], b.cloud[1], t)],
    cloudOp: num(a.cloudOp, b.cloudOp, t),
    bar: mix(a.bar, b.bar, t),
    lit: num(a.lit, b.lit, t),
    stars: num(a.stars, b.stars, t),
    moon: num(a.moon, b.moon, t),
    sunX,
    sunY,
    sunOp,
    fromLeft: sunOp > 0 && sunX < 220,
    dark: h >= 17.3 || h < 6.4,
  }
}

/** 시연용 시간 바꾸기에서 고르는 시각 (null = 휴대폰 시계) */
export const SKY_PRESETS: [number | null, string][] = [
  [null, '지금 시간'],
  [6.3, '새벽'],
  [9, '오전'],
  [12.5, '한낮'],
  [15.7, '오후'],
  [18, '해 질 녘'],
  [21, '밤'],
]

export function nowHour() {
  const d = new Date()
  return d.getHours() + d.getMinutes() / 60
}
