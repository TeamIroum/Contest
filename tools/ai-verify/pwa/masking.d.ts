// masking.js 타입 선언. 시니어 앱(TypeScript strict)에서 가져다 쓸 때 타입 검사·자동 완성용

export type MaskKind = "face" | "plate" | "vehicle";

/** 가린 위치. box = 사진 크기 대비 비율 [왼쪽, 위, 오른쪽, 아래] (0~1) */
export interface MaskBox {
  kind: MaskKind;
  /** 검출 점수 (maskCapture가 돌려주는 boxes에만 있음. 서버로 보내는 report.boxes에는 없음) */
  score?: number;
  box: [number, number, number, number];
}

/** 업무 API deviceMaskReport (API 명세서 6장) */
export interface DeviceMaskReport {
  faces: number;
  plates: number;
  vehicles: number;
  detector: string;
  appVersion: string;
  boxes: MaskBox[];
}

/** 처리 시간 (ms) */
export interface MaskTimings {
  face: number;
  plate: number;
  vehicle: number;
  mask: number;
  encode: number;
  total: number;
  /** camera.js가 보낼 JPEG를 다시 잰 시간 */
  check?: number;
}

export interface MaskResult {
  /** 가린 JPEG */
  blob: Blob;
  report: DeviceMaskReport;
  /** 화면 확인용 (점수 포함) */
  boxes: Required<MaskBox>[];
  size: { captured: [number, number]; sent: [number, number] };
  timings: MaskTimings;
  blur: boolean;
}

export type ModelState = "대기" | "받는 중" | "준비" | "실패" | (string & {});

export declare const MASKING_VERSION: string;
export declare const DETECTOR: string;
/** 기기 번호판 검출 점수 기준 */
export declare const PLATE_CONF: number;
/** 모델 파일 주소 (loadModels가 채움) */
export declare const MODEL_FILES: Partial<Record<MaskKind, string>>;
/** 모델별 준비 상태. backend = 실행 방식 (예: "wasm ×1") */
export declare const status: Record<MaskKind, ModelState> & { backend: string };

/** 업무 API maskingVersion (모델이 바뀌면 바뀜) */
export declare function maskingVersion(): string;

/** 얼굴 → 번호판 → 차량 모델을 차례로 받는다 (처음 약 20MB, 이어 받기). 상태가 바뀔 때마다 onChange */
export declare function loadModels(onChange?: () => void): Promise<void>;

/** 세 모델이 모두 준비되었는지. 준비 전에는 찍기 버튼을 끈다 */
export declare function ready(): boolean;

/**
 * 원본 캔버스를 가려 JPEG로 만든다. 끝나면 source는 성공·실패 모두 크기 0이 된다.
 * 보통은 직접 부르지 않고 camera.capture를 쓴다.
 */
export declare function maskCapture(source: HTMLCanvasElement, appVersion?: string): Promise<MaskResult>;

/** 얼굴 검출 자체 점검 (시험용) */
export declare function selfTestFaces(base?: string): Promise<{ ok: boolean; lines: string[] }>;
