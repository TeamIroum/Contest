// outbox.js 타입 선언. 시니어 앱(TypeScript strict)에서 가져다 쓸 때 타입 검사·자동 완성용
import type { DeviceMaskReport } from "./masking.js";

/** 대기함에 넣는 한 건. 가린 사진만 넣는다 */
export interface OutboxItem {
  /** 다시 보낼 때 그대로 써야 서버가 두 번 접수하지 않는다. 새로 찍으면 새 ID */
  clientSubmissionId: string;
  attemptId: string;
  patrolId: string;
  stopId: string;
  /** 대기함 목록 표시용 */
  stopName?: string;
  captureSlotId: string;
  capturedAt: string;
  maskingVersion: string;
  deviceMaskReport: DeviceMaskReport;
  /** 가린 JPEG. put 뒤 저장본에는 blob 대신 bytes·type이 들어 있다 */
  blob?: Blob;
  bytes?: ArrayBuffer;
  type?: string;
  location?: { latitude: number; longitude: number; accuracy: number } | null;
  /** 시험용 업무 API에만 보내는 시험 정보 (실제 앱에서는 넣지 않음) */
  testInfo?: Record<string, unknown>;
  /** 마지막 실패 사유 (대기함이 적음) */
  lastError?: string;
  /** 4xx 형식 오류: 자동으로 다시 보내지 않음 */
  permanent?: boolean;
  tries?: number;
}

/** 업무 API 사진 제출 응답 (API 명세서 6장). 위험도·판독 결과는 들어 있지 않다 */
export interface SubmissionResponse {
  inspectionId?: string;
  receiptStatus?: "RECEIVED" | "NO_MEDIA" | "RETRY_REQUIRED" | (string & {});
  captureStatus?: "ACCEPTED" | "REVIEW_REQUESTED" | "RETAKE_REQUIRED" | "TECHNICAL_FAILED" | (string & {});
  nextAction?: "RETAKE" | "NEXT_CAPTURE" | "NEXT_STOP" | "RETURN_TO_CENTER" | (string & {});
  nextStopId?: string | null;
  retakesRemaining?: number;
  /** 어르신 화면에 그대로 보여 줄 안내 */
  message?: string | null;
  duplicate?: boolean;
  /** 오류일 때. 예: SLOT_RESOLVED, SUBMISSION_PROCESSING, SUBMISSION_CONFLICT, ANALYSIS_UNAVAILABLE */
  code?: string;
  detail?: string;
  existingInspectionId?: string;
  [key: string]: unknown;
}

export interface SendResult {
  /** 대기함에서 뺐는지 (접수·사진 없는 품질 실패·이미 끝난 구도) */
  done: boolean;
  http?: number;
  body?: SubmissionResponse;
  /** 연결이 없어 못 보냄. 대기함에 남아 있다 */
  offline?: boolean;
}

export interface SendOptions {
  /** 업무 API 주소. 예: https://<서버>/api/v1 */
  apiBase: string;
}

/** 저장소(IndexedDB) 이름을 정한다. 기본 "pwa-test-outbox". 대기함을 처음 쓰기 전에 한 번 부른다 */
export declare function configure(opts?: { dbName?: string }): void;

/** 기기에 먼저 저장한다 (보내기 전에 반드시) */
export declare function put(item: OutboxItem): Promise<IDBValidKey>;
export declare function remove(clientSubmissionId: string): Promise<undefined>;
export declare function all(): Promise<OutboxItem[]>;
export declare function count(): Promise<number>;

/** 한 건 보내기. 같은 사진이 이미 보내는 중이면 그 결과를 함께 기다린다 */
export declare function send(item: OutboxItem, opts: SendOptions): Promise<SendResult>;

/** 대기함의 사진을 차례로 보낸다 (앱을 다시 열 때, 연결이 돌아올 때). 연결이 없으면 멈춘다 */
export declare function flush(opts: SendOptions): Promise<Array<SendResult & { item: OutboxItem }>>;

/** 최종 답을 받은 기록 (시험용 목록 표시) */
export interface SentRecord {
  clientSubmissionId: string;
  stopId: string;
  stopName?: string;
  capturedAt: string;
  http: number;
  body: SubmissionResponse;
  at: string;
}
export declare function results(): Promise<SentRecord[]>;
export declare function clearResults(): Promise<undefined>;
