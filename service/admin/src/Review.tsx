import { useMemo, useState, type Dispatch, type SetStateAction } from 'react'
import { KIND_NAME, Photo, type Kind } from './Photo'
import {
  AI_SUPPORTED,
  ANALYSIS_STATUS_NAME,
  DECISION_NAME,
  DEPT_OF,
  FOLLOW_UP_NAME,
  ISSUE_TYPES,
  ME,
  REVIEW_CATEGORY_NAME,
  REVIEW_STATUS_NAME,
  REVIEW_TYPE_NAME,
  TRIGGER_NAME,
  fac,
  issueLabel,
  type Classification,
  type Decision,
  type FollowUp,
  type Issue,
  type Reinspection,
  type ReviewTask,
  type ReviewType,
  type TriggerReason,
} from './data'
import { Button, Card, DetailPane, Empty, PickHint, Modal, PageHead, Pill, StatusBadge, Tabs, inputCls } from './ui'
import { nowAt } from './util'
import * as real from './api/real'

// 시설 담당자 · 통합 검토 목록 (응답 JSON 8절)
// 사진 검토 / 반복 촬영 실패 / 수리 확인을 한 목록에서 본다.
// AI 위험도·등급·등급 기반 제안은 표시하지 않는다(D-10). AI 결과는 판단 지원이고 판정은 담당자가 확정한다.

type Row =
  | { kind: 'task'; id: string; type: ReviewType; open: boolean; at: string; task: ReviewTask }
  | { kind: 'verify'; id: string; type: ReviewType; open: boolean; at: string; issue: Issue }

const TRIGGER_TONE: Record<TriggerReason, 'gray' | 'amber'> = {
  ISSUE_SUSPECTED: 'amber',
  AI_NEEDS_REVIEW: 'gray',
  NO_ISSUE_SAMPLE: 'gray',
  QUALITY_RETRY_EXHAUSTED: 'amber',
  TECHNICAL_FAILURE: 'gray',
  PRIVACY_HOLD: 'gray',
  FACILITY_MISMATCH: 'amber',
}

/** 목록·알림 제목: 검토에 올라온 이유 + 내용 */
export function reviewTitle(t: ReviewTask) {
  const f = fac(t.facilityId)
  if (t.type === 'CAPTURE_FAILURE') return `촬영 실패 · ${KIND_NAME[f.kind]} 사진을 받지 못함`
  if (t.trigger === 'NO_ISSUE_SAMPLE') return `AI 이상 미검출 · ${KIND_NAME[f.kind]} 표본 확인`
  const finding = t.analysis?.findings[0]
  if (finding) return `확인 필요 · ${KIND_NAME[f.kind]} ${finding.label} 의심`
  return `확인 필요 · ${KIND_NAME[f.kind]} 사진`
}

export function openReviewCount(reviews: ReviewTask[], issues: Issue[]) {
  return reviews.filter((r) => r.status !== 'RESOLVED').length + issues.filter((i) => i.status === 'AWAITING_VERIFICATION' && i.verification.after).length
}

export function Review({
  reviews,
  setReviews,
  issues,
  setIssues,
  setReqs,
  selected,
  onSelect,
  onOpenIssue,
}: {
  reviews: ReviewTask[]
  setReviews: Dispatch<SetStateAction<ReviewTask[]>>
  issues: Issue[]
  setIssues: Dispatch<SetStateAction<Issue[]>>
  setReqs: Dispatch<SetStateAction<Reinspection[]>>
  selected: string | null
  onSelect: (id: string | null) => void
  onOpenIssue: (id: string) => void
}) {
  const [tab, setTab] = useState<'todo' | 'done' | 'all'>('todo')
  const [type, setType] = useState<'all' | ReviewType>('all')

  const all = useMemo<Row[]>(
    () => [
      ...reviews.map((t) => ({ kind: 'task' as const, id: t.id, type: t.type, open: t.status !== 'RESOLVED', at: t.at, task: t })),
      ...issues
        .filter((i) => i.verification.after && (i.status === 'AWAITING_VERIFICATION' || i.status === 'CLOSED'))
        .map((i) => ({ kind: 'verify' as const, id: `v-${i.id}`, type: 'REPAIR_VERIFICATION' as const, open: i.status === 'AWAITING_VERIFICATION', at: i.verification.after!.at, issue: i })),
    ],
    [reviews, issues],
  )
  // 우선순위 없이 접수 순 (Q-02). 처리할 것이 위
  const rows = useMemo(
    () =>
      all
        .filter((x) => (tab === 'todo' ? x.open : tab === 'done' ? !x.open : true))
        .filter((x) => type === 'all' || x.type === type)
        .sort((a, b) => Number(b.open) - Number(a.open) || a.at.localeCompare(b.at)),
    [all, tab, type],
  )

  // 판정해서 목록 필터에서 빠져도 고른 항목은 계속 보여 준다
  const current = all.find((r) => r.id === selected) ?? null

  return (
    <>
      <PageHead
        title="검토 목록"
        sub="활동일마다 모아서 검토합니다."
        help="확인 필요 · 촬영 실패 · 수리 확인은 전부 올라오고, AI가 이상을 찾지 못한 사진은 표본만 올라옵니다. 목록은 접수 순입니다. AI 결과는 참고이며 판정은 담당자가 확정합니다."
        right={
          <>
            <select value={type} onChange={(e) => setType(e.target.value as typeof type)} className={`${inputCls} !w-auto`} aria-label="검토 종류">
              <option value="all">모든 종류</option>
              {(Object.keys(REVIEW_TYPE_NAME) as ReviewType[]).map((t) => (
                <option key={t} value={t}>
                  {REVIEW_TYPE_NAME[t]}
                </option>
              ))}
            </select>
            <Tabs
              value={tab}
              onChange={setTab}
              options={[
                ['todo', '처리할 것'],
                ['done', '처리됨'],
                ['all', '전체'],
              ]}
            />
          </>
        }
      />
      <div className="grid grid-cols-[minmax(0,1fr)] items-start gap-4 xl:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
        <Card>
          {rows.length === 0 ? (
            <Empty>표시할 검토가 없습니다.</Empty>
          ) : (
            <ul className="m-0 list-none p-0">
              {rows.map((r) => (
                <li key={r.id} className="border-b border-line last:border-0">
                  <button
                    type="button"
                    onClick={() => onSelect(r.id)}
                    aria-current={selected === r.id ? 'true' : undefined}
                    className={`flex w-full gap-3 px-4 py-3 text-left hover:bg-bg aria-[current=true]:bg-brand-50 aria-[current=true]:shadow-[inset_4px_0_0_var(--color-brand)] ${!r.open ? 'opacity-70' : ''}`}
                  >
                    <RowThumb row={r} />
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-1.5">
                        <Pill tone="gray">{REVIEW_TYPE_NAME[r.type]}</Pill>
                        {r.kind === 'task' && r.task.type === 'INSPECTION' && <Pill tone={TRIGGER_TONE[r.task.trigger]}>{TRIGGER_NAME[r.task.trigger]}</Pill>}
                        {!r.open && <Pill tone="green">처리됨</Pill>}
                        {r.kind === 'task' && r.task.status === 'IN_REVIEW' && <Pill tone="brand">현장 확인 중</Pill>}
                      </span>
                      <span className="mt-1 block truncate text-sm font-semibold">{r.kind === 'task' ? reviewTitle(r.task) : `수리 확인 · ${r.issue.title}`}</span>
                      <span className="block truncate text-xs text-mute">
                        {r.kind === 'task' ? `${fac(r.task.facilityId).name} · ${r.task.team} · ${r.at}` : `${fac(r.issue.facilityId).name} · ${r.issue.verification.after!.team} · ${r.at}`}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <DetailPane open={!!current} onClose={() => onSelect(null)}>
          {!current ? (
            <PickHint>왼쪽 목록에서 검토할 항목을 고르세요.</PickHint>
          ) : current.kind === 'verify' ? (
            <VerifyPreview issue={current.issue} onOpen={() => onOpenIssue(current.issue.id)} />
          ) : (
            <TaskDetail key={current.task.id} task={current.task} issues={issues} setReviews={setReviews} setIssues={setIssues} setReqs={setReqs} onOpenIssue={onOpenIssue} />
          )}
        </DetailPane>
      </div>
    </>
  )
}

function RowThumb({ row }: { row: Row }) {
  if (row.kind === 'verify') return <Photo kind={fac(row.issue.facilityId).kind} bad={row.issue.verification.after!.bad} media={row.issue.verification.after!.media} src={row.issue.verification.after!.src} className="w-16 flex-none" />
  const f = fac(row.task.facilityId)
  return <Photo kind={f.kind} bad={row.task.photos[0].bad} media={row.task.photos[0].media} src={row.task.photos[0].src} className={`w-16 flex-none ${row.task.type === 'CAPTURE_FAILURE' ? 'blur-[2px]' : ''}`} />
}

function VerifyPreview({ issue, onOpen }: { issue: Issue; onOpen: () => void }) {
  const f = fac(issue.facilityId)
  const a = issue.verification.after!
  return (
    <Card title={`수리 확인 · ${issue.title}`} action={<StatusBadge s={issue.status} />}>
      <div className="grid gap-4 p-4">
        <div className="grid grid-cols-2 gap-3 text-center text-xs text-mute">
          <div>
            <Photo kind={f.kind} bad {...issue.beforePhoto} className="w-full" />
            <p className="m-0 mt-1">보수 전</p>
          </div>
          <div>
            <Photo kind={f.kind} bad={a.bad} media={a.media} src={a.src} className="w-full" />
            <p className="m-0 mt-1">
              재점검 {a.at} · {a.team}
            </p>
          </div>
        </div>
        <p className="m-0 text-sm text-sub">수리 확인은 사건 화면에서 합니다.</p>
        <div>
          <Button variant="primary" onClick={onOpen}>
            사건 {issue.id}에서 수리 확인하기
          </Button>
        </div>
      </div>
    </Card>
  )
}

const clsText = (c: { kind: Kind; issueTypes: string[] }) => `${KIND_NAME[c.kind]} · ${c.issueTypes.length ? c.issueTypes.map((t) => issueLabel(c.kind, t)).join(', ') : '문제 없음'}`

function TaskDetail({
  task,
  issues,
  setReviews,
  setIssues,
  setReqs,
  onOpenIssue,
}: {
  task: ReviewTask
  issues: Issue[]
  setReviews: Dispatch<SetStateAction<ReviewTask[]>>
  setIssues: Dispatch<SetStateAction<Issue[]>>
  setReqs: Dispatch<SetStateAction<Reinspection[]>>
  onOpenIssue: (id: string) => void
}) {
  const f = fac(task.facilityId)
  const history = issues.filter((i) => i.facilityId === f.id)
  /** 같은 시설의 열린 사건 (응답 JSON 8.2 openIssues). 실제 백엔드는 작업 상세의 openIssues */
  const openIssues = task.real ? task.real.openIssues : history.filter((i) => i.status !== 'CLOSED')
  const siteVisit = task.status === 'IN_REVIEW'
  // allowedDecisions: 작업 종류에 따라 서버가 내려주는 판정 버튼
  const choices: Decision[] = task.real
    ? task.real.allowedDecisions.filter((d) => d in DECISION_NAME)
    : siteVisit
      ? ['ISSUE_CONFIRMED', 'NO_ISSUE']
      : task.type === 'CAPTURE_FAILURE'
        ? ['FOLLOW_UP_REQUIRED', 'RESOLVE_CAPTURE_PROBLEM']
        : ['ISSUE_CONFIRMED', 'NO_ISSUE', 'FOLLOW_UP_REQUIRED']
  const [decision, setDecision] = useState<Decision | null>(null)
  const [followUp, setFollowUp] = useState<FollowUp>('REPHOTO')
  const [reason, setReason] = useState('')
  const [zoom, setZoom] = useState<string | null>(null)
  // 이상 확인: 새 문제 등록 또는 기존 열린 문제 연결 (issueResolutions)
  const [resolution, setResolution] = useState<string>(openIssues[0]?.id ?? 'NEW')
  const firstFinding = task.analysis?.findings[0]
  const [title, setTitle] = useState(firstFinding ? `${f.name} ${firstFinding.label}` : `${f.name} 이상`)
  // 실제 백엔드: 새 문제는 문제 코드가 필요하다. 현재 분류 → AI 의심 내용 → 사전 첫 코드 순으로 고른다
  const codes = ISSUE_TYPES[task.classification?.kind ?? f.kind] ?? []
  const [defectCode, setDefectCode] = useState(() => {
    const want = task.classification?.issueTypes[0] ?? firstFinding?.code
    return codes.some((c) => c.code === want) ? want! : (codes[0]?.code ?? '')
  })
  const [saving, setSaving] = useState(false)

  // 실제 백엔드는 이유를 늘 받는다 (이상 확인도 포함)
  const needReason = siteVisit || (decision !== null && (decision !== 'ISSUE_CONFIRMED' || !!task.real))
  const newIssueOk = !task.real || !!defectCode
  const ok = decision !== null && !saving && (!needReason || reason.trim().length > 0) && (decision !== 'ISSUE_CONFIRMED' || resolution !== 'NEW' || (title.trim().length > 0 && newIssueOk))

  // 판정 근거로 고정할 값 (응답 JSON 8.4): AI 결과 없음 → 모두 null, 사람 정정 없음 → classificationVersion 0
  const basis = {
    analysisResultId: task.analysis?.analysisResultId ?? null,
    correctionId: task.classification?.correctionId ?? null,
    classificationVersion: task.analysis ? (task.classification?.version ?? 0) : null,
  }

  function submit() {
    if (!decision) return
    if (task.real) {
      setSaving(true)
      real
        .run(() => real.decide(task, { decision, followUp, reason: reason.trim(), resolution, defectCode, summary: title.trim() }))
        .then((done) => {
          setSaving(false)
          if (!done) return
          setDecision(null)
          setReason('')
        })
      return
    }
    const at = nowAt()
    const issueIds: string[] = []
    let log = `${DECISION_NAME[decision]}${decision === 'FOLLOW_UP_REQUIRED' ? ` (${FOLLOW_UP_NAME[followUp]})` : ''}${reason ? ` · ${reason}` : ''}`

    if (decision === 'ISSUE_CONFIRMED') {
      if (resolution !== 'NEW') {
        issueIds.push(resolution)
        setIssues((l) => l.map((i) => (i.id === resolution ? { ...i, logs: [...i.logs, { at, who: ME.name, text: `검토 ${task.id} 사진 연결` }] } : i)))
        log += ` → 기존 문제 ${resolution}에 연결`
      } else {
        const id = `IS-${at.slice(0, 5).replace('/', '')}-${String(issues.length + 1).padStart(2, '0')}`
        issueIds.push(id)
        const created: Issue = {
          id,
          facilityId: f.id,
          title: title.trim(),
          status: 'OPEN',
          priority: 'NORMAL',
          createdAt: at,
          reviewId: task.id,
          repairs: [],
          verification: {},
          logs: [{ at, who: ME.name, text: `검토 ${task.id}에서 이상 확인 → 새 문제 등록` }],
        }
        setIssues((l) => [created, ...l])
        log += ` → 새 문제 ${id} 등록`
      }
    }
    if (decision === 'FOLLOW_UP_REQUIRED' && followUp === 'REPHOTO') {
      const id = `RQ-${at.slice(0, 5).replace('/', '')}-${task.id.slice(-2)}`
      setReqs((l) => [{ id, facilityId: f.id, source: 'FOLLOW_UP', reviewId: task.id, reason: reason.trim(), createdAt: at, status: 'WAITING' }, ...l])
      log += ` → 재점검 요청 ${id} (활동 담당자가 다음 정기 순회에 배정)`
    }
    const toSiteVisit = decision === 'FOLLOW_UP_REQUIRED' && followUp === 'SITE_VISIT'
    setReviews((l) =>
      l.map((t) =>
        t.id === task.id
          ? {
              ...t,
              status: toSiteVisit ? 'IN_REVIEW' : 'RESOLVED',
              decision: { decision, followUp: decision === 'FOLLOW_UP_REQUIRED' ? followUp : undefined, reason: reason.trim(), at, who: ME.name, ...basis, issueIds },
              logs: [...t.logs, { at, who: ME.name, text: siteVisit ? `현장 확인 결과: ${log}` : log }],
            }
          : t,
      ),
    )
    setDecision(null)
    setReason('')
  }

  function saveCorrection(next: { kind: Kind; issueTypes: string[] }, why: string) {
    if (task.real) return void real.run(() => real.correctClassification(task, next, why))
    const at = nowAt()
    setReviews((l) =>
      l.map((t) => {
        if (t.id !== task.id) return t
        const prev: Classification = t.classification ?? { source: 'AI', kind: f.kind, issueTypes: [], version: 0, correctionId: null }
        const version = prev.version + 1
        const correctionId = `cor-${t.id.slice(-2)}-${version}`
        return {
          ...t,
          classification: { source: 'HUMAN', kind: next.kind, issueTypes: next.issueTypes, version, correctionId },
          corrections: [...t.corrections, { correctionId, at, who: ME.name, reason: why, from: { kind: prev.kind, issueTypes: prev.issueTypes }, to: next, version }],
          logs: [...t.logs, { at, who: ME.name, text: `분류 정정 ${version}차 · ${clsText(next)} · ${why}` }],
        }
      }),
    )
  }

  const a = task.analysis
  const zp = zoom ? task.photos.find((p) => p.slot === zoom) : undefined
  const zoomPhoto = zp && { bad: zp.bad, media: zp.media, src: zp.src }
  return (
    <Card title={reviewTitle(task)} action={<Pill tone={task.status === 'RESOLVED' ? 'green' : 'gray'}>{REVIEW_STATUS_NAME[task.status]}</Pill>}>
      <div className="grid gap-4 p-4">
        <div className={`grid gap-3 ${task.photos.length > 1 ? 'grid-cols-2' : ''}`}>
          {task.photos.map((p) => (
            <figure key={p.slot} className={`m-0 ${task.photos.length > 1 ? '' : 'mx-auto w-full max-w-[400px]'}`}>
              <button type="button" onClick={() => setZoom(p.slot)} aria-label={`${f.name} ${p.slot} 크게 보기`} className="group relative block w-full cursor-zoom-in rounded-[10px]">
                <Photo kind={f.kind} bad={p.bad} box={!!a?.findings.length} media={p.media} src={p.src} boxes={a?.findings.map((x) => x.bbox)} className={`w-full ${task.type === 'CAPTURE_FAILURE' ? 'blur-[3px]' : ''}`} label={`${f.name} ${p.slot}`} />
                <span className="absolute right-2 bottom-2 rounded-full bg-ink/70 px-2.5 py-1 text-xs font-bold text-white opacity-80 group-hover:opacity-100">크게 보기</span>
              </button>
              <figcaption className="mt-1 text-xs text-mute">
                {p.slot} · 얼굴·번호판 가림 처리된 사진{task.type === 'CAPTURE_FAILURE' ? ' (마지막 시도, 품질 실패)' : ''}
              </figcaption>
            </figure>
          ))}
        </div>
        {task.real && task.photos.map((p) => p.media?.version !== undefined && <PrivacyPanel key={`pv-${p.slot}`} media={p.media} />)}
        {zoom && zoomPhoto && (
          <Modal title={`${f.name} ${zoom}`} onClose={() => setZoom(null)} width="max-w-4xl">
            <Photo kind={f.kind} {...zoomPhoto} box={!!a?.findings.length} boxes={a?.findings.map((x) => x.bbox)} className={`w-full ${task.type === 'CAPTURE_FAILURE' ? 'blur-[4px]' : ''}`} label={`${f.name} ${zoom} 크게`} />
          </Modal>
        )}

        <dl className="m-0 grid grid-cols-[88px_1fr] gap-x-3 gap-y-1.5 text-sm">
          <dt className="text-mute">올라온 이유</dt>
          <dd className="m-0">{TRIGGER_NAME[task.trigger]}</dd>
          <dt className="text-mute">시설</dt>
          <dd className="m-0">
            {KIND_NAME[f.kind]} · {f.name} <span className="text-mute">({f.zone})</span>
          </dd>
          <dt className="text-mute">관리 부서</dt>
          <dd className="m-0">{f.dept ?? DEPT_OF[f.kind]} (시설 대장 기준)</dd>
          <dt className="text-mute">촬영</dt>
          <dd className="m-0">
            {task.team} ({task.members.join(', ')}) · {task.at}
          </dd>
          {f.tags.length > 0 && (
            <>
              <dt className="text-mute">위치 특성</dt>
              <dd className="m-0">{f.tags.join(', ')}</dd>
            </>
          )}
          {task.failure && (
            <>
              <dt className="text-mute">실패 사유</dt>
              <dd className="m-0">{task.failure}</dd>
            </>
          )}
        </dl>

        {a && (
          <section className="border-t border-line pt-4">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <p className="m-0 text-sm font-bold">AI 판독 결과 (참고)</p>
              <span className="flex gap-1.5">
                <Pill tone={a.reviewCategory === 'REVIEW_REQUIRED' ? 'amber' : 'gray'}>{REVIEW_CATEGORY_NAME[a.reviewCategory]}</Pill>
                {a.retryPending && <Pill tone="brand">AI 재판독 중</Pill>}
              </span>
            </div>
            <dl className="m-0 grid grid-cols-[88px_1fr] gap-x-3 gap-y-1 text-[14px]">
              <dt className="text-mute">분석 상태</dt>
              <dd className="m-0">{ANALYSIS_STATUS_NAME[a.status]}</dd>
              <dt className="text-mute">의심 내용</dt>
              <dd className="m-0">
                {a.findings.length ? (
                  <ul className="m-0 grid list-none gap-0.5 p-0">
                    {a.findings.map((fd) => (
                      <li key={fd.code}>
                        <b className="font-semibold">{fd.label}</b> <span className="text-sub">· {fd.evidence}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <span className="text-sub">AI가 이상을 찾지 못했습니다 (시설 안전을 보장하지 않음)</span>
                )}
              </dd>
              <dt className="text-mute">시설 종류</dt>
              <dd className="m-0">
                {a.facilityMatch.matched ? (
                  <>대장과 일치 ({KIND_NAME[f.kind]})</>
                ) : (
                  <span className="text-g2-ink">
                    AI {a.facilityMatch.aiKind ? KIND_NAME[a.facilityMatch.aiKind] : '판별 못함'} · 대장 {KIND_NAME[f.kind]}
                  </span>
                )}
              </dd>
              <dt className="text-mute">모델</dt>
              <dd className="m-0 text-sub">{a.modelVersion}</dd>
            </dl>
            {task.note && <p className="m-0 mt-2 rounded-md bg-g2-soft px-2.5 py-1.5 text-xs text-g2-ink">{task.note}</p>}
            {!AI_SUPPORTED.includes(f.kind) && !task.note && <p className="m-0 mt-2 text-xs text-mute">이 시설은 AI 분류 목록에 아직 없습니다.</p>}
            <p className="m-0 mt-2 text-xs text-mute">AI 결과는 판단 지원입니다. 판정 때 이 결과와 분류 버전이 근거로 함께 기록됩니다.</p>
          </section>
        )}

        {task.type === 'INSPECTION' && (!task.real || task.analysis) && (
          <ClassificationPanel task={task} onSave={saveCorrection} disabled={task.status === 'RESOLVED' || (!!task.real && !task.real.inspectionId)} />
        )}

        {history.length > 0 && (
          <section className="border-t border-line pt-4">
            <p className="m-0 mb-1.5 text-sm font-bold">이 시설의 문제</p>
            <ul className="m-0 grid list-none gap-1.5 p-0">
              {history.map((i) => (
                <li key={i.id}>
                  <button
                    type="button"
                    onClick={() => onOpenIssue(i.id)}
                    aria-label={`${i.id} · ${i.title}`}
                    className="flex w-full items-center gap-3 rounded-xl border border-line bg-surface px-3 py-2.5 text-left text-sm hover:border-brand hover:bg-brand-50/40"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-semibold">{i.title}</span>
                      <span className="block text-xs text-mute">문제 번호 {real.shortId(i.id)}</span>
                    </span>
                    <StatusBadge s={i.status} />
                    <svg viewBox="0 0 24 24" className="size-4 flex-none text-mute" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                      <path d="m9 6 6 6-6 6" />
                    </svg>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        {task.status === 'RESOLVED' && task.decision ? (
          <section className="rounded-lg bg-bg p-3 text-sm">
            <p className="m-0 font-bold">
              판정: {DECISION_NAME[task.decision.decision]}
              {task.decision.followUp && ` (${FOLLOW_UP_NAME[task.decision.followUp]})`}
            </p>
            {task.decision.reason && <p className="m-0 mt-0.5 text-sub">{task.decision.reason}</p>}
            <p className="m-0 mt-0.5 text-xs text-mute">
              {task.decision.who} · {task.decision.at} · 근거{' '}
              {task.decision.analysisResultId === null ? '없음' : task.decision.correctionId ? `사람 정정 ${task.decision.classificationVersion}차` : 'AI 원본 분류'}
            </p>
            {task.decision.issueIds.map((id) => (
              <button key={id} type="button" onClick={() => onOpenIssue(id)} className="mt-1.5 mr-3 text-sm font-semibold text-brand underline underline-offset-2">
                문제 {real.shortId(id)} 보기
              </button>
            ))}
          </section>
        ) : (
          <section className="grid gap-3 rounded-lg border border-brand-100 bg-brand-50/50 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="m-0 text-sm font-bold">{siteVisit ? '현장 확인 결과 입력' : '판정'}</p>
              {basis.analysisResultId && (
                <span className="text-xs text-mute">근거: {basis.correctionId ? `사람 정정 ${basis.classificationVersion}차` : 'AI 원본 분류'}</span>
              )}
            </div>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(150px,1fr))] gap-2" role="radiogroup" aria-label="판정">
              {choices.map((d) => (
                <button
                  key={d}
                  type="button"
                  role="radio"
                  aria-checked={decision === d}
                  aria-label={DECISION_NAME[d]}
                  onClick={() => setDecision(d)}
                  className={`grid content-start gap-0.5 rounded-xl border bg-surface px-3 py-2.5 text-left transition-colors ${decision === d ? DECISION_STYLE[d] : 'border-line-strong hover:border-sub'}`}
                >
                  <span className="flex items-center gap-1.5 text-sm font-bold">
                    <span className={`size-2 flex-none rounded-full ${DECISION_DOT[d]}`} aria-hidden />
                    {DECISION_NAME[d]}
                  </span>
                  <span className="text-xs text-sub">{DECISION_DESC[d]}</span>
                </button>
              ))}
            </div>
            {decision === 'FOLLOW_UP_REQUIRED' && (
              <div className="flex flex-wrap gap-3 text-sm">
                {(['REPHOTO', 'SITE_VISIT'] as FollowUp[]).map((fu) => (
                  <label key={fu} className="flex items-center gap-1.5">
                    <input type="radio" name={`fu-${task.id}`} checked={followUp === fu} onChange={() => setFollowUp(fu)} />
                    {FOLLOW_UP_NAME[fu]}
                    <span className="text-xs text-mute">{fu === 'REPHOTO' ? '· 다음 정기 순회에 재점검 배정' : '· 담당자가 직접 방문'}</span>
                  </label>
                ))}
              </div>
            )}
            {decision === 'ISSUE_CONFIRMED' && (
              <fieldset className="m-0 grid gap-1.5 border-0 p-0 text-sm">
                <legend className="mb-1 font-semibold">문제 처리</legend>
                {openIssues.map((i) => (
                  <label key={i.id} className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 ${resolution === i.id ? 'border-brand bg-surface' : 'border-line bg-surface/60'}`}>
                    <input type="radio" name={`res-${task.id}`} checked={resolution === i.id} onChange={() => setResolution(i.id)} />
                    <span className="min-w-0 flex-1">
                      기존 문제에 연결 · <b className="font-semibold">{i.title}</b>
                      <span className="text-xs text-mute"> ({real.shortId(i.id)})</span>
                    </span>
                    <StatusBadge s={i.status} />
                  </label>
                ))}
                <label className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 ${resolution === 'NEW' ? 'border-brand bg-surface' : 'border-line bg-surface/60'}`}>
                  <input type="radio" name={`res-${task.id}`} checked={resolution === 'NEW'} onChange={() => setResolution('NEW')} />
                  새 문제로 등록
                </label>
                {resolution === 'NEW' && <input value={title} onChange={(e) => setTitle(e.target.value)} className={inputCls} aria-label="새 문제 제목" />}
                {resolution === 'NEW' && task.real && (
                  codes.length ? (
                    <select value={defectCode} onChange={(e) => setDefectCode(e.target.value)} className={inputCls} aria-label="문제 유형">
                      {codes.map((c) => (
                        <option key={c.code} value={c.code}>
                          {c.label}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <p className="m-0 text-xs text-g3-ink">이 시설 종류는 문제 코드 사전이 아직 없어 새 문제로 등록할 수 없습니다. 추가 확인으로 처리해 주세요.</p>
                  )
                )}
              </fieldset>
            )}
            {decision === 'RESOLVE_CAPTURE_PROBLEM' && <p className="m-0 text-xs text-g2-ink">촬영 장애 처리만 끝냅니다. 시설이 정상이라는 판정이 아닙니다.</p>}
            {decision === 'NO_ISSUE' && <p className="m-0 text-xs text-mute">담당자가 사진을 보고 이상이 없다고 확인한 판정입니다. 시설 안전을 보장하지 않습니다.</p>}
            {decision && (
              <label className="grid gap-1 text-sm">
                <span className="font-semibold">
                  {siteVisit ? '현장 확인 내용' : '사유'}
                  {needReason && <span className="text-g3"> *</span>}
                </span>
                <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} className={`${inputCls} !h-auto py-2`} placeholder={siteVisit ? '방문 일시와 확인한 내용' : ''} />
              </label>
            )}
            <div>
              <Button variant="primary" disabled={!ok} onClick={submit}>
                {saving ? '저장 중…' : '판정 저장'}
              </Button>
            </div>
          </section>
        )}

        <section>
          <p className="m-0 mb-1.5 text-sm font-bold">처리 이력</p>
          <ol className="m-0 grid list-none gap-1 p-0 text-[14px]">
            {task.logs.map((l, i) => (
              <li key={i} className="flex gap-2">
                <span className="w-24 flex-none text-mute tabular-nums">{l.at}</span>
                <span>
                  <b className="font-semibold">{l.who}</b> · {l.text}
                </span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </Card>
  )
}

/** 판정 선택지: 고르면 의미별 색이 들어간다 (이상 확인=호박, 이상 없음=초록, 추가 확인=파랑) */
const DECISION_STYLE: Record<Decision, string> = {
  ISSUE_CONFIRMED: 'border-g2 bg-g2-soft ring-2 ring-g2/25',
  NO_ISSUE: 'border-g0 bg-g0-soft ring-2 ring-g0/25',
  FOLLOW_UP_REQUIRED: 'border-brand bg-brand-50 ring-2 ring-brand/25',
  RESOLVE_CAPTURE_PROBLEM: 'border-sub bg-bg ring-2 ring-sub/20',
}
const DECISION_DOT: Record<Decision, string> = {
  ISSUE_CONFIRMED: 'bg-g2',
  NO_ISSUE: 'bg-g0',
  FOLLOW_UP_REQUIRED: 'bg-brand',
  RESOLVE_CAPTURE_PROBLEM: 'bg-sub',
}
const DECISION_DESC: Record<Decision, string> = {
  ISSUE_CONFIRMED: '문제가 있어 처리가 필요해요',
  NO_ISSUE: '사진상 문제가 보이지 않아요',
  FOLLOW_UP_REQUIRED: '다시 찍거나 현장을 봐야 해요',
  RESOLVE_CAPTURE_PROBLEM: '촬영 장애만 끝내요',
}

/** 시설 종류·문제 유형 분류: AI 원본과 사람 정정을 구분해 보여 주고, 사유와 함께 정정한다 (D-08). 위험도 정정은 없다 */
/**
 * 개인정보 검토 (실제 업무 서버): 공개된 사진은 '가림 부족'으로 즉시 보류, 보류된 사진은 확인 뒤 공개 허용.
 * 공개는 AI가 해제를 확인한 뒤에만 된다(처리 중 표시). 실패하면 다시 시도.
 */
function PrivacyPanel({ media }: { media: NonNullable<ReviewTask['photos'][number]['media']> }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const held = media.privacyStatus !== 'APPROVED'
  const rel = media.privacyRelease
  const go = (action: () => Promise<unknown>) => {
    setSaving(true)
    real.run(action).then((done) => {
      setSaving(false)
      if (done) {
        setOpen(false)
        setReason('')
      }
    })
  }
  if (!held && !open)
    return (
      <button type="button" onClick={() => setOpen(true)} className="justify-self-start text-xs text-mute underline underline-offset-2">
        얼굴·번호판 가림이 부족해요
      </button>
    )
  const pending = rel?.status === 'PENDING'
  const failed = rel?.status === 'FAILED'
  return (
    <section className="grid gap-2 rounded-lg border border-g2/40 bg-g2-soft/60 p-3 text-sm" aria-label="개인정보 검토">
      <p className="m-0 font-bold">{held ? '개인정보 보류 중인 사진' : '가림 부족으로 보류하기'}</p>
      <p className="m-0 text-xs text-sub">
        {held
          ? pending
            ? '공개를 허용했습니다. AI가 가림을 다시 확인하면 공개됩니다.'
            : failed
              ? `공개 처리에 실패했습니다${rel?.failureReason ? ` (${rel.failureReason})` : ''}.`
              : '가려지지 않은 얼굴·번호판이 있어 사진을 숨겼습니다. 다시 가린 사진을 확인한 뒤 공개를 허용하세요.'
          : '사진에 가려지지 않은 얼굴이나 번호판이 있으면 바로 숨기고 AI에 다시 가리도록 알립니다.'}
      </p>
      {!pending && !(failed && rel?.retryable) && (
        <>
          <input value={reason} onChange={(e) => setReason(e.target.value)} className={inputCls} placeholder={held ? '예: 다시 가린 사진에서 얼굴이 모두 가려짐' : '예: 왼쪽 사람 얼굴이 가려지지 않음'} aria-label="개인정보 검토 사유" />
          <div className="flex gap-2">
            <Button size="sm" variant="primary" disabled={!reason.trim() || saving} onClick={() => go(() => real.privacyReview(media, held, reason.trim()))}>
              {held ? '가림 확인 · 공개 허용' : '보류하기'}
            </Button>
            {!held && (
              <Button size="sm" onClick={() => setOpen(false)}>
                취소
              </Button>
            )}
          </div>
        </>
      )}
      {failed && rel?.retryable && (
        <div>
          <Button size="sm" variant="primary" disabled={saving} onClick={() => go(() => real.privacyRetry(media))}>
            공개 다시 시도
          </Button>
        </div>
      )}
    </section>
  )
}

function ClassificationPanel({ task, onSave, disabled }: { task: ReviewTask; onSave: (next: { kind: Kind; issueTypes: string[] }, reason: string) => void; disabled: boolean }) {
  const f = fac(task.facilityId)
  const current = task.classification ?? { source: 'AI' as const, kind: f.kind, issueTypes: [], version: 0, correctionId: null }
  const aiOriginal = task.corrections[0]?.from ?? (task.classification?.source === 'AI' ? task.classification : null)
  const [editing, setEditing] = useState(false)
  const [kind, setKind] = useState<Kind>(current.kind)
  const [types, setTypes] = useState<string[]>(current.issueTypes)
  const [why, setWhy] = useState('')
  const changed = kind !== current.kind || types.slice().sort().join() !== current.issueTypes.slice().sort().join()

  return (
    <section className="border-t border-line pt-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <p className="m-0 text-sm font-bold">분류</p>
        {!editing && !disabled && (
          <Button
            size="sm"
            onClick={() => {
              setKind(current.kind)
              setTypes(current.issueTypes)
              setWhy('')
              setEditing(true)
            }}
          >
            분류 정정
          </Button>
        )}
      </div>
      <dl className="m-0 grid grid-cols-[88px_1fr] gap-x-3 gap-y-1 text-[14px]">
        <dt className="text-mute">현재</dt>
        <dd className="m-0">
          {clsText(current)} <Pill tone="gray">{current.source === 'HUMAN' ? `사람 정정 ${current.version}차` : 'AI 원본'}</Pill>
        </dd>
        {current.source === 'HUMAN' && aiOriginal && (
          <>
            <dt className="text-mute">AI 원본</dt>
            <dd className="m-0 text-sub">{clsText(aiOriginal)}</dd>
          </>
        )}
      </dl>

      {editing && (
        <div className="mt-3 grid gap-2.5 rounded-lg bg-bg p-3 text-sm">
          <label className="grid gap-1">
            <span className="font-semibold">시설 종류</span>
            <select
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as Kind)
                setTypes([])
              }}
              className={inputCls}
            >
              {(Object.keys(ISSUE_TYPES) as Kind[]).map((k) => (
                <option key={k} value={k}>
                  {KIND_NAME[k]}
                </option>
              ))}
            </select>
          </label>
          <fieldset className="m-0 border-0 p-0">
            <legend className="mb-1 font-semibold">문제 유형 (여러 개 선택 가능)</legend>
            <div className="flex flex-wrap gap-2">
              {ISSUE_TYPES[kind].map((t) => (
                <label key={t.code} className={`flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1 ${types.includes(t.code) ? 'border-brand bg-brand-50 text-brand' : 'border-line-strong bg-surface'}`}>
                  <input type="checkbox" checked={types.includes(t.code)} onChange={() => setTypes(types.includes(t.code) ? types.filter((x) => x !== t.code) : [...types, t.code])} className="size-4 accent-[var(--color-brand)]" />
                  {t.label}
                </label>
              ))}
            </div>
          </fieldset>
          <label className="grid gap-1">
            <span className="font-semibold">
              정정 사유 <span className="text-g3">*</span>
            </span>
            <input value={why} onChange={(e) => setWhy(e.target.value)} className={inputCls} placeholder="예: 사진상 들뜸이 아니라 깨짐" />
          </label>
          <p className="m-0 text-xs text-mute">AI 원본은 그대로 두고 정정 이력을 추가합니다. 시설 대장·관할 부서·문제 상태는 바뀌지 않습니다.</p>
          <div className="flex gap-2">
            <Button
              variant="primary"
              size="sm"
              disabled={!changed || !why.trim()}
              onClick={() => {
                onSave({ kind, issueTypes: types }, why.trim())
                setEditing(false)
              }}
            >
              정정 저장
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
              취소
            </Button>
          </div>
        </div>
      )}

      {task.corrections.length > 0 && (
        <details className="mt-2 text-[13px]">
          <summary className="cursor-pointer font-semibold text-sub">정정 이력 {task.corrections.length}건</summary>
          <ol className="m-0 mt-1.5 grid list-none gap-1 p-0">
            {task.corrections
              .slice()
              .reverse()
              .map((c) => (
                <li key={c.correctionId} className="rounded-md bg-bg px-2.5 py-1.5">
                  <b className="font-semibold">{c.version}차</b> · {clsText(c.from)} → {clsText(c.to)}
                  <span className="block text-mute">
                    {c.who} · {c.at} · {c.reason}
                  </span>
                </li>
              ))}
          </ol>
        </details>
      )}
    </section>
  )
}
