// 업무 API 호출 (명세: openapi.yaml servers /api/v1, bearerAuth). 시니어 앱 src/api/client.ts와 같은 방식
import { setMediaToken } from '../media'

/** API 서버 주소 (.env의 VITE_API_ORIGIN). 비우면 화면과 같은 주소의 /api/v1 */
export const API_BASE = `${import.meta.env.VITE_API_ORIGIN ?? ''}/api/v1`

/**
 * 실제 업무 백엔드(backend/, Spring Boot)에 붙는지 (.env의 VITE_BACKEND=real).
 * 실제 백엔드: 로그인·오늘 활동 현황·검토 목록·사건·보수를 서버 값으로 보여 주고 처리 결과를 서버에 저장한다.
 * 비우면 지금까지처럼 예시 데이터로 동작한다.
 */
export const REAL_BACKEND = import.meta.env.VITE_BACKEND === 'real'

let token: string | null = null
let csrf: string | null = null
/** 로그인 뒤 받은 액세스 토큰을 넣는다 (사진 불러오기에도 함께 쓰임). 로그아웃하면 null */
export function setAccessToken(t: string | null) {
  token = t
  setMediaToken(t)
}
const authHeaders = (): Record<string, string> => ({
  ...(token ? { Authorization: `Bearer ${token}` } : {}),
  ...(csrf ? { 'X-CSRF-Token': csrf } : {}),
})

/** 바꾸는 요청마다 CSRF 토큰(헤더 + 쿠키)이 필요하다. 처음 한 번 받아 둔다 */
async function ensureCsrf() {
  if (csrf) return
  const r = await fetch(`${API_BASE}/auth/csrf`, { cache: 'no-store', credentials: 'include' })
  const b = await r.json().catch(() => ({}))
  if (!r.ok || !b.csrfToken) throw new ApiError(r.status, undefined, 'CSRF 토큰을 받지 못했습니다.')
  csrf = b.csrfToken
}

/** 액세스 토큰(15분)이 끝나면 갱신 쿠키로 새로 받는다. 성공하면 true */
async function refreshSession(): Promise<boolean> {
  try {
    await ensureCsrf()
    const r = await fetch(`${API_BASE}/auth/token/refresh`, { method: 'POST', cache: 'no-store', credentials: 'include', headers: authHeaders() })
    if (!r.ok) return false
    setAccessToken((await r.json()).accessToken)
    return true
  } catch {
    return false
  }
}

/** 서버가 막았을 때 화면에서 바로 보일 수 있게 code·message를 담는다 */
export class ApiError extends Error {
  constructor(
    public status: number,
    public code?: string,
    public serverMessage?: string,
  ) {
    super(serverMessage || `${status} ${code ?? ''}`.trim())
  }
}

/** 로그인이 끝나 다시 로그인해야 할 때 (App이 로그인 화면으로 돌린다) */
let onSessionEnded: () => void = () => {}
export const setSessionEndedHandler = (fn: () => void) => (onSessionEnded = fn)

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
/** JSON API 호출. 실패하면 서버 code·message를 담은 오류를 던진다. idempotent=true면 Idempotency-Key를 붙인다 */
export async function api<T>(method: Method, path: string, body?: unknown, opts: { idempotent?: boolean } = {}): Promise<T> {
  if (method !== 'GET') await ensureCsrf()
  const key = opts.idempotent ? crypto.randomUUID() : undefined // 다시 보낼 때도 같은 키 (서버가 같은 결과를 돌려줌)
  const call = () =>
    fetch(`${API_BASE}${path}`, {
      method,
      cache: 'no-store',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...authHeaders(),
        ...(key ? { 'Idempotency-Key': key } : {}),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  let r = await call()
  if (r.status === 401 && token) {
    if (await refreshSession()) r = await call()
    else {
      setAccessToken(null)
      onSessionEnded()
    }
  }
  const b = await r.json().catch(() => ({}))
  if (!r.ok) throw new ApiError(r.status, b.code, b.message)
  return b as T
}

/** 파일 올리기 (multipart). 같은 요청을 다시 보내도 서버가 같은 결과를 돌려주도록 Idempotency-Key를 붙인다 */
export async function apiForm<T>(path: string, form: FormData): Promise<T> {
  await ensureCsrf()
  const key = crypto.randomUUID()
  const call = () =>
    fetch(`${API_BASE}${path}`, { method: 'POST', cache: 'no-store', credentials: 'include', headers: { Accept: 'application/json', ...authHeaders(), 'Idempotency-Key': key }, body: form })
  let r = await call()
  if (r.status === 401 && token && (await refreshSession())) r = await call()
  const b = await r.json().catch(() => ({}))
  if (!r.ok) throw new ApiError(r.status, b.code, b.message)
  return b as T
}

// ---------------- 관리자 로그인 (업무 API 4.3절, D-21~23)
export type LoginUser = { id: string; name: string; roles: string[] }
export type LoginResult = { kind: 'in'; user: LoginUser } | { kind: 'password-change' }

/** 같은 브라우저를 알아보는 값. 로그인 기록(세션)에 남는다 */
function clientId() {
  try {
    let id = localStorage.getItem('admin-client-id')
    if (!id) localStorage.setItem('admin-client-id', (id = crypto.randomUUID()))
    return id
  } catch {
    return crypto.randomUUID()
  }
}

export async function login(loginId: string, password: string): Promise<LoginResult> {
  const b = await api<{ accessToken?: string; user?: LoginUser; passwordChangeRequired?: boolean }>('POST', '/auth/admin/sessions', {
    loginId,
    password,
    clientId: clientId(),
    deviceLabel: '관리자 웹',
  })
  if (b.passwordChangeRequired) return { kind: 'password-change' } // 제한 세션은 쿠키로만 온다
  setAccessToken(b.accessToken!)
  return { kind: 'in', user: b.user! }
}

/** 임시 비밀번호 변경. 성공하면 서버가 모든 세션과 쿠키를 지우므로 새 비밀번호로 다시 로그인한다 */
export async function changePassword(currentPassword: string, newPassword: string) {
  await api('POST', '/auth/password-changes', { currentPassword, newPassword })
  csrf = null
}

export async function logout() {
  try {
    await api('DELETE', '/auth/session')
  } catch {
    // 이미 끝난 세션이면 그대로 로그인 화면으로
  }
  setAccessToken(null)
  csrf = null
}

export type Me = { id: string; name: string; roles: string[]; scopes?: { organizationId?: string; organizationName?: string; role?: string }[] }
export const fetchMe = () => api<Me>('GET', '/me')
