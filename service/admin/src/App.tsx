import { useEffect, useRef, useState, type ReactNode } from 'react'
import { KIND_NAME } from './Photo'
import {
  INCOMING,
  ISSUES,
  ME,
  PARTICIPANTS,
  PATROLS,
  REINSPECTIONS,
  REVIEWS,
  ROLE_NAME,
  TEAMS,
  course,
  fac,
  type Issue,
  type Participant,
  type Patrol,
  type Reinspection,
  type ReviewTask,
  type Role,
  type Team,
} from './data'
import { Review, openReviewCount, reviewTitle } from './Review'
import { Issues } from './Issues'
import { Assign, Participants, Teams, Today, TODAY, pendingActivityCount } from './Activity'
import { Stats } from './Stats'
import { Admins } from './Admins'
import { Button, Pill, Toast, inputCls } from './ui'
import { nowAt } from './util'
import { REAL_BACKEND, ApiError, changePassword, fetchMe, login, logout, markSignedIn, restoreSession, setSessionEndedHandler } from './api/client'
import { RealAdmins } from './RealAdmins'
import { NotificationMenu, RealAudit, RealOrgs } from './RealOps'
import { RealAssign } from './RealAssign'
import { RealStats } from './RealStats'
import { applyServerDefectCodes, loadIssues, loadReviews, loadTodayPatrols, onRealChanged, onRealError, todayKst } from './api/real'
import { ALERT_LABEL, loadAlertSettings, playAlert, saveAlertSettings, unlockAlertAudio, type AlertKind, type AlertSettings } from './alertSound'

// 시니어 동네 점검단 · 관리자 웹
// 시연: 관리자 계정 하나에 세 역할(활동 담당자 · 시설 담당자 · 사업 총괄). 역할별로 메뉴를 나눈다.
type View = 'today' | 'teams' | 'assign' | 'participants' | 'review' | 'issues' | 'stats' | 'orgs' | 'audit' | 'admins'

const NAV: { role: Role; items: { v: View; label: string }[] }[] = [
  {
    role: 'ACTIVITY_MANAGER',
    items: [
      { v: 'today', label: '오늘 활동 현황' },
      { v: 'teams', label: '조 편성' },
      { v: 'assign', label: '코스·순회 배정' },
      { v: 'participants', label: '참여자 계정' },
    ],
  },
  {
    role: 'FACILITY_MANAGER',
    items: [
      { v: 'review', label: '검토 목록' },
      { v: 'issues', label: '사건·보수' },
    ],
  },
  {
    role: 'PROGRAM_MANAGER',
    items: [
      { v: 'stats', label: '통계·보고' },
      { v: 'orgs', label: '기관·거점' },
      { v: 'audit', label: '감사 이력' },
    ],
  },
  {
    // 공무원 계정 발급·초기화·잠금 해제는 시스템 관리자만 (D-04)
    role: 'SYSTEM_ADMIN',
    items: [
      { v: 'admins', label: '공무원 계정' },
      { v: 'audit', label: '감사 이력' },
    ],
  },
]

/** 실제 백엔드에 연결하지 않은 메뉴 (실제 백엔드 모드에서 예시 표시) */
const EXAMPLE_VIEWS: View[] = []
const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토']
/** 위치 표시줄의 날짜. 실제 백엔드면 오늘 */
const TODAY_LABEL = REAL_BACKEND
  ? (() => {
      const d = todayKst()
      return `${d.split('-').join('.')} (${WEEKDAY[new Date(`${d}T00:00:00+09:00`).getUTCDay()]})`
    })()
  : '2026.10.12 (월)'

/** 3조 순회가 진행되며 들어오는 검토 (어르신 앱 시연과 같은 순서) */
const P3_REVIEW: Record<string, string> = { f1: 'rv-1012-01', f2: 'rv-1012-02', f4: 'rv-1012-03', f5: 'rv-1012-04' }
const P3_IDS = Object.values(P3_REVIEW)

export default function App() {
  // 실제 백엔드: 처음에는 갱신 쿠키로 로그인을 되살려 보고(checking), 안 되면 로그인 화면
  const [stage, setStage] = useState<'checking' | 'login' | 'password' | 'in'>(REAL_BACKEND ? 'checking' : 'login')
  const [view, setView] = useState<View>('today')
  // 실제 백엔드: 검토·사건·오늘 순회는 서버 값으로 채운다. 나머지 메뉴(예시)는 아래 demo* 값을 쓴다
  const [reviews, setReviews] = useState<ReviewTask[]>(() => (REAL_BACKEND ? [] : REVIEWS.filter((r) => !P3_IDS.includes(r.id))))
  const [issues, setIssues] = useState<Issue[]>(() => (REAL_BACKEND ? [] : ISSUES))
  const [reqs, setReqs] = useState<Reinspection[]>(REINSPECTIONS)
  const [patrols, setPatrols] = useState<Patrol[]>(() => (REAL_BACKEND ? [] : PATROLS))
  const [demoPatrols, setDemoPatrols] = useState<Patrol[]>(PATROLS)
  // 실제 백엔드 응답이 TEAMS·PARTICIPANTS에 더해지므로 예시 메뉴용으로 처음 값을 복사해 둔다
  const [teams, setTeams] = useState<Team[]>(() => [...TEAMS])
  const [participants, setParticipants] = useState<Participant[]>(() => [...PARTICIPANTS])
  const [creds, setCreds] = useState<{ loginId: string; password: string } | null>(null)
  const [realError, setRealError] = useState('')
  const [selReview, setSelReview] = useState<string | null>(null)
  const [selIssue, setSelIssue] = useState<string | null>(null)
  const [toast, setToast] = useState<ReviewTask | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [alerts, setAlerts] = useState<AlertSettings>(loadAlertSettings)
  useEffect(() => unlockAlertAudio(), [])
  useEffect(() => saveAlertSettings(alerts), [alerts])

  // 새 출석 요청·복귀 요청이 생기면 (켜 둔 항목만) 알림음. 다른 창에서 일할 때 놓치지 않게 한다
  const seen = useRef<{ att: Set<string>; ret: Set<string> } | null>(null)
  useEffect(() => {
    const att = new Set(patrols.flatMap((p) => p.members.filter((m) => m.attendance === 'REQUESTED').map((m) => `${p.id}:${m.id}`)))
    const ret = new Set(patrols.filter((p) => p.status === 'RETURNING').map((p) => p.id))
    const prev = seen.current
    seen.current = { att, ret }
    if (!prev) return
    if (alerts.attendance && [...att].some((k) => !prev.att.has(k))) playAlert('attendance')
    if (alerts.return && [...ret].some((k) => !prev.ret.has(k))) playAlert('return')
  }, [patrols, alerts])

  // 실제 백엔드: 로그인 뒤 서버 값을 불러오고 5초마다 다시 받는다. 처리(run) 뒤에도 바로 다시 받는다
  const knownReviews = useRef<Set<string> | null>(null)
  useEffect(() => {
    if (!REAL_BACKEND || stage !== 'in') return
    let alive = true
    let busy = false
    const has = (r: Role) => ME.roles.includes(r)
    const load = async () => {
      if (busy) return
      busy = true
      try {
        const [p, r, i] = await Promise.all([
          has('ACTIVITY_MANAGER') ? loadTodayPatrols() : Promise.resolve(null),
          has('FACILITY_MANAGER') ? loadReviews() : Promise.resolve(null),
          has('FACILITY_MANAGER') ? loadIssues() : Promise.resolve(null),
        ])
        if (!alive) return
        if (p) setPatrols(p)
        if (i) setIssues(i)
        if (r) {
          setReviews(r)
          // 새 검토 요청은 화면 안 알림으로 (처음 불러온 것은 알리지 않음)
          const prev = knownReviews.current
          knownReviews.current = new Set(r.map((x) => x.id))
          const fresh = prev ? r.find((x) => !prev.has(x.id) && x.status === 'OPEN') : undefined
          if (fresh) setToast(fresh)
        }
      } catch (e) {
        if (alive) setRealError(e instanceof ApiError ? (e.serverMessage ?? e.message) : '업무 서버에 연결하지 못했습니다.')
      } finally {
        busy = false
      }
    }
    load()
    const t = setInterval(load, 5000)
    const off = onRealChanged(load)
    return () => {
      alive = false
      clearInterval(t)
      off()
    }
  }, [stage])
  useEffect(() => onRealError(setRealError), [])
  useEffect(() => {
    setSessionEndedHandler(() => {
      markSignedIn(false)
      setStage('login')
    })
  }, [])
  useEffect(() => {
    if (!REAL_BACKEND) return
    let alive = true
    restoreSession().then(async (me) => {
      if (!alive) return
      if (me && !me.passwordChangeRequired) {
        await applyMe(savedLoginId(), me)
        if (alive) setStage('in')
      } else setStage('login')
    })
    return () => {
      alive = false
    }
  }, [])

  // 시연: 로그인 뒤 잠시 있으면 박영자 어르신의 출석 요청이 들어온다
  useEffect(() => {
    if (stage !== 'in' || REAL_BACKEND) return
    const t = setTimeout(() => {
      setPatrols((l) => l.map((p) => (p.id === 'p-1012-3' ? { ...p, members: p.members.map((m) => (m.id === 'u2' && m.attendance === 'NONE' ? { ...m, attendance: 'REQUESTED' } : m)) } : p)))
    }, 5000)
    return () => clearTimeout(t)
  }, [stage])

  // 시연: 새 검토 요청을 관리자 화면 안 알림으로 띄운다. 위험도·우선순위와 무관 (D-10)
  useEffect(() => {
    if (stage !== 'in' || REAL_BACKEND) return
    const t = setTimeout(() => {
      setReviews((l) => (l.some((r) => r.id === INCOMING.id) ? l : [{ ...INCOMING, at: nowAt() }, ...l]))
      setToast(INCOMING)
    }, 9000)
    return () => clearTimeout(t)
  }, [stage])

  // 시연: 3조 어르신이 출석 확인 뒤 각자 앱에서 [활동 시작]을 누른다.
  // 출발 확인 뒤 조원 누구든 활동 시작을 누르면 조 전체가 '활동 중'이 된다 (기능정의서 SEN-13·ACC-02).
  const p3 = patrols.find((p) => p.id === 'p-1012-3')
  const p3NextStarter = p3?.status !== 'CANCELLED' ? p3?.members.find((mb) => mb.attendance === 'PRESENT' && !mb.startedAt)?.id : undefined
  useEffect(() => {
    if (!p3NextStarter) return
    const t = setTimeout(() => {
      const hm = nowAt().slice(6)
      setPatrols((l) =>
        l.map((p) =>
          p.id === 'p-1012-3'
            ? {
                ...p,
                status: p.status === 'ASSIGNED' ? 'IN_PROGRESS' : p.status,
                members: p.members.map((mb) => (mb.id === p3NextStarter ? { ...mb, startedAt: hm, startSource: 'SELF' } : mb)),
              }
            : p,
        ),
      )
    }, 2500)
    return () => clearTimeout(t)
  }, [p3NextStarter])

  // 시연: 3조가 활동 중이면 장소가 하나씩 처리되고 검토·재점검 사진이 들어온다
  const p3Done = p3 ? p3.progress.captured + p3.progress.skipped + p3.progress.review : 0
  useEffect(() => {
    if (!p3 || p3.status !== 'IN_PROGRESS') return
    const stops = course(p3.courseId).stops
    const t = setTimeout(() => {
      const stop = stops[p3Done]
      const at = nowAt()
      const isReview = stop.facilityId === 'f2'
      setPatrols((l) =>
        l.map((p) => {
          if (p.id !== p3.id) return p
          const progress = isReview ? { ...p.progress, review: p.progress.review + 1 } : { ...p.progress, captured: p.progress.captured + 1 }
          const last = p3Done + 1 >= stops.length
          return { ...p, progress, status: last ? 'RETURNING' : p.status, returnReason: last ? 'NORMAL' : p.returnReason }
        }),
      )
      const rid = P3_REVIEW[stop.facilityId]
      const seed = REVIEWS.find((r) => r.id === rid)
      if (seed) setReviews((l) => (l.some((r) => r.id === rid) ? l : [{ ...seed, at, logs: seed.logs.map((g) => ({ ...g, at })) }, ...l]))
      if (p3.reinspect.includes(stop.facilityId)) {
        const bad = stop.facilityId === 'f6'
        setIssues((l) =>
          l.map((i) =>
            i.facilityId === stop.facilityId && i.status === 'AWAITING_VERIFICATION'
              ? { ...i, verification: { after: { at, team: '3조', bad } }, logs: [...i.logs, { at, who: '시스템', text: '재점검 사진 접수 (3조)' }] }
              : i,
          ),
        )
        setReqs((l) => l.map((r) => (r.facilityId === stop.facilityId && r.patrolId === p3.id ? { ...r, status: 'DONE' } : r)))
      }
    }, 4000)
    return () => clearTimeout(t)
  }, [p3?.status, p3Done]) // eslint-disable-line react-hooks/exhaustive-deps

  if (stage === 'checking')
    return (
      <Shell>
        <p className="m-0 text-sm text-sub">로그인 확인 중…</p>
      </Shell>
    )
  if (stage === 'login')
    return (
      <Login
        onLogin={(first, c) => {
          setCreds(c ?? null)
          setStage(first ? 'password' : 'in')
        }}
      />
    )
  if (stage === 'password') return <ChangePassword creds={creds} onDone={() => setStage('in')} />
  const signOut = () => {
    if (REAL_BACKEND) {
      logout()
      knownReviews.current = null
      setPatrols([])
      setReviews([])
      setIssues([])
      setRealError('')
    }
    setStage('login')
  }

  const badge = (v: View) => (v === 'review' ? openReviewCount(reviews, issues) : v === 'today' ? pendingActivityCount(patrols) : v === 'assign' && !REAL_BACKEND ? reqs.filter((r) => r.status === 'WAITING').length : 0)
  /** 메뉴 숫자가 무엇을 센 것인지 (마우스를 올리면 보임) */
  const badgeTitle = (v: View) => {
    if (v === 'today') {
      const today = patrols.filter((p) => p.date === TODAY)
      const att = today.reduce((n, p) => n + p.members.filter((m) => m.attendance === 'REQUESTED').length, 0)
      const ret = today.filter((p) => p.status === 'RETURNING').length
      return `출석 요청 ${att}명 · 복귀 확인 대기 ${ret}개 조`
    }
    if (v === 'review') return `처리할 검토 ${badge(v)}건`
    if (v === 'assign') return `배정 전 재점검 요청 ${badge(v)}건`
    return ''
  }
  const current = (() => {
    for (const g of NAV) for (const it of g.items) if (it.v === view) return { role: g.role, label: it.label }
    return { role: NAV[0].role, label: NAV[0].items[0].label }
  })()
  const go = (v: View) => {
    setView(v)
    window.scrollTo(0, 0)
  }

  return (
    <div className="min-h-screen bg-bg lg:flex">
      <aside className="relative z-30 flex flex-col bg-surface shadow-card lg:z-10 lg:sticky lg:top-0 lg:h-screen lg:w-64 lg:flex-none">
        <div className="flex items-center gap-2.5 px-5 py-4 lg:py-5">
          <Logo />
          <div className="flex-1 leading-tight">
            <p className="m-0 text-[17px] font-extrabold">시니어 동네 점검단</p>
            <p className="m-0 text-xs text-mute">관리자 화면</p>
          </div>
          <MenuButton onClick={() => setMenuOpen(true)} />
        </div>
        {/* 좁은 화면: 메뉴 버튼을 누르면 위에서 펼쳐진다 */}
        {menuOpen && <div className="fixed inset-0 z-30 bg-ink/40 lg:hidden" onClick={() => setMenuOpen(false)} aria-hidden />}
        <nav
          className={`${menuOpen ? 'fixed inset-x-0 top-0 z-40 flex max-h-screen overflow-y-auto rounded-b-[24px] p-4 shadow-pop' : 'hidden'} flex-col bg-surface lg:static lg:flex lg:max-h-none lg:overflow-visible lg:rounded-none lg:px-3 lg:py-0 lg:shadow-none`}
          aria-label="주 메뉴"
        >
          <div className="mb-2 flex items-center justify-between px-2 lg:hidden">
            <p className="m-0 text-base font-extrabold">메뉴</p>
            <button type="button" onClick={() => setMenuOpen(false)} aria-label="메뉴 닫기" className="grid size-10 place-items-center rounded-xl text-sub hover:bg-bg">
              ✕
            </button>
          </div>
          {NAV.filter((g) => ME.roles.includes(g.role)).map((g) => (
            <div key={g.role} className="mb-3 flex flex-col">
              <p className="m-0 px-3 pt-2 pb-1.5 text-[13px] font-bold tracking-wide text-mute">{ROLE_NAME[g.role]}</p>
              {g.items.map(({ v, label }) => (
                <button
                  key={v}
                  type="button"
                  onClick={() => {
                    go(v)
                    setMenuOpen(false)
                  }}
                  aria-current={view === v ? 'page' : undefined}
                  className="flex h-11 flex-none items-center gap-2.5 rounded-xl px-3 text-left text-[15px] font-bold text-sub hover:bg-bg aria-[current=page]:bg-brand aria-[current=page]:text-white aria-[current=page]:shadow-[0_4px_12px_rgba(47,107,235,0.25)]"
                >
                  <NavIcon v={v} />
                  <span className="flex-1">{label}</span>
                  {REAL_BACKEND && EXAMPLE_VIEWS.includes(v) && (
                    <span title="실제 업무 서버에 아직 연결하지 않은 메뉴입니다" className="rounded-full border border-current px-1.5 text-[11px] leading-[18px] font-semibold opacity-70">
                      예시
                    </span>
                  )}
                  {badge(v) > 0 && (
                    <span title={badgeTitle(v)} aria-label={badgeTitle(v)} className="min-w-[22px] rounded-full bg-g2 px-1.5 text-center text-[13px] leading-[22px] font-bold text-[#4a3200]">
                      {badge(v)}
                    </span>
                  )}
                </button>
              ))}
            </div>
          ))}
          <div className="flex items-center justify-between border-t border-line px-3 pt-3 lg:hidden">
            <span className="text-sm">
              <b className="font-semibold">{ME.name}</b> <span className="text-mute">· {ME.org}</span>
            </span>
            <Button size="sm" onClick={signOut}>
              로그아웃
            </Button>
          </div>
        </nav>
        <div className="mt-auto hidden border-t border-line px-5 py-4 lg:block">
          <p className="m-0 text-sm font-semibold">{ME.name}</p>
          <p className="m-0 text-xs text-mute">{ME.org}</p>
          <p className="m-0 mt-1 flex flex-wrap gap-1">
            {ME.roles.map((r) => (
              <Pill key={r}>{ROLE_NAME[r]}</Pill>
            ))}
          </p>
          <button type="button" onClick={signOut} className="mt-2 text-xs text-mute underline underline-offset-2">
            로그아웃
          </button>
        </div>
      </aside>

      <main className="relative min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-10 lg:py-8">
        {/* 업무 화면은 그림 대신 옅은 색 띠만 둬서 표·카드에 집중하게 한다 */}
        <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 h-[200px] bg-gradient-to-b from-[#e4f0fb] to-bg" />
        {/* 지금 어느 역할의 어느 메뉴인지 항상 보이는 위치 표시줄 */}
        <div className="sticky top-0 z-20 -mx-4 mb-5 flex items-center justify-between gap-3 border-b border-line/70 bg-[#f4f6fa]/85 px-4 py-2.5 backdrop-blur -mt-6 sm:-mx-6 sm:px-6 lg:-mx-10 lg:-mt-8 lg:px-10">
          <p className="m-0 flex min-w-0 items-center gap-1.5 text-sm">
            <MenuButton compact onClick={() => setMenuOpen(true)} />
            <span className="font-semibold text-mute">{ROLE_NAME[current.role]}</span>
            <span className="text-line-strong" aria-hidden>
              ›
            </span>
            <span className="truncate font-bold text-ink">{current.label}</span>
          </p>
          <div className="flex flex-none items-center gap-2">
            <p className="m-0 hidden text-sm font-semibold text-sub sm:block">{TODAY_LABEL}</p>
            {REAL_BACKEND && <NotificationMenu onGo={(v) => go(v as View)} />}
            <AlertSoundMenu value={alerts} onChange={setAlerts} />
          </div>
        </div>
        <div className="relative mx-auto max-w-[1400px]">
          {REAL_BACKEND && realError && (
            <div role="alert" className="mb-4 flex items-start justify-between gap-3 rounded-xl border-l-4 border-g3 bg-g3-soft px-4 py-3 text-sm text-g3-ink">
              <span>{realError}</span>
              <button type="button" onClick={() => setRealError('')} aria-label="알림 닫기" className="font-bold">
                ✕
              </button>
            </div>
          )}
          {REAL_BACKEND && EXAMPLE_VIEWS.includes(view) && (
            <p className="m-0 mb-4 rounded-xl bg-g2-soft px-4 py-2.5 text-sm font-semibold text-g2-ink">예시 화면입니다. 이 메뉴는 아직 실제 업무 서버에 연결하지 않았고, 여기서 바꾼 내용은 저장되지 않습니다.</p>
          )}
          {view === 'today' && <Today patrols={patrols} setPatrols={setPatrols} teams={REAL_BACKEND ? TEAMS : teams} participants={participants} />}
          {view === 'teams' && <Teams teams={teams} setTeams={setTeams} participants={participants} />}
          {view === 'assign' &&
            (REAL_BACKEND ? (
              <RealAssign />
            ) : (
              <Assign patrols={patrols} setPatrols={setPatrols} teams={teams} reqs={reqs} setReqs={setReqs} />
            ))}
          {view === 'participants' && <Participants participants={participants} setParticipants={setParticipants} />}
          {view === 'review' && (
            <Review
              reviews={reviews}
              setReviews={setReviews}
              issues={issues}
              setIssues={setIssues}
              setReqs={setReqs}
              selected={selReview}
              onSelect={setSelReview}
              onOpenIssue={(id) => {
                setSelIssue(id)
                go('issues')
              }}
            />
          )}
          {view === 'issues' && <Issues issues={issues} setIssues={setIssues} reqs={reqs} setReqs={setReqs} patrols={patrols} selected={selIssue} onSelect={setSelIssue} onGoAssign={() => go('assign')} />}
          {view === 'stats' && (REAL_BACKEND ? <RealStats /> : <Stats reviews={reviews} issues={issues} patrols={patrols} teams={teams} />)}
          {view === 'admins' && (REAL_BACKEND ? <RealAdmins me={ME.loginId} /> : <Admins />)}
          {view === 'orgs' && (REAL_BACKEND ? <RealOrgs /> : <p className="m-0 text-sm text-sub">기관·거점 관리는 실제 업무 서버에 연결했을 때만 씁니다.</p>)}
          {view === 'audit' && (REAL_BACKEND ? <RealAudit /> : <p className="m-0 text-sm text-sub">감사 이력은 실제 업무 서버에 연결했을 때만 볼 수 있습니다.</p>)}
          <p className="mt-8 text-center text-xs text-mute">
            {REAL_BACKEND
              ? '업무 서버에 연결되어 있습니다. AI 판독 결과는 판단 지원이며 판정은 담당자가 확정합니다.'
              : '시연용 예시 데이터입니다. 그림은 실제 사진 자리를 단순화한 것이며, 실제 AI 판독 결과나 실제 시설·인물 정보가 아닙니다.'}
          </p>
        </div>
      </main>

      {toast && (
        <Toast tone="info" onClose={() => setToast(null)}>
          <p className="m-0 text-sm font-bold text-brand">새 검토 요청이 들어왔습니다</p>
          <p className="m-0 mt-1.5 text-sm">
            <b>{reviewTitle(toast)}</b>
            <br />
            <span className="text-sub">
              {KIND_NAME[fac(toast.facilityId).kind]} · {fac(toast.facilityId).name} · {toast.team}
            </span>
          </p>
          <div className="mt-3 flex gap-2">
            <Button
              size="sm"
              variant="primary"
              onClick={() => {
                setSelReview(toast.id)
                go('review')
                setToast(null)
              }}
            >
              바로 보기
            </Button>
            <Button size="sm" onClick={() => setToast(null)}>
              나중에
            </Button>
          </div>
        </Toast>
      )}
    </div>
  )
}

const ICON: Record<View, string> = {
  today: 'M4 6h16M4 12h16M4 18h10',
  teams: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7 0a2.5 2.5 0 1 0 0-5M3 19c0-3 3-5 6-5s6 2 6 5M16 14c2.5 0 5 1.5 5 4.5',
  assign: 'M5 4v16M5 5h11l-2 3.5 2 3.5H5',
  participants: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8c0-4 3-6 7-6s7 2 7 6',
  review: 'M4 8h3l1.5-2h7L17 8h3v11H4ZM12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z',
  issues: 'M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.5 2.5-2.5-.5-.5-2.5Z',
  stats: 'M5 20V10M12 20V4M19 20v-7',
  admins: 'M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6Z',
  orgs: 'M4 20V8l8-4 8 4v12M9 20v-6h6v6',
  audit: 'M7 4h10v16H7ZM10 8h4M10 12h4M10 16h2',
}

function NavIcon({ v }: { v: View }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden className="flex-none">
      <path d={ICON[v]} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
function AlertSoundMenu({ value, onChange }: { value: AlertSettings; onChange: (v: AlertSettings) => void }) {
  const [open, setOpen] = useState(false)
  const on = Object.values(value).some(Boolean)
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-label="알림음 설정"
        className={`flex h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${on ? 'border-brand bg-brand-50 text-brand' : 'border-line-strong bg-surface text-sub'}`}
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M6 9a6 6 0 0 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9ZM10 19a2 2 0 0 0 4 0" />
          {!on && <path d="M3 3l18 18" />}
        </svg>
        알림음 {on ? '켜짐' : '꺼짐'}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} aria-hidden />
          <div className="absolute right-0 z-40 mt-2 w-[300px] rounded-[18px] bg-surface p-4 shadow-pop" role="dialog" aria-label="알림음 설정">
            <p className="m-0 text-sm font-extrabold text-ink">알림음</p>
            <p className="m-0 mt-1 text-xs text-sub">다른 창에서 일할 때도 놓치지 않도록, 켠 항목이 새로 들어오면 짧은 소리로 알려 줍니다.</p>
            <div className="mt-3 grid gap-2">
              {(Object.keys(ALERT_LABEL) as AlertKind[]).map((k) => (
                <label key={k} className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 ${value[k] ? 'border-brand bg-brand-50' : 'border-line'}`}>
                  <input type="checkbox" checked={value[k]} onChange={() => onChange({ ...value, [k]: !value[k] })} className="size-5 flex-none accent-[var(--color-brand)]" />
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-bold text-ink">{ALERT_LABEL[k].title}</span>
                    <span className="block text-xs text-mute">{ALERT_LABEL[k].desc}</span>
                  </span>
                  <button type="button" onClick={(e) => (e.preventDefault(), playAlert(k))} className="flex-none rounded-full border border-line-strong px-2 py-1 text-xs font-semibold text-sub hover:bg-bg">
                    들어보기
                  </button>
                </label>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function MenuButton({ onClick, compact = false }: { onClick: () => void; compact?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label="메뉴 열기"
      className={`flex flex-none items-center gap-1.5 rounded-xl border border-line-strong bg-surface font-bold text-ink lg:hidden ${compact ? 'mr-1 h-8 px-2 text-xs' : 'h-10 px-3 text-sm'}`}
    >
      <svg width={compact ? 16 : 18} height={compact ? 16 : 18} viewBox="0 0 24 24" aria-hidden>
        <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
      </svg>
      {!compact && '메뉴'}
    </button>
  )
}

function Logo({ size = 8 }: { size?: 8 | 10 }) {
  return (
    <span className={`grid place-items-center rounded-lg bg-brand text-white ${size === 10 ? 'size-10 rounded-xl' : 'size-8'}`}>
      <svg width={size === 10 ? 22 : 18} height={size === 10 ? 22 : 18} viewBox="0 0 24 24" aria-hidden>
        <path d="M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
        <circle cx="12" cy="13" r="3.4" fill="none" stroke="currentColor" strokeWidth="2" />
      </svg>
    </span>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="relative grid min-h-screen place-items-center bg-bg p-4">
      <div className="relative w-full max-w-[400px]">
        <div className="mb-6 flex items-center gap-2.5">
          <Logo size={10} />
          <div className="leading-tight">
            <p className="m-0 text-lg font-bold">시니어 동네 점검단</p>
            <p className="m-0 text-sm text-mute">관리자 화면</p>
          </div>
        </div>
        {children}
      </div>
    </div>
  )
}

/** 실제 백엔드: 로그인한 계정의 이름·역할·소속을 화면에 반영한다 */
/** 새로 고침 뒤에도 아이디를 보여 주려고 이 탭에만 둔다 (비밀 값 아님. /me 응답에는 아이디가 없다) */
function savedLoginId() {
  try {
    return sessionStorage.getItem('admin-login-id') ?? ''
  } catch {
    return ''
  }
}
async function applyMe(loginId: string, user: { name: string; roles: string[] }) {
  markSignedIn(true)
  try {
    if (loginId) sessionStorage.setItem('admin-login-id', loginId)
  } catch {
    // 저장 못 해도 로그인은 그대로
  }
  ME.loginId = loginId
  ME.name = user.name
  ME.roles = user.roles.filter((r): r is Role => r in ROLE_NAME)
  ME.org = ''
  try {
    const me = await fetchMe()
    ME.org = [...new Set((me.scopes ?? []).map((s) => s.organizationName).filter(Boolean))].join(', ')
  } catch {
    // 소속은 표시용이라 못 받아도 계속한다
  }
  applyServerDefectCodes()
}

function Login({ onLogin }: { onLogin: (first: boolean, creds?: { loginId: string; password: string }) => void }) {
  const [id, setId] = useState('')
  const [pw, setPw] = useState('')
  const [first, setFirst] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  return (
    <Shell>
      <form
        className="grid gap-4 rounded-[24px] bg-surface p-7 shadow-pop"
        onSubmit={async (e) => {
          e.preventDefault()
          if (!REAL_BACKEND) return onLogin(first)
          setBusy(true)
          setError('')
          try {
            const r = await login(id.trim(), pw)
            if (r.kind === 'password-change') return onLogin(true, { loginId: id.trim(), password: pw })
            await applyMe(id.trim(), r.user)
            onLogin(false)
          } catch (err) {
            setError(err instanceof ApiError ? (err.serverMessage ?? err.message) : '업무 서버에 연결하지 못했습니다.')
          } finally {
            setBusy(false)
          }
        }}
      >
        <h1 className="m-0 text-xl font-bold">로그인</h1>
        <label className="grid gap-1.5 text-sm">
          <span className="font-semibold">아이디</span>
          <input value={id} onChange={(e) => setId(e.target.value)} className={inputCls} autoComplete="username" />
        </label>
        <label className="grid gap-1.5 text-sm">
          <span className="font-semibold">비밀번호</span>
          <input type="password" value={pw} onChange={(e) => setPw(e.target.value)} className={inputCls} autoComplete="current-password" />
        </label>
        {error && (
          <p role="alert" className="m-0 rounded-lg bg-g3-soft px-3 py-2 text-sm text-g3-ink">
            {error}
          </p>
        )}
        <Button type="submit" variant="primary" disabled={!id || !pw || busy}>
          {busy ? '확인 중…' : '로그인'}
        </Button>
        <p className="m-0 text-xs leading-relaxed text-mute">계정은 직접 만들 수 없습니다. 시스템 관리자가 발급한 계정을 사용하세요. 비밀번호를 5번 연속 틀리면 10분 동안 잠기며, 시스템 관리자에게 초기화나 잠금 해제를 요청할 수 있습니다.</p>
      </form>
      {/* 실제 백엔드: 시연 계정 목록을 보여 주지 않는다 (아이디는 담당자가 따로 알려 줌) */}
      {!REAL_BACKEND && (
      <div className="mt-4 rounded-[18px] border border-dashed border-line-strong bg-surface/70 p-3">
        <p className="m-0 mb-2 text-xs font-semibold text-mute">시연용 계정 (누르면 입력됩니다)</p>
        <div className="grid gap-1.5">
          {[
            { first: false, label: '시연 관리자 · 활동 담당자 + 시설 담당자 + 사업 총괄' },
            { first: true, label: '같은 계정 · 처음 로그인 (임시 비밀번호 변경 보기)' },
          ].map((d) => (
            <button
              key={d.label}
              type="button"
              onClick={() => {
                setId(ME.loginId)
                setPw(d.first ? 'TEMP2026' : 'demo1234')
                setFirst(d.first)
              }}
              aria-pressed={!!id && first === d.first}
              className="rounded-xl border border-line bg-surface px-3 py-2.5 text-left text-[14px] font-semibold text-sub aria-pressed:border-brand aria-pressed:bg-brand-50 aria-pressed:text-brand"
            >
              {d.label}
            </button>
          ))}
        </div>
      </div>
      )}
    </Shell>
  )
}

// 처음 로그인: 임시 비밀번호를 본인 비밀번호로 바꿔야 들어갈 수 있다 (체크리스트 2-8)
function ChangePassword({ creds, onDone }: { creds: { loginId: string; password: string } | null; onDone: () => void }) {
  const [a, setA] = useState('')
  const [b, setB] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // D-22: 영문·숫자·기호를 모두 포함한 8자 이상
  const strong = a.length >= 8 && /[A-Za-z]/.test(a) && /\d/.test(a) && /[^A-Za-z0-9]/.test(a)
  const same = a === b
  return (
    <Shell>
      <form
        className="grid gap-4 rounded-[24px] bg-surface p-7 shadow-pop"
        onSubmit={async (e) => {
          e.preventDefault()
          if (!REAL_BACKEND || !creds) return onDone()
          setBusy(true)
          setError('')
          try {
            // 바꾸면 서버가 세션을 모두 끝내므로 새 비밀번호로 다시 로그인한다
            await changePassword(creds.password, a)
            const r = await login(creds.loginId, a)
            if (r.kind !== 'in') throw new Error('다시 로그인해 주세요.')
            await applyMe(creds.loginId, r.user)
            onDone()
          } catch (err) {
            setError(err instanceof ApiError ? (err.serverMessage ?? err.message) : (err as Error).message)
          } finally {
            setBusy(false)
          }
        }}
      >
        <div>
          <h1 className="m-0 text-xl font-bold">비밀번호 변경</h1>
          <p className="m-0 mt-1 text-sm text-sub">임시 비밀번호로 로그인했습니다. 본인 비밀번호로 바꿔야 계속할 수 있습니다.</p>
        </div>
        <label className="grid gap-1.5 text-sm">
          <span className="font-semibold">새 비밀번호</span>
          <input type="password" value={a} onChange={(e) => setA(e.target.value)} className={inputCls} autoComplete="new-password" />
          <span className={`text-xs ${a && !strong ? 'text-g3-ink' : 'text-mute'}`}>8자 이상, 영문·숫자·기호를 모두 포함</span>
        </label>
        <label className="grid gap-1.5 text-sm">
          <span className="font-semibold">새 비밀번호 확인</span>
          <input type="password" value={b} onChange={(e) => setB(e.target.value)} className={inputCls} autoComplete="new-password" />
          {b && !same && <span className="text-xs text-g3-ink">비밀번호가 서로 다릅니다.</span>}
        </label>
        {error && (
          <p role="alert" className="m-0 rounded-lg bg-g3-soft px-3 py-2 text-sm text-g3-ink">
            {error}
          </p>
        )}
        <Button type="submit" variant="primary" disabled={!strong || !same || busy}>
          {busy ? '바꾸는 중…' : '변경하고 시작하기'}
        </Button>
        {/* 실제 업무 백엔드에서는 모두가 같은 예시 비밀번호를 쓰게 되므로 보여 주지 않는다 */}
        {!REAL_BACKEND && (
          <button
            type="button"
            onClick={() => {
              setA('demo2026!')
              setB('demo2026!')
            }}
            className="text-xs text-mute underline underline-offset-2"
          >
            시연용: 예시 비밀번호 넣기
          </button>
        )}
      </form>
    </Shell>
  )
}
