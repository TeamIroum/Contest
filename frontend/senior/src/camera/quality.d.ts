// quality.js 타입 선언. 시니어 앱(TypeScript strict)에서 가져다 쓸 때 타입 검사·자동 완성용
// 보통은 직접 부르지 않고 camera.capture가 쓴다.

/** 선명도 기준 (ai/quality.py BLUR_THRESHOLD) */
export declare const BLUR_THRESHOLD: number;
/** 밝기 기준 (ai/quality.py DARK_THRESHOLD) */
export declare const DARK_THRESHOLD: number;

export interface QualityMeasure {
  /** 라플라시안 분산 (높을수록 선명) */
  sharpness: number;
  /** 흑백 평균 밝기 (0~255) */
  brightness: number;
}

/**
 * 선명도·밝기를 잰다.
 * exact=false: 줄이기를 브라우저에 맡김 (빠름, 서로 비교만 할 때)
 * exact=true: 서버(OpenCV)와 같은 방식으로 줄임 (보낼 사진 판정용)
 */
export declare function measure(
  source: HTMLCanvasElement | HTMLVideoElement | ImageBitmap,
  opts?: { exact?: boolean },
): QualityMeasure;

/** 문제가 있으면 "dark" | "blur", 괜찮으면 null (서버와 같은 순서) */
export declare function problem(q: QualityMeasure): "dark" | "blur" | null;

// 시니어 앱(frontend/senior)이 먼저 쓰던 이름
export type Quality = QualityMeasure;
