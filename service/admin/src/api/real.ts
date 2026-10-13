// 실제 업무 백엔드 응답을 화면 모양(data.ts의 Patrol·ReviewTask·Issue)으로 바꾸고, 처리 결과를 서버에 보낸다.
// 응답 형식: backend PatrolViews(AdminPatrol), ReviewViews(ReviewTask), IssueViews(IssueDetail). 필드 이름이 계약이다.
import type { Kind } from '../Photo'
import type { MediaRef } from '../media'
import {
  COURSES,
  FACILITIES,
  FACILITY_TYPE,
  ISSUE_TYPES,
  KIND_OF_TYPE,
  PARTICIPANTS,
  TEAMS,
  TRIGGER_NAME,
  DECISION_NAME,
  FOLLOW_UP_NAME,
  VERIFY_NAME,
  type Analysis,
  type Attendance,
  type Decision,
  type FollowUp,
  type Issue,
  type IssueStatus,
  type Log,
  type Member,
  type Participant,
  type Patrol,
  type PatrolStatus,
  type Repair,
  type ReviewTask,
  type TriggerReason,
} from '../data'
import { ApiError, api } from './client'

/* ---------------- 처리 결과 알림 (App이 다시 불러오고, 실패하면 알림을 띄운다) ---------------- */

const changedListeners = new Set<() => void>()
const errorListeners = new Set<(msg: string) => void>()
export function onRealChanged(fn: () => void) {
  changedListeners.add(fn)
  return () => void changedListeners.delete(fn)
}
export function onRealError(fn: (msg: string) => void) {
  errorListeners.add(fn)
  return () => void errorListeners.delete(fn)
}
/** 서버에 저장하는 처리 하나를 실행한다. 성공하면 화면을 서버 값으로 다시 불러오고 true, 실패하면 서버 안내를 띄우고 false */
export async function run(action: () => Promise<unknown>): Promise<boolean> {
  try {
    await action()
    return true
  } catch (e) {
    const msg = e instanceof ApiError ? (e.serverMessage ?? e.message) : '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    errorListeners.forEach((f) => f(msg))
    return false
  } finally {
    // 실패해도 최신 상태(version)를 받아 둔다. 다른 사람이 먼저 처리한 경우 등
    changedListeners.forEach((f) => f())
  }
}

/* ---------------- 시각 (모두 한국 시간) ---------------- */

const kst = (iso: string) => new Date(new Date(iso).getTime() + 9 * 3600_000).toISOString()
/** 'MM/DD HH:mm' (화면의 다른 시각 표기와 같은 모양) */
export const mdhm = (iso?: string | null) => (iso ? `${kst(iso).slice(5, 7)}/${kst(iso).slice(8, 10)} ${kst(iso).slice(11, 16)}` : '')
const hm = (iso?: string | null) => (iso ? kst(iso).slice(11, 16) : undefined)
/** 한국 시간 오늘 (YYYY-MM-DD) */
export const todayKst = () => new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10)
/** 'YYYY-MM-DD' → 'MM/DD' */
export const md = (date: string) => `${date.slice(5, 7)}/${date.slice(8, 10)}`
/** 오늘 날짜의 'HH:mm'(한국 시간)을 ISO 시각으로 */
export function todayAt(hhmm: string) {
  // 지금과 같은 분이면 현재 시각(초 포함)을 보낸다. 분 단위로 자르면 같은 분에 시작한 조원의 복귀가
  // '복귀 시각이 시작 시각보다 빨라요'(422)로 거절된다
  const now = new Date()
  const nowHm = new Date(now.getTime() + 9 * 3600_000).toISOString().slice(11, 16)
  if (hhmm === nowHm) return now.toISOString()
  return new Date(`${todayKst()}T${hhmm}:00+09:00`).toISOString()
}

/* ---------------- 시설·조·코스·참여자 (fac·course·pName이 찾을 수 있게 등록) ---------------- */

const kindOf = (type?: string | null): Kind => KIND_OF_TYPE[type ?? ''] ?? 'sidewalk'

type ApiFacility = { id: string; type: string; name: string; areaLabel?: string | null; managingDepartment?: { id: string; name: string } | null }
function putFacility(f: ApiFacility) {
  const row = { id: f.id, kind: kindOf(f.type), name: f.name, zone: f.areaLabel ?? '', tags: [] as string[], dept: f.managingDepartment?.name, deptId: f.managingDepartment?.id }
  const i = FACILITIES.findIndex((x) => x.id === f.id)
  if (i < 0) FACILITIES.push(row)
  else FACILITIES[i] = { ...FACILITIES[i], ...row, dept: row.dept ?? FACILITIES[i].dept, deptId: row.deptId ?? FACILITIES[i].deptId }
}
function upsert<T extends { id: string }>(list: T[], row: T) {
  const i = list.findIndex((x) => x.id === row.id)
  if (i < 0) list.push(row)
  else list[i] = row
}

/** 문제 코드 사전을 업무 백엔드 사전(backend taxonomy/defect-codes.json, AI taxonomy 사본)으로 맞춘다 */
const DEFECTS: Partial<Record<Kind, { code: string; label: string }[]>> = {
  sidewalk: [
    { code: 'lifted', label: '들뜸' },
    { code: 'broken', label: '파손' },
    { code: 'sunken', label: '꺼짐' },
  ],
  drain: [
    { code: 'covered', label: '낙엽·쓰레기로 덮임' },
    { code: 'cover_damaged', label: '덮개 파손' },
    { code: 'cover_missing', label: '덮개 이탈' },
  ],
  bench: [
    { code: 'board_broken', label: '좌판·등받이 파손' },
    { code: 'part_detached', label: '부품 이탈' },
  ],
  fence: [
    { code: 'tilted', label: '기울어짐' },
    { code: 'broken', label: '파손' },
    { code: 'joint_detached', label: '연결부 이탈' },
  ],
  exercise: [
    { code: 'broken', label: '부품 파손' },
    { code: 'part_detached', label: '부품 이탈' },
    { code: 'deformed', label: '변형' },
  ],
  playground: [],
  snowbox: [],
  shade: [],
}
export function applyServerDefectCodes() {
  for (const k of Object.keys(DEFECTS) as Kind[]) ISSUE_TYPES[k] = DEFECTS[k]!
}

/* ---------------- 오늘 활동 현황 (업무 API 8절) ---------------- */

type ApiAdminMember = {
  participantId: string
  name: string
  attendance: 'UNCHECKED' | 'PRESENT' | 'ABSENT'
  attendanceRequestedAt?: string | null
  startedAt?: string | null
  startSource?: string | null
  returnedAt?: string | null
  returnSource?: string | null
  phoneNumber?: string | null
  proxyAttendanceReason?: string | null
  returned: boolean
  returnReason?: string | null
  abortReason?: string | null
  returnRequest?: { returnReason?: string | null; abortReason?: string | null } | null
  paused?: boolean
}
type ApiStop = { id: string; order: number; facilityId: string; facilityType: string; name: string; captureSlots: { label: string; purpose?: string }[] }
type ApiAdminPatrol = {
  id: string
  version: number
  date: string
  activityStatus: 'ASSIGNED' | 'IN_PROGRESS' | 'RETURNING' | 'ENDED' | 'CANCELLED'
  currentOutcomes?: { normalReturned: number; abortReturned: number } | null
  teamName: string
  routeName: string
  meetingPoint?: { name: string } | null
  scheduledStartAt?: string | null
  expectedReturnAt?: string | null
  departureConfirmedAt?: string | null
  members: ApiAdminMember[]
  progress: { total: number; captured: number; skipped: number; reviewRequested: number }
  stops: ApiStop[]
  organizationName?: string | null
  cancellationReason?: string | null
  recentEvents?: { type: string; actor?: { name: string } | null; occurredAt: string }[]
}

function attendanceOf(m: ApiAdminMember): Attendance {
  if (m.attendance === 'PRESENT') return 'PRESENT'
  if (m.attendance === 'ABSENT') return 'ABSENT'
  return m.attendanceRequestedAt ? 'REQUESTED' : 'NONE'
}

function toPatrol(p: ApiAdminPatrol): Patrol {
  const teamId = `team:${p.teamName}`
  upsert(TEAMS, { id: teamId, name: p.teamName, memberIds: p.members.map((m) => m.participantId) })
  for (const s of p.stops) putFacility({ id: s.facilityId, type: s.facilityType, name: s.name })
  upsert(COURSES, {
    id: `route:${p.id}`,
    name: p.routeName,
    zone: '',
    start: p.meetingPoint?.name ?? '',
    stops: [...p.stops].sort((a, b) => a.order - b.order).map((s) => ({ facilityId: s.facilityId, slots: s.captureSlots.map((c) => c.label) })),
  })
  for (const m of p.members) {
    const known = PARTICIPANTS.find((x) => x.id === m.participantId)
    upsert(PARTICIPANTS, { id: m.participantId, name: m.name, phone: m.phoneNumber ?? known?.phone ?? '', org: p.organizationName ?? '', days: [], status: 'ACTIVE' })
  }
  const abort = p.members.find((m) => (m.returnReason ?? m.returnRequest?.returnReason) === 'ABORT')
  const ended = p.activityStatus === 'ENDED'
  const status: PatrolStatus = ended ? ((p.currentOutcomes?.abortReturned ?? 0) > 0 && (p.currentOutcomes?.normalReturned ?? 0) === 0 ? 'ABORTED' : 'COMPLETED') : (p.activityStatus as PatrolStatus)
  const members: Member[] = p.members.map((m) => ({
    id: m.participantId,
    attendance: attendanceOf(m),
    proxyReason: m.proxyAttendanceReason ?? undefined,
    startedAt: hm(m.startedAt),
    startSource: m.startSource === 'ADMIN_CORRECTION' ? 'ADMIN_CORRECTION' : m.startedAt ? 'SELF' : undefined,
    returnedAt: hm(m.returnedAt),
    returnSource: m.returnSource === 'ADMIN_CORRECTION' ? 'ADMIN_CORRECTION' : m.returnedAt ? 'MANAGER_CONFIRMATION' : undefined,
    returned: m.returned,
    paused: !!m.paused,
  }))
  return {
    id: p.id,
    date: md(p.date),
    teamId,
    courseId: `route:${p.id}`,
    start: hm(p.scheduledStartAt) ?? '',
    expectedReturn: hm(p.expectedReturnAt) ?? '',
    status,
    departureConfirmedAt: hm(p.departureConfirmedAt),
    members,
    progress: { captured: p.progress.captured, skipped: p.progress.skipped, review: p.progress.reviewRequested },
    paused: p.members.some((m) => m.paused),
    returnReason: abort ? 'ABORT' : p.members.some((m) => m.returnReason ?? m.returnRequest?.returnReason) ? 'NORMAL' : undefined,
    abortReason: ((abort?.abortReason ?? abort?.returnRequest?.abortReason) as Patrol['abortReason']) ?? undefined,
    cancellationReason: p.cancellationReason ?? undefined,
    reinspect: p.stops.filter((s) => s.captureSlots.some((c) => c.purpose === 'REINSPECTION')).map((s) => s.facilityId),
    followUps: [],
    real: { version: p.version },
  }
}

/** 오늘 순회 전부 (목록 → 순회마다 상세) */
export async function loadTodayPatrols(): Promise<Patrol[]> {
  const list = await api<{ items: { id: string }[] }>('GET', `/admin/patrols?date=${todayKst()}&size=100`)
  const details = await Promise.all(list.items.map((x) => api<ApiAdminPatrol>('GET', `/admin/patrols/${x.id}`)))
  return details.map(toPatrol)
}

export const confirmAttendance = (p: Patrol, participantId: string, attendance: 'PRESENT' | 'ABSENT', proxyReason?: string) =>
  api('PUT', `/admin/patrols/${p.id}/attendance/${participantId}`, { attendance, proxyReason: proxyReason || undefined, version: p.real!.version })
export const confirmDeparture = (p: Patrol) => api('POST', `/admin/patrols/${p.id}/departure-confirmations`, { version: p.real!.version }, { idempotent: true })
export const confirmReturn = (p: Patrol, participantId: string, returnedAt: string, returnReason: 'NORMAL' | 'ABORT', abortReason?: string) =>
  api('POST', `/admin/patrols/${p.id}/return-confirmations`, { participantId, returnedAt, returnReason, abortReason: returnReason === 'ABORT' ? (abortReason ?? 'OTHER') : null, version: p.real!.version }, { idempotent: true })
export const correctTime = (p: Patrol, participantId: string, startedAt: string | undefined, returnedAt: string | undefined, reason: string) =>
  api('POST', `/admin/patrols/${p.id}/members/${participantId}/time-corrections`, { ...(startedAt ? { startedAt } : {}), ...(returnedAt ? { returnedAt } : {}), reason, version: p.real!.version }, { idempotent: true })
export const cancelPatrol = (p: Patrol, reason: string) => api('POST', `/admin/patrols/${p.id}/cancellations`, { reason, version: p.real!.version }, { idempotent: true })

/* ---------------- 검토 목록 (업무 API 9절) ---------------- */

type ApiMedia = MediaRef
type ApiAnalysis = {
  analysisResultId: string
  status: Analysis['status']
  reviewCategory: Analysis['reviewCategory'] | null
  findings: { code: string; label: string; bbox: number[] | null; evidence: string }[]
  facilityMatch: { aiFacilityType: string | null; matched: boolean }
  retry?: { pending: boolean } | null
  modelVersion: string
}
type ApiInspection = {
  id: string
  attemptNo: number
  capturedBy?: { teamName: string; members: { name: string }[] } | null
  capturedAt?: string | null
  receivedAt?: string | null
  retakeReason?: string | null
  media?: ApiMedia | null
  analysis?: ApiAnalysis | null
  classification?: { source: 'AI' | 'HUMAN'; facilityType: string; issueTypes: string[]; classificationVersion: number; correctionId: string | null } | null
}
type ApiReviewTask = {
  id: string
  type: 'INSPECTION' | 'CAPTURE_FAILURE' | 'REPAIR_VERIFICATION'
  status: ReviewTask['status']
  version: number
  triggerReason: TriggerReason
  allowedDecisions: Decision[]
  facility: ApiFacility
  patrol?: { teamName: string } | null
  captureSlot?: { label: string } | null
  inspections: ApiInspection[]
  openIssues: { id: string; status: IssueStatus; defectCode?: string | null; summary?: string | null }[]
  decisions: {
    decision: Decision
    followUpType?: FollowUp | null
    analysisResultId: string | null
    correctionId: string | null
    classificationVersion: number | null
    reason: string
    decidedBy?: { name: string } | null
    decidedAt: string
    issueIds?: string[] | null
  }[]
  createdAt: string
}

const RETAKE_NAME: Record<string, string> = { BLUR: '흐림', DARK: '어두움', OUT_OF_FRAME: '틀 밖', TOO_DARK: '어두움', BLURRY: '흐림' }

function toReview(t: ApiReviewTask): ReviewTask {
  putFacility(t.facility)
  const withAi = [...t.inspections].reverse().find((i) => i.analysis)
  const last = t.inspections.at(-1)
  const a = withAi?.analysis
  const label = t.captureSlot?.label ?? '사진'
  const logs: Log[] = [{ at: mdhm(t.createdAt), who: '시스템', text: `검토 요청 생성 · ${TRIGGER_NAME[t.triggerReason] ?? t.triggerReason}` }]
  for (const d of t.decisions)
    logs.push({ at: mdhm(d.decidedAt), who: d.decidedBy?.name ?? '-', text: `${DECISION_NAME[d.decision]}${d.followUpType ? ` (${FOLLOW_UP_NAME[d.followUpType]})` : ''}${d.reason ? ` · ${d.reason}` : ''}` })
  const d = t.decisions.at(-1)
  return {
    id: t.id,
    type: t.type === 'CAPTURE_FAILURE' ? 'CAPTURE_FAILURE' : 'INSPECTION',
    status: t.status,
    trigger: t.triggerReason,
    facilityId: t.facility.id,
    at: mdhm(t.createdAt),
    team: last?.capturedBy?.teamName ?? t.patrol?.teamName ?? '',
    members: last?.capturedBy?.members.map((m) => m.name) ?? [],
    photos: (t.inspections.length ? t.inspections : [null]).map((i) => ({
      slot: i && t.inspections.length > 1 ? `${label} · ${i.attemptNo}번째` : label,
      bad: false,
      media: i?.media ?? null,
    })),
    analysis: a
      ? {
          analysisResultId: a.analysisResultId,
          status: a.status,
          reviewCategory: a.reviewCategory ?? 'REVIEW_REQUIRED',
          findings: a.findings.map((f) => ({ code: f.code, label: f.label, evidence: f.evidence, bbox: f.bbox && f.bbox.length === 4 ? (f.bbox as [number, number, number, number]) : null })),
          facilityMatch: { aiKind: a.facilityMatch.aiFacilityType ? kindOf(a.facilityMatch.aiFacilityType) : null, matched: a.facilityMatch.matched },
          modelVersion: a.modelVersion,
          retryPending: !!a.retry?.pending,
        }
      : undefined,
    classification: withAi?.classification
      ? {
          source: withAi.classification.source,
          kind: kindOf(withAi.classification.facilityType),
          issueTypes: withAi.classification.issueTypes,
          version: withAi.classification.classificationVersion,
          correctionId: withAi.classification.correctionId,
        }
      : undefined,
    corrections: [],
    failure: t.type === 'CAPTURE_FAILURE' ? `품질 재촬영 실패${last?.retakeReason ? ` (${RETAKE_NAME[last.retakeReason] ?? last.retakeReason})` : ''}` : undefined,
    note: !a && t.type === 'INSPECTION' ? 'AI 판독 결과가 아직 없습니다.' : undefined,
    decision: d
      ? {
          decision: d.decision,
          followUp: d.followUpType ?? undefined,
          reason: d.reason,
          at: mdhm(d.decidedAt),
          who: d.decidedBy?.name ?? '-',
          analysisResultId: d.analysisResultId,
          correctionId: d.correctionId,
          classificationVersion: d.classificationVersion,
          issueIds: d.issueIds ?? [],
        }
      : undefined,
    logs,
    real: {
      version: t.version,
      // 판정에 쓸 사진: AI 결과가 있는 사진, 없으면 마지막 사진
      inspectionId: withAi?.id ?? last?.id ?? null,
      allowedDecisions: t.allowedDecisions,
      openIssues: t.openIssues.map((i) => ({ id: i.id, title: i.summary || i.defectCode || '문제', status: i.status })),
    },
  }
}

/** 검토 작업 전부 (목록 → 작업마다 상세). 수리 확인 작업은 사건 화면에서 다룬다 */
export async function loadReviews(): Promise<ReviewTask[]> {
  const list = await api<{ items: { id: string; type: string }[] }>('GET', '/admin/review-tasks?size=100')
  const details = await Promise.all(list.items.filter((x) => x.type !== 'REPAIR_VERIFICATION').map((x) => api<ApiReviewTask>('GET', `/admin/review-tasks/${x.id}`)))
  return details.map(toReview)
}

export type DecisionInput = {
  decision: Decision
  followUp?: FollowUp
  reason: string
  /** ISSUE_CONFIRMED: 'NEW'(새 문제) 또는 연결할 기존 문제 ID */
  resolution?: string
  defectCode?: string
  summary?: string
}
export function decide(task: ReviewTask, input: DecisionInput) {
  const r = task.real!
  const a = task.analysis
  const issueResolutions =
    input.decision === 'ISSUE_CONFIRMED'
      ? [
          input.resolution && input.resolution !== 'NEW'
            ? { mode: 'LINK', inspectionId: r.inspectionId, existingIssueId: input.resolution, defectCode: null, summary: null }
            : { mode: 'CREATE', inspectionId: r.inspectionId, existingIssueId: null, defectCode: input.defectCode, summary: input.summary },
        ]
      : []
  return api('POST', `/admin/review-tasks/${task.id}/decisions`, {
    decision: input.decision,
    followUpType: input.decision === 'FOLLOW_UP_REQUIRED' ? (input.followUp ?? 'REPHOTO') : null,
    // 판정 근거: AI 결과가 없으면 셋 다 null, 사람 정정이 없으면 분류 버전 0
    analysisResultId: a?.analysisResultId ?? null,
    correctionId: a ? (task.classification?.correctionId ?? null) : null,
    classificationVersion: a ? (task.classification?.version ?? 0) : null,
    reason: input.reason,
    version: r.version,
    issueResolutions,
  }, { idempotent: true })
}

export function correctClassification(task: ReviewTask, next: { kind: Kind; issueTypes: string[] }, reason: string) {
  return api('POST', `/admin/inspections/${task.real!.inspectionId}/classification-corrections`, {
    analysisResultId: task.analysis?.analysisResultId,
    facilityType: FACILITY_TYPE[next.kind],
    issueTypes: next.issueTypes,
    reason,
    classificationVersion: task.classification?.version ?? 0,
  }, { idempotent: true })
}

/* ---------------- 사건·보수 (업무 API 10절) ---------------- */

type ApiIssue = {
  id: string
  status: IssueStatus
  version: number
  defectCode?: string | null
  summary?: string | null
  facility: ApiFacility
  assignedDepartment?: { id: string; name: string } | null
  sourceReviewId?: string | null
  openedAt: string
  closedAt?: string | null
  inspections: { inspectionId: string; capturedAt?: string | null; media?: ApiMedia | null; unlinkedAt?: string | null }[]
  currentReinspectionRequestId?: string | null
  reinspectionRequests?: { id: string; status: string; version: number; repairEventId?: string | null }[]
  repairEvents: { id?: string; type: string; actor?: { name: string } | null; department?: { name: string } | null; description?: string | null; occurredAt: string; evidenceMedia?: ApiMedia[] | null }[]
}

const REPAIR_EVENT_NAME: Record<string, string> = { REQUESTED: '보수 요청', STARTED: '보수 시작', COMPLETED: '보수 완료' }

/** 개인정보 검토: ok=false 가림 부족(즉시 보류) / ok=true 가림 확인(보류 사진 공개 허용, AI 확인 뒤 공개) */
export const privacyReview = (m: MediaRef, ok: boolean, reason: string) =>
  api('POST', `/admin/media/${m.id}/privacy-reviews`, { ok, reason, version: m.version }, { idempotent: true })
/** 공개 허용이 실패했을 때 다시 시도 */
export const privacyRetry = (m: MediaRef) =>
  api('POST', `/admin/media/${m.id}/privacy-release-retries`, { releaseId: m.privacyRelease!.releaseId, version: m.version }, { idempotent: true })

/** 수리 확인 기록 (GET /admin/issues/{id}/verifications). 판정까지 된 것만 처리 이력에 넣는다 */
type ApiVerification = {
  verificationMethod: 'PHOTO' | 'SITE_VISIT'
  notes?: { note: string }[]
  decision?: 'CLOSE' | 'REPAIR_AGAIN' | 'FOLLOW_UP_REQUIRED' | null
  followUpType?: 'REPHOTO' | 'SITE_VISIT' | null
  reason?: string | null
  decidedBy?: { name: string } | null
  decidedAt?: string | null
}

function toIssue(i: ApiIssue, verifications: ApiVerification[] = []): Issue {
  putFacility(i.facility)
  const kind = kindOf(i.facility.type)
  const label = ISSUE_TYPES[kind].find((t) => t.code === i.defectCode)?.label
  // 보수 이력: 요청마다 회차를 하나씩 연다
  const repairs: Repair[] = []
  for (const e of i.repairEvents) {
    const at = mdhm(e.occurredAt)
    if (e.type === 'REQUESTED') repairs.push({ round: repairs.length + 1, dept: e.department?.name ?? '', memo: e.description ?? '', requestedAt: at })
    const cur = repairs.at(-1)
    if (!cur) continue
    if (e.type === 'STARTED') cur.startedAt = at
    if (e.type === 'COMPLETED') Object.assign(cur, { completedAt: at, description: e.description ?? '', evidence: !!e.evidenceMedia?.length })
  }
  const linked = i.inspections.filter((x) => !x.unlinkedAt)
  const before = linked[0]
  const logs: Log[] = [{ at: mdhm(i.openedAt), who: '시스템', text: '이상 확인 → 문제 등록' }]
  for (const e of i.repairEvents)
    logs.push({ at: mdhm(e.occurredAt), who: e.actor?.name ?? e.department?.name ?? '-', text: `${REPAIR_EVENT_NAME[e.type] ?? e.type}${e.department?.name ? ` (${e.department.name})` : ''}${e.description ? ` · ${e.description}` : ''}` })
  const decided = verifications.filter((v) => v.decision && v.decidedAt)
  for (const v of decided) {
    const how = v.verificationMethod === 'SITE_VISIT' ? `현장 확인${v.notes?.[0]?.note ? `(${v.notes[0].note})` : ''}` : '사진 확인'
    const fu = v.decision === 'FOLLOW_UP_REQUIRED' && v.followUpType ? ` (${FOLLOW_UP_NAME[v.followUpType]})` : ''
    logs.push({ at: mdhm(v.decidedAt!), who: v.decidedBy?.name ?? '-', text: `수리 확인 · ${how} → ${VERIFY_NAME[v.decision!]}${fu}${v.reason ? ` · ${v.reason}` : ''}` })
  }
  // 종결은 수리 확인 판정이 있으면 그 기록으로 충분하다
  if (i.closedAt && !decided.some((v) => v.decision === 'CLOSE')) logs.push({ at: mdhm(i.closedAt), who: '-', text: '종결' })
  logs.sort((x, y) => x.at.localeCompare(y.at))
  const cur = i.reinspectionRequests?.find((r) => r.id === i.currentReinspectionRequestId)
  return {
    id: i.id,
    facilityId: i.facility.id,
    title: i.summary || `${i.facility.name} ${label ?? i.defectCode ?? ''}`.trim(),
    status: i.status,
    priority: 'NORMAL',
    createdAt: mdhm(i.openedAt),
    reviewId: i.sourceReviewId ?? undefined,
    repairs,
    verification: {},
    beforePhoto: before?.media ? { media: before.media } : undefined,
    closedAt: i.closedAt ? mdhm(i.closedAt) : undefined,
    logs,
    real: {
      version: i.version,
      deptId: i.assignedDepartment?.id ?? i.facility.managingDepartment?.id ?? null,
      deptName: i.assignedDepartment?.name ?? i.facility.managingDepartment?.name ?? null,
      reqId: cur?.id ?? null,
      reqVersion: cur?.version,
      repairEventId: cur?.repairEventId ?? null,
    },
  }
}

export async function loadIssues(): Promise<Issue[]> {
  const list = await api<{ items: { id: string }[] }>('GET', '/admin/issues?size=100')
  const details = await Promise.all(list.items.map((x) => api<ApiIssue>('GET', `/admin/issues/${x.id}`)))
  // 재점검 요청이 생긴 사건(보수 완료 뒤)만 수리 확인 기록을 함께 받는다
  const verifs = await Promise.all(
    details.map((d) => (d.reinspectionRequests?.length ? api<{ items: ApiVerification[] }>('GET', `/admin/issues/${d.id}/verifications?size=50`).then((r) => r.items, () => []) : Promise.resolve([]))),
  )
  return details.map((d, k) => toIssue(d, verifs[k]))
}

export const requestRepair = (i: Issue, memo: string) =>
  api('POST', `/admin/issues/${i.id}/repair-requests`, { assignedDepartmentId: i.real!.deptId, memo: memo || null, version: i.real!.version }, { idempotent: true })
export const startRepair = (i: Issue) => api('POST', `/admin/issues/${i.id}/repair-starts`, { version: i.real!.version }, { idempotent: true })
export const completeRepair = (i: Issue, description: string) =>
  api('POST', `/admin/issues/${i.id}/repair-completions`, { description, completedAt: new Date().toISOString(), evidenceMediaIds: [], version: i.real!.version }, { idempotent: true })

/** 수리 확인(현장 확인): 준비(SITE_VISIT, 확인 내용) → 판정. 재점검 사진 판정은 재점검 촬영 칸 API가 생기면 연결 */
export type VerifyInput = { decision: 'CLOSE' | 'REPAIR_AGAIN' | 'FOLLOW_UP_REQUIRED'; followUp?: 'REPHOTO' | 'SITE_VISIT'; fieldNote: string; reason: string }
export async function verifySiteVisit(i: Issue, v: VerifyInput) {
  const r = i.real!
  if (!r.reqId || !r.repairEventId || r.reqVersion === undefined) throw new ApiError(409, 'NO_REQUEST', '수리 확인할 재점검 요청이 없습니다. 목록을 새로 불러와 주세요.')
  const prep = await api<{ verificationId: string; verificationVersion: number; requestVersion: number; issueVersion: number }>(
    'POST',
    `/admin/issues/${i.id}/verification-preparations`,
    { requestId: r.reqId, repairEventId: r.repairEventId, verificationMethod: 'SITE_VISIT', beforeInspectionIds: [], afterInspectionIds: [], fieldNote: v.fieldNote, version: r.version, requestVersion: r.reqVersion },
    { idempotent: true },
  )
  return api('POST', `/admin/issues/${i.id}/verification-decisions`, {
    verificationId: prep.verificationId,
    decision: v.decision,
    followUpType: v.decision === 'FOLLOW_UP_REQUIRED' ? (v.followUp ?? 'SITE_VISIT') : null,
    reason: v.reason || null,
    version: prep.issueVersion,
    requestVersion: prep.requestVersion,
    verificationVersion: prep.verificationVersion,
  }, { idempotent: true })
}

/** 짧게 보여 줄 문제 번호 (UUID 앞 8자리) */
export const shortId = (id: string) => (/^[0-9a-f]{8}-/.test(id) ? id.slice(0, 8) : id)

/* ---------------- 참여자 계정 (ACT-01, service/backend AdminParticipantController) ---------------- */

type ApiParticipant = {
  id: string
  name: string
  phoneMasked: string | null
  organization: { id: string; name: string }
  availableDays: string[]
  status: 'ACTIVE' | 'SUSPENDED' | 'ENDED'
  version: number
}
const DAY_NAME: Record<string, string> = { MON: '월', TUE: '화', WED: '수', THU: '목', FRI: '금', SAT: '토', SUN: '일' }
const DAY_CODE = Object.fromEntries(Object.entries(DAY_NAME).map(([k, v]) => [v, k]))
export type RealParticipant = Participant & { version: number; orgId: string }
const toParticipant = (p: ApiParticipant): RealParticipant => ({
  id: p.id,
  name: p.name,
  phone: p.phoneMasked ?? '',
  org: p.organization.name,
  orgId: p.organization.id,
  days: p.availableDays.map((d) => DAY_NAME[d] ?? d),
  status: p.status,
  version: p.version,
})

export async function loadParticipants(): Promise<RealParticipant[]> {
  const r = await api<{ items: ApiParticipant[] }>('GET', '/admin/participants?size=100')
  return r.items.map(toParticipant)
}
export const loadParticipantOrgs = () => api<{ id: string; name: string }[]>('GET', '/admin/participants/organizations')
export const createParticipant = (p: { organizationId: string; name: string; phoneNumber: string; days: string[] }) =>
  api<ApiParticipant>(
    'POST',
    '/admin/participants',
    { organizationId: p.organizationId, name: p.name, phoneNumber: p.phoneNumber, availableDays: p.days.map((d) => DAY_CODE[d]) },
    { idempotent: true },
  )
export const changeParticipantStatus = (p: RealParticipant, status: Participant['status'], reason: string) =>
  api<ApiParticipant>('POST', `/admin/participants/${p.id}/status-changes`, { status, reason, version: p.version }, { idempotent: true })
