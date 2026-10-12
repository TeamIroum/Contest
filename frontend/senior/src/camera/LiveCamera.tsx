import { useEffect, useRef, type RefObject } from 'react'

/** 촬영 화면이 부르는 손잡이: 지금 장면을 원본 캔버스로 떼기, 카메라 끄기 */
export type CameraHandle = {
  /** 지금 장면을 캔버스에 그려 돌려준다. reuse를 주면 그 캔버스를 다시 쓴다 (연속 장면 중 고르기) */
  frame: (reuse?: HTMLCanvasElement | null) => HTMLCanvasElement | null
  stop: () => void
}

/**
 * 앱 안 카메라 (getUserMedia, 후면). 갤러리·기본 카메라 앱·<input capture>는 쓰지 않는다 (안내서 2장).
 * 시험용 PWA app.js의 openCamera·closeCamera를 옮긴 것.
 * 떼어 낸 원본 캔버스는 바로 masking.maskCapture로 넘기거나 크기 0으로 지워야 한다.
 */
export function LiveCamera({
  handle,
  onError,
  onReady,
  className = '',
}: {
  handle: RefObject<CameraHandle | null>
  onError: (why: string) => void
  /** 화면이 실제로 나오기 시작하면 true (그 전에는 셔터를 막는다) */
  onReady: (ready: boolean) => void
  className?: string
}) {
  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)

  useEffect(() => {
    let stopped = false
    const stop = () => {
      stream.current?.getTracks().forEach((t) => t.stop())
      stream.current = null
      if (video.current) video.current.srcObject = null
    }
    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false })
      .then(async (s) => {
        if (stopped) return s.getTracks().forEach((t) => t.stop())
        stream.current = s
        if (video.current) {
          video.current.srcObject = s
          await video.current.play().catch(() => {})
          if (!stopped && video.current?.videoWidth) onReady(true)
        }
      })
      .catch((e: Error) => onError(e.name === 'NotAllowedError' ? '카메라 사용을 허락해 주세요.' : '카메라를 열지 못했어요.'))

    handle.current = {
      frame: (reuse) => {
        const v = video.current
        if (!v || !v.videoWidth) return null
        const c = reuse || document.createElement('canvas')
        c.width = v.videoWidth
        c.height = v.videoHeight
        c.getContext('2d')!.drawImage(v, 0, 0)
        return c
      },
      stop,
    }
    return () => {
      stopped = true
      handle.current = null
      onReady(false)
      stop()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <video
      ref={video}
      playsInline
      muted
      onLoadedData={(e) => e.currentTarget.videoWidth && onReady(true)}
      className={`block aspect-[4/3] w-full rounded-[10px] bg-black object-contain ${className}`}
    />
  )
}
