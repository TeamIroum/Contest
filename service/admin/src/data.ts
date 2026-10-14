import type { Kind } from './Photo'
import type { MediaRef } from './media'

// 시연용 예시 데이터. 실제 인물·시설·판독 결과가 아니다.
// 기준: 프론트엔드_전달용 (OpenAPI v0.2, 프론트 API 전달서 v1.4, 응답 JSON 명세 v0.3)
// 시연 기준일: 2026-10-12 (월)

/* ---------------- 역할·계정 ---------------- */

export type Role = 'ACTIVITY_MANAGER' | 'FACILITY_MANAGER' | 'PROGRAM_MANAGER' | 'SYSTEM_ADMIN'
export const ROLE_NAME: Record<Role, string> = {
  ACTIVITY_MANAGER: '활동 담당자',
  FACILITY_MANAGER: '시설 담당자',
  PROGRAM_MANAGER: '사업 총괄',
  SYSTEM_ADMIN: '시스템 관리자',
}

/** 시연: 관리자 계정 하나에 네 역할. SYSTEM_ADMIN은 다른 업무 권한을 자동으로 갖지 않는다 */
export const ME = {
  name: '이수진',
  org: '양주시 · 양주시니어클럽',
  loginId: 'demo.admin',
  roles: ['ACTIVITY_MANAGER', 'FACILITY_MANAGER', 'PROGRAM_MANAGER', 'SYSTEM_ADMIN'] as Role[],
}

export type AdminAccount = {
  id: string
  name: string
  org: string
  loginId: string
  roles: Role[]
  status: 'ACTIVE' | 'SUSPENDED'
  /** 임시 비밀번호: 처음 로그인할 때 변경 (D-23) */
  temp: boolean
  /** 비밀번호 5회 연속 실패로 10분 잠김 */
  locked: boolean
}

export const ADMINS: AdminAccount[] = [
  { id: 'a1', name: '이수진', org: '양주시니어클럽', loginId: 'demo.admin', roles: ['ACTIVITY_MANAGER', 'FACILITY_MANAGER', 'PROGRAM_MANAGER', 'SYSTEM_ADMIN'], status: 'ACTIVE', temp: false, locked: false },
  { id: 'a2', name: '최민호', org: '양주시 도로관리과', loginId: 'road.choi', roles: ['FACILITY_MANAGER'], status: 'ACTIVE', temp: false, locked: false },
  { id: 'a3', name: '한지은', org: '양주시 공원사업과', loginId: 'park.han', roles: ['FACILITY_MANAGER'], status: 'ACTIVE', temp: true, locked: false },
  { id: 'a4', name: '오세훈', org: '양주시 하수과', loginId: 'sewer.oh', roles: ['FACILITY_MANAGER'], status: 'ACTIVE', temp: false, locked: true },
  { id: 'a5', name: '정다은', org: '양주시니어클럽', loginId: 'club.jung', roles: ['ACTIVITY_MANAGER'], status: 'ACTIVE', temp: false, locked: false },
  { id: 'a6', name: '김태윤', org: '양주시 복지정책과', loginId: 'welfare.kim', roles: ['PROGRAM_MANAGER'], status: 'SUSPENDED', temp: false, locked: false },
]

export const ORGS = ['양주시니어클럽', '양주시 도로관리과', '양주시 공원사업과', '양주시 하수과', '양주시 시민안전과', '양주시 복지정책과']

/* ---------------- 시설 ---------------- */

/** 업무 시설 코드 (프론트 API 전달서 8절). 뒤 3개는 신규 코드 제안 */
export const FACILITY_TYPE: Record<Kind, string> = {
  sidewalk: 'SIDEWALK',
  drain: 'DRAIN',
  bench: 'BENCH',
  fence: 'SAFETY_FENCE',
  exercise: 'EXERCISE_EQUIPMENT',
  playground: 'PARK_EQUIPMENT',
  snowbox: 'SNOW_REMOVAL_BIN',
  shade: 'SHADE_STRUCTURE',
}
/** AI taxonomy가 판독하는 시설 */
export const AI_SUPPORTED: Kind[] = ['sidewalk', 'drain', 'bench', 'fence', 'exercise']
/** 계절 시설: 해당 계절 코스에만 넣는다 */
export const SEASONAL: Partial<Record<Kind, string>> = { snowbox: '겨울(11~3월)', shade: '여름(5~9월)' }

export const DEPTS = ['도로관리과', '하수과', '공원사업과', '시민안전과']
export const DEPT_OF: Record<Kind, string> = {
  sidewalk: '도로관리과',
  drain: '하수과',
  bench: '공원사업과',
  fence: '시민안전과',
  exercise: '공원사업과',
  playground: '공원사업과',
  snowbox: '도로관리과',
  shade: '공원사업과',
}

/** dept·deptId: 실제 백엔드의 관리 부서 (예시 데이터는 DEPT_OF로 정함) */
export type Facility = { id: string; kind: Kind; name: string; zone: string; tags: string[]; dept?: string; deptId?: string }

export const FACILITIES: Facility[] = [
  { id: 'f1', kind: 'sidewalk', name: '초등학교 정문 앞 보도', zone: '3구역', tags: ['통학로'] },
  { id: 'f2', kind: 'drain', name: '버스정류장 앞 빗물받이', zone: '3구역', tags: ['차도변'] },
  { id: 'f3', kind: 'playground', name: '근린공원 놀이터 그네', zone: '3구역', tags: ['어린이 시설'] },
  { id: 'f4', kind: 'exercise', name: '근린공원 산책로 운동기구', zone: '3구역', tags: [] },
  { id: 'f5', kind: 'bench', name: '근린공원 입구 벤치', zone: '3구역', tags: [] },
  { id: 'f6', kind: 'fence', name: '하천 산책로 입구 펜스', zone: '3구역', tags: [] },
  { id: 'f7', kind: 'sidewalk', name: '시장 사거리 보도', zone: '1구역', tags: [] },
  { id: 'f8', kind: 'drain', name: '경로당 앞 빗물받이', zone: '1구역', tags: ['차도변'] },
  { id: 'f9', kind: 'bench', name: '주민센터 앞 벤치', zone: '1구역', tags: [] },
  { id: 'f10', kind: 'shade', name: '횡단보도 그늘막', zone: '1구역', tags: ['차도변'] },
  { id: 'f11', kind: 'exercise', name: '체육공원 허리돌리기', zone: '2구역', tags: [] },
  { id: 'f12', kind: 'fence', name: '중학교 옆 펜스', zone: '2구역', tags: ['통학로'] },
  { id: 'f13', kind: 'snowbox', name: '언덕길 제설함', zone: '2구역', tags: [] },
  { id: 'f14', kind: 'playground', name: '어린이공원 시소', zone: '2구역', tags: ['어린이 시설'] },
]
/** 실제 백엔드 시설은 응답을 받을 때 FACILITIES에 더한다(api/real.ts). 아직 모르는 시설은 이름 없이 보여 준다 */
export const fac = (id: string): Facility => FACILITIES.find((f) => f.id === id) ?? { id, kind: 'sidewalk', name: '알 수 없는 시설', zone: '', tags: [] }
/** 업무 시설 코드 → 화면 시설 종류 */
export const KIND_OF_TYPE = Object.fromEntries(Object.entries(FACILITY_TYPE).map(([k, t]) => [t, k])) as Record<string, Kind>

/** 시설별 문제 유형 (분류 정정에서 고를 수 있는 값, 예시) */
// 업무 백엔드 문제 코드(service/backend taxonomy/defect-codes.json = AI taxonomy)와 같다. 없는 코드를 고르면 판정이 거절된다.
// 놀이기구·제설함·그늘막은 백엔드에 코드가 아직 없어 비워 둔다 (새 문제 만들기 불가, 기존 문제 연결·다른 판정은 가능)
export const ISSUE_TYPES: Record<Kind, { code: string; label: string }[]> = {
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
export const issueLabel = (kind: Kind, code: string) => ISSUE_TYPES[kind].find((t) => t.code === code)?.label ?? code

/* ---------------- AI 분석 결과 (관리자에게만, 판단 지원) ----------------
 * 응답 JSON 8.2 analysis. AI 위험도 등급·등급 기반 제안은 응답에 없고 어느 화면에도 표시하지 않는다(D-10·D-13).
 */

export type AnalysisStatus = 'OK' | 'NEEDS_REVIEW' | 'RETAKE' | 'ERROR'
export const ANALYSIS_STATUS_NAME: Record<AnalysisStatus, string> = {
  OK: '판독 완료',
  NEEDS_REVIEW: '사람 확인 필요',
  RETAKE: '다시 찍기',
  ERROR: '처리 실패',
}
/** 백엔드가 AI status+issues를 바꾼 검토 분류 */
export type ReviewCategory = 'NO_ISSUE_DETECTED' | 'REVIEW_REQUIRED'
export const REVIEW_CATEGORY_NAME: Record<ReviewCategory, string> = { NO_ISSUE_DETECTED: 'AI 이상 미검출', REVIEW_REQUIRED: '확인 필요' }

/**
 * 실제 사진 정보. media = 서버 응답의 media 객체(토큰을 붙여 받음), src = 앱에 넣은 시연용 사진 파일 주소.
 * 둘 다 없으면 시연용 그림을 그린다. (bad 값은 그림에만 쓴다)
 */
export type PhotoSource = { media?: MediaRef | null; src?: string }

export type Finding ={ code: string; label: string; evidence: string; bbox: [number, number, number, number] | null }

export type Analysis = {
  analysisResultId: string
  status: AnalysisStatus
  reviewCategory: ReviewCategory
  findings: Finding[]
  facilityMatch: { aiKind: Kind | null; matched: boolean }
  modelVersion: string
  /** 재판독 중이면 true */
  retryPending?: boolean
}

/** 사진의 현재 분류 (AI 원본 또는 사람 정정). classificationVersion은 사람 정정마다 1씩 증가 */
export type Classification = { source: 'AI' | 'HUMAN'; kind: Kind; issueTypes: string[]; version: number; correctionId: string | null }
export type Correction = { correctionId: string; at: string; who: string; reason: string; from: { kind: Kind; issueTypes: string[] }; to: { kind: Kind; issueTypes: string[] }; version: number }

/* ---------------- 검토 작업 (응답 JSON 8.1~8.4) ---------------- */

export type ReviewType = 'INSPECTION' | 'CAPTURE_FAILURE' | 'REPAIR_VERIFICATION'
export const REVIEW_TYPE_NAME: Record<ReviewType, string> = {
  INSPECTION: '사진 검토',
  CAPTURE_FAILURE: '촬영 실패',
  REPAIR_VERIFICATION: '수리 확인',
}
export type ReviewStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED'
export const REVIEW_STATUS_NAME: Record<ReviewStatus, string> = { OPEN: '확인 전', IN_REVIEW: '현장 확인 중', RESOLVED: '처리됨' }

/** 검토에 올라온 이유 (응답 JSON 8.1 triggerReason) */
export type TriggerReason =
  | 'ISSUE_SUSPECTED'
  | 'AI_NEEDS_REVIEW'
  | 'NO_ISSUE_SAMPLE'
  | 'QUALITY_RETRY_EXHAUSTED'
  | 'TECHNICAL_FAILURE'
  | 'PRIVACY_HOLD'
  | 'FACILITY_MISMATCH'
export const TRIGGER_NAME: Record<TriggerReason, string> = {
  ISSUE_SUSPECTED: '이상 의심',
  AI_NEEDS_REVIEW: 'AI 확인 필요',
  NO_ISSUE_SAMPLE: 'AI 이상 미검출 · 표본',
  QUALITY_RETRY_EXHAUSTED: '품질 3회 실패',
  TECHNICAL_FAILURE: '기술 실패',
  PRIVACY_HOLD: '개인정보 보류',
  FACILITY_MISMATCH: '시설 종류 불일치',
}

export type Decision = 'ISSUE_CONFIRMED' | 'NO_ISSUE' | 'FOLLOW_UP_REQUIRED' | 'RESOLVE_CAPTURE_PROBLEM'
/** 화면 표시 문구 (D-13): 담당자 NO_ISSUE는 'AI 이상 미검출'과 구분해 '이상 없음(담당자 확인)' */
export const DECISION_NAME: Record<Decision, string> = {
  ISSUE_CONFIRMED: '이상 확인',
  NO_ISSUE: '이상 없음(담당자 확인)',
  FOLLOW_UP_REQUIRED: '추가 확인',
  RESOLVE_CAPTURE_PROBLEM: '촬영 문제 종료',
}
export type FollowUp = 'REPHOTO' | 'SITE_VISIT'
export const FOLLOW_UP_NAME: Record<FollowUp, string> = { REPHOTO: '다시 촬영', SITE_VISIT: '현장 확인' }

export type Log = { at: string; who: string; text: string }

export type DecisionRecord = {
  decision: Decision
  followUp?: FollowUp
  reason: string
  at: string
  who: string
  /** 판정 근거 고정: AI 결과·사람 정정·분류 버전 (모두 null이면 근거 없이 판단) */
  analysisResultId: string | null
  correctionId: string | null
  classificationVersion: number | null
  issueIds: string[]
}

export type ReviewTask = {
  id: string
  type: Exclude<ReviewType, 'REPAIR_VERIFICATION'>
  status: ReviewStatus
  trigger: TriggerReason
  facilityId: string
  at: string
  team: string
  /** 촬영 조 전체 실명 (C-16) */
  members: string[]
  /** 사진 구도별 그림 (bad = 그림에 이상이 보임) */
  photos: ({ slot: string; bad: boolean } & PhotoSource)[]
  analysis?: Analysis
  classification?: Classification
  corrections: Correction[]
  /** CAPTURE_FAILURE: 품질 재촬영 3번 실패 */
  failure?: string
  /** AI가 판독하지 못한 사유 안내 (화면 참고용) */
  note?: string
  decision?: DecisionRecord
  logs: Log[]
  /** 실제 백엔드 작업일 때만: 판정에 보낼 version·사진 ID, 서버가 허용한 판정, 같은 시설의 열린 문제 */
  real?: {
    version: number
    inspectionId: string | null
    allowedDecisions: Decision[]
    openIssues: { id: string; title: string; status: IssueStatus }[]
  }
}

const ai = (id: string, status: AnalysisStatus, category: ReviewCategory, kind: Kind | null, matched: boolean, findings: Finding[] = []): Analysis => ({
  analysisResultId: id,
  status,
  reviewCategory: category,
  findings,
  facilityMatch: { aiKind: kind, matched },
  modelVersion: 'qwen3-vl:8b-instruct',
})
const cls = (kind: Kind, issueTypes: string[]): Classification => ({ source: 'AI', kind, issueTypes, version: 0, correctionId: null })
const team3 = ['김순자', '박영자']
const team1 = ['이말순', '정복동']
const team2 = ['최옥희', '한상철']

export const REVIEWS: ReviewTask[] = [
  {
    id: 'rv-1012-01', type: 'INSPECTION', status: 'OPEN', trigger: 'ISSUE_SUSPECTED', facilityId: 'f1', at: '10/12 09:41', team: '3조', members: team3,
    photos: [{ slot: '전체 모습', bad: true }],
    analysis: ai('an-0101', 'NEEDS_REVIEW', 'REVIEW_REQUIRED', 'sidewalk', true, [{ code: 'lifted', label: '들뜸', evidence: '블록 일부가 주변보다 돌출돼 보임', bbox: [0.36, 0.25, 0.65, 0.58] }]),
    classification: cls('sidewalk', ['lifted']), corrections: [],
    logs: [{ at: '10/12 09:41', who: '시스템', text: '사진 접수 · AI 판독 완료' }],
  },
  {
    id: 'rv-1012-02', type: 'CAPTURE_FAILURE', status: 'OPEN', trigger: 'QUALITY_RETRY_EXHAUSTED', facilityId: 'f2', at: '10/12 09:52', team: '3조', members: team3,
    photos: [{ slot: '덮개 전체', bad: false }], failure: '품질 재촬영 3번 모두 흐림 (BLUR)', corrections: [],
    logs: [{ at: '10/12 09:52', who: '시스템', text: '3번째 품질 실패 · 확인 요청 생성' }],
  },
  {
    id: 'rv-1012-03', type: 'INSPECTION', status: 'OPEN', trigger: 'ISSUE_SUSPECTED', facilityId: 'f4', at: '10/12 10:18', team: '3조', members: team3,
    photos: [{ slot: '전체 모습', bad: true }, { slot: '반대쪽에서', bad: true }],
    analysis: ai('an-0103', 'OK', 'REVIEW_REQUIRED', 'exercise', true, [{ code: 'bent', label: '휨', evidence: '손잡이 끝이 아래로 굽어 보임', bbox: [0.55, 0.2, 0.83, 0.63] }]),
    classification: cls('exercise', ['bent']), corrections: [],
    logs: [{ at: '10/12 10:18', who: '시스템', text: '사진 2장 접수 · AI 판독 완료' }],
  },
  {
    id: 'rv-1012-04', type: 'INSPECTION', status: 'OPEN', trigger: 'NO_ISSUE_SAMPLE', facilityId: 'f5', at: '10/12 10:27', team: '3조', members: team3,
    photos: [{ slot: '전체 모습', bad: false }],
    analysis: ai('an-0104', 'OK', 'NO_ISSUE_DETECTED', 'bench', true),
    classification: cls('bench', []), corrections: [],
    logs: [{ at: '10/12 10:27', who: '시스템', text: 'AI 이상 미검출 결과 중 표본 검토로 선정' }],
  },
  {
    id: 'rv-1012-05', type: 'INSPECTION', status: 'OPEN', trigger: 'AI_NEEDS_REVIEW', facilityId: 'f10', at: '10/12 10:05', team: '1조', members: team1,
    photos: [{ slot: '전체 모습', bad: true }],
    analysis: ai('an-0105', 'NEEDS_REVIEW', 'REVIEW_REQUIRED', null, false),
    corrections: [], note: 'AI 분류 대상이 아닌 시설(그늘막)이라 이상 판독을 하지 않았습니다.',
    logs: [{ at: '10/12 10:05', who: '시스템', text: '사진 접수 · AI 분류 대상 아님' }],
  },
  {
    id: 'rv-1012-06', type: 'INSPECTION', status: 'OPEN', trigger: 'ISSUE_SUSPECTED', facilityId: 'f8', at: '10/12 09:58', team: '1조', members: team1,
    photos: [{ slot: '덮개 전체', bad: true }],
    analysis: ai('an-0106', 'OK', 'REVIEW_REQUIRED', 'drain', true, [{ code: 'covered', label: '막힘', evidence: '덮개 위에 낙엽이 쌓여 있음', bbox: [0.21, 0.42, 0.79, 0.8] }]),
    classification: cls('drain', ['covered']), corrections: [],
    logs: [{ at: '10/12 09:58', who: '시스템', text: '사진 접수 · AI 판독 완료' }],
  },
  {
    // 열린 문제(IS-1009-01)가 있는 시설: 이상 확인 시 기존 문제에 연결할 수 있다
    id: 'rv-1012-08', type: 'INSPECTION', status: 'OPEN', trigger: 'ISSUE_SUSPECTED', facilityId: 'f7', at: '10/12 09:47', team: '1조', members: team1,
    photos: [{ slot: '전체 모습', bad: true }],
    analysis: ai('an-0108', 'OK', 'REVIEW_REQUIRED', 'sidewalk', true, [{ code: 'lifted', label: '들뜸', evidence: '블록 몇 장이 솟아 있음', bbox: [0.36, 0.25, 0.65, 0.58] }]),
    classification: cls('sidewalk', ['lifted']), corrections: [],
    logs: [{ at: '10/12 09:47', who: '시스템', text: '사진 접수 · AI 판독 완료' }],
  },
  {
    id: 'rv-1008-01', type: 'INSPECTION', status: 'RESOLVED', trigger: 'ISSUE_SUSPECTED', facilityId: 'f12', at: '10/08 10:12', team: '2조', members: team2,
    photos: [{ slot: '펜스 구간 전체', bad: true }],
    analysis: ai('an-0801', 'OK', 'REVIEW_REQUIRED', 'fence', true, [{ code: 'tilted', label: '기울어짐', evidence: '가운데 기둥이 옆으로 기울어 있음', bbox: [0.39, 0.23, 0.64, 0.77] }]),
    classification: cls('fence', ['tilted']), corrections: [],
    decision: { decision: 'ISSUE_CONFIRMED', reason: '기둥 1개 기울어짐 확인', at: '10/08 15:20', who: '이수진', analysisResultId: 'an-0801', correctionId: null, classificationVersion: 0, issueIds: ['IS-1008-01'] },
    logs: [{ at: '10/08 15:20', who: '이수진', text: '이상 확인 → 새 문제 IS-1008-01 등록' }],
  },
  {
    id: 'rv-1008-02', type: 'INSPECTION', status: 'RESOLVED', trigger: 'ISSUE_SUSPECTED', facilityId: 'f2', at: '10/08 09:40', team: '1조', members: team1,
    photos: [{ slot: '덮개 전체', bad: false }],
    analysis: ai('an-0802', 'OK', 'REVIEW_REQUIRED', 'drain', true, [{ code: 'covered', label: '막힘', evidence: '덮개 일부에 낙엽', bbox: null }]),
    classification: cls('drain', ['covered']), corrections: [],
    decision: { decision: 'NO_ISSUE', reason: '배수에 지장 없는 수준', at: '10/08 15:31', who: '이수진', analysisResultId: 'an-0802', correctionId: null, classificationVersion: 0, issueIds: [] },
    logs: [{ at: '10/08 15:31', who: '이수진', text: '이상 없음(담당자 확인)' }],
  },
]

/** 활동 중 새로 들어오는 검토 (관리자 화면 안 알림 시연). 위험도와 무관하게 '새 검토'로만 알린다 */
export const INCOMING: ReviewTask = {
  id: 'rv-1012-07', type: 'INSPECTION', status: 'OPEN', trigger: 'AI_NEEDS_REVIEW', facilityId: 'f14', at: '10/12 10:44', team: '2조', members: team2,
  photos: [{ slot: '전체 모습', bad: true }],
  analysis: ai('an-0107', 'NEEDS_REVIEW', 'REVIEW_REQUIRED', null, false),
  corrections: [], note: 'AI 분류 대상이 아닌 시설(공원기구)이라 이상 판독을 하지 않았습니다.',
  logs: [{ at: '10/12 10:44', who: '시스템', text: '사진 접수 · AI 확인 필요' }],
}
/* ---------------- 사건·보수 (API 10절) ---------------- */

export type IssueStatus = 'OPEN' | 'REPAIR_REQUESTED' | 'IN_REPAIR' | 'AWAITING_VERIFICATION' | 'CLOSED'
/** 체크리스트 3-7: 접수 → 보수 요청 → 보수 중 → 확인 대기 → 종결 */
export const ISSUE_STATUS_NAME: Record<IssueStatus, string> = {
  OPEN: '접수',
  REPAIR_REQUESTED: '보수 요청',
  IN_REPAIR: '보수 중',
  AWAITING_VERIFICATION: '확인 대기',
  CLOSED: '종결',
}
export const ISSUE_FLOW: IssueStatus[] = ['OPEN', 'REPAIR_REQUESTED', 'IN_REPAIR', 'AWAITING_VERIFICATION', 'CLOSED']

export type Repair = {
  round: number
  dept: string
  memo: string
  requestedAt: string
  startedAt?: string
  completedAt?: string
  description?: string
  evidence?: boolean
  /** 보수 증빙 사진 (실제 업무 서버. 기기에서 가린 뒤 서버 개인정보 검사를 거친 사진) */
  evidenceMedia?: import('./media').MediaRef[]
}

export type Verification = {
  /** 재점검 사진이 들어왔는지, 그림에 이상이 남아 있는지 */
  after?: { at: string; team: string; bad: boolean } & PhotoSource
  /** AI 전후 비교 (판단 지원) */
  ai?: { verdict: 'fixed' | 'not_fixed' | 'unclear'; change: 'better' | 'same' | 'worse' | 'unclear' }
}
export const VERDICT_NAME = { fixed: '고쳐진 것으로 보임', not_fixed: '그대로인 것으로 보임', unclear: '판단 어려움' } as const
export const CHANGE_NAME = { better: '나아짐', same: '같음', worse: '나빠짐', unclear: '알 수 없음' } as const

export type VerifyDecision = 'CLOSE' | 'REPAIR_AGAIN' | 'FOLLOW_UP_REQUIRED'
export const VERIFY_NAME: Record<VerifyDecision, string> = { CLOSE: '종결', REPAIR_AGAIN: '다시 보수 요청', FOLLOW_UP_REQUIRED: '추가 확인' }

export type Issue = {
  id: string
  facilityId: string
  title: string
  status: IssueStatus
  priority: 'NORMAL' | 'URGENT'
  createdAt: string
  reviewId?: string
  repairs: Repair[]
  verification: Verification
  /** 처음 문제를 발견한 사진 (없으면 시연용 그림) */
  beforePhoto?: PhotoSource
  closedAt?: string
  logs: Log[]
  /** 실제 백엔드 문제일 때만: 상태 변경에 보낼 version, 보수 담당 부서 */
  real?: {
    version: number
    deptId: string | null
    deptName: string | null
    /** 현재 재점검 요청(보수 완료 때 생김)과 그 버전·보수 회차. 수리 확인 준비에 쓴다 */
    reqId?: string | null
    reqVersion?: number
    repairEventId?: string | null
    /** 연결된 점검 사진 (FAC-06 연결 해제용) */
    links?: { inspectionId: string; capturedAt: string; media: import('./media').MediaRef | null }[]
  }
}

export const ISSUES: Issue[] = [
  {
    id: 'IS-1003-01', facilityId: 'f3', title: '놀이터 그네 줄 끊어짐', status: 'AWAITING_VERIFICATION', priority: 'URGENT', createdAt: '10/03 10:20',
    repairs: [{ round: 1, dept: '공원사업과', memo: '그네 줄 교체', requestedAt: '10/03 14:00', startedAt: '10/06 09:00', completedAt: '10/08 16:30', description: '그네 줄 2개 교체', evidence: true }],
    verification: {},
    logs: [
      { at: '10/03 10:20', who: '이수진', text: '이상 확인 → 사건 접수' },
      { at: '10/03 14:00', who: '이수진', text: '공원사업과에 보수 요청' },
      { at: '10/06 09:00', who: '한지은', text: '보수 시작' },
      { at: '10/08 16:30', who: '한지은', text: '보수 완료 · 재점검 요청 생성' },
      { at: '10/09 11:00', who: '이수진', text: '재점검을 10/12 3조 순회에 배정' },
    ],
  },
  {
    id: 'IS-1002-01', facilityId: 'f6', title: '하천 산책로 펜스 기울어짐', status: 'AWAITING_VERIFICATION', priority: 'NORMAL', createdAt: '10/02 11:05',
    repairs: [{ round: 1, dept: '시민안전과', memo: '기둥 재고정', requestedAt: '10/02 15:00', startedAt: '10/05 10:00', completedAt: '10/07 15:00', description: '기둥 고정 작업' }],
    verification: {},
    logs: [
      { at: '10/02 11:05', who: '이수진', text: '이상 확인 → 사건 접수' },
      { at: '10/02 15:00', who: '이수진', text: '시민안전과에 보수 요청' },
      { at: '10/07 15:00', who: '시민안전과', text: '보수 완료 · 재점검 요청 생성' },
      { at: '10/09 11:02', who: '이수진', text: '재점검을 10/12 3조 순회에 배정' },
    ],
  },
  {
    id: 'IS-1008-01', facilityId: 'f12', title: '중학교 옆 펜스 기울어짐', status: 'IN_REPAIR', priority: 'NORMAL', createdAt: '10/08 15:20', reviewId: 'rv-1008-01',
    repairs: [{ round: 1, dept: '시민안전과', memo: '기둥 1개 기울어짐', requestedAt: '10/08 15:25', startedAt: '10/10 09:30' }],
    verification: {},
    logs: [
      { at: '10/08 15:20', who: '이수진', text: '이상 확인 → 사건 접수' },
      { at: '10/08 15:25', who: '이수진', text: '시민안전과에 보수 요청' },
      { at: '10/10 09:30', who: '시민안전과', text: '보수 시작' },
    ],
  },
  {
    id: 'IS-1009-01', facilityId: 'f7', title: '시장 사거리 보도블록 깨짐', status: 'REPAIR_REQUESTED', priority: 'NORMAL', createdAt: '10/09 10:30',
    repairs: [{ round: 1, dept: '도로관리과', memo: '블록 3장 교체 필요', requestedAt: '10/09 13:00' }],
    verification: {},
    logs: [
      { at: '10/09 10:30', who: '이수진', text: '이상 확인 → 사건 접수' },
      { at: '10/09 13:00', who: '이수진', text: '도로관리과에 보수 요청' },
    ],
  },
  {
    id: 'IS-1009-02', facilityId: 'f11', title: '체육공원 허리돌리기 고정볼트 풀림', status: 'AWAITING_VERIFICATION', priority: 'NORMAL', createdAt: '10/09 11:10',
    repairs: [{ round: 1, dept: '공원사업과', memo: '볼트 조임', requestedAt: '10/09 14:00', startedAt: '10/10 10:00', completedAt: '10/10 11:30', description: '볼트 4개 조임' }],
    verification: { after: { at: '10/12 09:52', team: '2조', bad: false } },
    logs: [
      { at: '10/09 11:10', who: '이수진', text: '이상 확인 → 사건 접수' },
      { at: '10/10 11:30', who: '공원사업과', text: '보수 완료 · 재점검 요청 생성' },
      { at: '10/10 14:00', who: '이수진', text: '재점검을 10/12 2조 순회에 배정' },
      { at: '10/12 09:52', who: '시스템', text: '재점검 사진 접수 (2조)' },
    ],
  },
  {
    id: 'IS-1006-01', facilityId: 'f9', title: '주민센터 앞 벤치 앉는 판 파손', status: 'AWAITING_VERIFICATION', priority: 'NORMAL', createdAt: '10/01 10:40',
    repairs: [{ round: 1, dept: '공원사업과', memo: '앉는 판 교체', requestedAt: '10/01 14:00', startedAt: '10/05 09:00', completedAt: '10/06 17:00', description: '앉는 판 1장 교체' }],
    verification: { after: { at: '10/12 09:35', team: '1조', bad: true } },
    logs: [
      { at: '10/01 10:40', who: '이수진', text: '이상 확인 → 사건 접수' },
      { at: '10/06 17:00', who: '공원사업과', text: '보수 완료 · 재점검 요청 생성' },
      { at: '10/07 10:00', who: '이수진', text: '재점검을 10/12 1조 순회에 배정' },
      { at: '10/12 09:35', who: '시스템', text: '재점검 사진 접수 (1조)' },
    ],
  },
  {
    id: 'IS-0925-01', facilityId: 'f8', title: '경로당 앞 빗물받이 덮개 파손', status: 'CLOSED', priority: 'NORMAL', createdAt: '09/25 10:00', closedAt: '10/05 16:00',
    repairs: [
      { round: 1, dept: '하수과', memo: '덮개 교체', requestedAt: '09/25 14:00', startedAt: '09/28 09:00', completedAt: '09/29 15:00', description: '덮개 교체' },
      { round: 2, dept: '하수과', memo: '덮개 수평 맞지 않음, 재시공', requestedAt: '10/01 16:00', startedAt: '10/02 09:00', completedAt: '10/02 14:00', description: '받침 보강 후 재설치' },
    ],
    verification: { after: { at: '10/05 10:10', team: '1조', bad: false }, ai: { verdict: 'fixed', change: 'better' } },
    logs: [
      { at: '10/01 16:00', who: '이수진', text: '수리 확인: 다시 보수 요청 (덮개 수평 불량)' },
      { at: '10/05 16:00', who: '이수진', text: '수리 확인: 종결' },
    ],
  },
]

/** 재점검 사진의 AI 전후 비교 (시연용으로 그림 상태에 맞춘 값) */
export function aiCompare(bad: boolean): NonNullable<Verification['ai']> {
  return bad ? { verdict: 'not_fixed', change: 'same' } : { verdict: 'fixed', change: 'better' }
}

/* ---------------- 재점검 요청 (다음 정기 순회에 수동 배정, 체크리스트 2-9) ---------------- */

export type Reinspection = {
  id: string
  facilityId: string
  source: 'REPAIR' | 'FOLLOW_UP'
  issueId?: string
  reviewId?: string
  reason: string
  createdAt: string
  status: 'WAITING' | 'ASSIGNED' | 'DONE'
  patrolId?: string
}

export const REINSPECTIONS: Reinspection[] = [
  { id: 'RQ-1008-01', facilityId: 'f3', source: 'REPAIR', issueId: 'IS-1003-01', reason: '보수 완료 확인', createdAt: '10/08 16:30', status: 'ASSIGNED', patrolId: 'p-1012-3' },
  { id: 'RQ-1007-01', facilityId: 'f6', source: 'REPAIR', issueId: 'IS-1002-01', reason: '보수 완료 확인', createdAt: '10/07 15:00', status: 'ASSIGNED', patrolId: 'p-1012-3' },
  { id: 'RQ-1010-01', facilityId: 'f11', source: 'REPAIR', issueId: 'IS-1009-02', reason: '보수 완료 확인', createdAt: '10/10 11:30', status: 'DONE', patrolId: 'p-1012-2' },
  { id: 'RQ-1006-01', facilityId: 'f9', source: 'REPAIR', issueId: 'IS-1006-01', reason: '보수 완료 확인', createdAt: '10/06 17:00', status: 'DONE', patrolId: 'p-1012-1' },
  { id: 'RQ-1009-03', facilityId: 'f14', source: 'FOLLOW_UP', reason: '사진이 어두워 다시 촬영', createdAt: '10/09 16:10', status: 'WAITING' },
]

/* ---------------- 참여자·조·코스·순회 (API 5·8절) ---------------- */

export type Participant = {
  id: string
  name: string
  phone: string
  org: string
  days: string[]
  status: 'ACTIVE' | 'SUSPENDED' | 'ENDED'
}

export const PARTICIPANTS: Participant[] = [
  { id: 'u1', name: '김순자', phone: '010-1234-5678', org: '양주시니어클럽', days: ['월', '수', '금'], status: 'ACTIVE' },
  { id: 'u2', name: '박영자', phone: '010-2345-6789', org: '양주시니어클럽', days: ['월', '수', '금'], status: 'ACTIVE' },
  { id: 'u3', name: '이말순', phone: '010-3456-7890', org: '양주시니어클럽', days: ['월', '목'], status: 'ACTIVE' },
  { id: 'u4', name: '정복동', phone: '010-4567-8901', org: '양주시니어클럽', days: ['월', '목'], status: 'ACTIVE' },
  { id: 'u5', name: '최옥희', phone: '010-5678-9012', org: '양주시니어클럽', days: ['월', '화', '목'], status: 'ACTIVE' },
  { id: 'u6', name: '한상철', phone: '010-6789-0123', org: '양주시니어클럽', days: ['월', '화'], status: 'ACTIVE' },
  { id: 'u7', name: '윤정숙', phone: '010-7890-1234', org: '양주시니어클럽', days: ['월', '수'], status: 'ACTIVE' },
  { id: 'u8', name: '강영수', phone: '010-8901-2345', org: '양주시니어클럽', days: ['월', '금'], status: 'ACTIVE' },
  { id: 'u9', name: '조금례', phone: '010-9012-3456', org: '양주시니어클럽', days: ['월', '수', '금'], status: 'ACTIVE' },
  { id: 'u10', name: '서동만', phone: '010-1122-3344', org: '양주시니어클럽', days: ['화', '목'], status: 'ACTIVE' },
  { id: 'u11', name: '임춘자', phone: '010-2233-4455', org: '양주시니어클럽', days: ['월', '수'], status: 'ACTIVE' },
  { id: 'u12', name: '오길남', phone: '010-3344-5566', org: '양주시니어클럽', days: ['월'], status: 'SUSPENDED' },
  { id: 'u13', name: '문순이', phone: '010-4455-6677', org: '양주시니어클럽', days: ['수', '금'], status: 'ACTIVE' },
]
export const pName = (id: string) => PARTICIPANTS.find((p) => p.id === id)?.name ?? '-'

/** 조: 2~3명, 조 대표 없음 (체크리스트 1-5, 1-7) */
export type Team = { id: string; name: string; memberIds: string[] }
export const TEAMS: Team[] = [
  { id: 't1', name: '1조', memberIds: ['u3', 'u4'] },
  { id: 't2', name: '2조', memberIds: ['u5', 'u6'] },
  { id: 't3', name: '3조', memberIds: ['u1', 'u2'] },
  { id: 't4', name: '4조', memberIds: ['u7', 'u8', 'u9'] },
  { id: 't5', name: '5조', memberIds: ['u11', 'u13'] },
]

export type RouteStop = { facilityId: string; slots: string[] }
export type Course = { id: string; name: string; zone: string; start: string; stops: RouteStop[] }

export const COURSES: Course[] = [
  {
    id: 'c3', name: '회천2동 통학로 코스', zone: '3구역', start: '회천2동 행정복지센터',
    stops: [
      { facilityId: 'f1', slots: ['전체 모습'] },
      { facilityId: 'f2', slots: ['덮개 전체'] },
      { facilityId: 'f3', slots: ['전체 모습'] },
      { facilityId: 'f4', slots: ['전체 모습', '반대쪽에서'] },
      { facilityId: 'f5', slots: ['전체 모습'] },
      { facilityId: 'f6', slots: ['펜스 구간 전체'] },
    ],
  },
  {
    id: 'c1', name: '시장·경로당 코스', zone: '1구역', start: '회천1동 경로당',
    stops: [
      { facilityId: 'f7', slots: ['전체 모습'] },
      { facilityId: 'f8', slots: ['덮개 전체'] },
      { facilityId: 'f9', slots: ['전체 모습'] },
      { facilityId: 'f10', slots: ['전체 모습'] },
    ],
  },
  {
    id: 'c2', name: '체육공원 코스', zone: '2구역', start: '회천3동 행정복지센터',
    stops: [
      { facilityId: 'f11', slots: ['전체 모습', '반대쪽에서'] },
      { facilityId: 'f12', slots: ['펜스 구간 전체'] },
      { facilityId: 'f13', slots: ['전체 모습'] },
      { facilityId: 'f14', slots: ['전체 모습'] },
    ],
  },
]
export const course = (id: string) => COURSES.find((c) => c.id === id)!

export type Attendance = 'NONE' | 'REQUESTED' | 'PRESENT' | 'ABSENT'
/** 화면 표시. API attendance는 UNCHECKED/PRESENT/ABSENT이고 요청 여부는 attendanceRequestedAt으로 구분 */
export const ATTENDANCE_NAME: Record<Attendance, string> = { NONE: '미확인', REQUESTED: '출석 요청', PRESENT: '출석', ABSENT: '결석' }

export type PatrolStatus = 'ASSIGNED' | 'IN_PROGRESS' | 'RETURNING' | 'COMPLETED' | 'ABORTED' | 'CANCELLED'
export const PATROL_STATUS_NAME: Record<PatrolStatus, string> = {
  ASSIGNED: '출발 전',
  IN_PROGRESS: '활동 중',
  RETURNING: '복귀 중',
  COMPLETED: '완료',
  ABORTED: '중도 종료',
  CANCELLED: '취소',
}

/** 조원 한 명의 활동 기록 (응답 JSON 5.1 members[]) */
export type Member = {
  id: string
  attendance: Attendance
  /** 요청 없이 담당자가 출석 처리한 사유 */
  proxyReason?: string
  /** 활동 시작 시각. 출발 확인 뒤 조원 누구든 누르면 출석한 조원 모두 같은 시각 (없으면 '시작 기록 없음') */
  startedAt?: string
  startSource?: 'SELF' | 'ADMIN_CORRECTION'
  /** 실제 복귀 시각 (입력·정정 가능) */
  returnedAt?: string
  returnSource?: 'MANAGER_CONFIRMATION' | 'ADMIN_CORRECTION'
  /** 담당자 복귀 확인 여부 (returnedAt 유무로 계산하지 않음) */
  returned: boolean
  /** 어르신이 앱에서 '잠깐 쉬기'를 누른 상태 */
  paused?: boolean
}

export type Patrol = {
  id: string
  date: string
  teamId: string
  courseId: string
  start: string
  expectedReturn: string
  status: PatrolStatus
  /** 담당자 출발 확인 시각. 출석 확정 2명 이상일 때만, 이 뒤에야 활동을 시작할 수 있다 */
  departureConfirmedAt?: string
  members: Member[]
  /** 처리된 장소 수 */
  progress: { captured: number; skipped: number; review: number }
  paused?: boolean
  returnReason?: 'NORMAL' | 'ABORT'
  abortReason?: 'HEALTH' | 'DEVICE' | 'OTHER'
  cancellationReason?: string
  /** 재점검 구도가 추가된 시설 */
  reinspect: string[]
  followUps: Log[]
  /** 실제 백엔드 순회일 때만: 출석·출발·복귀 확인에 보낼 version */
  real?: { version: number }
}
export const ABORT_NAME = { HEALTH: '몸이 안 좋음', DEVICE: '휴대폰 문제', OTHER: '기타' } as const

const m = (id: string, attendance: Attendance = 'NONE', more: Partial<Member> = {}): Member => ({ id, attendance, returned: false, ...more })
const started = (t: string): Partial<Member> => ({ startedAt: t, startSource: 'SELF' })

export const PATROLS: Patrol[] = [
  {
    id: 'p-1012-3', date: '10/12', teamId: 't3', courseId: 'c3', start: '09:00', expectedReturn: '11:30', status: 'ASSIGNED',
    members: [m('u1', 'REQUESTED'), m('u2')], progress: { captured: 0, skipped: 0, review: 0 }, reinspect: ['f3', 'f6'], followUps: [],
  },
  {
    id: 'p-1012-1', date: '10/12', teamId: 't1', courseId: 'c1', start: '09:00', expectedReturn: '11:00', status: 'IN_PROGRESS', paused: true, departureConfirmedAt: '09:04',
    members: [m('u3', 'PRESENT', started('09:06')), m('u4', 'PRESENT')], progress: { captured: 2, skipped: 0, review: 0 }, reinspect: ['f9'], followUps: [],
  },
  {
    id: 'p-1012-2', date: '10/12', teamId: 't2', courseId: 'c2', start: '09:30', expectedReturn: '11:30', status: 'RETURNING', returnReason: 'NORMAL', departureConfirmedAt: '09:33',
    members: [m('u5', 'PRESENT', started('09:35')), m('u6', 'PRESENT', started('09:36'))], progress: { captured: 3, skipped: 1, review: 0 }, reinspect: ['f11'], followUps: [],
  },
  {
    id: 'p-1012-4', date: '10/12', teamId: 't4', courseId: 'c1', start: '13:30', expectedReturn: '15:30', status: 'ASSIGNED',
    members: [m('u7'), m('u8'), m('u9')], progress: { captured: 0, skipped: 0, review: 0 }, reinspect: [], followUps: [],
  },
  {
    id: 'p-1012-5', date: '10/12', teamId: 't5', courseId: 'c2', start: '09:00', expectedReturn: '10:30', status: 'RETURNING', returnReason: 'ABORT', abortReason: 'HEALTH', departureConfirmedAt: '09:02',
    members: [
      m('u11', 'PRESENT', { ...started('09:03'), returnedAt: '09:58', returnSource: 'MANAGER_CONFIRMATION', returned: true }),
      m('u13', 'PRESENT', started('09:03')),
    ],
    progress: { captured: 1, skipped: 0, review: 0 }, reinspect: [],
    followUps: [{ at: '10/12 09:48', who: '이수진', text: '임춘자 어르신 통화 · 어지러움, 천천히 복귀 중' }],
  },
  {
    id: 'p-1014-3', date: '10/14', teamId: 't3', courseId: 'c3', start: '09:00', expectedReturn: '11:30', status: 'ASSIGNED',
    members: [m('u1'), m('u2')], progress: { captured: 0, skipped: 0, review: 0 }, reinspect: [], followUps: [],
  },
  {
    id: 'p-1015-1', date: '10/15', teamId: 't1', courseId: 'c2', start: '09:00', expectedReturn: '11:00', status: 'ASSIGNED',
    members: [m('u3'), m('u4')], progress: { captured: 0, skipped: 0, review: 0 }, reinspect: [], followUps: [],
  },
]
/* ---------------- 지난 활동 기록 (통계용) ---------------- */

export type PastPatrol = {
  date: string
  team: string
  members: string[]
  minutes: number
  photos: number
  facilities: number
  skipped: number
  notVisited: number
  status: 'COMPLETED' | 'ABORTED'
}

const PAST_SEED: [string, string, string[], number, number, number, number, number, 'COMPLETED' | 'ABORTED'][] = [
  ['09/29', '1조', ['이말순', '정복동'], 128, 5, 4, 0, 0, 'COMPLETED'],
  ['09/29', '3조', ['김순자', '박영자'], 142, 8, 6, 0, 0, 'COMPLETED'],
  ['10/01', '2조', ['최옥희', '한상철'], 121, 5, 4, 0, 0, 'COMPLETED'],
  ['10/01', '3조', ['김순자', '박영자'], 150, 7, 5, 1, 0, 'COMPLETED'],
  ['10/02', '4조', ['윤정숙', '강영수', '조금례'], 118, 4, 4, 0, 0, 'COMPLETED'],
  ['10/03', '1조', ['이말순', '정복동'], 96, 3, 3, 0, 1, 'ABORTED'],
  ['10/05', '5조', ['임춘자', '문순이'], 132, 5, 4, 0, 0, 'COMPLETED'],
  ['10/06', '3조', ['김순자', '박영자'], 139, 8, 6, 0, 0, 'COMPLETED'],
  ['10/07', '2조', ['최옥희', '한상철'], 125, 6, 4, 0, 0, 'COMPLETED'],
  ['10/08', '1조', ['이말순', '정복동'], 131, 4, 4, 0, 0, 'COMPLETED'],
  ['10/08', '4조', ['윤정숙', '강영수', '조금례'], 127, 5, 4, 0, 0, 'COMPLETED'],
  ['10/09', '5조', ['임춘자', '문순이'], 119, 5, 3, 1, 0, 'COMPLETED'],
  ['10/09', '2조', ['최옥희', '한상철'], 134, 5, 4, 0, 0, 'COMPLETED'],
]
export const PAST: PastPatrol[] = PAST_SEED.map(([date, team, members, minutes, photos, facilities, skipped, notVisited, status]) => ({
  date, team, members, minutes, photos, facilities, skipped, notVisited, status,
}))
