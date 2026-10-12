// masking.js (시험용 PWA에서 옮긴 기기 마스킹) 타입
export type MaskReport = {
  faces: number
  plates: number
  vehicles: number
  detector: string
  appVersion: string
  boxes?: { kind: 'face' | 'plate' | 'vehicle'; box: number[] }[]
}
export type Masked = {
  blob: Blob
  report: MaskReport
  boxes: { kind: 'face' | 'plate' | 'vehicle'; score: number; box: number[] }[]
  size: { captured: number[]; sent: number[] }
  timings: Record<string, number>
  blur: boolean
}
/** 모델 준비 상태: 대기 / 받는 중 / 받는 중 42% / 연결이 끊겨 … / 준비 / 실패 */
export const status: { face: string; plate: string; vehicle: string; backend: string }
export function loadModels(onChange?: () => void): Promise<void>
export function ready(): boolean
export function maskingVersion(): string
/** source: 원본 캔버스. 끝나면 크기가 0이 된다 (성공·실패 모두) */
export function maskCapture(source: HTMLCanvasElement, appVersion: string): Promise<Masked>
