import { useEffect, useState } from 'react'

// 서버 사진 불러오기 (명세: openapi.yaml Media, GET /api/v1/media/{id}/content)
// - 사진 주소(contentPath)는 로그인 토큰(Authorization: Bearer)을 붙여야 열려서 <img src>에 바로 못 쓴다.
//   토큰을 붙여 받아온 뒤 브라우저 안의 임시 주소(blob:)로 바꿔 보여 준다.
// - 서버가 Cache-Control: no-store라서 저장해 두지 않고, 화면을 떠나면 임시 주소를 지운다.
// - 개인정보 검토를 통과한(APPROVED) 사진만 받는다. 나머지는 사진 대신 안내를 보여 준다.

export type MediaRef = {
  id: string
  mediaVersion: number
  privacyStatus: 'APPROVED' | 'QUARANTINED' | 'REJECTED'
  contentPath: string
  /** 사진 기록 version. 개인정보 검토 요청에 보낸다 (실제 업무 서버만) */
  version?: number
  /** 보류 해제(재승인) 처리 상태 */
  privacyRelease?: { releaseId: string; status: string; retryable?: boolean; failureReason?: string | null } | null
}

/** API 서버 주소. 같은 주소에서 서비스하면 비워 둔다 (.env의 VITE_API_ORIGIN) */
const API_ORIGIN: string = import.meta.env.VITE_API_ORIGIN ?? ''

let accessToken: string | null = null
/** 로그인 후 받은 액세스 토큰을 넣어 둔다. 로그아웃하면 null */
export function setMediaToken(token: string | null) {
  accessToken = token
}

/**
 * AI 문제 위치(bbox) 순서. 0~1 비율 [왼쪽, 위, 오른쪽, 아래] (응답 JSON 명세 findings, AI API_CONTRACT issues[].bbox 기준)
 * 'xyxy' = [왼쪽, 위, 오른쪽, 아래] / 'xywh' = [왼쪽, 위, 너비, 높이]
 */
export const BBOX_FORMAT: 'xyxy' | 'xywh' = 'xyxy'

/** bbox를 화면 배치용 비율(%)로 바꾼다. 값이 이상하면 null */
export function bboxToRect(b: number[] | null | undefined) {
  if (!b || b.length !== 4 || b.some((v) => !(v >= 0 && v <= 1))) return null
  const [x, y, c, d] = b
  const w = BBOX_FORMAT === 'xyxy' ? c - x : c
  const h = BBOX_FORMAT === 'xyxy' ? d - y : d
  if (w <= 0 || h <= 0) return null
  return { left: `${x * 100}%`, top: `${y * 100}%`, width: `${Math.min(w, 1 - x) * 100}%`, height: `${Math.min(h, 1 - y) * 100}%` }
}

export type MediaState =
  | { status: 'none' } // 사진 정보 없음 → 시연용 그림
  | { status: 'loading' }
  | { status: 'ready'; url: string }
  | { status: 'blocked'; privacy: MediaRef['privacyStatus'] } // 개인정보 검토 전/반려
  | { status: 'error'; code?: number }

/**
 * 사진 하나를 불러온다.
 * src: 앱에 넣어 둔 시연용 사진 파일 주소 (토큰 없이 바로 표시)
 * media: 서버 응답의 media 객체 (토큰을 붙여 받아옴)
 */
export function useMedia(media?: MediaRef | null, src?: string): MediaState {
  const key = media ? `${media.id}:${media.mediaVersion}:${media.privacyStatus}` : ''
  const [state, setState] = useState<MediaState>({ status: 'none' })

  useEffect(() => {
    if (src || !media) return
    if (media.privacyStatus !== 'APPROVED') {
      setState({ status: 'blocked', privacy: media.privacyStatus })
      return
    }
    const ctrl = new AbortController()
    let url: string | null = null
    setState({ status: 'loading' })
    fetch(API_ORIGIN + media.contentPath, {
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
      cache: 'no-store',
      credentials: 'include',
      signal: ctrl.signal,
    })
      .then(async (res) => {
        if (!res.ok) {
          setState({ status: 'error', code: res.status })
          return
        }
        url = URL.createObjectURL(await res.blob())
        setState({ status: 'ready', url })
      })
      .catch((e: unknown) => {
        if ((e as Error).name !== 'AbortError') setState({ status: 'error' })
      })
    return () => {
      ctrl.abort()
      if (url) URL.revokeObjectURL(url)
    }
    // key가 같으면 같은 사진이다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, src])

  if (src) return { status: 'ready', url: src }
  if (!media) return { status: 'none' }
  return state
}
