import { useId, type ReactNode } from 'react'
import { lookAt, type SkyLook } from './sky'

// 화면 위쪽 풍경 배경.
// 하늘 → 해·구름·새 → 아주 먼 산 → 먼 산 → 언덕·밭 → 마을·나무 → 앞 언덕·오솔길 순으로 겹쳐 깊이를 만든다.
// 빛은 해에서 온다: 해가 있는 쪽 면은 밝게, 반대쪽은 그늘지게 칠한다 (해 위치·하늘 색은 sky.ts가 시간에 맞춰 정함).
// 맨 아래는 화면 바탕색으로 자연스럽게 사라져 내용 카드와 이어진다.

type Tree = { x: number; y: number; r: number; kind: 'round' | 'pine' }

const TREES: Tree[] = [
  { x: 58, y: 236, r: 13, kind: 'round' },
  { x: 80, y: 241, r: 9, kind: 'pine' },
  { x: 96, y: 238, r: 8, kind: 'round' },
  { x: 168, y: 246, r: 7, kind: 'pine' },
  { x: 292, y: 226, r: 14, kind: 'round' },
  { x: 314, y: 231, r: 9, kind: 'pine' },
  { x: 332, y: 228, r: 10, kind: 'round' },
  { x: 398, y: 224, r: 12, kind: 'round' },
  { x: 420, y: 228, r: 8, kind: 'pine' },
]

/**
 * part: 'all' 한 장 전체 / 'sky' 해·구름·새만 (위쪽에 고정) / 'ground' 산·언덕·마을만 (아래에 고정)
 * village: 설명 칩이 마을을 가려 문만 삐져나오는 화면에서는 끈다
 * 글씨를 키워 화면이 길어질 때 하늘 층은 위에, 땅 층은 첫 카드 위에 따로 붙어 사이 하늘만 늘어난다.
 */
/** 아주 먼 산줄기 (땅 덮개 모양에도 쓴다) */
const VERY_FAR = 'M0 196 L38 170 L70 186 L118 150 L164 182 L206 160 L252 186 L300 154 L350 180 L398 158 L440 176 V320 H0Z'
/** 먼 산 4개: [꼭대기 x, y, 왼쪽 아래 x, y, 오른쪽 아래 x, y] */
const FAR_PEAKS = [
  [52, 150, 0, 214, 108, 214],
  [152, 126, 92, 214, 222, 214],
  [270, 140, 206, 214, 336, 214],
  [380, 132, 318, 214, 446, 214],
]

/** 밤하늘 별 자리 (하늘 층 좌표). 반짝이는 때를 조금씩 어긋나게 */
const STARS: [number, number, number][] = [
  [24, 18, 1.2], [58, 44, 0.9], [92, 14, 1.4], [128, 36, 0.8], [160, 10, 1.1], [196, 52, 0.9], [214, 22, 1.3],
  [248, 40, 0.8], [276, 12, 1], [300, 58, 0.9], [392, 18, 1.2], [418, 46, 0.9], [40, 76, 0.8], [110, 70, 1],
  [178, 84, 0.8], [232, 96, 0.9], [270, 78, 1.1], [420, 92, 0.8], [8, 104, 1], [150, 112, 0.8],
]

/** 창문 자리 (집 2채 + 마을회관) : 밤에 불빛을 겹쳐 그린다 */
const WINDOWS: [number, number][] = [
  ...[
    [196, 218, 22],
    [258, 220, 26],
  ].flatMap(([x, y, w]) => [
    [x + 4, y + 5],
    [x + w - 10, y + 5],
  ]),
  ...[8, 18, 28].flatMap((dy) => [
    [232, 194 + dy],
    [242, 194 + dy],
  ]),
] as [number, number][]

export function Scenery({
  className = '',
  part = 'all',
  village = true,
  look = lookAt(9),
}: {
  className?: string
  part?: 'all' | 'sky' | 'ground'
  village?: boolean
  /** 시간에 맞춘 하늘·빛 (sky.ts) */
  look?: SkyLook
}) {
  const sky = part !== 'ground'
  const ground = part !== 'sky'
  const skyOnly = part === 'sky'
  const u = useId().replace(/:/g, '')
  const id = (n: string) => `${n}-${u}`
  const url = (n: string) => `url(#${id(n)})`
  const L = look
  const fl = L.fromLeft
  // 하늘 층 좌표 → 한 장(all) 좌표: 한 장일 때는 하늘이 조금 더 아래까지 내려온다
  const sunY = skyOnly ? L.sunY : L.sunY + 26

  const cloud = (x: number, y: number, s: number, o = 1): ReactNode => (
    <g transform={`translate(${x} ${y}) scale(${s})`} opacity={o * L.cloudOp}>
      {/* 아래 그림자 */}
      <ellipse cx="40" cy="26" rx="42" ry="9" fill={L.cloud[1]} opacity="0.6" />
      {/* 덩어리: 각자 위는 밝고 아래는 푸르스름 */}
      <circle cx="22" cy="18" r="14" fill={url('puff')} />
      <circle cx="44" cy="11" r="19" fill={url('puff')} />
      <circle cx="64" cy="18" r="12" fill={url('puff')} />
      <rect x="10" y="17" width="62" height="12" rx="6" fill={url('puffBase')} />
      {/* 해 쪽 가장자리 빛 */}
      <path d="M50 -6 a19 19 0 0 1 12 12" stroke="#ffffff" strokeWidth="2.5" fill="none" strokeLinecap="round" opacity={0.9 * (1 - L.stars)} />
    </g>
  )

  return (
    <svg
      viewBox={part === 'sky' ? '0 0 440 140' : part === 'ground' ? '0 112 440 208' : '0 0 440 320'}
      preserveAspectRatio={part === 'sky' ? 'xMidYMin meet' : 'xMidYMax slice'}
      overflow="visible"
      className={className}
      aria-hidden
    >
      <defs>
        <linearGradient id={id('sky')} gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="320">
          <stop offset="0" stopColor={L.sky[0]} />
          <stop offset="0.35" stopColor={L.sky[1]} />
          <stop offset="0.62" stopColor={L.sky[2]} />
          <stop offset="0.82" stopColor={L.sky[3]} />
          <stop offset="1" stopColor={L.sky[3]} />
        </linearGradient>
        {/* 해 주변과 지평선 쪽 빛 번짐 (해를 따라 움직임) */}
        <radialGradient id={id('glow')} gradientUnits="userSpaceOnUse" cx={L.sunX} cy={sunY} r="264">
          <stop offset="0" stopColor={L.glow} stopOpacity={0.95 * L.glowOp} />
          <stop offset="0.25" stopColor={L.glow} stopOpacity={0.55 * L.glowOp} />
          <stop offset="0.6" stopColor={L.glow} stopOpacity="0" />
        </radialGradient>
        <radialGradient id={id('glowSky')} cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor={L.glow} stopOpacity={0.9 * L.glowOp} />
          <stop offset="0.3" stopColor={L.glow} stopOpacity={0.45 * L.glowOp} />
          <stop offset="1" stopColor={L.glow} stopOpacity="0" />
        </radialGradient>
        {/* 초승달: 둥근 달에서 옆으로 비킨 원만큼 지운다 */}
        <mask id={id('moon')}>
          <circle cx="0" cy="0" r="15" fill="#fff" />
          <circle cx="7" cy="-5" r="13" fill="#000" />
        </mask>
        <radialGradient id={id('sun')} cx="0.42" cy="0.4" r="0.6">
          <stop offset="0" stopColor="#fffdf2" />
          <stop offset="0.7" stopColor="#fff0b8" />
          <stop offset="1" stopColor="#ffe08a" />
        </radialGradient>
        <radialGradient id={id('puff')} cx={fl ? 0.4 : 0.6} cy="0.25" r="0.8">
          <stop offset="0" stopColor={L.cloud[0]} />
          <stop offset="0.65" stopColor={L.cloud[0]} />
          <stop offset="1" stopColor={L.cloud[1]} />
        </radialGradient>
        <linearGradient id={id('puffBase')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={L.cloud[0]} />
          <stop offset="1" stopColor={L.cloud[1]} />
        </linearGradient>
        <linearGradient id={id('veryFar')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#c3daef" />
          <stop offset="1" stopColor="#dfedf7" />
        </linearGradient>
        <linearGradient id={id('farLit')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#b6d3ee" />
          <stop offset="1" stopColor="#d6e8f5" />
        </linearGradient>
        <linearGradient id={id('farShade')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#8fb2d8" />
          <stop offset="1" stopColor="#bcd5ec" />
        </linearGradient>
        <linearGradient id={id('hill')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#86cfa2" />
          <stop offset="1" stopColor="#b5e2c4" />
        </linearGradient>
        <linearGradient id={id('hillBack')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#a6dbbb" />
          <stop offset="1" stopColor="#c8ead3" />
        </linearGradient>
        <linearGradient id={id('near')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#a5dfb6" />
          <stop offset="1" stopColor="#dbf3e2" />
        </linearGradient>
        <radialGradient id={id('leaf')} cx={fl ? 0.32 : 0.68} cy="0.3" r="0.8">
          <stop offset="0" stopColor="#a8e6b8" />
          <stop offset="0.55" stopColor="#5fbb83" />
          <stop offset="1" stopColor="#3b8f62" />
        </radialGradient>
        <linearGradient id={id('pine')} x1={fl ? 0 : 1} y1="0" x2={fl ? 1 : 0} y2="0">
          <stop offset="0" stopColor="#7fcf98" />
          <stop offset="1" stopColor="#3f8f63" />
        </linearGradient>
        <linearGradient id={id('path')} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#f3e7cf" />
          <stop offset="1" stopColor="#fff8ea" />
        </linearGradient>
        <linearGradient id={id('fade')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#f4f6fa" stopOpacity="0" />
          <stop offset="1" stopColor="#f4f6fa" />
        </linearGradient>
        <filter id={id('soft')} x="-20%" y="-20%" width="140%" height="160%">
          <feDropShadow dx={fl ? 1.5 : -1.5} dy="2.5" stdDeviation="2.5" floodColor="#3d6f9c" floodOpacity="0.18" />
        </filter>
        <filter id={id('blur')}>
          <feGaussianBlur stdDeviation="1.2" />
        </filter>
      </defs>

      {/* 하늘 바탕: 한 장(all)일 때만. 나눠 그릴 때는 감싸는 쪽 그라데이션이 하늘을 칠한다 */}
      {part === 'all' && (
        <>
          <rect x="-200" y="-600" width="840" height="920" fill={url('sky')} />
          <rect x="-200" y="-600" width="840" height="920" fill={url('glow')} />
        </>
      )}

      {sky && (
        <>
          {/* 별: 밤에만, 하나씩 다른 때에 반짝인다 */}
          {L.stars > 0.01 && (
            <g opacity={L.stars}>
              {STARS.map(([x, y, r], n) => (
                <circle key={n} cx={x} cy={skyOnly ? y : y + 10} r={r} fill="#fffbe8" className="twinkle" style={{ animationDelay: `${(n * 0.37) % 3}s` }} />
              ))}
            </g>
          )}
          {/* 달: 오른쪽 위 */}
          {L.moon > 0.01 && (
            <g transform={skyOnly ? 'translate(356 54)' : 'translate(356 70)'} opacity={L.moon}>
              <circle r="34" fill="#fff6d0" opacity="0.08" />
              <circle r="24" fill="#fff6d0" opacity="0.14" />
              <circle r="15" fill="#fdf1c4" mask={url('moon')} />
            </g>
          )}
          {/* 해: 시간에 따라 왼쪽 아래 → 가운데 위 → 오른쪽 아래 */}
          {L.sunOp > 0.01 && (
            <>
              {skyOnly && <circle cx={L.sunX} cy={sunY} r="200" fill={url('glowSky')} opacity={L.sunOp} />}
              <g transform={`translate(${L.sunX} ${sunY})`} opacity={L.sunOp}>
                <circle r="46" fill={L.glow} opacity="0.35" />
                <circle r="31" fill={L.glow} opacity="0.55" />
                <circle r="21" fill={url('sun')} />
              </g>
            </>
          )}
          <g filter={url('soft')}>
            {skyOnly ? (
              <>
                {cloud(222, 16, 0.7)}
                {cloud(384, 104, 0.42, 0.9)}
              </>
            ) : (
              <>
                {cloud(30, 56, 0.95)}
                {cloud(236, 34, 0.78)}
                {cloud(150, 108, 0.48, 0.85)}
              </>
            )}
          </g>
          <g stroke="#5f84a8" strokeWidth="1.6" fill="none" strokeLinecap="round" opacity={0.7 * (1 - L.stars)}>
            {skyOnly ? (
              <>
                <path d="M286 30 q4 -4 8 0 q4 -4 8 0" />
                <path d="M302 42 q3 -3 6 0 q3 -3 6 0" />
              </>
            ) : (
              <>
                <path d="M196 78 q4 -4 8 0 q4 -4 8 0" />
                <path d="M214 90 q3 -3 6 0 q3 -3 6 0" />
                <path d="M120 92 q3 -3 6 0 q3 -3 6 0" />
              </>
            )}
          </g>
        </>
      )}

      {ground && (
        <>
      {/* 아주 먼 산: 공기에 흐려진 산줄기 */}
      <path d={VERY_FAR} fill={url('veryFar')} />

      {/* 먼 산: 오른쪽(해 쪽)은 밝고 왼쪽은 그늘 */}
      <g>
        {FAR_PEAKS.map(([px, py, lx, ly, rx, ry]) => {
          // 눈 덮인 봉우리: 산의 실제 비탈(왼쪽·오른쪽 기울기)과 그늘 경계선에 맞춰 계산한다
          const h = 14 // 눈이 덮인 높이
          const kl = (px - lx) / (ly - py) // 왼쪽 비탈: 아래로 1 내려갈 때 왼쪽으로 가는 거리
          const kr = (rx - px) / (ry - py) // 오른쪽 비탈
          // 그늘 경계선: 해가 오른쪽이면 왼쪽으로(px-6), 해가 왼쪽이면 오른쪽으로(px+6) 내려간다
          const sd = fl ? 6 : -6
          const ridge = (y: number) => px + (sd * (y - py)) / (ly - py)
          const Lp = [px - h * kl, py + h] // 왼쪽 비탈 위의 눈 끝
          const Rp = [px + h * kr, py + h] // 오른쪽 비탈 위의 눈 끝
          const LM = [px - h * 0.55 * kl, py + h * 0.72] // 눈 아래 들쭉날쭉한 자리
          const RM = [px + h * 0.5 * kr, py + h * 0.7]
          const S = [ridge(py + h * 1.12), py + h * 1.12] // 눈 맨 아래 (그늘 경계선 위)
          const pts = (a: number[][]) => a.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join(' L')
          const rightSnow = `M${pts([[px, py], S, RM, Rp])} Z`
          const leftSnow = `M${pts([[px, py], Lp, LM, S])} Z`
          return (
            <g key={px}>
              <path d={`M${lx} ${ly} L${px} ${py} L${rx} ${ry} Z`} fill={url('farLit')} />
              {fl ? (
                <path d={`M${rx} ${ry} L${px} ${py} L${px + 6} ${ry} Z`} fill={url('farShade')} />
              ) : (
                <path d={`M${lx} ${ly} L${px} ${py} L${px - 6} ${ly} Z`} fill={url('farShade')} />
              )}
              {/* 해 쪽 눈은 하얗게, 그늘 쪽 눈은 푸르스름하게 */}
              <path d={fl ? leftSnow : rightSnow} fill="#ffffff" />
              <path d={fl ? rightSnow : leftSnow} fill="#d3e3f3" />
            </g>
          )
        })}
      </g>

      {/* 뒤 언덕 */}
      <path d="M0 226 C50 206 96 208 142 220 C188 232 230 210 280 206 C330 202 380 212 440 206 V320 H0Z" fill={url('hillBack')} />

      {/* 앞 언덕과 밭 */}
      <path d="M0 246 C60 222 116 224 168 238 C220 252 262 222 330 216 C378 212 410 224 440 222 V320 H0Z" fill={url('hill')} />
      <path d="M0 246 C60 222 116 224 168 238" stroke="#ffffff" strokeWidth="2" fill="none" opacity="0.45" />
      <path d="M262 226 C292 218 318 216 340 216 C380 214 410 224 440 222" stroke="#ffffff" strokeWidth="2" fill="none" opacity="0.45" />
      <g opacity="0.35" stroke="#5fae80" strokeWidth="1.2">
        <path d="M110 250 L170 244" />
        <path d="M106 256 L176 249" />
        <path d="M102 262 L182 254" />
        <path d="M346 236 L404 232" />
        <path d="M342 242 L410 237" />
      </g>

      {/* 마을: 깊이는 모두 왼쪽 위로 일정하게 물러난다 (DX, DY) → 옆면·지붕이 같은 방향으로 기운다 */}
      {village && (
        <g filter={url('soft')}>
          {HOUSES.map((hs) => (
            <House key={hs.x} {...hs} />
          ))}
          <Hall x={228} y={194} w={24} h={50} />
        </g>
      )}
      {/* 나무: 바닥 그림자 → 줄기 → 잎 (해 쪽이 밝음) */}
      {TREES.map(({ x, y, r, kind }) => (
        <g key={`${x}-${y}`}>
          <ellipse cx={x - r * 0.5} cy={y + r * 0.95} rx={r * 0.95} ry={r * 0.28} fill="#3f8a5f" opacity="0.25" filter={url('blur')} />
          <rect x={x - 1.6} y={y + r * 0.1} width="3.2" height={r * 0.9} rx="1.2" fill="#7b6650" />
          {kind === 'round' ? (
            <>
              <circle cx={x} cy={y - r * 0.25} r={r} fill={url('leaf')} />
              <circle cx={fl ? x - r * 0.35 : x + r * 0.35} cy={y - r * 0.6} r={r * 0.32} fill="#c4f0cf" opacity="0.55" />
            </>
          ) : (
            <>
              <path d={`M${x} ${y - r * 1.6} L${x + r * 0.8} ${y + r * 0.2} L${x - r * 0.8} ${y + r * 0.2} Z`} fill={url('pine')} />
              <path d={`M${x} ${y - r * 1.6} L${fl ? x - r * 0.8 : x + r * 0.8} ${y + r * 0.2} L${fl ? x - r * 0.15 : x + r * 0.15} ${y + r * 0.2} Z`} fill="#a7e2b8" opacity="0.5" />
            </>
          )}
        </g>
      ))}

      {/* 앞 언덕·오솔길·꽃 */}
      <path d="M0 274 C86 250 168 258 248 270 C326 282 388 262 440 260 V320 H0Z" fill={url('near')} />
      <path d="M0 274 C86 250 168 258 248 270 C326 282 388 262 440 260" stroke="#ffffff" strokeWidth="2" fill="none" opacity="0.5" />
      <path d="M140 320 C164 298 208 286 258 278 C298 272 330 268 364 264" stroke="#e6d6b6" strokeWidth="10" fill="none" strokeLinecap="round" opacity="0.6" />
      <path d="M140 320 C164 298 208 286 258 278 C298 272 330 268 364 264" stroke={url('path')} strokeWidth="7" fill="none" strokeLinecap="round" />
      <g>
        {[
          [40, 284, '#ffd36b'],
          [62, 290, '#ffffff'],
          [96, 280, '#ff9fb2'],
          [304, 286, '#ffd36b'],
          [330, 282, '#ffffff'],
          [372, 276, '#ff9fb2'],
          [404, 280, '#ffffff'],
        ].map(([x, y, c]) => (
          <g key={`${x}-${y}`}>
            <path d={`M${x} ${Number(y) + 6} V${y}`} stroke="#5fae80" strokeWidth="1.2" />
            <circle cx={x} cy={y} r="2.2" fill={String(c)} />
          </g>
        ))}
        <g fill="#7cc795" opacity="0.8">
          <ellipse cx="120" cy="288" rx="9" ry="4" />
          <ellipse cx="128" cy="286" rx="6" ry="4" />
          <ellipse cx="350" cy="280" rx="8" ry="3.5" />
          <ellipse cx="357" cy="278" rx="5" ry="3.5" />
        </g>
      </g>

      {/* 시간에 맞춰 땅을 어둡게(밤) · 붉게(해 질 녘) 덮는다 */}
      {/* 네모로 덮으면 산 위 하늘에 경계선이 생기므로, 산·언덕 모양 안에만 칠한다 */}
      {L.tintOp > 0.005 && (
        <>
          <clipPath id={id('land')}>
            <path d={VERY_FAR} />
            {FAR_PEAKS.map(([px, py, lx, ly, rx, ry]) => (
              <path key={px} d={`M${lx} ${ly} L${px} ${py} L${rx} ${ry} Z`} />
            ))}
          </clipPath>
          <rect x="-200" y="112" width="840" height="208" fill={L.tint} opacity={L.tintOp} clipPath={url('land')} />
        </>
      )}
      {/* 밤: 마을 창문에 불이 켜진다 (어두운 덮개 위에 그려야 밝게 보임) */}
      {village && L.lit > 0.01 && (
        <g opacity={L.lit}>
          {WINDOWS.map(([x, y]) => (
            <g key={`${x}-${y}`}>
              <rect x={x - 2} y={y - 2} width="10" height="10" rx="3" fill="#ffd56b" opacity="0.35" />
              <rect x={x} y={y} width="6" height="6" rx="1" fill="#ffe08a" />
            </g>
          ))}
        </g>
      )}

      {/* 바탕색으로 자연스럽게 */}
      <rect y="248" width="440" height="72" fill={url('fade')} />
        </>
      )}
    </svg>
  )
}

/** 건물 깊이 방향: 왼쪽 위로 물러남 */
const DX = -6
const DY = -4

type HouseProps = { x: number; y: number; w: number; h: number; roof: string; roofLight: string; door: number }

const HOUSES: HouseProps[] = [
  { x: 196, y: 218, w: 22, h: 26, roof: '#e08560', roofLight: '#f0a37e', door: 207 },
  { x: 258, y: 220, w: 26, h: 24, roof: '#d97b59', roofLight: '#eb9a76', door: 268 },
]

/** 박공지붕 주택: 앞면 + 왼쪽 옆면 + 삼각형 박공 + 뒤로 이어지는 지붕 판 */
function House({ x, y, w, h, roof, roofLight, door }: HouseProps) {
  const peak = y - 12 // 박공 꼭대기 높이
  const mid = x + w / 2
  const eave = 2 // 처마가 벽보다 나오는 길이
  return (
    <g>
      {/* 왼쪽 옆면 (그늘) */}
      <path d={`M${x} ${y} L${x + DX} ${y + DY} L${x + DX} ${y + h + DY} L${x} ${y + h} Z`} fill="#d9e3ee" />
      {/* 앞면 벽 + 박공 삼각형 벽 */}
      <path d={`M${x} ${y + h} L${x} ${y} L${mid} ${peak} L${x + w} ${y} L${x + w} ${y + h} Z`} fill="#ffffff" />
      {/* 왼쪽 지붕 판: 박공 왼쪽 변에서 깊이 방향으로 물러남 */}
      <path
        d={`M${x - eave} ${y + 1.2} L${mid} ${peak - 1} L${mid + DX} ${peak - 1 + DY} L${x - eave + DX} ${y + 1.2 + DY} Z`}
        fill={roof}
      />
      {/* 박공 가장자리 (처마 선) */}
      <path
        d={`M${x - eave} ${y + 1.2} L${mid} ${peak - 1} L${x + w + eave} ${y + 1.2}`}
        stroke={roofLight}
        strokeWidth="2.6"
        fill="none"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      {/* 창문·문 */}
      <rect x={x + 4} y={y + 5} width="6" height="6" rx="1" fill="#a9cdee" />
      <rect x={x + w - 10} y={y + 5} width="6" height="6" rx="1" fill="#a9cdee" />
      <rect x={door} y={y + h - 11} width="7" height="11" rx="1" fill="#c9a17d" />
      {/* 박공 작은 창 */}
      <circle cx={mid} cy={y - 4} r="2.2" fill="#a9cdee" />
    </g>
  )
}

/** 평지붕 마을회관: 앞면 + 왼쪽 옆면 + 윗면 + 깃발 */
function Hall({ x, y, w, h }: { x: number; y: number; w: number; h: number }) {
  return (
    <g>
      <path d={`M${x} ${y} L${x + DX} ${y + DY} L${x + DX} ${y + h + DY} L${x} ${y + h} Z`} fill="#d6e1ec" />
      <path d={`M${x} ${y} L${x + w} ${y} L${x + w + DX} ${y + DY} L${x + DX} ${y + DY} Z`} fill="#e9f0f7" />
      <rect x={x} y={y} width={w} height={h} fill="#f8fbfe" />
      <rect x={x} y={y} width={w} height="3" fill="#dbe6f1" />
      {[8, 18, 28].map((dy) => (
        <g key={dy}>
          <rect x={x + 4} y={y + dy} width="6" height="6" rx="1" fill="#a9cdee" />
          <rect x={x + w - 10} y={y + dy} width="6" height="6" rx="1" fill="#a9cdee" />
        </g>
      ))}
      <rect x={x + w / 2 - 4} y={y + h - 11} width="8" height="11" rx="1" fill="#8fb4da" />
      {/* 깃발: 지붕 윗면 가운데에서 */}
      <path d={`M${x + w / 2 + DX / 2} ${y + DY / 2} V${y - 16}`} stroke="#7f8fa3" strokeWidth="1.4" />
      <path d={`M${x + w / 2 + DX / 2} ${y - 16} L${x + w / 2 + DX / 2 + 11} ${y - 12.5} L${x + w / 2 + DX / 2} ${y - 9} Z`} fill="#2f6beb" />
    </g>
  )
}