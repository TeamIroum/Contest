// quality.js (흐림·어두움 계산, 서버 ai/quality.py와 같음) 타입
export type Quality = { sharpness: number; brightness: number }
export const BLUR_THRESHOLD: number
export const DARK_THRESHOLD: number
export function measure(source: HTMLCanvasElement | HTMLVideoElement | ImageBitmap, opts?: { exact?: boolean }): Quality
/** 'dark' 어두움 / 'blur' 흐림 / null 괜찮음 */
export function problem(q: Quality): 'dark' | 'blur' | null
