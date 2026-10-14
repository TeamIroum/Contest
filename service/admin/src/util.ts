import { REAL_BACKEND } from './api/client'
import { mdhm } from './api/real'

// 'MM/DD HH:MM' 또는 'MM/DD' 형식의 시연용 날짜 다루기 (연도는 2026년으로 고정)
export function parseAt(s: string) {
  const m = s.match(/(\d{2})\/(\d{2})(?:\s+(\d{2}):(\d{2}))?/)
  if (!m) return new Date(2026, 9, 12)
  return new Date(2026, Number(m[1]) - 1, Number(m[2]), Number(m[3] ?? 0), Number(m[4] ?? 0))
}

export function fmtMD(d: Date) {
  return `${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

/** 시연 시계(실제 백엔드면 실제 시각): 2026-10-12 오전 10:40에서 시작해 실제 시간만큼 흐른다 (실제 컴퓨터 시각과 무관).
 *  real=true: 체험판에서 직접 찍은 사진(과 그 사진에서 생긴 사건)을 처리할 때는 실제 시각을 쓴다 */
const DEMO_START = Date.now()
export function nowAt(real = false) {
  if (REAL_BACKEND || real) return mdhm(new Date().toISOString()) // 실제 한국 시각
  const min = 10 * 60 + 40 + Math.floor((Date.now() - DEMO_START) / 60000)
  return `10/12 ${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`
}

export function addDays(md: string, n: number) {
  const d = parseAt(md)
  d.setDate(d.getDate() + n)
  return fmtMD(d)
}

/** 시연 기준일(10/12)부터 남은 일수 */
export function daysLeft(md: string) {
  const base = new Date(2026, 9, 12)
  return Math.round((parseAt(md).getTime() - base.getTime()) / 86400000)
}
