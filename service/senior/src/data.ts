import type { Kind } from './Photo'
import type { MediaRef } from './media'

/** 실제 사진 정보. media = 서버 응답의 media 객체(토큰을 붙여 받음), src = 사진 파일 주소. 둘 다 없으면 그림 */
export type PhotoSource = { media?: MediaRef | null; src?: string }

// 시연용 예시 데이터. 실제 인물·시설 정보가 아니다.
// 기준: 팀 저장소 docs/API_명세서.md 6절 + api-alignment-checklist.md 결정

export const ME = { name: '김○○', phone: '010-0000-0001' }
// 같은 조 조원: 이름만 보여 주고 전화번호는 노출하지 않음 (체크리스트 1-8)
export const PARTNER = { name: '박○○' }
export const STAFF = { name: '이○○', org: '옥정동 노인복지관', phone: '031-000-0000' }
export const TEAM = { patrolId: 'demo-patrol-3', no: 3, zone: '3구역', center: '회천2동 행정복지센터', meetAt: '오전 9시', backBy: '오전 11시 30분' }

export type Stop = {
  id: string
  kind: Kind
  place: string
  dist: string
  /** REGULAR 정기 점검 / REINSPECTION 재점검(수리 확인 등) */
  purpose: 'REGULAR' | 'REINSPECTION'
  /** 서버가 정한 촬영 구도 (체크리스트 3-2) */
  slots: string[]
  instruction: string
  /** 시연용: 첫 사진이 품질 실패(흐림)로 나오는 횟수 */
  blurCount?: number
  prevDate?: string
  /** 시연용 장면: 사진에 이상이 보이는지 (어르신 화면엔 판정을 띄우지 않음) */
  bad?: boolean
  /** 참고 사진: 장소 카드·재점검 겹쳐 보기에 쓰는 지난 사진 (서버 referenceMediaId). 없으면 그림 */
  refPhoto?: PhotoSource
  /** 시연용: 찍으면 나오는 사진. 실제로는 카메라로 찍은 파일을 src(임시 주소)로 넣는다. 없으면 그림 */
  shotPhoto?: PhotoSource
  /** 서버 구도 번호 (captureSlots[].id). 예시 데이터에는 없음 → `${id}-${구도 순번}` */
  slotIds?: string[]
  /** 서버 구도 상태 (captureStatus). ACCEPTED·REVIEW_REQUESTED면 이미 끝난 구도 */
  slotStatus?: (string | null | undefined)[]
  /** 서버 장소 상태 (visitStatus): PENDING 말고는 끝난 장소 (CAPTURED·SKIPPED·REVIEW_REQUESTED·NOT_VISITED). 조원이 건너뛴 곳도 여기로 안다 */
  visitStatus?: string | null
  /** 서버가 알려 준 남은 재촬영 횟수 (구도별) */
  slotRetakes?: (number | undefined)[]
  x: number
  y: number
}

/** 오늘 순회 장소. 업무 API가 있으면 applyPatrol()로 서버 순회로 바뀐다 (ES 모듈이라 바꾸면 쓰는 곳에도 바로 반영) */
// eslint-disable-next-line import/no-mutable-exports
export let STOPS: Stop[] = [
  { id: 's1', kind: 'sidewalk', place: '초등학교 정문 앞', dist: '200m', purpose: 'REGULAR', slots: ['전체 모습'], instruction: '블록이 깔린 바닥 전체가 보이게 찍어 주세요.', bad: true, x: 40, y: 120 },
  { id: 's2', kind: 'drain', place: '버스정류장 앞', dist: '120m', purpose: 'REGULAR', slots: ['덮개 전체'], instruction: '덮개 전체와 주변 바닥을 찍어 주세요.', blurCount: 1, x: 100, y: 120 },
  { id: 's3', kind: 'playground', place: '근린공원 놀이터', dist: '150m', purpose: 'REINSPECTION', slots: ['전체 모습'], instruction: '지난번 사진과 같은 방향에서 찍어 주세요.', prevDate: '10월 3일', x: 100, y: 50 },
  { id: 's4', kind: 'exercise', place: '근린공원 산책로', dist: '90m', purpose: 'REGULAR', slots: ['전체 모습', '반대쪽에서'], instruction: '기구 전체가 보이게 찍고, 반대쪽에서 한 장 더 찍어 주세요.', bad: true, x: 170, y: 50 },
  { id: 's5', kind: 'bench', place: '근린공원 입구', dist: '60m', purpose: 'REGULAR', slots: ['전체 모습'], instruction: '앉는 판과 등받이가 다 보이게 찍어 주세요.', x: 220, y: 50 },
  { id: 's6', kind: 'fence', place: '하천 산책로 입구', dist: '180m', purpose: 'REINSPECTION', slots: ['펜스 구간 전체'], instruction: '지난번 사진과 같은 방향에서 찍어 주세요.', prevDate: '10월 2일', bad: true, x: 220, y: 130 },
]

/** 지도 위 장소 자리 (서버 순회에는 좌표가 없어 차례대로 놓는다) */
const MAP_SPOTS = STOPS.map(({ x, y }) => ({ x, y }))
/** 업무 API facilityType → 그림 종류 */
const KIND_OF: Record<string, Kind> = {
  SIDEWALK: 'sidewalk',
  DRAIN: 'drain',
  BENCH: 'bench',
  SAFETY_FENCE: 'fence',
  EXERCISE_EQUIPMENT: 'exercise',
  PARK_EQUIPMENT: 'playground',
  SNOW_REMOVAL_BIN: 'snowbox',
  SHADE_STRUCTURE: 'shade',
}

/** 끝난 구도: 다시 찍지 않는다 (찍어 올려도 '이미 끝난 곳'으로 거절됨) */
export const isResolvedStatus = (s?: string | null) => s === 'ACCEPTED' || s === 'REVIEW_REQUESTED'
export const slotDone = (st: Stop, slot: number) => isResolvedStatus(st.slotStatus?.[slot])
export const stopDone = (st: Stop) => (!!st.visitStatus && st.visitStatus !== 'PENDING') || st.slots.every((_, i) => slotDone(st, i))

/** 서버 순회(GET /patrols/{id})를 앱 장소 목록으로 바꿔 넣는다 */
export function applyPatrol(p: {
  id: string
  stops: { id: string; name: string; facilityType: string; instruction?: string; visitStatus?: string | null; captureSlots: { id: string; label: string; captureStatus?: string | null; retakesRemaining?: number; purpose?: string }[] }[]
}) {
  TEAM.patrolId = p.id
  STOPS = p.stops.map((s, i) => ({
    id: s.id,
    kind: KIND_OF[s.facilityType] ?? 'sidewalk',
    place: s.name,
    dist: '',
    purpose: s.captureSlots.some((c) => c.purpose === 'REINSPECTION') ? 'REINSPECTION' : 'REGULAR',
    slots: s.captureSlots.map((c) => c.label),
    instruction: s.instruction ?? '',
    slotIds: s.captureSlots.map((c) => c.id),
    slotStatus: s.captureSlots.map((c) => c.captureStatus),
    visitStatus: s.visitStatus,
    slotRetakes: s.captureSlots.map((c) => c.retakesRemaining),
    ...MAP_SPOTS[i % MAP_SPOTS.length],
  }))
}

/** 품질 재촬영: 처음 + 2회 (총 3번). 세 번째도 실패하면 담당자 확인 요청 후 다음으로 (체크리스트 3-1) */
export const MAX_ATTEMPTS = 3

/** 촬영 불가 사유 (skip-events reason: UNSAFE / NOT_FOUND / FACILITY_REMOVED / OTHER) */
export const SKIP_REASONS = [
  { code: 'UNSAFE', label: '위험해서 못 찍겠어요' },
  { code: 'NOT_FOUND', label: '장소를 못 찾겠어요' },
  { code: 'FACILITY_REMOVED', label: '시설이 없어졌어요' },
  { code: 'OTHER', label: '다른 이유로 못 찍겠어요' },
] as const

/** 중도 종료 사유 (API 6절 return-requests abortReason) */
export const ABORT_REASONS = [
  { code: 'HEALTH', label: '몸이 안 좋아요' },
  { code: 'DEVICE', label: '휴대폰에 문제가 있어요' },
  { code: 'OTHER', label: '다른 이유가 있어요' },
] as const
