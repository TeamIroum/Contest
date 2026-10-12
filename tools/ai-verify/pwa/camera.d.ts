// camera.js 타입 선언. 시니어 앱(TypeScript strict)에서 가져다 쓸 때 타입 검사·자동 완성용
import type { DeviceMaskReport, MaskBox, MaskTimings } from "./masking.js";

/** 다시 찍게 하는 이유. blur·dark = 흐림·어두움, mask = 가리기 실패 (원본은 버려짐) */
export type RetakeReason = "blur" | "dark" | "mask";

/** 어르신 화면 문구 */
export declare const RETAKE_TEXT: Record<RetakeReason, { title: string; sub: string }>;

/** 카메라를 열지 못함. reason: denied = 권한 거부, unavailable = 카메라 없음·사용 중 등 */
export declare class CameraError extends Error {
  readonly name: "CameraError";
  readonly reason: "denied" | "unavailable";
  readonly cause: unknown;
  constructor(reason: "denied" | "unavailable", cause?: unknown);
}

/** 후면 카메라를 video에 띄운다. 실제로 받은 해상도를 돌려준다. 실패하면 CameraError */
export declare function open(video: HTMLVideoElement): Promise<{ width: number; height: number }>;

export declare function close(video: HTMLVideoElement): void;

/** 카메라를 여는 버튼의 클릭 처리 안에서 한 번 부른다 (아이폰 촬영음 준비) */
export declare function unlockSound(): void;

export interface CaptureOptions {
  /** 서버에 보고할 앱 버전 (deviceMaskReport.appVersion) */
  appVersion?: string;
  /** 촬영음 직후 (화면 번쩍임 등) */
  onShutter?: () => void;
  /** 장면을 고른 뒤 가리기 전에 (화면을 '가리는 중'으로). 카메라는 이미 닫혀 있다 */
  onProcessing?: () => void;
}

/** 시험 기록용 정보. 사진 내용은 없다 */
export interface CaptureInfo {
  stage?: "frame" | "mask" | "encode";
  /** 연속 촬영 중 고른 장면과 선명도. 저장된 사진 시험(fromCanvas)이면 null */
  burst: {
    frames: number;
    picked: number;
    sharpness: number;
    brightness: number;
    sentSharpness?: number;
    sentSharpnessFast?: number;
  } | null;
  boxes?: MaskBox[];
  size?: { captured: [number, number]; sent: [number, number] };
  timings?: MaskTimings;
  /** 캔버스 흐림 효과 지원 여부 (안 되면 모자이크를 더 거칠게) */
  blur?: boolean;
  /** 보낼 JPEG로 다시 잰 값 */
  sent?: { sharpness: number; brightness: number };
  /** 가리기 실패 사유 (reason이 mask일 때) */
  error?: string;
}

export type CaptureResult =
  | {
      ok: true;
      /** 가린 JPEG (긴 변 1,280px, 위치·기기 정보 없음) */
      blob: Blob;
      /** 업무 API deviceMaskReport */
      report: DeviceMaskReport;
      /** 업무 API maskingVersion */
      maskingVersion: string;
      /** 셔터를 누른 시각 (ISO 8601) */
      capturedAt: string;
      info: CaptureInfo;
    }
  | { ok: false; reason: RetakeReason; info: CaptureInfo };

/**
 * 셔터 버튼의 클릭 처리 안에서 바로 부른다 (아이폰은 사용자 동작 안에서만 소리를 낸다).
 * 촬영음 → 연속 장면 중 가장 선명한 것 → 흐림·어두움 확인 → 가리기 → 보낼 JPEG 재확인.
 * 카메라 영상이 아직 없으면 null.
 */
export declare function capture(video: HTMLVideoElement, opts?: CaptureOptions): Promise<CaptureResult | null>;

/** 저장된 사진으로 시험할 때 (담당자용, 실제 시니어 앱에는 두지 않음). raw는 끝나면 크기 0이 된다 */
export declare function fromCanvas(raw: HTMLCanvasElement, opts?: Pick<CaptureOptions, "appVersion">): Promise<CaptureResult>;
