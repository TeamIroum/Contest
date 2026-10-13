import { useMemo, useState, type Dispatch, type SetStateAction } from 'react'
import { KIND_NAME, Photo } from './Photo'
import {
  CHANGE_NAME,
  DEPTS,
  DEPT_OF,
  FOLLOW_UP_NAME,
  ISSUE_FLOW,
  ISSUE_STATUS_NAME,
  ME,
  PATROLS,
  TEAMS,
  VERDICT_NAME,
  VERIFY_NAME,
  aiCompare,
  fac,
  type FollowUp,
  type Issue,
  type IssueStatus,
  type Patrol,
  type Reinspection,
  type VerifyDecision,
} from './data'
import { Button, Card, DetailPane, Empty, PickHint, PageHead, Pill, StatusBadge, Tabs, inputCls } from './ui'
import { nowAt } from './util'
import * as real from './api/real'

// 시설 담당자 · 사건·보수 (API 10절)
// 접수 → 보수 요청 → 보수 중 → 확인 대기 → 종결. 수리 확인은 사람이 최종 판정한다.

export function Issues({
  issues,
  setIssues,
  reqs,
  setReqs,
  patrols,
  selected,
  onSelect,
  onGoAssign,
}: {
  issues: Issue[]
  setIssues: Dispatch<SetStateAction<Issue[]>>
  reqs: Reinspection[]
  setReqs: Dispatch<SetStateAction<Reinspection[]>>
  patrols: Patrol[]
  selected: string | null
  onSelect: (id: string | null) => void
  onGoAssign: () => void
}) {
  const [status, setStatus] = useState<'all' | IssueStatus>('all')
  const [dept, setDept] = useState('all')
  const deptOf = (i: Issue) => i.repairs.at(-1)?.dept || i.real?.deptName || DEPT_OF[fac(i.facilityId).kind]
  // 부서 거르기: 실제 백엔드면 받은 문제의 부서, 아니면 예시 부서 목록
  const depts = issues.some((i) => i.real) ? [...new Set(issues.map(deptOf))] : DEPTS
  const list = useMemo(
    () =>
      issues
        .filter((i) => status === 'all' || i.status === status)
        .filter((i) => dept === 'all' || deptOf(i) === dept)
        .sort((a, b) => Number(a.status === 'CLOSED') - Number(b.status === 'CLOSED') || b.createdAt.localeCompare(a.createdAt)),
    [issues, status, dept],
  )
  const current = issues.find((i) => i.id === selected) ?? null
  const count = (s: IssueStatus) => issues.filter((i) => i.status === s).length

  return (
    <>
      <PageHead
        title="사건·보수"
        sub="보수 진행과 수리 확인을 관리합니다."
        help="접수 → 보수 요청 → 보수 중 → 확인 대기 → 종결 순서로 진행됩니다. 보수가 끝나면 재점검 요청이 만들어지고, 다음 정기 순회 사진이나 현장 확인으로 수리 여부를 판정합니다."
        right={
          <select value={dept} onChange={(e) => setDept(e.target.value)} className={`${inputCls} !w-auto`} aria-label="담당 부서">
            <option value="all">모든 부서</option>
            {depts.map((d) => (
              <option key={d}>{d}</option>
            ))}
          </select>
        }
      />
      <div className="mb-4 overflow-x-auto">
        <Tabs value={status} onChange={setStatus} options={[['all', `전체 ${issues.length}`], ...ISSUE_FLOW.map((s) => [s, `${ISSUE_STATUS_NAME[s]} ${count(s)}`] as [IssueStatus, string])]} />
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)] items-start gap-4 xl:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
        <Card>
          {list.length === 0 ? (
            <Empty>해당하는 사건이 없습니다.</Empty>
          ) : (
            <ul className="m-0 list-none p-0">
              {list.map((i) => {
                const f = fac(i.facilityId)
                return (
                  <li key={i.id} className="border-b border-line last:border-0">
                    <button
                      type="button"
                      onClick={() => onSelect(i.id)}
                      aria-current={selected === i.id ? 'true' : undefined}
                      className={`flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-bg aria-[current=true]:bg-brand-50 aria-[current=true]:shadow-[inset_4px_0_0_var(--color-brand)] ${i.status === 'CLOSED' ? 'opacity-70' : ''}`}
                    >
                      <Photo kind={f.kind} bad={i.status !== 'CLOSED'} media={(i.status === 'CLOSED' ? i.verification.after : i.beforePhoto)?.media} src={(i.status === 'CLOSED' ? i.verification.after : i.beforePhoto)?.src} className="w-16 flex-none" />
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-1.5">
                          <StatusBadge s={i.status} />
                          {i.status === 'AWAITING_VERIFICATION' && i.verification.after && <Pill tone="amber">재점검 사진 도착</Pill>}
                        </span>
                        <span className="mt-1 block truncate text-sm font-semibold">{i.title}</span>
                        <span className="block truncate text-xs text-mute">
                          {real.shortId(i.id)} · {deptOf(i)} · 접수 {i.createdAt}
                        </span>
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </Card>
        <DetailPane open={!!current} onClose={() => onSelect(null)}>
          {current ? (
            <IssueDetail key={current.id} issue={current} setIssues={setIssues} reqs={reqs} setReqs={setReqs} patrols={patrols} onGoAssign={onGoAssign} />
          ) : (
            <PickHint>왼쪽 목록에서 사건을 고르세요.</PickHint>
          )}
        </DetailPane>
      </div>
    </>
  )
}

function Stepper({ status }: { status: IssueStatus }) {
  const at = ISSUE_FLOW.indexOf(status)
  return (
    <ol className="m-0 flex list-none gap-1 p-0" aria-label="진행 단계">
      {ISSUE_FLOW.map((s, i) => (
        <li key={s} className="flex-1">
          <i className={`block h-1.5 rounded-full ${i <= at ? 'bg-brand' : 'bg-line'}`} />
          <span className={`mt-1 block text-center text-[13px] ${i === at ? 'font-bold text-brand' : 'text-mute'}`}>{ISSUE_STATUS_NAME[s]}</span>
        </li>
      ))}
    </ol>
  )
}

function patrolLabel(patrols: Patrol[], id?: string) {
  const p = patrols.find((x) => x.id === id) ?? PATROLS.find((x) => x.id === id)
  if (!p) return '-'
  return `${p.date} ${TEAMS.find((t) => t.id === p.teamId)?.name ?? ''} 순회`
}

function IssueDetail({
  issue,
  setIssues,
  reqs,
  setReqs,
  patrols,
  onGoAssign,
}: {
  issue: Issue
  setIssues: Dispatch<SetStateAction<Issue[]>>
  reqs: Reinspection[]
  setReqs: Dispatch<SetStateAction<Reinspection[]>>
  patrols: Patrol[]
  onGoAssign: () => void
}) {
  const f = fac(issue.facilityId)
  const last = issue.repairs.at(-1)
  const req = reqs.find((r) => r.issueId === issue.id && r.status !== 'DONE') ?? reqs.find((r) => r.issueId === issue.id)
  const [dept, setDept] = useState(issue.real?.deptName ?? DEPT_OF[f.kind])
  const [memo, setMemo] = useState('')
  const [desc, setDesc] = useState('')
  const [evidence, setEvidence] = useState(false)
  const [saving, setSaving] = useState(false)
  /** 실제 백엔드: 서버에 저장하고 다시 불러온다 (입력 칸은 성공했을 때만 비운다) */
  const save = (action: () => Promise<unknown>, clear: () => void) => {
    setSaving(true)
    real.run(action).then((ok) => {
      setSaving(false)
      if (ok) clear()
    })
  }

  function patch(p: Partial<Issue>, text: string) {
    const at = nowAt()
    setIssues((l) => l.map((i) => (i.id === issue.id ? { ...i, ...p, logs: [...i.logs, { at, who: ME.name, text }] } : i)))
  }

  function requestRepair() {
    if (issue.real) return save(() => real.requestRepair(issue, memo.trim()), () => setMemo(''))
    const at = nowAt()
    patch({ status: 'REPAIR_REQUESTED', repairs: [...issue.repairs, { round: issue.repairs.length + 1, dept, memo: memo.trim(), requestedAt: at }] }, `${dept}에 보수 요청${memo ? ` · ${memo}` : ''}`)
    setMemo('')
  }
  function startRepair() {
    if (issue.real) return save(() => real.startRepair(issue), () => {})
    const at = nowAt()
    patch({ status: 'IN_REPAIR', repairs: issue.repairs.map((r, i) => (i === issue.repairs.length - 1 ? { ...r, startedAt: at } : r)) }, '보수 시작')
  }
  function completeRepair() {
    if (issue.real) return save(() => real.completeRepair(issue, desc.trim()), () => setDesc(''))
    const at = nowAt()
    // 보수 완료와 재점검 요청 생성은 한 번에 기록한다 (API 10절)
    const rqId = `RQ-${at.slice(0, 5).replace('/', '')}-${issue.id.slice(-2)}${issue.repairs.length}`
    setReqs((l) => [{ id: rqId, facilityId: f.id, source: 'REPAIR', issueId: issue.id, reason: `보수 완료 확인 (${issue.repairs.length}회차)`, createdAt: at, status: 'WAITING' }, ...l])
    patch(
      {
        status: 'AWAITING_VERIFICATION',
        verification: {},
        repairs: issue.repairs.map((r, i) => (i === issue.repairs.length - 1 ? { ...r, completedAt: at, description: desc.trim(), evidence } : r)),
      },
      `보수 완료${desc ? ` · ${desc}` : ''} → 재점검 요청 ${rqId} 생성`,
    )
    setDesc('')
  }

  return (
    <Card title={issue.title} action={<StatusBadge s={issue.status} />}>
      <div className="grid gap-4 p-4">
        <Stepper status={issue.status} />
        <dl className="m-0 grid grid-cols-[88px_1fr] gap-x-3 gap-y-1.5 text-sm">
          <dt className="text-mute">문제 번호</dt>
          <dd className="m-0">{real.shortId(issue.id)}</dd>
          <dt className="text-mute">시설</dt>
          <dd className="m-0">
            {KIND_NAME[f.kind]} · {f.name} <span className="text-mute">({f.zone})</span>
          </dd>
          <dt className="text-mute">접수</dt>
          <dd className="m-0">
            {issue.createdAt}
            {issue.reviewId && <span className="text-mute"> · 검토 {real.shortId(issue.reviewId)}</span>}
          </dd>
          {issue.closedAt && (
            <>
              <dt className="text-mute">종결</dt>
              <dd className="m-0">{issue.closedAt}</dd>
            </>
          )}
        </dl>

        {issue.repairs.length > 0 && (
          <section>
            <p className="m-0 mb-1.5 text-sm font-bold">보수 이력</p>
            <ol className="m-0 grid list-none gap-2 p-0">
              {issue.repairs.map((r) => (
                <li key={r.round} className="rounded-lg border border-line px-3 py-2 text-[14px]">
                  <p className="m-0 font-semibold">
                    {r.round}회차 · {r.dept}
                    {r.round > 1 && (
                      <span className="ml-1.5">
                        <Pill tone="amber">재보수</Pill>
                      </span>
                    )}
                  </p>
                  {r.memo && <p className="m-0 text-sub">요청: {r.memo}</p>}
                  <p className="m-0 text-mute">
                    요청 {r.requestedAt}
                    {r.startedAt && ` · 시작 ${r.startedAt}`}
                    {r.completedAt && ` · 완료 ${r.completedAt}`}
                  </p>
                  {r.description && (
                    <p className="m-0 text-sub">
                      완료 내용: {r.description}
                      {r.evidence && ' · 증빙 사진 있음'}
                    </p>
                  )}
                </li>
              ))}
            </ol>
          </section>
        )}

        {issue.status === 'OPEN' && (
          <Action title="보수 요청">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="grid gap-1 text-sm">
                <span className="font-semibold">담당 부서</span>
                <select value={dept} onChange={(e) => setDept(e.target.value)} className={inputCls} disabled={!!issue.real}>
                  {(issue.real ? [dept] : DEPTS).map((d) => (
                    <option key={d}>
                      {d}
                      {d === (f.dept ?? DEPT_OF[f.kind]) ? ' (시설 대장)' : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="grid gap-1 text-sm">
                <span className="font-semibold">요청 메모</span>
                <input value={memo} onChange={(e) => setMemo(e.target.value)} className={inputCls} />
              </label>
            </div>
            <div>
              <Button variant="primary" onClick={requestRepair} disabled={saving || (!!issue.real && !issue.real.deptId)}>
                보수 요청
              </Button>
            </div>
          </Action>
        )}
        {issue.status === 'REPAIR_REQUESTED' && (
          <Action title={`${last?.dept} 보수 대기`}>
            <p className="m-0 text-sm text-sub">담당 부서가 보수를 시작하면 표시합니다.</p>
            <div>
              <Button variant="primary" onClick={startRepair} disabled={saving}>
                보수 시작으로 변경
              </Button>
            </div>
          </Action>
        )}
        {issue.status === 'IN_REPAIR' && (
          <Action title="보수 완료 입력">
            <label className="grid gap-1 text-sm">
              <span className="font-semibold">완료 내용</span>
              <input value={desc} onChange={(e) => setDesc(e.target.value)} className={inputCls} placeholder="예: 블록 3장 교체" />
            </label>
            {!issue.real && (
            <label className="flex items-start gap-2 text-sm">
              <input type="checkbox" checked={evidence} onChange={(e) => setEvidence(e.target.checked)} className="mt-1" />
              <span>
                보수 증빙 사진 첨부
                <span className="block text-xs text-mute">개인정보 검사를 거친 사진만 저장되며 판독 실적에는 넣지 않습니다.</span>
              </span>
            </label>
            )}
            <p className="m-0 text-xs text-mute">완료하면 재점검 요청이 만들어지고, 활동 담당자가 다음 정기 순회에 배정합니다.</p>
            <div>
              <Button variant="primary" onClick={completeRepair} disabled={saving || (!!issue.real && !desc.trim())}>
                보수 완료
              </Button>
            </div>
          </Action>
        )}
        {issue.status === 'AWAITING_VERIFICATION' && (
          <Verify issue={issue} req={req} patrols={patrols} setIssues={setIssues} setReqs={setReqs} onGoAssign={onGoAssign} />
        )}
        {issue.status === 'CLOSED' && issue.verification.after && (
          <section className="grid grid-cols-2 gap-3 text-center text-xs text-mute">
            <div>
              <Photo kind={f.kind} bad {...issue.beforePhoto} className="w-full" />
              <p className="m-0 mt-1">보수 전</p>
            </div>
            <div>
              <Photo kind={f.kind} bad={issue.verification.after.bad} media={issue.verification.after.media} src={issue.verification.after.src} className="w-full" />
              <p className="m-0 mt-1">재점검 {issue.verification.after.at}</p>
            </div>
          </section>
        )}

        <section>
          <p className="m-0 mb-1.5 text-sm font-bold">처리 이력</p>
          <ol className="m-0 grid list-none gap-1 p-0 text-[14px]">
            {issue.logs.map((l, i) => (
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

function Action({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-3 rounded-lg border border-brand-100 bg-brand-50/50 p-3">
      <p className="m-0 text-sm font-bold">{title}</p>
      {children}
    </section>
  )
}

// 수리 확인: 근거 준비(사진 또는 현장 확인) → 사람 판정 (CLOSE / REPAIR_AGAIN / FOLLOW_UP_REQUIRED)
function Verify({
  issue,
  req,
  patrols,
  setIssues,
  setReqs,
  onGoAssign,
}: {
  issue: Issue
  req?: Reinspection
  patrols: Patrol[]
  setIssues: Dispatch<SetStateAction<Issue[]>>
  setReqs: Dispatch<SetStateAction<Reinspection[]>>
  onGoAssign: () => void
}) {
  const f = fac(issue.facilityId)
  const after = issue.verification.after
  const ai = after ? (issue.verification.ai ?? aiCompare(after.bad)) : undefined
  const [method, setMethod] = useState<'PHOTO' | 'SITE_VISIT'>(after ? 'PHOTO' : 'SITE_VISIT')
  const [fieldNote, setFieldNote] = useState('')
  const [decision, setDecision] = useState<VerifyDecision | null>(null)
  const [followUp, setFollowUp] = useState<FollowUp>('REPHOTO')
  const [reason, setReason] = useState('')

  const [saving, setSaving] = useState(false)
  const evidenceOk = method === 'PHOTO' ? !!after : fieldNote.trim().length > 0
  // 실제 업무 서버는 종결할 때도 사유를 받는다 (검토 판정과 같음)
  const needReason = decision !== null && (decision !== 'CLOSE' || !!issue.real)
  const ok = decision !== null && evidenceOk && (!needReason || reason.trim().length > 0) && !saving

  function submit() {
    if (!decision) return
    if (issue.real) {
      // 실제 업무 서버: 현장 확인으로 준비 → 판정 (재점검 사진 판정은 재점검 촬영 칸 배정이 연결되면)
      setSaving(true)
      real
        .run(() => real.verifySiteVisit(issue, { decision, followUp, fieldNote: fieldNote.trim(), reason: reason.trim() }))
        .then((done) => {
          setSaving(false)
          if (!done) return
          setDecision(null)
          setReason('')
          setFieldNote('')
        })
      return
    }
    const at = nowAt()
    const how = method === 'PHOTO' ? '사진 확인' : `현장 확인(${fieldNote.trim()})`
    const text = `수리 확인 · ${how} → ${VERIFY_NAME[decision]}${decision === 'FOLLOW_UP_REQUIRED' ? ` (${FOLLOW_UP_NAME[followUp]})` : ''}${reason ? ` · ${reason}` : ''}`
    setIssues((l) =>
      l.map((i) => {
        if (i.id !== issue.id) return i
        const logs = [...i.logs, { at, who: ME.name, text }]
        if (decision === 'CLOSE') return { ...i, status: 'CLOSED', closedAt: at, verification: { after: i.verification.after, ai }, logs }
        if (decision === 'REPAIR_AGAIN') {
          const prev = i.repairs.at(-1)!
          return { ...i, status: 'REPAIR_REQUESTED', verification: {}, repairs: [...i.repairs, { round: i.repairs.length + 1, dept: prev.dept, memo: reason.trim(), requestedAt: at }], logs }
        }
        // 추가 확인: 같은 회차의 요청을 열어 두고 확인 방법만 바꾼다
        return { ...i, verification: followUp === 'REPHOTO' ? {} : i.verification, logs }
      }),
    )
    if (req) {
      if (decision === 'FOLLOW_UP_REQUIRED' && followUp === 'REPHOTO') setReqs((l) => l.map((r) => (r.id === req.id ? { ...r, status: 'WAITING', patrolId: undefined, reason: `추가 확인: ${reason.trim()}` } : r)))
      else if (decision !== 'FOLLOW_UP_REQUIRED') setReqs((l) => l.map((r) => (r.id === req.id ? { ...r, status: 'DONE' } : r)))
    }
    setDecision(null)
    setReason('')
  }

  return (
    <section className="grid gap-3 rounded-lg border border-brand-100 bg-brand-50/50 p-3">
      <p className="m-0 text-sm font-bold">수리 확인</p>
      <div className="grid grid-cols-2 gap-3 text-center text-xs text-mute">
        <div>
          <Photo kind={f.kind} bad {...issue.beforePhoto} className="w-full" />
          <p className="m-0 mt-1">보수 전</p>
        </div>
        <div>
          {after ? (
            <>
              <Photo kind={f.kind} bad={after.bad} media={after.media} src={after.src} className="w-full" />
              <p className="m-0 mt-1">
                재점검 {after.at} · {after.team}
              </p>
            </>
          ) : (
            <div className="grid aspect-[4/3] place-items-center rounded-[10px] border border-dashed border-line-strong bg-surface p-2 text-[14px] text-sub">
              {issue.real ? (
                <span>
                  재점검 촬영 배정은
                  <br />
                  아직 준비 중이에요
                  <br />
                  <span className="text-xs text-mute">현장 확인으로 판정해 주세요</span>
                </span>
              ) : req?.status === 'ASSIGNED' ? (
                <span>
                  {patrolLabel(patrols, req.patrolId)}에 배정됨
                  <br />
                  촬영 대기
                </span>
              ) : (
                <span>
                  다음 정기 순회 배정 전
                  <br />
                  <button type="button" onClick={onGoAssign} className="mt-1 font-semibold text-brand underline underline-offset-2">
                    순회에 배정하러 가기
                  </button>
                </span>
              )}
            </div>
          )}
        </div>
      </div>
      {ai && (
        <p className="m-0 rounded-md bg-surface px-3 py-2 text-[14px]">
          AI 전후 비교 (참고): <b>{VERDICT_NAME[ai.verdict]}</b> · 변화 {CHANGE_NAME[ai.change]}
          <span className="block text-xs text-mute">AI 비교는 판단 지원입니다. 판단이 어려우면 종결로 처리하지 않습니다.</span>
        </p>
      )}
      <div className="flex flex-wrap gap-4 text-sm">
        <label className="flex items-center gap-1.5">
          <input type="radio" name={`m-${issue.id}`} checked={method === 'PHOTO'} disabled={!after} onChange={() => setMethod('PHOTO')} />
          재점검 사진으로 확인
        </label>
        <label className="flex items-center gap-1.5">
          <input type="radio" name={`m-${issue.id}`} checked={method === 'SITE_VISIT'} onChange={() => setMethod('SITE_VISIT')} />
          현장 확인 (직접 방문)
        </label>
      </div>
      {method === 'SITE_VISIT' && (
        <label className="grid gap-1 text-sm">
          <span className="font-semibold">
            현장 확인 기록 <span className="text-g3">*</span>
          </span>
          <input value={fieldNote} onChange={(e) => setFieldNote(e.target.value)} className={inputCls} placeholder="방문 일시와 확인한 내용" />
        </label>
      )}
      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="수리 확인 판정">
        {(['CLOSE', 'REPAIR_AGAIN', 'FOLLOW_UP_REQUIRED'] as VerifyDecision[]).map((d) => (
          <button
            key={d}
            type="button"
            role="radio"
            aria-checked={decision === d}
            onClick={() => setDecision(d)}
            className="h-9 rounded-lg border border-line-strong bg-surface px-3 text-sm font-semibold text-sub aria-checked:border-brand aria-checked:bg-brand aria-checked:text-white"
          >
            {VERIFY_NAME[d]}
          </button>
        ))}
      </div>
      {decision === 'FOLLOW_UP_REQUIRED' && (
        <div className="flex flex-wrap gap-3 text-sm">
          {(['REPHOTO', 'SITE_VISIT'] as FollowUp[]).map((fu) => (
            <label key={fu} className="flex items-center gap-1.5">
              <input type="radio" name={`vf-${issue.id}`} checked={followUp === fu} onChange={() => setFollowUp(fu)} />
              {FOLLOW_UP_NAME[fu]}
            </label>
          ))}
        </div>
      )}
      {needReason && (
        <label className="grid gap-1 text-sm">
          <span className="font-semibold">
            사유 <span className="text-g3">*</span>
          </span>
          <input value={reason} onChange={(e) => setReason(e.target.value)} className={inputCls} />
        </label>
      )}
      {!evidenceOk && decision && <p className="m-0 text-xs text-g3-ink">{method === 'PHOTO' ? '재점검 사진이 아직 없습니다.' : '현장 확인 기록을 입력하세요.'}</p>}
      <div>
        <Button variant="primary" disabled={!ok} onClick={submit}>
          판정 저장
        </Button>
      </div>
    </section>
  )
}
