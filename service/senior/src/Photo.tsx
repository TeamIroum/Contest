import { useId, useState } from 'react'
import { bboxToRect, useMedia, type MediaRef } from './media'

// 실제 사진이 준비되기 전까지 쓰는 "사진 자리" 그림. (실제 사진은 아래 Photo의 media/src로 받는다)
// 꾸밈 없이 시설의 상태만 보이도록 차분한 색으로 단순화했다.
export type Kind = 'sidewalk' | 'drain' | 'bench' | 'fence' | 'exercise' | 'playground' | 'snowbox' | 'shade'

export const KIND_NAME: Record<Kind, string> = {
  sidewalk: '보도블록',
  drain: '빗물받이',
  bench: '벤치',
  fence: '안전펜스',
  exercise: '운동기구',
  playground: '공원기구',
  snowbox: '제설함',
  shade: '그늘막',
}

const C = {
  ink: '#2B3340',
  steel: '#5F6B7A',
  steelLight: '#8A95A3',
  pave: '#E4E0D8',
  paveGap: '#C9C3B8',
  road: '#A3A9B1',
  curb: '#CDD1D6',
  grass: '#C9D4C1',
  sky: '#DCE3EA',
  wood: '#A6805F',
  woodDark: '#86654A',
  frame: '#4D6785',
  seat: '#B9784A',
  leafA: '#A7773F',
  leafB: '#8C9A62',
  leafC: '#C6A04E',
  crack: '#3A3F47',
}

// AI 판독 영역 (그림 좌표 160x120 기준)
const BOX: Record<Kind, [number, number, number, number]> = {
  sidewalk: [58, 30, 46, 40],
  drain: [34, 50, 92, 46],
  bench: [52, 52, 50, 22],
  fence: [62, 28, 40, 64],
  exercise: [88, 24, 44, 52],
  playground: [80, 30, 40, 66],
  snowbox: [44, 30, 72, 34],
  shade: [60, 14, 74, 40],
}

function Scene({ kind, bad }: { kind: Kind; bad: boolean }) {
  switch (kind) {
    case 'sidewalk': {
      const tiles = []
      for (let r = 0; r < 5; r++)
        for (let c = 0; c < 6; c++) {
          const x = 4 + c * 26 + (r % 2 ? 13 : 0) - 13
          const y = 4 + r * 23
          const hit = bad && r === 2 && c === 3
          if (!hit) tiles.push(<rect key={`${r}-${c}`} x={x} y={y} width="23" height="20" rx="2" fill={C.pave} />)
        }
      return (
        <>
          <rect width="160" height="120" fill={C.paveGap} />
          {tiles}
          {bad ? (
            <>
              <rect x="69" y="50" width="23" height="20" rx="2" fill="#B4AC9F" />
              <path d="M67 44 L92 40 L95 60 L70 64 Z" fill="#D8D2C7" stroke="#9E968A" strokeWidth="1" />
              <path d="M74 47 l5 6 -3 4 6 6" stroke={C.crack} strokeWidth="1.6" fill="none" strokeLinecap="round" />
              <path d="M86 66 l3 5" stroke={C.crack} strokeWidth="1.4" />
            </>
          ) : (
            <rect x="69" y="50" width="23" height="20" rx="2" fill={C.pave} />
          )}
        </>
      )
    }
    case 'drain':
      return (
        <>
          <rect width="160" height="120" fill={C.curb} />
          <rect y="88" width="160" height="32" fill={C.road} />
          <rect x="40" y="56" width="80" height="34" rx="3" fill={C.ink} />
          {Array.from({ length: 9 }, (_, i) => (
            <rect key={i} x={45 + i * 8.4} y="60" width="4" height="26" rx="1.5" fill={C.steel} />
          ))}
          {bad && (
            <>
              <ellipse cx="58" cy="76" rx="16" ry="9" fill={C.leafA} />
              <ellipse cx="80" cy="70" rx="14" ry="8" fill={C.leafB} />
              <ellipse cx="98" cy="80" rx="15" ry="8" fill={C.leafC} />
              <ellipse cx="72" cy="84" rx="12" ry="6" fill="#9A8A70" />
              <rect x="88" y="62" width="10" height="6" rx="1" fill="#E8E8E8" transform="rotate(-12 93 65)" />
            </>
          )}
        </>
      )
    case 'bench':
      return (
        <>
          <rect width="160" height="120" fill={C.grass} />
          <rect y="84" width="160" height="36" fill={C.pave} />
          <rect x="44" y="44" width="72" height="7" rx="2" fill={C.wood} />
          <rect x="44" y="34" width="72" height="7" rx="2" fill={C.wood} />
          <rect x="48" y="51" width="4" height="12" fill={C.steel} />
          <rect x="108" y="51" width="4" height="12" fill={C.steel} />
          {bad ? (
            <>
              <rect x="44" y="62" width="30" height="8" rx="2" fill={C.wood} />
              <rect x="86" y="62" width="30" height="8" rx="2" fill={C.wood} />
              <path d="M74 62 l6 3 -3 3" stroke={C.crack} strokeWidth="1.5" fill="none" />
              <rect x="70" y="72" width="20" height="5" rx="1.5" fill={C.woodDark} transform="rotate(14 80 74)" />
            </>
          ) : (
            <rect x="44" y="62" width="72" height="8" rx="2" fill={C.wood} />
          )}
          <rect x="48" y="70" width="4" height="16" fill={C.steel} />
          <rect x="108" y="70" width="4" height="16" fill={C.steel} />
        </>
      )
    case 'fence':
      return (
        <>
          <rect width="160" height="120" fill={C.sky} />
          <rect y="88" width="160" height="32" fill={C.grass} />
          {[20, 52, 116, 148].map((x) => (
            <rect key={x} x={x - 3} y="34" width="6" height="56" fill={C.steel} />
          ))}
          <path d="M20 44 H52 M116 44 H148 M20 70 H52 M116 70 H148" stroke={C.steelLight} strokeWidth="5" />
          {bad ? (
            <>
              <g transform="rotate(22 84 90)">
                <rect x="81" y="34" width="6" height="56" fill={C.steel} />
              </g>
              <path d="M52 44 H74 M52 70 H70" stroke={C.steelLight} strokeWidth="5" />
              <path d="M98 50 L116 44 M96 74 L116 70" stroke={C.steelLight} strokeWidth="5" />
            </>
          ) : (
            <>
              <rect x="81" y="34" width="6" height="56" fill={C.steel} />
              <path d="M52 44 H116 M52 70 H116" stroke={C.steelLight} strokeWidth="5" />
            </>
          )}
        </>
      )
    case 'exercise':
      return (
        <>
          <rect width="160" height="120" fill={C.grass} />
          <rect y="92" width="160" height="28" fill={C.pave} />
          <rect x="60" y="30" width="7" height="64" rx="2" fill={C.frame} />
          <rect x="40" y="88" width="80" height="6" rx="2" fill={C.frame} />
          <path d="M64 34 L106 34" stroke={C.frame} strokeWidth="6" strokeLinecap="round" />
          <circle cx="78" cy="72" r="9" fill="none" stroke={C.steel} strokeWidth="4" />
          <rect x="70" y="58" width="16" height="5" rx="2" fill={C.steel} />
          {bad ? (
            <path d="M106 34 Q112 44 104 56" stroke={C.frame} strokeWidth="6" fill="none" strokeLinecap="round" />
          ) : (
            <>
              <path d="M106 34 L106 60" stroke={C.frame} strokeWidth="6" strokeLinecap="round" />
              <rect x="98" y="58" width="18" height="6" rx="3" fill={C.ink} />
            </>
          )}
        </>
      )
    case 'playground':
      return (
        <>
          <rect width="160" height="120" fill={C.sky} />
          <rect y="94" width="160" height="26" fill="#D9C6A5" />
          <path d="M34 96 L52 22 L118 22 L136 96" stroke={C.frame} strokeWidth="6" fill="none" strokeLinejoin="round" />
          <path d="M60 24 V74 M78 24 V74" stroke={C.steel} strokeWidth="2" strokeDasharray="3 2" />
          <rect x="56" y="74" width="26" height="6" rx="2" fill={C.seat} />
          {bad ? (
            <>
              <path d="M96 24 V78" stroke={C.steel} strokeWidth="2" strokeDasharray="3 2" />
              <path d="M110 80 Q116 66 112 54" stroke={C.steel} strokeWidth="2" strokeDasharray="3 2" fill="none" />
              <rect x="92" y="78" width="26" height="6" rx="2" fill={C.seat} transform="rotate(20 96 81)" />
            </>
          ) : (
            <>
              <path d="M96 24 V74 M114 24 V74" stroke={C.steel} strokeWidth="2" strokeDasharray="3 2" />
              <rect x="92" y="74" width="26" height="6" rx="2" fill={C.seat} />
            </>
          )}
        </>
      )
    case 'snowbox':
      return (
        <>
          <rect width="160" height="120" fill={C.sky} />
          <rect y="88" width="160" height="32" fill={C.pave} />
          <rect x="46" y="52" width="68" height="40" rx="3" fill="#B98B33" />
          {bad ? (
            <>
              <path d="M46 52 L60 26 L128 34 L114 52 Z" fill="#D9A441" />
              <rect x="50" y="56" width="60" height="32" rx="2" fill="#3A3F47" />
            </>
          ) : (
            <>
              <rect x="44" y="44" width="72" height="10" rx="3" fill="#D9A441" />
              <rect x="70" y="66" width="20" height="5" rx="2" fill="#86652A" />
            </>
          )}
        </>
      )
    case 'shade':
      return (
        <>
          <rect width="160" height="120" fill={C.sky} />
          <rect y="92" width="160" height="28" fill={C.pave} />
          <g transform={`rotate(${bad ? 10 : 0} 80 94)`}>
            <rect x="77" y="34" width="6" height="60" fill={C.steel} />
            {bad ? (
              <>
                <path d="M30 40 L80 18 L100 27 L92 44 Z" fill="#7A8FA8" />
                <path d="M106 30 L130 40 L112 44 Z" fill="#7A8FA8" />
              </>
            ) : (
              <path d="M30 40 L80 18 L130 40 Z" fill="#7A8FA8" />
            )}
          </g>
        </>
      )
  }
}

/**
 * 사진 자리.
 * - media(서버 사진) 또는 src(앱에 넣은 시연용 사진 파일)가 있으면 실제 사진을 보여 준다.
 * - 둘 다 없으면 지금처럼 시설 그림을 그린다 (시연용).
 * 실제 사진의 AI 문제 위치는 boxes(서버 findings의 bbox 목록)로 그린다. 그림일 때는 box로 고정 위치를 쓴다.
 */
export function Photo({
  kind,
  bad,
  box = false,
  className = '',
  label,
  media,
  src,
  boxes,
}: {
  kind: Kind
  bad: boolean
  /** AI가 문제를 찾은 영역 표시 (그림용) */
  box?: boolean
  className?: string
  label?: string
  media?: MediaRef | null
  src?: string
  /** AI가 문제를 찾은 영역 (실제 사진용, 0~1 비율 bbox 목록) */
  boxes?: (number[] | null)[]
}) {
  if (media || src) return <RealPhoto media={media} src={src} boxes={boxes} className={className} label={label} />
  return <DrawnPhoto kind={kind} bad={bad} box={box} className={className} label={label} />
}

function RealPhoto({ media, src, boxes, className, label }: { media?: MediaRef | null; src?: string; boxes?: (number[] | null)[]; className: string; label?: string }) {
  const m = useMedia(media, src)
  // 사진 원래 비율. 상자 표시가 어긋나지 않도록 사진을 자르지 않고(contain) 사진 크기에 맞춘 틀 안에 상자를 그린다
  const [ratio, setRatio] = useState<number | null>(null)
  const rects = (boxes ?? []).map(bboxToRect).filter((r) => r !== null)
  const note =
    m.status === 'loading' ? '사진 불러오는 중' : m.status === 'blocked' ? (m.privacy === 'REJECTED' ? '개인정보 문제로 볼 수 없는 사진' : '개인정보 검토 중인 사진') : m.status === 'error' ? '사진을 불러오지 못했어요' : ''
  return (
    <div
      className={`relative grid aspect-[4/3] place-items-center overflow-hidden rounded-[10px] bg-[#1d232c] ${className}`}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      {m.status === 'ready' ? (
        <div className="relative" style={ratio ? { aspectRatio: ratio, ...(ratio >= 4 / 3 ? { width: '100%' } : { height: '100%' }) } : { width: '100%', height: '100%' }}>
          <img
            src={m.url}
            alt=""
            draggable={false}
            onLoad={(e) => setRatio(e.currentTarget.naturalWidth / e.currentTarget.naturalHeight || null)}
            className="block size-full object-contain"
          />
          {ratio &&
            rects.map((r, i) => <span key={i} className="pointer-events-none absolute rounded-[3px] border-2 border-dashed border-[#E5484D]" style={r} />)}
        </div>
      ) : (
        <span className="px-2 text-center text-[11px] leading-tight font-semibold text-white/70">{note}</span>
      )}
    </div>
  )
}

function DrawnPhoto({ kind, bad, box, className, label }: { kind: Kind; bad: boolean; box: boolean; className: string; label?: string }) {
  const clip = useId()
  const [bx, by, bw, bh] = BOX[kind]
  return (
    <svg
      viewBox="0 0 160 120"
      className={`block ${className}`}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <clipPath id={clip}>
        <rect width="160" height="120" rx="10" />
      </clipPath>
      <g clipPath={`url(#${clip})`}>
        <Scene kind={kind} bad={bad} />
        {box && bad && (
          <g>
            <rect x={bx} y={by} width={bw} height={bh} rx="3" fill="none" stroke="#E5484D" strokeWidth="2" strokeDasharray="5 3" />
          </g>
        )}
      </g>
    </svg>
  )
}

// 아직 찍지 않은 사진 자리
export function EmptyPhoto({ className = '', text = '아직 없음' }: { className?: string; text?: string }) {
  return (
    <div className={`grid aspect-[4/3] place-items-center rounded-[10px] border border-dashed border-line bg-bg text-sm text-mute ${className}`}>
      {text}
    </div>
  )
}
