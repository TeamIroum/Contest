// 업무 API 호출 (명세: openapi.yaml servers /api/v1, bearerAuth)
import { setMediaToken } from '../media'

/** API 서버 주소 (.env의 VITE_API_ORIGIN). 비우면 화면과 같은 주소의 /api/v1 */
export const API_BASE = `${import.meta.env.VITE_API_ORIGIN ?? ''}/api/v1`

/**
 * 실제 업무 백엔드(backend/, Spring Boot)에 붙는지 (.env의 VITE_BACKEND=real).
 * 실제 백엔드: 인증번호 로그인·CSRF·출석 요청·활동 시작을 서버로 보낸다.
 * 비우면 지금까지처럼 시험용 업무 API(tools/ai-verify, :8100) 기준으로 동작한다 (로그인·출석은 화면에서 흉내).
 */
export const REAL_BACKEND = import.meta.env.VITE_BACKEND === 'real'

let token: string | null = null
let csrf: string | null = null
/** 로그인 뒤 받은 액세스 토큰을 한 번 넣는다 (사진 불러오기에도 함께 쓰임). 로그아웃하면 null */
export function setAccessToken(t: string | null) {
  token = t
  setMediaToken(t)
}
export const authHeaders = (): Record<string, string> => ({
  ...(token ? { Authorization: `Bearer ${token}` } : {}),
  ...(csrf ? { 'X-CSRF-Token': csrf } : {}),
})
/** 전송 대기함(offline/outbox.js)에 넘기는 설정 */
export const sendOpts = (onUploaded?: () => void) => ({ apiBase: API_BASE, headers: authHeaders(), onUploaded })

/** 실제 백엔드는 바꾸는 요청마다 CSRF 토큰(헤더 + 쿠키)을 요구한다. 처음 한 번 받아 둔다 */
export async function ensureCsrf() {
  if (!REAL_BACKEND || csrf) return
  const r = await fetch(`${API_BASE}/auth/csrf`, { cache: 'no-store', credentials: 'include' })
  const b = await r.json().catch(() => ({}))
  if (!r.ok || !b.csrfToken) throw new Error(`${r.status} CSRF 토큰을 받지 못했어요`)
  csrf = b.csrfToken
}

/** 액세스 토큰(15분)이 끝나면 갱신 쿠키로 새로 받는다. 성공하면 true */
export async function refreshSession(): Promise<boolean> {
  if (!REAL_BACKEND) return false
  try {
    await ensureCsrf()
    const r = await fetch(`${API_BASE}/auth/token/refresh`, { method: 'POST', cache: 'no-store', credentials: 'include', headers: authHeaders() })
    if (!r.ok) {
      await r.text().catch(() => '') // 본문을 읽어 요청을 끝낸다 (안 읽으면 연결이 열린 채 남음)
      return false
    }
    const b = await r.json()
    setAccessToken(b.accessToken)
    return true
  } catch {
    return false
  }
}

/** JSON API 호출. 실패하면 서버 code·message를 담은 오류를 던진다 */
export async function api<T>(method: 'GET' | 'POST' | 'PUT', path: string, body?: unknown): Promise<T> {
  if (method !== 'GET') await ensureCsrf()
  const call = () =>
    fetch(`${API_BASE}${path}`, {
      method,
      cache: 'no-store',
      credentials: 'include',
      headers: { ...authHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  let r = await call()
  if (r.status === 401 && token && (await refreshSession())) r = await call()
  const b = await r.json().catch(() => ({}))
  if (!r.ok) throw new ApiError(r.status, b.code, b.message)
  // 로그인한 채로 요청이 성공하면 '이 휴대폰에서 로그인 중' 표시를 남긴다 (고치기 전에 로그인해 둔 휴대폰도 새로 고침 때 되살리게)
  if (token) markSignedIn(true)
  return b as T
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code?: string,
    public serverMessage?: string,
  ) {
    super(`${status} ${code ?? ''} ${serverMessage ?? ''}`.trim())
  }
}

// ---------------- 시연 인증번호 로그인 (실제 백엔드, local 프로필의 /auth/demo/*)
/** 같은 휴대폰을 알아보는 값. 로그인 기록(세션)에 남는다 */
function clientId() {
  try {
    let id = localStorage.getItem('senior-client-id')
    if (!id) localStorage.setItem('senior-client-id', (id = crypto.randomUUID()))
    return id
  } catch {
    return crypto.randomUUID()
  }
}
export type OtpChallenge = { challengeId: string; expiresAt: string; resendAvailableAt: string }
export const requestOtp = (phone: string) => api<OtpChallenge>('POST', '/auth/demo/otp-requests', { phoneNumber: phone })
export type LoginUser = { id: string; name: string; roles: string[] }
export async function verifyOtp(challengeId: string, code: string): Promise<LoginUser> {
  const b = await api<{ accessToken: string; user: LoginUser }>('POST', '/auth/demo/otp-verifications', {
    challengeId,
    code,
    clientId: clientId(),
    deviceLabel: '시니어 앱',
  })
  setAccessToken(b.accessToken)
  markSignedIn(true)
  return b.user
}

/** 이 휴대폰에서 로그인 중인지 표시 (비밀 값 아님. 실제 로그인은 HttpOnly 갱신 쿠키로 확인) */
function markSignedIn(on: boolean) {
  try {
    if (on) localStorage.setItem('senior-signed-in', '1')
    else localStorage.removeItem('senior-signed-in')
  } catch {
    // 저장소를 못 쓰면 새로 고침 때 다시 로그인한다
  }
}
/** 새로 고침했을 때 되살릴 로그인이 있을 수 있는지 (있으면 시작 화면 대신 잠깐 기다림 화면) */
export function maybeSignedIn() {
  try {
    return REAL_BACKEND && localStorage.getItem('senior-signed-in') === '1'
  } catch {
    return false
  }
}
/**
 * 새로 고침 뒤 로그인 되살리기: 액세스 토큰은 메모리에만 두므로 새로 고침하면 사라진다.
 * 이 휴대폰에서 로그인한 적이 있고 갱신 쿠키(7일)가 살아 있으면 새 토큰을 받고 이름을 돌려준다. 아니면 null
 */
export async function restoreSession(): Promise<{ name: string } | null> {
  if (!maybeSignedIn() || !(await refreshSession())) {
    markSignedIn(false)
    return null
  }
  try {
    return await api<{ name: string }>('GET', '/me')
  } catch {
    markSignedIn(false)
    setAccessToken(null)
    return null
  }
}

// ---------------- 오늘 순회 (응답 JSON 명세 7절: GET /me/patrols, GET /patrols/{id})
export type ApiSlot = { id: string; label: string; instruction?: string; captureStatus?: string | null; retakesRemaining?: number; purpose?: 'REGULAR' | 'REINSPECTION' }
export type ApiStop = { id: string; order?: number; name: string; facilityType: string; instruction?: string; visitStatus?: string | null; captureSlots: ApiSlot[] }
export type ApiMember = { participantId: string; name: string; attendance?: 'UNCHECKED' | 'PRESENT' | 'ABSENT'; attendanceRequestedAt?: string | null; startedAt?: string | null; paused?: boolean; returned?: boolean }
export type ApiPatrol = {
  id: string
  stops: ApiStop[]
  // 실제 백엔드만 주는 값
  teamName?: string
  routeName?: string
  meetingPoint?: { id?: string; name?: string } | null
  scheduledStartAt?: string | null
  expectedReturnAt?: string | null
  members?: ApiMember[]
  departureConfirmedAt?: string | null
  contact?: { name?: string; phoneNumber?: string } | null
  myAssignment?: { participantId: string; assignmentRef?: string } | null
}

/** 서버 시각(ISO)을 한국 시간 '오전 10시'·'오후 12시 30분' 꼴로 */
export function kstClock(iso: string) {
  const d = new Date(new Date(iso).getTime() + 9 * 3600_000)
  const h = d.getUTCHours()
  const m = d.getUTCMinutes()
  return `${h < 12 ? '오전' : '오후'} ${h % 12 || 12}시${m ? ` ${m}분` : ''}`
}

/** 한국 시간 기준 오늘 (YYYY-MM-DD). 실제 백엔드는 활동일을 꼭 받는다 */
export const todayKst = () => new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10)

export async function fetchTodayPatrol(): Promise<ApiPatrol> {
  const list = await api<{ items?: { id: string }[] }>('GET', REAL_BACKEND ? `/me/patrols?date=${todayKst()}` : '/me/patrols')
  if (!list.items?.length) throw new NoPatrolError()
  return api<ApiPatrol>('GET', `/patrols/${list.items[0].id}`)
}
/** 오늘 배정된 순회가 없음 (오류가 아니라 안내할 상태) */
export class NoPatrolError extends Error {
  constructor() {
    super('오늘 배정된 순회가 없어요.')
  }
}
export const fetchPatrol = (id: string) => api<ApiPatrol>('GET', `/patrols/${id}`)

/** 내 행과 짝 어르신 행 (출석·출발 확인 상태) */
export function memberStates(p: ApiPatrol) {
  const meId = p.myAssignment?.participantId
  const me = p.members?.find((m) => m.participantId === meId)
  const partner = p.members?.find((m) => m.participantId !== meId)
  const att = (m?: ApiMember) => (m?.attendance === 'PRESENT' ? 'present' : m?.attendanceRequestedAt ? 'requested' : 'none') as 'none' | 'requested' | 'present'
  return { me, partner, myAtt: att(me), partnerAtt: att(partner), departed: !!p.departureConfirmedAt, returned: !!me?.returned }
}

// ---------------- 출석 요청·활동 시작 (같은 clientEventId로 다시 보내면 서버가 같은 결과를 돌려줌)
const event = (p: ApiPatrol | null) => ({ clientEventId: crypto.randomUUID(), occurredAt: new Date().toISOString(), assignmentRef: p?.myAssignment?.assignmentRef })
export const requestAttendance = (patrolId: string, p: ApiPatrol | null) => api('POST', `/patrols/${patrolId}/attendance-requests`, event(p))
export const startActivity = (patrolId: string, p: ApiPatrol | null) => api('POST', `/patrols/${patrolId}/activity-starts`, event(p))
/** 잠깐 쉬기 시작·끝. 담당자 화면(오늘 활동 현황)에 '쉬는 중'으로 보인다 */
export const sendPause = (patrolId: string, p: ApiPatrol | null, on: boolean) =>
  api('POST', `/patrols/${patrolId}/pause-events`, { ...event(p), type: on ? 'PAUSE_STARTED' : 'PAUSE_ENDED' })
/** 이곳은 찍을 수 없어요: 장소를 건너뜀으로 기록한다. reason = UNSAFE / NOT_FOUND / FACILITY_REMOVED / OTHER */
export const sendSkip = (patrolId: string, p: ApiPatrol | null, stopId: string, reason: string) =>
  api('POST', `/patrols/${patrolId}/stops/${stopId}/skip-events`, { ...event(p), reason })
/** 복귀 요청. abortReason(HEALTH / DEVICE / OTHER)이 있으면 중도 종료, 없으면 정상 복귀. 담당자가 복귀 확인을 하면 끝난다 */
export const sendReturn = (patrolId: string, p: ApiPatrol | null, abortReason?: string) =>
  api('POST', `/patrols/${patrolId}/return-requests`, { ...event(p), returnReason: abortReason ? 'ABORT' : 'NORMAL', abortReason: abortReason ?? null })
