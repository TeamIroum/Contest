// outbox.js (전송 대기함) 타입
import type { MaskReport } from '../masking/masking'

export type OutboxItem = {
  clientSubmissionId: string
  attemptId: string
  patrolId: string
  stopId: string
  stopName?: string
  captureSlotId: string
  capturedAt: string
  maskingVersion: string
  deviceMaskReport: MaskReport
  blob?: Blob
  lastError?: string
  permanent?: boolean
}
/** 서버 답 (응답 JSON 명세): receiptStatus로 정리 여부, nextAction으로 다음 화면, message는 그대로 표시 */
export type SubmitBody = {
  receiptStatus?: 'RECEIVING' | 'RECEIVED' | 'NO_MEDIA' | 'RETRY_REQUIRED'
  captureStatus?: 'PROCESSING' | 'ACCEPTED' | 'RETAKE_REQUIRED' | 'TECHNICAL_FAILED' | 'REVIEW_REQUESTED'
  nextAction?: 'WAIT' | 'RETAKE' | 'RETRY' | 'NEXT_CAPTURE' | 'NEXT_STOP' | 'RETURN_TO_CENTER'
  nextStopId?: string | null
  retakesRemaining?: number
  message?: string
  code?: string
}
export type SendResult = { done: boolean; offline?: boolean; http?: number; body?: SubmitBody }
export type SendOpts = { apiBase: string; headers?: Record<string, string>; onUploaded?: () => void }

export function put(item: OutboxItem): Promise<unknown>
export function remove(id: string): Promise<unknown>
export function all(): Promise<OutboxItem[]>
export function count(): Promise<number>
export function send(item: OutboxItem, opts: SendOpts): Promise<SendResult>
export function flush(opts: SendOpts): Promise<(SendResult & { item: OutboxItem })[]>
