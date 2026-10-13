// 업무 API 호출 (명세: openapi.yaml servers /api/v1, bearerAuth)
import { setMediaToken } from '../media'

/** API 서버 주소 (.env의 VITE_API_ORIGIN). 비우면 화면과 같은 주소의 /api/v1 */
export const API_BASE = `${import.meta.env.VITE_API_ORIGIN ?? ''}/api/v1`

let token: string | null = null
/** 로그인 뒤 받은 액세스 토큰을 한 번 넣는다 (사진 불러오기에도 함께 쓰임). 로그아웃하면 null */
export function setAccessToken(t: string | null) {
  token = t
  setMediaToken(t)
}
export const authHeaders = (): Record<string, string> => (token ? { Authorization: `Bearer ${token}` } : {})
/** 전송 대기함(offline/outbox.js)에 넘기는 설정 */
export const sendOpts = (onUploaded?: () => void) => ({ apiBase: API_BASE, headers: authHeaders(), onUploaded })

/** JSON API 호출. 실패하면 서버 code·message를 담은 오류를 던진다 */
export async function api<T>(method: 'GET' | 'POST', path: string): Promise<T> {
  const r = await fetch(`${API_BASE}${path}`, { method, cache: 'no-store', credentials: 'include', headers: authHeaders() })
  const body = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(`${r.status} ${body.code ?? ''} ${body.message ?? ''}`.trim())
  return body as T
}

// ---------------- 오늘 순회 (응답 JSON 명세 7절: GET /me/patrols, GET /patrols/{id})
export type ApiSlot = { id: string; label: string; instruction?: string; captureStatus?: string | null; retakesRemaining?: number; purpose?: 'REGULAR' | 'REINSPECTION' }
export type ApiStop = { id: string; order?: number; name: string; facilityType: string; instruction?: string; captureSlots: ApiSlot[] }
export type ApiPatrol = { id: string; stops: ApiStop[] }

export async function fetchTodayPatrol(): Promise<ApiPatrol> {
  const list = await api<{ items?: { id: string }[] }>('GET', '/me/patrols')
  if (!list.items?.length) throw new Error('오늘 배정된 순회가 없어요.')
  return api<ApiPatrol>('GET', `/patrols/${list.items[0].id}`)
}
export const fetchPatrol = (id: string) => api<ApiPatrol>('GET', `/patrols/${id}`)
