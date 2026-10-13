import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { ISSUE_STATUS_NAME, type IssueStatus } from './data'

// 담당자 화면 공용 UI 조각

export function Button({
  children,
  onClick,
  variant = 'secondary',
  size = 'md',
  disabled,
  type = 'button',
}: {
  children: ReactNode
  onClick?: () => void
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  size?: 'sm' | 'md'
  disabled?: boolean
  type?: 'button' | 'submit'
}) {
  const v = {
    primary: 'bg-brand text-white shadow-[0_4px_12px_rgba(47,107,235,0.25)] hover:bg-brand-700 disabled:bg-line-strong disabled:text-mute disabled:shadow-none',
    secondary: 'border border-line-strong bg-surface text-ink hover:border-sub hover:bg-bg disabled:text-mute',
    danger: 'bg-g3 text-white hover:bg-g3-ink disabled:bg-line-strong',
    ghost: 'text-sub hover:bg-bg hover:text-ink',
  }[variant]
  const s = size === 'sm' ? 'h-9 px-3 text-[14px]' : 'h-10 px-4 text-[15px]'
  return (
    <button type={type} onClick={onClick} disabled={disabled} className={`inline-flex flex-none items-center justify-center gap-1.5 rounded-full font-bold whitespace-nowrap transition-colors ${s} ${v}`}>
      {children}
    </button>
  )
}

const STATUS_STYLE: Record<IssueStatus, string> = {
  OPEN: 'bg-g2-soft text-g2-ink',
  REPAIR_REQUESTED: 'bg-bg text-sub',
  IN_REPAIR: 'bg-brand-50 text-brand',
  AWAITING_VERIFICATION: 'bg-g2-soft text-g2-ink',
  CLOSED: 'bg-g0-soft text-g0-ink',
}

export function StatusBadge({ s }: { s: IssueStatus }) {
  return <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-bold whitespace-nowrap ${STATUS_STYLE[s]}`}>{ISSUE_STATUS_NAME[s]}</span>
}

export function Pill({ children, tone = 'gray' }: { children: ReactNode; tone?: 'gray' | 'brand' | 'red' | 'green' | 'amber' }) {
  const t = {
    gray: 'bg-bg text-sub',
    brand: 'bg-brand-50 text-brand',
    red: 'bg-g3-soft text-g3-ink',
    green: 'bg-g0-soft text-g0-ink',
    amber: 'bg-g2-soft text-g2-ink',
  }[tone]
  return <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-bold whitespace-nowrap ${t}`}>{children}</span>
}

export function Card({ children, className = '', title, action }: { children: ReactNode; className?: string; title?: ReactNode; action?: ReactNode }) {
  return (
    <section className={`rounded-[20px] bg-surface shadow-card ${className}`}>
      {title && (
        <div className="flex items-center justify-between gap-3 border-b border-line px-5 py-4">
          <h2 className="m-0 text-[17px] font-extrabold">{title}</h2>
          {action}
        </div>
      )}
      {children}
    </section>
  )
}

/** 페이지 제목. 설명(sub)은 한 줄로 짧게, 자세한 안내(help)는 제목 옆 "?"를 눌러 펼친다 */
export function PageHead({ title, sub, help, right }: { title: string; sub?: string; help?: string; right?: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <h1 className="m-0 text-[28px] font-extrabold tracking-tight">{title}</h1>
          {help && (
            <button
              type="button"
              onClick={() => setOpen(!open)}
              aria-expanded={open}
              aria-label={`${title} 도움말`}
              title={help}
              className="grid size-7 place-items-center rounded-full border border-line-strong bg-surface text-[13px] font-bold text-sub hover:border-sub aria-expanded:border-brand aria-expanded:bg-brand-50 aria-expanded:text-brand"
            >
              ?
            </button>
          )}
        </div>
        {sub && <p className="m-0 mt-1.5 text-sm text-sub">{sub}</p>}
        {help && open && <p className="m-0 mt-2 max-w-3xl rounded-xl bg-surface px-3.5 py-2.5 text-sm text-sub shadow-card">{help}</p>}
      </div>
      {right && <div className="flex flex-wrap items-center gap-2">{right}</div>}
    </header>
  )
}

/**
 * 목록 옆 상세 칸.
 * 넓은 화면(1280px 이상): 목록 오른쪽에 붙어 있는 칸.
 * 그보다 좁은 화면: 항목을 고르면 오른쪽에서 밀려 나오는 창(휴대폰은 화면 전체)으로 열리고 [← 목록으로]로 닫는다.
 */
export function DetailPane({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    if (!open) return
    const k = (e: KeyboardEvent) => e.key === 'Escape' && window.innerWidth < 1280 && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [open, onClose])
  return (
    <div
      className={`${open ? 'fixed inset-0 z-40 flex justify-end bg-ink/40' : 'hidden'} xl:static xl:z-auto xl:block xl:bg-transparent`}
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="h-full w-full max-w-[640px] overflow-y-auto bg-bg p-4 shadow-pop sm:p-5 xl:sticky xl:top-16 xl:h-auto xl:max-w-none xl:overflow-visible xl:bg-transparent xl:p-0 xl:shadow-none">
        <button
          type="button"
          onClick={onClose}
          className="mb-3 inline-flex h-10 items-center gap-1.5 rounded-full border border-line-strong bg-surface px-4 text-[15px] font-bold text-ink xl:hidden"
        >
          ← 목록으로
        </button>
        {children}
      </div>
    </div>
  )
}
/** 요약 숫자 카드 아이콘. 카드 내용을 한눈에 알아볼 수 있게 뜻이 있는 그림을 쓴다 */
export type StatIcon = 'route' | 'attend' | 'walk' | 'home' | 'people' | 'camera' | 'review' | 'alert' | 'wrench' | 'check' | 'repeat'

const STAT_ICON: Record<StatIcon, ReactNode> = {
  // 순회: 지도 위 경로
  route: <path d="M5 18c2.5 0 3-4 6-4s3.5 4 7 4M7 9.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm0 0V12M17 7.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm0 0v3" />,
  // 출석: 체크가 붙은 사람
  attend: <path d="M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM3.5 20c0-3.6 2.9-6 6.5-6 1.4 0 2.7.4 3.7 1M15 18l2 2 4-4.5" />,
  // 활동 중: 걷는 사람
  walk: <path d="M13 5.5a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6ZM10 21l2-6 3 3v3M8.5 12.5l2-4.5 3.5 1.5 2 3.5M12 8l-1.5 6" />,
  // 복귀: 집으로 돌아오는 화살표
  home: <path d="M4 11.5 12 5l8 6.5V20h-5v-5H9v5H4Z" />,
  // 참여 인원: 두 사람
  people: <path d="M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7 0a2.5 2.5 0 1 0 0-5M3 19.5c0-3 2.7-5 6-5s6 2 6 5M16 14.5c2.6 0 5 1.6 5 4.5" />,
  // 사진
  camera: <path d="M4 8h3l1.5-2h7L17 8h3v11H4ZM12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" />,
  // 검토: 돋보기
  review: <path d="M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13ZM15.5 15.5 20 20M8 10.5l2 2 3.5-3.5" />,
  // 신규 사건: 경고 삼각형
  alert: <path d="M12 4 21 19.5H3ZM12 10v4.5M12 17.2v.3" />,
  // 보수: 공구
  wrench: <path d="M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.5 2.5-2.5-.5-.5-2.5Z" />,
  // 종결: 원 안 체크
  check: <path d="M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17ZM8 12.2l2.8 2.8L16.5 9.3" />,
  // 재보수: 되돌아가는 화살표
  repeat: <path d="M4 12a8 8 0 0 1 13.7-5.6L20 8.5M20 4v4.5h-4.5M20 12a8 8 0 0 1-13.7 5.6L4 15.5M4 20v-4.5h4.5" />,
}

/**
 * 요약 숫자 카드.
 * onClick이 있으면 눌러서 목록을 거르는 카드가 된다(active = 지금 거르는 중).
 * todo: 처리할 일이 있으면 호박색 테두리로 강조한다.
 */
export function Stat({
  label,
  value,
  sub,
  tone,
  icon,
  onClick,
  active = false,
  todo = false,
}: {
  label: string
  value: ReactNode
  sub?: string
  tone?: 'red' | 'amber' | 'green'
  icon?: StatIcon
  onClick?: () => void
  active?: boolean
  todo?: boolean
}) {
  const c = tone ? { red: 'bg-g3-soft text-g3', amber: 'bg-g2-soft text-g2-ink', green: 'bg-g0-soft text-g0' }[tone] : 'bg-brand-50 text-brand'
  const ring = active ? 'ring-2 ring-brand' : todo ? 'ring-2 ring-g2/70' : ''
  const body = (
    <>
      <span className={`mt-0.5 hidden size-11 flex-none place-items-center rounded-2xl sm:grid ${c}`} aria-hidden>
        {icon ? (
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
            {STAT_ICON[icon]}
          </svg>
        ) : (
          <i className="size-3 rounded-full bg-current" />
        )}
      </span>
      <div className="min-w-0">
        <p className="m-0 text-sm font-semibold text-sub">{label}</p>
        <p className="m-0 mt-0.5 text-[28px] leading-tight font-extrabold tabular-nums">{value}</p>
        {sub && <p className="m-0 mt-1 text-xs text-mute">{sub}</p>}
      </div>
    </>
  )
  if (onClick)
    return (
      <button
        type="button"
        onClick={onClick}
        aria-pressed={active}
        className={`flex items-start gap-3.5 rounded-[20px] bg-surface px-5 py-4 text-left shadow-card transition-shadow hover:shadow-pop ${ring}`}
      >
        {body}
      </button>
    )
  return <div className={`flex items-start gap-3.5 rounded-[20px] bg-surface px-5 py-4 shadow-card ${ring}`}>{body}</div>
}

export function Tabs<T extends string>({ value, options, onChange }: { value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div className="inline-flex rounded-full bg-surface p-1 shadow-card" role="tablist">
      {options.map(([v, l]) => (
        <button
          key={v}
          type="button"
          role="tab"
          aria-selected={value === v}
          onClick={() => onChange(v)}
          // 이름이 " 0"으로 끝나는(0건) 탭은 흐리게
          className={`h-9 rounded-full px-4 text-[14px] font-bold whitespace-nowrap aria-selected:bg-brand aria-selected:text-white ${/ 0$/.test(l) && value !== v ? 'text-mute/60' : 'text-sub'}`}
        >
          {l}
        </button>
      ))}
    </div>
  )
}

export function Field({ label, children, hint, required }: { label: string; children: ReactNode; hint?: string; required?: boolean }) {
  return (
    <label className="grid content-start gap-1.5 text-sm">
      <span className="font-semibold text-ink">
        {label}
        {required && <span className="ml-0.5 text-g3">*</span>}
      </span>
      {children}
      {hint && <span className="text-xs text-mute">{hint}</span>}
    </label>
  )
}

export const inputCls = 'h-10 w-full rounded-xl border border-line-strong bg-surface px-3 text-[15px] outline-none focus:border-brand focus:ring-2 focus:ring-brand-100'

export function Modal({ title, children, onClose, footer, width = 'max-w-lg' }: { title: string; children: ReactNode; onClose: () => void; footer?: ReactNode; width?: string }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-ink/40 p-4" role="dialog" aria-modal="true" aria-label={title} onClick={onClose}>
      {/* 제목과 아래 버튼은 고정하고 가운데 내용만 스크롤 (내용이 길어도 저장 버튼이 늘 보이게) */}
      <div className={`flex w-full ${width} max-h-[90vh] flex-col overflow-hidden rounded-[24px] bg-surface shadow-pop`} onClick={(e) => e.stopPropagation()}>
        <div className="flex flex-none items-center justify-between border-b border-line px-5 py-4">
          <h2 className="m-0 text-[18px] font-extrabold">{title}</h2>
          <button type="button" onClick={onClose} aria-label="닫기" className="grid size-8 place-items-center rounded-lg text-mute hover:bg-bg">
            ✕
          </button>
        </div>
        <div className="grid min-h-0 gap-4 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex flex-none justify-end gap-2 border-t border-line px-5 py-3">{footer}</div>}
      </div>
    </div>
  )
}

/** 알림 창은 모두 한 묶음에 쌓는다. 여러 개가 뜨면 겹치지 않고 아래로 차례로 붙는다 */
function toastStack() {
  let el = document.getElementById('toast-stack')
  if (!el) {
    el = document.createElement('div')
    el.id = 'toast-stack'
    // 오른쪽 아래에 쌓는다 (페이지 오른쪽 위의 주요 버튼을 가리지 않게)
    el.className = 'pointer-events-none fixed right-4 bottom-4 z-50 flex w-[380px] max-w-[calc(100vw-2rem)] flex-col gap-3'
    document.body.appendChild(el)
  }
  return el
}

/** 알림은 10초 동안 보였다가 서서히 사라진다 */
const TOAST_MS = 10_000
/** 나타나고 사라지는 데 걸리는 시간 */
const TOAST_FADE_MS = 600

export function Toast({ children, onClose, tone = 'red' }: { children: ReactNode; onClose: () => void; tone?: 'red' | 'info' | 'done' }) {
  // 화면이 다시 그려져도 타이머가 처음부터 다시 시작되지 않도록 최신 닫기 함수만 따로 보관
  const close = useRef(onClose)
  close.current = onClose
  const [shown, setShown] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const leave = useCallback(() => setLeaving(true), [])
  useEffect(() => {
    const enter = requestAnimationFrame(() => setShown(true))
    const t = setTimeout(leave, TOAST_MS - TOAST_FADE_MS)
    return () => {
      cancelAnimationFrame(enter)
      clearTimeout(t)
    }
  }, [leave])
  // 사라지는 효과가 끝난 뒤에 실제로 닫는다
  useEffect(() => {
    if (!leaving) return
    const t = setTimeout(() => close.current(), TOAST_FADE_MS)
    return () => clearTimeout(t)
  }, [leaving])
  const visible = shown && !leaving
  return createPortal(
    <div
      role="alert"
      style={{ transitionDuration: `${TOAST_FADE_MS}ms` }}
      className={`pointer-events-auto rounded-[18px] p-4 shadow-pop transition-[opacity,transform] ease-out motion-reduce:transition-none ${visible ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-0'} ${tone === 'red' ? 'border-l-4 border-g3 bg-surface' : tone === 'info' ? 'border-l-4 border-brand bg-surface' : 'border-l-4 border-g0 bg-surface'}`}
    >
      <div className="flex gap-3">
        <div className="min-w-0 flex-1">{children}</div>
        <button type="button" onClick={leave} aria-label="닫기" className="h-6 flex-none text-mute">
          ✕
        </button>
      </div>
    </div>,
    toastStack(),
  )
}
export function Empty({ children }: { children: ReactNode }) {
  return <p className="m-0 px-4 py-10 text-center text-sm text-mute">{children}</p>
}

/** 목록에서 아무것도 고르지 않았을 때 오른쪽 상세 칸 (목록 높이만큼 늘어나 빈 칸이 덜 어색하게) */
export function PickHint({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-[420px] place-items-center rounded-[20px] border-2 border-dashed border-line-strong/70 bg-surface/50 p-6 text-center">
      <div>
        <span className="mx-auto mb-3 grid size-14 place-items-center rounded-2xl bg-brand-50 text-brand" aria-hidden>
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
            <path d="M4 5h9M4 10h9M4 15h5M15 13l5 2.2-2.2.9-.9 2.2Z" />
          </svg>
        </span>
        <p className="m-0 text-[15px] font-bold text-ink">{children}</p>
        <p className="m-0 mt-1 text-sm text-mute">항목을 누르면 여기에 자세한 내용이 열립니다.</p>
      </div>
    </div>
  )
}

export function maskPhone(p: string) {
  return p.replace(/^(\d{3})-(\d{3,4})-(\d{4})$/, '$1-****-$3')
}
