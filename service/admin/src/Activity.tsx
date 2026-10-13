import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { KIND_NAME } from './Photo'
import {
  ABORT_NAME,
  AI_SUPPORTED,
  ATTENDANCE_NAME,
  COURSES,
  FACILITY_TYPE,
  ME,
  PATROL_STATUS_NAME,
  SEASONAL,
  course,
  fac,
  pName,
  type Attendance,
  type Member,
  type Participant,
  type Patrol,
  type Reinspection,
  type Team,
} from './data'
import { Button, Card, Empty, Field, Modal, PageHead, Pill, Stat, Toast, inputCls, maskPhone } from './ui'
import { nowAt } from './util'
import { REAL_BACKEND } from './api/client'
import * as real from './api/real'

// 활동 담당자 화면 (API 5·8절)
/** 오늘 ('MM/DD'). 실제 백엔드면 실제 오늘(한국 시간), 아니면 시연 기준일 */
export const TODAY = REAL_BACKEND ? real.md(real.todayKst()) : '10/12'
/** 지금 시각 'HH:mm': 복귀 예정 시각 지남 표시용 (시연은 10:40 고정) */
const nowHm = () => (REAL_BACKEND ? nowAt().slice(6) : '10:40')

const STATUS_TONE: Record<Patrol['status'], 'gray' | 'brand' | 'amber' | 'green' | 'red'> = {
  ASSIGNED: 'gray',
  IN_PROGRESS: 'brand',
  RETURNING: 'amber',
  COMPLETED: 'green',
  ABORTED: 'amber',
  CANCELLED: 'gray',
}

export function pendingActivityCount(patrols: Patrol[]) {
  return patrols.filter((p) => p.date === TODAY).reduce((n, p) => n + todoOf(p).length, 0)
}

const isActive = (p: Patrol) => p.status === 'IN_PROGRESS' || p.status === 'RETURNING'
const isFinished = (p: Patrol) => p.status === 'COMPLETED' || p.status === 'ABORTED' || p.status === 'CANCELLED'
/** 실제 활동 기록(개인 시작·복귀·촬영)이 있는지. 있으면 '활동 없이 해산'을 쓸 수 없다 */
const hasActivity = (p: Patrol) => p.members.some((m) => m.startedAt || m.returnedAt) || p.progress.captured + p.progress.skipped + p.progress.review > 0

/** 담당자가 지금 해야 할 일 (화면 강조용) */
function todoOf(p: Patrol): string[] {
  const out: string[] = []
  if (isFinished(p)) return out
  const req = p.members.filter((m) => m.attendance === 'REQUESTED').length
  if (req) out.push(`출석 확인 ${req}명`)
  if (p.status === 'ASSIGNED' && p.members.some((m) => m.attendance === 'ABSENT') && p.members.filter((m) => m.attendance !== 'ABSENT').length < 2) out.push('결원 재편성')
  if (p.status === 'RETURNING') {
    const n = p.members.filter((m) => m.attendance !== 'ABSENT' && !m.returned).length
    if (n) out.push(`복귀 확인 ${n}명`)
  }
  return out
}

const teamName = (teams: Team[], id: string) => teams.find((t) => t.id === id)?.name ?? '-'

/** 조원 진행 단계를 작은 칩으로: 끝난 단계는 초록, 기다리는 단계는 호박색, 아직인 단계는 회색 */
function MemberSteps({ mb, warnNoStart }: { mb: Member; warnNoStart: boolean }) {
  const chip = (tone: 'done' | 'wait' | 'none', text: string) => (
    <span
      className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[13px] font-semibold whitespace-nowrap ${
        tone === 'done' ? 'bg-g0-soft text-g0-ink' : tone === 'wait' ? 'bg-g2-soft text-g2-ink' : 'border border-line bg-surface text-mute'
      }`}
    >
      {text}
    </span>
  )
  const fix = (s?: string) => (s === 'ADMIN_CORRECTION' ? ' (정정)' : '')
  if (mb.attendance !== 'PRESENT') {
    return chip(mb.attendance === 'REQUESTED' ? 'wait' : 'none', ATTENDANCE_NAME[mb.attendance])
  }
  return (
    <>
      {chip('done', `✓ 출석${mb.proxyReason ? '(대리)' : ''}`)}
      {mb.startedAt ? chip('done', `시작 ${mb.startedAt}${fix(mb.startSource)}`) : chip(warnNoStart ? 'wait' : 'none', '시작 기록 없음')}
      {mb.returnedAt && chip(mb.returned ? 'done' : 'wait', `복귀 ${mb.returnedAt}${fix(mb.returnSource)}${mb.returned ? ' ✓' : ' · 확인 전'}`)}
      {mb.returned && !mb.returnedAt && chip('done', '복귀 확인 ✓')}
    </>
  )
}

/** 자주 쓰지 않는 기능을 접어 두는 ⋯ 메뉴 */
function RowMenu({ label, items }: { label: string; items: [string, () => void][] }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!open) return
    const down = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false)
    const key = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', down)
    document.addEventListener('keydown', key)
    return () => {
      document.removeEventListener('mousedown', down)
      document.removeEventListener('keydown', key)
    }
  }, [open])
  return (
    <span ref={ref} className="relative">
      <button
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="grid size-9 place-items-center rounded-full text-sub hover:bg-surface hover:text-ink aria-expanded:bg-surface aria-expanded:text-ink"
      >
        <svg viewBox="0 0 24 24" className="size-5" fill="currentColor" aria-hidden>
          <circle cx="5" cy="12" r="1.8" />
          <circle cx="12" cy="12" r="1.8" />
          <circle cx="19" cy="12" r="1.8" />
        </svg>
      </button>
      {open && (
        <span role="menu" className="absolute top-full right-0 z-20 mt-1 grid min-w-32 rounded-xl border border-line bg-surface p-1 shadow-pop">
          {items.map(([t, fn]) => (
            <button
              key={t}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false)
                fn()
              }}
              className="rounded-lg px-3 py-2 text-left text-sm font-semibold whitespace-nowrap hover:bg-bg"
            >
              {t}
            </button>
          ))}
        </span>
      )}
    </span>
  )
}

type TodayFilter = 'all' | 'attend' | 'active' | 'return'
const FILTER_NAME: Record<TodayFilter, string> = { all: '전체', attend: '출석 확인 대기', active: '활동 중', return: '복귀 확인 대기' }
const FILTER_FN: Record<TodayFilter, (p: Patrol) => boolean> = {
  all: () => true,
  attend: (p) => p.members.some((m) => m.attendance === 'REQUESTED'),
  active: (p) => p.status === 'IN_PROGRESS',
  return: (p) => p.status === 'RETURNING',
}

type ReturnInput = { returnedAt: string; reason: 'NORMAL' | 'ABORT'; abort?: 'HEALTH' | 'DEVICE' | 'OTHER' }

/* ---------------- 오늘 활동 현황 ---------------- */

export function Today({
  patrols,
  setPatrols,
  teams,
  participants,
}: {
  patrols: Patrol[]
  setPatrols: Dispatch<SetStateAction<Patrol[]>>
  teams: Team[]
  participants: Participant[]
}) {
  const today = patrols.filter((p) => p.date === TODAY)
  const todayLabel = REAL_BACKEND ? (() => {
    const d = real.todayKst()
    return `${Number(d.slice(0, 4))}년 ${Number(d.slice(5, 7))}월 ${Number(d.slice(8, 10))}일 (${'일월화수목금토'[new Date(`${d}T00:00:00+09:00`).getUTCDay()]})`
  })() : '2026년 10월 12일 (월)'
  // 할 일이 있는 조를 위로, 끝난 조를 아래로 (표시 순서만 바꿈)
  const rank = (p: Patrol) => (todoOf(p).length ? 0 : isActive(p) ? 1 : p.status === 'ASSIGNED' ? 2 : 3)
  const ordered = [...today].sort((a, b) => rank(a) - rank(b) || a.start.localeCompare(b.start))
  const [filter, setFilter] = useState<TodayFilter>('all')
  const toggle = (f: TodayFilter) => setFilter((cur) => (cur === f ? 'all' : f))
  const shown = ordered.filter(FILTER_FN[filter])
  const returning = today.filter((p) => p.status === 'RETURNING').length
  const [followFor, setFollowFor] = useState<Patrol | null>(null)
  const [replaceFor, setReplaceFor] = useState<{ p: Patrol; memberId: string } | null>(null)
  const [proxyFor, setProxyFor] = useState<{ p: Patrol; memberId: string } | null>(null)
  const [returnFor, setReturnFor] = useState<{ p: Patrol; memberId: string } | null>(null)
  const [timeFor, setTimeFor] = useState<{ p: Patrol; memberId: string } | null>(null)
  const [cancelFor, setCancelFor] = useState<Patrol | null>(null)
  const [toast, setToast] = useState('')
  const log = (text: string) => ({ at: nowAt(), who: ME.name, text })

  function patch(id: string, fn: (p: Patrol) => Patrol) {
    setPatrols((l) => l.map((p) => (p.id === id ? fn(p) : p)))
  }
  function setAtt(p: Patrol, memberId: string, attendance: Attendance, proxyReason?: string) {
    // 실제 백엔드: 출석 요청이 없으면 대리 출석 사유가 필요하다 (결석은 사유 없이)
    if (p.real) return void real.run(() => real.confirmAttendance(p, memberId, attendance === 'ABSENT' ? 'ABSENT' : 'PRESENT', proxyReason))
    patch(p.id, (x) => ({ ...x, members: x.members.map((m) => (m.id === memberId ? { ...m, attendance, proxyReason } : m)) }))
  }
  /** 출발 확인: 확인자·시각만 기록. 개인 시작과 순서·조건 없이 독립 (상태를 바꾸지 않음) */
  function depart(p: Patrol) {
    if (p.real) {
      real.run(() => real.confirmDeparture(p)).then((ok) => ok && setToast(`${teamName(teams, p.teamId)} 출발 확인을 기록했습니다. 어르신 개인 시작과는 별개입니다.`))
      return
    }
    const hm = nowAt().slice(6)
    patch(p.id, (x) => ({ ...x, departureConfirmedAt: hm }))
    setToast(`${teamName(teams, p.teamId)} 출발 확인을 기록했습니다. 어르신 개인 시작과는 별개입니다.`)
  }
  /** 복귀 확인: 앱 복귀 요청이 없어도 가능. 정상/중도와 사유를 함께 기록. 전원 확인되면 완료/중도 종료 */
  function confirmReturn(p: Patrol, memberId: string, input: ReturnInput) {
    if (p.real) return void real.run(() => real.confirmReturn(p, memberId, real.todayAt(input.returnedAt), input.reason, input.abort))
    patch(p.id, (x) => {
      const members = x.members.map((m) => (m.id === memberId ? { ...m, returned: true, returnedAt: input.returnedAt, returnSource: 'MANAGER_CONFIRMATION' as const } : m))
      const all = members.filter((m) => m.attendance !== 'ABSENT').every((m) => m.returned)
      const returnReason = x.returnReason ?? input.reason
      const abortReason = returnReason === 'ABORT' ? (x.abortReason ?? input.abort) : undefined
      const status = all ? (returnReason === 'ABORT' ? 'ABORTED' : 'COMPLETED') : x.status === 'IN_PROGRESS' ? 'RETURNING' : x.status
      return { ...x, members, returnReason, abortReason, status }
    })
  }
  function correctTime(p: Patrol, memberId: string, startedAt: string | undefined, returnedAt: string | undefined, reason: string) {
    if (p.real) return void real.run(() => real.correctTime(p, memberId, startedAt && real.todayAt(startedAt), returnedAt && real.todayAt(returnedAt), reason))
    patch(p.id, (x) => ({
      ...x,
      // 첫 유효 시작을 담당자가 입력하면 활동 중으로. 종료 상태는 다시 열지 않음
      status: x.status === 'ASSIGNED' && startedAt ? 'IN_PROGRESS' : x.status,
      members: x.members.map((m) =>
        m.id === memberId
          ? {
              ...m,
              ...(startedAt ? { startedAt, startSource: 'ADMIN_CORRECTION' as const } : {}),
              ...(returnedAt ? { returnedAt, returnSource: 'ADMIN_CORRECTION' as const } : {}),
            }
          : m,
      ),
      followUps: [...x.followUps, log(`[시각 정정] ${pName(memberId)} ${startedAt ? `시작 ${startedAt}` : ''}${startedAt && returnedAt ? ' · ' : ''}${returnedAt ? `복귀 ${returnedAt}` : ''} · ${reason}`)],
    }))
  }

  const waitingAtt = today.reduce((n, p) => n + p.members.filter((m) => m.attendance === 'REQUESTED').length, 0)
  return (
    <>
      <PageHead
        title="오늘 활동 현황"
        sub={todayLabel}
        help="출석은 어르신 요청을 담당자가 확정합니다. 출발 확인과 어르신 개인 시작은 서로 기다리지 않는 별개 기록이며, 첫 개인 시작부터 조가 활동 중이 됩니다. 결석이 생기면 출발 전에 다시 편성하세요. 복귀는 앱 요청이 없어도, 사진 전송이 남아 있어도 담당자가 확인할 수 있으며 모두 확인하면 활동이 끝납니다. 복귀가 늦어도 자동 알림은 보내지 않습니다. 시각 정정은 조원 줄 끝 ⋯ 메뉴에 있습니다."
      />
      {/* 숫자 카드를 누르면 그 조만 걸러 본다. 처리할 일이 있는 카드는 호박색 테두리 */}
      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat icon="route" label="오늘 순회" value={`${today.filter((p) => p.status !== 'CANCELLED').length}개 조`} onClick={() => setFilter('all')} />
        <Stat icon="attend" label="출석 확인 대기" value={`${waitingAtt}명`} tone={waitingAtt ? 'amber' : undefined} todo={waitingAtt > 0} onClick={() => toggle('attend')} active={filter === 'attend'} />
        <Stat icon="walk" label="활동 중" value={`${today.filter((p) => p.status === 'IN_PROGRESS').length}개 조`} sub={`쉬는 중 ${today.filter((p) => p.paused && p.status === 'IN_PROGRESS').length}`} onClick={() => toggle('active')} active={filter === 'active'} />
        <Stat icon="home" label="복귀 확인 대기" value={`${returning}개 조`} tone="amber" todo={returning > 0} onClick={() => toggle('return')} active={filter === 'return'} />
      </div>
      {filter !== 'all' && (
        <p className="m-0 mb-4 flex flex-wrap items-center gap-2 text-sm text-sub">
          <b className="text-ink">{FILTER_NAME[filter]}</b> 조만 보는 중 · {shown.length}개 조
          <Button size="sm" variant="secondary" onClick={() => setFilter('all')}>
            전체 보기
          </Button>
        </p>
      )}
      {shown.length === 0 && <Card><Empty>해당하는 조가 없습니다.</Empty></Card>}
      {/* 할 일이 있는 조를 "지금 할 일"로 따로 모아 위에 둔다 */}
      {(
        [
          ['지금 할 일', shown.filter((p) => todoOf(p).length > 0)],
          ['그 밖의 순회', shown.filter((p) => todoOf(p).length === 0)],
        ] as const
      ).map(([label, list]) =>
        list.length === 0 ? null : (
          <div key={label} className="mb-6">
            <h2 className="m-0 mb-3 flex items-center gap-2 text-[15px] font-extrabold text-ink">
              {label === '지금 할 일' && <i className="size-2.5 rounded-full bg-g2" aria-hidden />}
              {label}
              <span className="text-sm font-semibold text-mute">{list.length}개 조</span>
            </h2>
      <div className="grid items-start gap-4 xl:grid-cols-2">
        {list.map((p) => {
          const c = course(p.courseId)
          const total = c.stops.length
          const done = p.progress.captured + p.progress.skipped + p.progress.review
          const late = isActive(p) && !!p.expectedReturn && p.expectedReturn < nowHm()
          const todo = todoOf(p)
          const finished = isFinished(p)
          return (
            <Card
              key={p.id}
              className={todo.length ? 'border-l-4 border-l-g2' : finished ? 'opacity-70' : ''}
              title={
                <span className="flex flex-wrap items-center gap-2">
                  {teamName(teams, p.teamId)} · {c.name}
                  <Pill tone={STATUS_TONE[p.status]}>{PATROL_STATUS_NAME[p.status]}</Pill>
                  {p.paused && p.status === 'IN_PROGRESS' && <Pill tone="amber">쉬는 중</Pill>}
                  {p.returnReason === 'ABORT' && <Pill tone="amber">중도 복귀 · {ABORT_NAME[p.abortReason ?? 'OTHER']}</Pill>}
                  {p.reinspect.length > 0 && <Pill tone="gray">재점검 {p.reinspect.length}곳</Pill>}
                </span>
              }
              action={
                <Button size="sm" variant="ghost" onClick={() => setFollowFor(p)}>
                  후속 기록
                </Button>
              }
            >
              {p.status === 'CANCELLED' ? (
                <div className="grid gap-1 p-4 text-sm">
                  <p className="m-0 font-semibold text-sub">취소됨 · {p.cancellationReason}</p>
                  <p className="m-0 text-xs text-mute">
                    {c.start} · 예정 {p.start}~{p.expectedReturn} · 조원 {p.members.map((m) => pName(m.id)).join(', ')}
                  </p>
                </div>
              ) : (
              <div className="grid gap-3 p-4">
                {todo.length > 0 && (
                  <p className="m-0 flex flex-wrap items-center gap-1.5 rounded-xl bg-g2-soft px-3 py-2 text-sm font-bold text-g2-ink">
                    할 일
                    {todo.map((x) => (
                      <span key={x} className="rounded-full bg-surface px-2.5 py-0.5 text-[13px] font-bold text-g2-ink">
                        {x}
                      </span>
                    ))}
                  </p>
                )}
                <div className="grid gap-0.5 text-sm text-sub">
                  <p className="m-0 flex flex-wrap items-center gap-x-2 gap-y-1">
                    {c.start} · 예정 {p.start}~{p.expectedReturn}
                    {late && <Pill tone="amber">예정 시각 지남</Pill>}
                  </p>
                  <p className="m-0">{p.departureConfirmedAt ? `담당자 출발 확인 ${p.departureConfirmedAt}` : <span className="text-mute">담당자 출발 확인 전</span>}</p>
                </div>
                <ul className="m-0 grid list-none gap-1.5 p-0">
                  {p.members.map((mb) => (
                    <li key={mb.id} className="flex min-h-11 flex-wrap items-center justify-between gap-2 rounded-lg bg-bg px-3 py-1.5 text-sm">
                      <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                        <b className="mr-1 font-semibold">{pName(mb.id)}</b>
                        <MemberSteps mb={mb} warnNoStart={isActive(p) || finished} />
                      </span>
                      <span className="ml-auto flex flex-wrap items-center justify-end gap-1.5">
                        {p.status !== 'CANCELLED' && !finished && mb.attendance === 'REQUESTED' && (
                          <>
                            <Button size="sm" variant="primary" onClick={() => setAtt(p, mb.id, 'PRESENT')}>
                              출석 확인
                            </Button>
                            <Button size="sm" variant="ghost" onClick={() => setAtt(p, mb.id, 'ABSENT')}>
                              결석
                            </Button>
                          </>
                        )}
                        {p.status === 'ASSIGNED' && mb.attendance === 'NONE' && (
                          <>
                            <Button size="sm" onClick={() => setProxyFor({ p, memberId: mb.id })}>
                              대리 출석
                            </Button>
                            <Button size="sm" variant="ghost" onClick={() => setAtt(p, mb.id, 'ABSENT')}>
                              결석
                            </Button>
                          </>
                        )}
                        {p.status === 'ASSIGNED' && mb.attendance === 'ABSENT' && !p.real && (
                          <Button size="sm" onClick={() => setReplaceFor({ p, memberId: mb.id })}>
                            결원 교체
                          </Button>
                        )}
                        {isActive(p) && mb.attendance === 'PRESENT' && !mb.returned && (
                          <Button size="sm" variant={p.status === 'RETURNING' ? 'primary' : 'secondary'} onClick={() => setReturnFor({ p, memberId: mb.id })}>
                            복귀 확인
                          </Button>
                        )}
                        {(isActive(p) || p.status === 'COMPLETED' || p.status === 'ABORTED') && mb.attendance === 'PRESENT' && (
                          <RowMenu label={`${pName(mb.id)} 더 보기`} items={[['시각 정정', () => setTimeFor({ p, memberId: mb.id })]]} />
                        )}
                      </span>
                    </li>
                  ))}
                </ul>
                {p.status !== 'ASSIGNED' && (
                  <div>
                    <div className="mb-1 flex justify-between text-xs text-mute">
                      <span>
                        {total}곳 중 {done}곳 처리 · 촬영 {p.progress.captured} · 건너뜀 {p.progress.skipped} · 확인 요청 {p.progress.review}
                        {p.status === 'ABORTED' && ` · 미방문 ${total - done}`}
                      </span>
                    </div>
                    <div className="flex h-2 overflow-hidden rounded-full bg-line">
                      <div className="bg-brand" style={{ width: `${(p.progress.captured / total) * 100}%` }} />
                      {/* 색 규칙: 찍음 = 파랑(진행), 확인 요청 = 호박(처리할 일), 건너뜀 = 회색(정보) */}
                      <div className="bg-g2" style={{ width: `${(p.progress.review / total) * 100}%` }} />
                      <div className="bg-[#aab3bd]" style={{ width: `${(p.progress.skipped / total) * 100}%` }} />
                    </div>
                  </div>
                )}
                {(p.status === 'ASSIGNED' || p.status === 'IN_PROGRESS') && (
                  <div className="flex flex-wrap items-center gap-2">
                    {!p.departureConfirmedAt && (
                      <Button variant="primary" onClick={() => depart(p)}>
                        출발 확인
                      </Button>
                    )}
                    {p.status === 'ASSIGNED' && !hasActivity(p) && (
                      <Button variant="ghost" onClick={() => setCancelFor(p)}>
                        활동 없이 해산
                      </Button>
                    )}
                    <span className="text-xs text-mute">
                      {p.status === 'ASSIGNED' ? '어르신은 출발 확인 없이도 시작할 수 있습니다.' : '출발 확인은 개인 시작과 별도 기록입니다.'}
                    </span>
                  </div>
                )}
                {p.status === 'RETURNING' && <p className="m-0 text-xs text-mute">도착한 조원부터 복귀를 확인하세요.</p>}
                {p.followUps.length > 0 && (
                  <div className="border-t border-line pt-2.5">
                    <p className="m-0 mb-1 flex items-center gap-1.5 text-xs font-bold text-sub">
                      <svg viewBox="0 0 24 24" className="size-3.5" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                        <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
                        <path d="M14 3v5h5M9 13h6M9 17h4" />
                      </svg>
                      최근 기록 {p.followUps.length}건
                    </p>
                    <ul className="m-0 grid list-none gap-0.5 p-0 text-xs text-sub">
                      {p.followUps.map((f, i) => (
                        <li key={i}>
                          <span className="text-mute">{f.at} · {f.who}</span> · {f.text}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
              )}
            </Card>
          )
        })}
      </div>
          </div>
        ),
      )}
      {followFor && (
        <FollowUpModal
          patrol={followFor}
          teams={teams}
          onClose={() => setFollowFor(null)}
          onSave={(text) => {
            patch(followFor.id, (x) => ({ ...x, followUps: [...x.followUps, log(text)] }))
            setFollowFor(null)
          }}
        />
      )}
      {replaceFor && (
        <ReplaceModal
          patrols={patrols}
          participants={participants}
          onClose={() => setReplaceFor(null)}
          onPick={(id) => {
            patch(replaceFor.p.id, (x) => ({ ...x, members: x.members.map((m) => (m.id === replaceFor.memberId ? { id, attendance: 'NONE', returned: false } : m)) }))
            setReplaceFor(null)
          }}
        />
      )}
      {proxyFor && (
        <ReasonModal
          title={`${pName(proxyFor.memberId)} 대리 출석`}
          note="어르신의 출석 요청 없이 담당자가 출석으로 기록합니다. 개인 시작 시각은 자동으로 만들지 않습니다."
          placeholder="예: 휴대폰을 두고 오셔서 센터에서 직접 확인"
          confirm="대리 출석"
          onClose={() => setProxyFor(null)}
          onSave={(reason) => {
            setAtt(proxyFor.p, proxyFor.memberId, 'PRESENT', reason)
            setProxyFor(null)
          }}
        />
      )}
      {cancelFor && (
        <ReasonModal
          title={`${teamName(teams, cancelFor.teamId)} 활동 없이 해산`}
          note="실제 활동 없이 해산한 순회를 취소합니다. 출석·출발 확인 기록은 남고 시작·복귀 시각은 만들지 않습니다."
          placeholder="예: 우천으로 현장에서 활동 없이 해산"
          confirm="해산 처리"
          danger
          onClose={() => setCancelFor(null)}
          onSave={(reason) => {
            if (cancelFor.real) real.run(() => real.cancelPatrol(cancelFor, reason))
            else patch(cancelFor.id, (x) => ({ ...x, status: 'CANCELLED', cancellationReason: reason, followUps: [...x.followUps, log(`[해산] ${reason}`)] }))
            setCancelFor(null)
          }}
        />
      )}
      {returnFor && (
        <ReturnModal
          patrol={returnFor.p}
          memberId={returnFor.memberId}
          onClose={() => setReturnFor(null)}
          onSave={(input) => {
            confirmReturn(returnFor.p, returnFor.memberId, input)
            setReturnFor(null)
          }}
        />
      )}
      {timeFor && (
        <TimeModal
          patrol={timeFor.p}
          memberId={timeFor.memberId}
          onClose={() => setTimeFor(null)}
          onSave={(s, r, reason) => {
            correctTime(timeFor.p, timeFor.memberId, s, r, reason)
            setTimeFor(null)
          }}
        />
      )}
      {toast && (
        <Toast tone="done" onClose={() => setToast('')}>
          <p className="m-0 text-sm">{toast}</p>
        </Toast>
      )}
    </>
  )
}

/** 사유가 꼭 필요한 짧은 확인 창 (대리 출석, 활동 없이 해산) */
function ReasonModal({ title, note, placeholder, confirm, danger, onClose, onSave }: { title: string; note: string; placeholder: string; confirm: string; danger?: boolean; onClose: () => void; onSave: (reason: string) => void }) {
  const [reason, setReason] = useState('')
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant={danger ? 'danger' : 'primary'} disabled={!reason.trim()} onClick={() => onSave(reason.trim())}>
            {confirm}
          </Button>
        </>
      }
    >
      <p className="m-0 text-sm text-sub">{note}</p>
      <Field label="사유" required>
        <input value={reason} onChange={(e) => setReason(e.target.value.slice(0, 500))} className={inputCls} placeholder={placeholder} />
      </Field>
    </Modal>
  )
}

/** 복귀 확인: 정상/중도 복귀와 사유를 함께 기록 (앱 복귀 요청이 있으면 그 값으로 채움) */
function ReturnModal({ patrol, memberId, onClose, onSave }: { patrol: Patrol; memberId: string; onClose: () => void; onSave: (v: ReturnInput) => void }) {
  const fixed = patrol.returnReason
  const [reason, setReason] = useState<'NORMAL' | 'ABORT'>(fixed ?? 'NORMAL')
  const [abort, setAbort] = useState<'HEALTH' | 'DEVICE' | 'OTHER'>(patrol.abortReason ?? 'HEALTH')
  const [time, setTime] = useState(nowAt().slice(6))
  return (
    <Modal
      title={`${pName(memberId)} 복귀 확인`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" onClick={() => onSave({ returnedAt: time, reason, abort: reason === 'ABORT' ? abort : undefined })}>
            복귀 확인
          </Button>
        </>
      }
    >
      {fixed ? (
        <p className="m-0 text-sm text-sub">
          앱 복귀 요청: <b className="text-ink">{fixed === 'NORMAL' ? '정상 복귀' : `중도 복귀 · ${ABORT_NAME[patrol.abortReason ?? 'OTHER']}`}</b>
        </p>
      ) : (
        <>
          <p className="m-0 text-sm text-sub">앱 복귀 요청이 없어도 확인할 수 있습니다. 복귀 구분을 골라 주세요.</p>
          <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="복귀 구분">
            {(
              [
                ['NORMAL', '정상 복귀'],
                ['ABORT', '중도 복귀'],
              ] as const
            ).map(([v, l]) => (
              <button key={v} type="button" role="radio" aria-checked={reason === v} onClick={() => setReason(v)} className="h-10 rounded-xl border border-line-strong bg-surface text-[15px] font-semibold text-sub aria-checked:border-brand aria-checked:bg-brand aria-checked:text-white">
                {l}
              </button>
            ))}
          </div>
          {reason === 'ABORT' && (
            <Field label="중도 복귀 사유" required>
              <select value={abort} onChange={(e) => setAbort(e.target.value as typeof abort)} className={inputCls}>
                {(Object.keys(ABORT_NAME) as (keyof typeof ABORT_NAME)[]).map((k) => (
                  <option key={k} value={k}>
                    {ABORT_NAME[k]}
                  </option>
                ))}
              </select>
            </Field>
          )}
        </>
      )}
      <Field label="복귀 시각">
        <select value={time} onChange={(e) => setTime(e.target.value)} className={inputCls}>
          {[...new Set([time, ...MINUTES])].sort().map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </Field>
    </Modal>
  )
}

/** 시작·복귀 시각 입력/정정. 생략한 값은 그대로 두고, 복귀 확인 여부는 바꾸지 않는다 */
function TimeModal({ patrol, memberId, onClose, onSave }: { patrol: Patrol; memberId: string; onClose: () => void; onSave: (startedAt: string | undefined, returnedAt: string | undefined, reason: string) => void }) {
  const mb = patrol.members.find((m) => m.id === memberId)!
  const [start, setStart] = useState(mb.startedAt ?? '')
  const [ret, setRet] = useState(mb.returnedAt ?? '')
  const [reason, setReason] = useState('')
  const startChanged = start !== (mb.startedAt ?? '') && start !== ''
  const retChanged = ret !== (mb.returnedAt ?? '') && ret !== ''
  const orderOk = !start || !ret || start <= ret
  const opts = (cur: string) => ['', ...new Set([cur, ...MINUTES].filter(Boolean))].sort()
  return (
    <Modal
      title={`${pName(memberId)} 시각 정정`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!(startChanged || retChanged) || !reason.trim() || !orderOk} onClick={() => onSave(startChanged ? start : undefined, retChanged ? ret : undefined, reason.trim())}>
            정정 저장
          </Button>
        </>
      }
    >
      <p className="m-0 text-sm text-sub">시작 버튼을 빠뜨렸거나 시각이 틀렸을 때 입력합니다. 원래 기록은 남고, 복귀 시각을 바꿔도 복귀 확인 여부는 바뀌지 않습니다.</p>
      <div className="grid grid-cols-2 gap-3">
        <Field label="시작 시각" hint={mb.startedAt ? `현재 ${mb.startedAt}` : '기록 없음'}>
          <select value={start} onChange={(e) => setStart(e.target.value)} className={inputCls}>
            {opts(start).map((t) => (
              <option key={t} value={t}>
                {t || '그대로 두기'}
              </option>
            ))}
          </select>
        </Field>
        <Field label="복귀 시각" hint={mb.returnedAt ? `현재 ${mb.returnedAt}` : '기록 없음'}>
          <select value={ret} onChange={(e) => setRet(e.target.value)} className={inputCls}>
            {opts(ret).map((t) => (
              <option key={t} value={t}>
                {t || '그대로 두기'}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {!orderOk && <p className="m-0 text-xs text-g3-ink">시작 시각이 복귀 시각보다 늦을 수 없습니다.</p>}
      <Field label="사유" required>
        <input value={reason} onChange={(e) => setReason(e.target.value.slice(0, 500))} className={inputCls} placeholder="예: 시작 버튼 누락, 현장 확인 후 입력" />
      </Field>
    </Modal>
  )
}

/** 활동일 시각 선택지 (10분 단위, 07:00 ~ 18:00) */
const MINUTES = Array.from({ length: 67 }, (_, i) => `${String(7 + Math.floor(i / 6)).padStart(2, '0')}:${String((i % 6) * 10).padStart(2, '0')}`)

function FollowUpModal({ patrol, teams, onClose, onSave }: { patrol: Patrol; teams: Team[]; onClose: () => void; onSave: (t: string) => void }) {
  const [type, setType] = useState('연락')
  const [text, setText] = useState('')
  return (
    <Modal
      title={`${teamName(teams, patrol.teamId)} 후속 기록`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!text.trim()} onClick={() => onSave(`[${type}] ${text.trim()}`)}>
            기록
          </Button>
        </>
      }
    >
      <div className="grid gap-1.5 text-sm">
        <span className="font-semibold text-ink">종류</span>
        {/* 네 가지뿐이라 펼침 목록 대신 버튼으로 바로 고른다 */}
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" role="radiogroup" aria-label="후속 기록 종류">
          {['연락', '지연 확인', '현장 확인', '기타 대응'].map((t) => (
            <button
              key={t}
              type="button"
              role="radio"
              aria-checked={type === t}
              onClick={() => setType(t)}
              className="h-10 rounded-xl border border-line-strong bg-surface text-[15px] font-semibold text-sub aria-checked:border-brand aria-checked:bg-brand aria-checked:text-white"
            >
              {t}
            </button>
          ))}
        </div>
      </div>
      <Field label="내용" required>
        <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} className={`${inputCls} !h-auto py-2`} />
      </Field>
    </Modal>
  )
}

function ReplaceModal({ patrols, participants, onClose, onPick }: { patrols: Patrol[]; participants: Participant[]; onClose: () => void; onPick: (id: string) => void }) {
  const busy = new Set(patrols.filter((p) => p.date === TODAY && p.status !== 'CANCELLED').flatMap((p) => p.members.map((m) => m.id)))
  const free = participants.filter((p) => p.status === 'ACTIVE' && !busy.has(p.id))
  return (
    <Modal title="결원 교체 (출발 전)" onClose={onClose}>
      <p className="m-0 text-sm text-sub">오늘 다른 순회에 배정되지 않은 참여자만 보입니다. 교체 이력이 남습니다.</p>
      {free.length === 0 ? (
        <Empty>교체할 수 있는 참여자가 없습니다.</Empty>
      ) : (
        <ul className="m-0 grid list-none gap-1.5 p-0">
          {free.map((p) => (
            <li key={p.id} className="flex items-center justify-between rounded-lg border border-line px-3 py-2 text-sm">
              <span>
                <b>{p.name}</b> <span className="text-mute">· 활동 가능 {p.days.join('·')}</span>
              </span>
              <Button size="sm" onClick={() => onPick(p.id)}>
                이 분으로 교체
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )
}

/* ---------------- 조 편성 ---------------- */

export function Teams({ teams, setTeams, participants }: { teams: Team[]; setTeams: Dispatch<SetStateAction<Team[]>>; participants: Participant[] }) {
  const [edit, setEdit] = useState<Team | 'new' | null>(null)
  return (
    <>
      <PageHead
        title="조 편성"
        sub="한 조는 2~3명입니다."
        help="조 대표·촬영 담당은 정하지 않고, 조원 정보(건강·생활권 등)는 참여 신청서로 관리합니다."
        right={
          <Button variant="primary" onClick={() => setEdit('new')}>
            + 조 만들기
          </Button>
        }
      />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {teams.map((t) => (
          <Card key={t.id}>
            <div className="flex items-start justify-between gap-2 p-4">
              <div>
                <p className="m-0 text-base font-bold">
                  {t.name} <span className="text-sm font-normal text-mute">· {t.memberIds.length}명</span>
                </p>
                <p className="m-0 mt-1 text-sm">{t.memberIds.map(pName).join(', ')}</p>
              </div>
              <Button size="sm" onClick={() => setEdit(t)}>
                편집
              </Button>
            </div>
          </Card>
        ))}
      </div>
      {edit && (
        <TeamModal
          team={edit === 'new' ? null : edit}
          teams={teams}
          participants={participants}
          onClose={() => setEdit(null)}
          onSave={(t) => {
            setTeams((l) => (l.some((x) => x.id === t.id) ? l.map((x) => (x.id === t.id ? t : x)) : [...l, t]))
            setEdit(null)
          }}
        />
      )}
    </>
  )
}

function TeamModal({ team, teams, participants, onClose, onSave }: { team: Team | null; teams: Team[]; participants: Participant[]; onClose: () => void; onSave: (t: Team) => void }) {
  const [name, setName] = useState(team?.name ?? `${teams.length + 1}조`)
  const [ids, setIds] = useState<string[]>(team?.memberIds ?? [])
  const other = (id: string) => teams.find((t) => t.id !== team?.id && t.memberIds.includes(id))?.name
  const active = participants.filter((p) => p.status === 'ACTIVE')
  const ok = name.trim() && ids.length >= 2 && ids.length <= 3
  return (
    <Modal
      title={team ? `${team.name} 편집` : '조 만들기'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!ok} onClick={() => onSave({ id: team?.id ?? `t${Date.now()}`, name: name.trim(), memberIds: ids })}>
            저장
          </Button>
        </>
      }
    >
      <Field label="조 이름" required>
        <input value={name} onChange={(e) => setName(e.target.value)} className={inputCls} />
      </Field>
      <fieldset className="m-0 grid gap-1.5 border-0 p-0">
        <legend className="mb-1.5 text-sm font-semibold">
          조원 선택 <span className="font-normal text-mute">({ids.length}/3명, 최소 2명)</span>
        </legend>
        {(
          [
            ['아직 조가 없는 참여자', active.filter((p) => !other(p.id))],
            ['다른 조에 속한 참여자', active.filter((p) => other(p.id))],
          ] as [string, Participant[]][]
        ).map(([heading, list]) =>
          list.length === 0 ? null : (
            <div key={heading} className="grid gap-1.5">
              <p className="m-0 mt-1 text-xs font-bold text-mute">
                {heading} · {list.length}명
              </p>
              {list.map((p) => {
                const on = ids.includes(p.id)
                const full = !on && ids.length >= 3
                return (
                  <label
                    key={p.id}
                    className={`flex min-h-12 items-center gap-3 rounded-xl border px-3.5 py-2 text-[15px] ${on ? 'border-brand bg-brand-50' : 'border-line'} ${full ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:bg-bg'}`}
                  >
                    <input
                      type="checkbox"
                      checked={on}
                      disabled={full}
                      onChange={() => setIds(on ? ids.filter((x) => x !== p.id) : [...ids, p.id])}
                      className="size-5 flex-none accent-[var(--color-brand)]"
                    />
                    <span className="flex-1">
                      <b className="font-bold">{p.name}</b> <span className="text-sm text-mute">· 활동 {p.days.join('·')}</span>
                    </span>
                    {other(p.id) && <span className="rounded-full bg-bg px-2 py-0.5 text-xs font-semibold text-sub">현재 {other(p.id)}</span>}
                  </label>
                )
              })}
            </div>
          ),
        )}
      </fieldset>
    </Modal>
  )
}

/* ---------------- 코스·순회 배정 ---------------- */

export function Assign({
  patrols,
  setPatrols,
  teams,
  reqs,
  setReqs,
}: {
  patrols: Patrol[]
  setPatrols: Dispatch<SetStateAction<Patrol[]>>
  teams: Team[]
  reqs: Reinspection[]
  setReqs: Dispatch<SetStateAction<Reinspection[]>>
}) {
  const [assignFor, setAssignFor] = useState<Reinspection | null>(null)
  const [newPatrol, setNewPatrol] = useState(false)
  const open = reqs.filter((r) => r.status !== 'DONE')
  // 순회 일정: 날짜별로 묶고 날짜 안에서는 출발 시각 순
  const byDate: [string, Patrol[]][] = [...new Set(patrols.map((p) => p.date))]
    .sort()
    .map((d) => [d, patrols.filter((p) => p.date === d).sort((a, b) => a.start.localeCompare(b.start))])
  const label = (id?: string) => {
    const p = patrols.find((x) => x.id === id)
    return p ? `${p.date} ${teamName(teams, p.teamId)}` : '-'
  }

  function cancel(r: Reinspection) {
    setReqs((l) => l.map((x) => (x.id === r.id ? { ...x, status: 'WAITING', patrolId: undefined } : x)))
    setPatrols((l) => l.map((p) => (p.id === r.patrolId ? { ...p, reinspect: p.reinspect.filter((f) => f !== r.facilityId) } : p)))
  }

  return (
    <>
      <PageHead
        title="코스·순회 배정"
        sub="재점검 요청과 순회 일정을 관리합니다."
        help="재점검은 기한이나 자동 알림 없이, 같은 시설이 들어 있는 다음 정기 순회(출발 전)에 직접 배정합니다."
        right={
          <Button variant="primary" onClick={() => setNewPatrol(true)}>
            + 순회 배정
          </Button>
        }
      />
      <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
        <Card title={`재점검 요청 ${open.length}건`}>
          {open.length === 0 ? (
            <Empty>배정할 재점검 요청이 없습니다.</Empty>
          ) : (
            <>
            <ul className="m-0 grid list-none gap-0 p-0 md:hidden">
              {open.map((r) => {
                const f = fac(r.facilityId)
                const p = patrols.find((x) => x.id === r.patrolId)
                return (
                  <li key={r.id} className="grid gap-1.5 border-b border-line px-4 py-3 last:border-0">
                    <p className="m-0 flex flex-wrap items-center gap-1.5">
                      {r.status === 'WAITING' ? <Pill tone="amber">배정 전</Pill> : <Pill tone="green">배정됨</Pill>}
                      <Pill tone="gray">{r.source === 'REPAIR' ? '보수 확인' : '추가 확인'}</Pill>
                    </p>
                    <p className="m-0 font-bold">
                      {KIND_NAME[f.kind]} · {f.name}
                    </p>
                    <p className="m-0 text-sm text-sub">{r.reason}</p>
                    <p className="m-0 text-xs text-mute tabular-nums">
                      {r.id} · 요청 {r.createdAt}
                      {r.patrolId && ` · ${label(r.patrolId)}`}
                    </p>
                    <div>
                      {r.status === 'WAITING' ? (
                        <Button size="sm" variant="primary" onClick={() => setAssignFor(r)}>
                          순회에 배정
                        </Button>
                      ) : (
                        p?.status === 'ASSIGNED' && (
                          <Button size="sm" onClick={() => cancel(r)}>
                            배정 취소
                          </Button>
                        )
                      )}
                    </div>
                  </li>
                )
              })}
            </ul>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[760px] border-collapse text-sm">
                <thead>
                  <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                    {['시설', '사유', '요청일', '상태', '배정 순회', ''].map((h) => (
                      <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {open.map((r) => {
                    const f = fac(r.facilityId)
                    const p = patrols.find((x) => x.id === r.patrolId)
                    return (
                      <tr key={r.id} className="border-b border-line last:border-0">
                        <td className="px-4 py-2.5">
                          <span className="block">
                            {KIND_NAME[f.kind]} · {f.name}
                          </span>
                          <span className="block text-xs text-mute tabular-nums">요청 번호 {r.id}</span>
                        </td>
                        <td className="px-4 py-2.5">
                          <Pill tone="gray">{r.source === 'REPAIR' ? '보수 확인' : '추가 확인'}</Pill> <span className="text-sub">{r.reason}</span>
                        </td>
                        <td className="px-4 py-2.5 whitespace-nowrap tabular-nums">{r.createdAt}</td>
                        <td className="px-4 py-2.5">{r.status === 'WAITING' ? <Pill tone="amber">배정 전</Pill> : <Pill tone="green">배정됨</Pill>}</td>
                        <td className="px-4 py-2.5 whitespace-nowrap">{label(r.patrolId)}</td>
                        <td className="px-4 py-2.5 text-right">
                          {r.status === 'WAITING' ? (
                            <Button size="sm" variant="primary" onClick={() => setAssignFor(r)}>
                              순회에 배정
                            </Button>
                          ) : (
                            p?.status === 'ASSIGNED' && (
                              <Button size="sm" variant="ghost" onClick={() => cancel(r)}>
                                배정 취소
                              </Button>
                            )
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            </>
          )}
        </Card>

        <Card title="순회 일정">
          <ul className="m-0 grid list-none gap-0 p-0 md:hidden">
            {byDate.map(([date, list]) => [
              <li key={`h-${date}`} className="border-b border-line bg-bg/70 px-4 py-2 text-[13px] font-bold text-sub">
                {dayLabel(date)} · {list.length}개 조
              </li>,
              ...list.map((p) => (
                <li key={p.id} className="flex items-start gap-3 border-b border-line px-4 py-3 last:border-0">
                  <div className="w-12 flex-none text-center">
                    <p className="m-0 text-sm font-bold tabular-nums">{p.start}</p>
                    <p className="m-0 text-xs text-mute tabular-nums">~{p.expectedReturn}</p>
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="m-0 flex flex-wrap items-center gap-1.5 font-bold">
                      {teamName(teams, p.teamId)} · {course(p.courseId).name}
                      <Pill tone={STATUS_TONE[p.status]}>{PATROL_STATUS_NAME[p.status]}</Pill>
                    </p>
                    <p className="m-0 mt-0.5 text-sm text-sub">{p.members.map((m) => pName(m.id)).join(', ')}</p>
                    {p.reinspect.length > 0 && <p className="m-0 text-xs text-sub">재점검: {p.reinspect.map((f) => KIND_NAME[fac(f).kind]).join(', ')}</p>}
                  </div>
                </li>
              )),
            ])}
          </ul>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[720px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                  {['조', '코스', '시간', '조원', '재점검', '상태'].map((h) => (
                    <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {byDate.map(([date, list]) => [
                  <tr key={`h-${date}`} className="border-b border-line">
                    <th scope="colgroup" colSpan={6} className="bg-bg/70 px-4 py-2 text-left text-[13px] font-bold text-sub">
                      {dayLabel(date)} · {list.length}개 조
                    </th>
                  </tr>,
                  ...list.map((p) => (
                    <tr key={p.id} className="border-b border-line last:border-0">
                      <td className="px-4 py-2.5 font-semibold whitespace-nowrap">{teamName(teams, p.teamId)}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap">{course(p.courseId).name}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap tabular-nums">
                        {p.start}~{p.expectedReturn}
                      </td>
                      <td className="px-4 py-2.5">{p.members.map((m) => pName(m.id)).join(', ')}</td>
                      <td className="px-4 py-2.5">{p.reinspect.length ? p.reinspect.map((f) => KIND_NAME[fac(f).kind]).join(', ') : '-'}</td>
                      <td className="px-4 py-2.5">
                        <Pill tone={STATUS_TONE[p.status]}>{PATROL_STATUS_NAME[p.status]}</Pill>
                      </td>
                    </tr>
                  )),
                ])}
              </tbody>
            </table>
          </div>
        </Card>

        <div className="grid items-start gap-4 xl:grid-cols-3">
          {COURSES.map((c) => (
            <Card key={c.id} title={`${c.name} · ${c.zone}`}>
              <p className="m-0 px-4 pt-3 text-xs text-mute">출발·복귀 {c.start}</p>
              <ol className="m-0 grid list-none gap-0 p-0 pb-1">
                {c.stops.map((s, i) => {
                  const f = fac(s.facilityId)
                  return (
                    <li key={s.facilityId} className="flex gap-3 border-b border-line px-4 py-2.5 text-sm last:border-0">
                      <span className="grid size-6 flex-none place-items-center rounded-full bg-bg text-xs font-bold text-sub">{i + 1}</span>
                      <span className="min-w-0 flex-1">
                        <span className="block">
                          {KIND_NAME[f.kind]} · {f.name}
                        </span>
                        <span className="block text-xs text-mute">
                          <span title={FACILITY_TYPE[f.kind]}>
                            사진 {s.slots.length}장 ({s.slots.join(', ')})
                          </span>
                        </span>
                        <span className="mt-0.5 flex flex-wrap gap-1">
                          {!AI_SUPPORTED.includes(f.kind) && <Pill tone="gray">AI 분류 대상 아님</Pill>}
                          {SEASONAL[f.kind] && <Pill tone="gray">계절 시설 · {SEASONAL[f.kind]}</Pill>}
                        </span>
                      </span>
                    </li>
                  )
                })}
              </ol>
            </Card>
          ))}
        </div>
      </div>
      {assignFor && (
        <AssignModal
          req={assignFor}
          patrols={patrols}
          teams={teams}
          onClose={() => setAssignFor(null)}
          onPick={(pid) => {
            setReqs((l) => l.map((x) => (x.id === assignFor.id ? { ...x, status: 'ASSIGNED', patrolId: pid } : x)))
            setPatrols((l) => l.map((p) => (p.id === pid ? { ...p, reinspect: [...new Set([...p.reinspect, assignFor.facilityId])] } : p)))
            setAssignFor(null)
          }}
        />
      )}
      {newPatrol && (
        <NewPatrolModal
          teams={teams}
          onClose={() => setNewPatrol(false)}
          onSave={(p) => {
            setPatrols((l) => [...l, p])
            setNewPatrol(false)
          }}
        />
      )}
    </>
  )
}

function AssignModal({ req, patrols, teams, onClose, onPick }: { req: Reinspection; patrols: Patrol[]; teams: Team[]; onClose: () => void; onPick: (id: string) => void }) {
  const f = fac(req.facilityId)
  const options = patrols
    .filter((p) => p.status === 'ASSIGNED' && course(p.courseId).stops.some((s) => s.facilityId === f.id))
    .sort((a, b) => a.date.localeCompare(b.date))
  return (
    <Modal title="재점검 순회 배정" onClose={onClose}>
      <p className="m-0 text-sm">
        <b>
          {KIND_NAME[f.kind]} · {f.name}
        </b>
        <span className="block text-sub">{req.reason}</span>
      </p>
      <p className="m-0 text-xs text-mute">이 시설이 코스에 들어 있고 아직 출발하지 않은 순회만 보입니다. 배정하면 해당 장소에 재점검 구도(이전 사진 겹쳐 보기)가 추가됩니다.</p>
      {options.length === 0 ? (
        <Empty>배정할 수 있는 순회가 없습니다. 먼저 순회를 배정하세요.</Empty>
      ) : (
        <ul className="m-0 grid list-none gap-1.5 p-0">
          {options.map((p) => (
            <li key={p.id} className="flex items-center justify-between rounded-lg border border-line px-3 py-2 text-sm">
              <span>
                <b>
                  {p.date} {teamName(teams, p.teamId)}
                </b>{' '}
                <span className="text-mute">
                  · {course(p.courseId).name} · {p.start}
                </span>
              </span>
              <Button size="sm" variant="primary" onClick={() => onPick(p.id)}>
                배정
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )
}

/** 30분 단위 시간 (07:00 ~ 18:00) */
const TIMES = Array.from({ length: 23 }, (_, i) => `${String(7 + Math.floor(i / 2)).padStart(2, '0')}:${i % 2 ? '30' : '00'}`)

/** 'MM/DD' → '10/16 (금)'처럼 요일을 붙인다 (2026년 기준) */
function dayLabel(md: string) {
  const [m, d] = md.split('/').map(Number)
  const w = '일월화수목금토'[new Date(2026, m - 1, d).getDay()]
  return md === TODAY ? `오늘 · ${md} (${w})` : `${md} (${w})`
}

function NewPatrolModal({ teams, onClose, onSave }: { teams: Team[]; onClose: () => void; onSave: (p: Patrol) => void }) {
  const [date, setDate] = useState('10/16')
  const [teamId, setTeamId] = useState(teams[0]?.id ?? '')
  const [courseId, setCourseId] = useState(COURSES[0].id)
  const [start, setStart] = useState('09:00')
  const [end, setEnd] = useState('11:30')
  const team = teams.find((t) => t.id === teamId)
  return (
    <Modal
      title="순회 배정"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button
            variant="primary"
            disabled={!team || !/^\d{2}\/\d{2}$/.test(date)}
            onClick={() =>
              onSave({
                id: `p-${date.replace('/', '')}-${teamId}`,
                date,
                teamId,
                courseId,
                start,
                expectedReturn: end,
                status: 'ASSIGNED',
                members: team!.memberIds.map((id) => ({ id, attendance: 'NONE', returned: false })),
                progress: { captured: 0, skipped: 0, review: 0 },
                reinspect: [],
                followUps: [],
              })
            }
          >
            배정
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="날짜" required hint={date ? dayLabel(date) : undefined}>
          {/* 달력에서 고른다. 저장은 기존 형식(MM/DD) 그대로 */}
          <input
            type="date"
            value={date ? `2026-${date.replace('/', '-')}` : ''}
            min="2026-10-12"
            onChange={(e) => setDate(e.target.value ? e.target.value.slice(5).replace('-', '/') : '')}
            className={inputCls}
          />
        </Field>
        <Field label="조">
          <select value={teamId} onChange={(e) => setTeamId(e.target.value)} className={inputCls}>
            {teams.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} ({t.memberIds.map(pName).join(', ')})
              </option>
            ))}
          </select>
        </Field>
        <Field label="코스">
          <select value={courseId} onChange={(e) => setCourseId(e.target.value)} className={inputCls}>
            {COURSES.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="출발">
            <select
              value={start}
              onChange={(e) => {
                const v = e.target.value
                setStart(v)
                // 복귀 예정이 출발보다 앞서면 2시간 뒤로 맞춘다
                if (end <= v) setEnd(TIMES[Math.min(TIMES.indexOf(v) + 4, TIMES.length - 1)])
              }}
              className={inputCls}
            >
              {TIMES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Field>
          <Field label="복귀 예정">
            <select value={end} onChange={(e) => setEnd(e.target.value)} className={inputCls}>
              {TIMES.filter((t) => t > start).map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Field>
        </div>
      </div>
      <p className="m-0 text-xs text-mute">배정 당시의 조원과 코스가 순회에 그대로 저장됩니다. 출발 뒤에는 조원·코스를 바꿀 수 없습니다.</p>
    </Modal>
  )
}

/* ---------------- 참여자 계정 ---------------- */

const STATUS_NAME: Record<Participant['status'], string> = { ACTIVE: '활동 중', SUSPENDED: '일시 중지', ENDED: '종료' }

export function Participants({ participants, setParticipants }: { participants: Participant[]; setParticipants: Dispatch<SetStateAction<Participant[]>> }) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Participant | null>(null)
  return (
    <>
      <PageHead
        title="참여자 계정"
        sub="어르신 계정과 활동 상태를 관리합니다."
        help="어르신은 등록한 휴대폰 번호로 문자 인증 로그인합니다. 건강·편성 정보는 참여 신청서로 관리하고 앱에는 저장하지 않습니다."
        right={
          <Button variant="primary" onClick={() => setOpen(true)}>
            + 참여자 등록
          </Button>
        }
      />
      <Card>
        {/* 넓은 화면: 표 */}
        <div className="hidden overflow-x-auto md:block">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                {['이름', '휴대폰', '소속 기관', '활동 가능 요일', '상태', ''].map((h) => (
                  <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {participants.map((p) => (
                <tr key={p.id} className="border-b border-line last:border-0">
                  <td className="px-4 py-2.5 font-semibold">{p.name}</td>
                  <td className="px-4 py-2.5 tabular-nums">{maskPhone(p.phone)}</td>
                  <td className="px-4 py-2.5">{p.org}</td>
                  <td className="px-4 py-2.5">{p.days.join(' · ')}</td>
                  <td className="px-4 py-2.5">
                    <Pill tone={STATUS_TONE_P[p.status]}>{STATUS_NAME[p.status]}</Pill>
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <Button size="sm" onClick={() => setEditing(p)}>
                      상태 변경
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {/* 좁은 화면: 한 사람당 카드 한 장 */}
        <ul className="m-0 grid list-none gap-0 p-0 md:hidden">
          {participants.map((p) => (
            <li key={p.id} className="flex items-center gap-3 border-b border-line px-4 py-3 last:border-0">
              <div className="min-w-0 flex-1">
                <p className="m-0 flex items-center gap-2 font-bold">
                  {p.name}
                  <Pill tone={STATUS_TONE_P[p.status]}>{STATUS_NAME[p.status]}</Pill>
                </p>
                <p className="m-0 mt-0.5 text-sm text-sub tabular-nums">{maskPhone(p.phone)}</p>
                <p className="m-0 text-xs text-mute">
                  {p.org} · 활동 {p.days.join('·')}
                </p>
              </div>
              <Button size="sm" onClick={() => setEditing(p)}>
                상태 변경
              </Button>
            </li>
          ))}
        </ul>
        <p className="m-0 border-t border-line px-4 py-2.5 text-xs text-mute">일시 중지·종료하면 그 기기의 로그인도 함께 끊깁니다. 번호 변경은 새 번호 확인 절차를 거칩니다.</p>
      </Card>
      {open && (
        <NewParticipant
          onClose={() => setOpen(false)}
          onSave={(p) => {
            setParticipants((l) => [...l, p])
            setOpen(false)
          }}
        />
      )}
      {editing && (
        <StatusModal
          participant={editing}
          onClose={() => setEditing(null)}
          onSave={(s) => {
            setParticipants((l) => l.map((x) => (x.id === editing.id ? { ...x, status: s } : x)))
            setEditing(null)
          }}
        />
      )}
    </>
  )
}

const STATUS_TONE_P: Record<Participant['status'], 'green' | 'amber' | 'gray'> = { ACTIVE: 'green', SUSPENDED: 'amber', ENDED: 'gray' }
const STATUS_HINT: Record<Participant['status'], string> = {
  ACTIVE: '순회에 배정하고 앱에 로그인할 수 있습니다.',
  SUSPENDED: '잠시 쉬는 상태입니다. 앱 로그인이 끊기고 새 순회에 배정되지 않습니다.',
  ENDED: '참여를 마친 상태입니다. 앱 로그인이 끊기고 기록만 남습니다.',
}

// 상태는 평소엔 배지로만 보이고, 바꿀 때만 이 창을 연다 (스크롤 중 실수 방지)
function StatusModal({ participant, onClose, onSave }: { participant: Participant; onClose: () => void; onSave: (s: Participant['status']) => void }) {
  const [s, setS] = useState<Participant['status']>(participant.status)
  return (
    <Modal
      title={`${participant.name} 상태 변경`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant={s === 'ACTIVE' ? 'primary' : 'danger'} disabled={s === participant.status} onClick={() => onSave(s)}>
            {STATUS_NAME[s]}(으)로 변경
          </Button>
        </>
      }
    >
      <div className="grid gap-2" role="radiogroup" aria-label="상태">
        {(Object.keys(STATUS_NAME) as Participant['status'][]).map((k) => (
          <label key={k} className={`flex cursor-pointer items-start gap-3 rounded-xl border px-4 py-3 ${s === k ? 'border-brand bg-brand-50' : 'border-line'}`}>
            <input type="radio" name="p-status" checked={s === k} onChange={() => setS(k)} className="mt-1 size-4 accent-[var(--color-brand)]" />
            <span>
              <span className="flex items-center gap-2 font-bold">
                {STATUS_NAME[k]}
                {k === participant.status && <span className="text-xs font-semibold text-mute">현재</span>}
              </span>
              <span className="block text-sm text-sub">{STATUS_HINT[k]}</span>
            </span>
          </label>
        ))}
      </div>
    </Modal>
  )
}

function NewParticipant({ onClose, onSave }: { onClose: () => void; onSave: (p: Participant) => void }) {
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [days, setDays] = useState<string[]>([])
  const ok = name.trim() && /^010-\d{4}-\d{4}$/.test(phone) && days.length > 0
  return (
    <Modal
      title="참여자 등록"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!ok} onClick={() => onSave({ id: `u${Date.now()}`, name: name.trim(), phone, org: '양주시니어클럽', days, status: 'ACTIVE' })}>
            등록
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="이름" required>
          <input value={name} onChange={(e) => setName(e.target.value)} className={inputCls} />
        </Field>
        <Field label="휴대폰" required hint="010-0000-0000 형식">
          <input value={phone} onChange={(e) => setPhone(e.target.value)} className={inputCls} placeholder="010-0000-0000" />
        </Field>
      </div>
      <fieldset className="m-0 border-0 p-0">
        <legend className="mb-1.5 text-sm font-semibold">활동 가능 요일</legend>
        {/* 누르면 파랗게 채워지는 요일 버튼 (다른 창의 선택 버튼과 같은 모양) */}
        <div className="grid grid-cols-5 gap-2">
          {['월', '화', '수', '목', '금'].map((d) => (
            <button
              key={d}
              type="button"
              aria-pressed={days.includes(d)}
              onClick={() => setDays(days.includes(d) ? days.filter((x) => x !== d) : [...days, d])}
              className="h-11 rounded-xl border border-line-strong bg-surface text-[15px] font-bold text-sub hover:border-sub aria-pressed:border-brand aria-pressed:bg-brand aria-pressed:text-white"
            >
              {d}
            </button>
          ))}
        </div>
      </fieldset>
    </Modal>
  )
}
