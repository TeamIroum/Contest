// 보수 증빙 사진 (실제 모드): 사진을 고르면 이 브라우저에서 얼굴·번호판·차량을 먼저 가리고, 가린 사진만 서버에 올린다.
// 원본은 브라우저 메모리에서만 다루고 어디에도 저장·전송하지 않는다. 가림 코드와 모델은 시니어 앱 것을 그대로 쓴다
// (모델 파일은 Caddy가 관리자 주소의 /models/에 시니어 앱 폴더를 그대로 연결한다).
import { useEffect, useRef, useState } from 'react'
import * as masking from '../../senior/src/masking/masking.js'
import type { Issue } from './data'
import { Button } from './ui'
import * as real from './api/real'

export type Evidence = { mediaId: string; preview: string }
const MAX = 3

export function EvidencePicker({ issue, value, onChange, disabled }: { issue: Issue; value: Evidence[]; onChange: (v: Evidence[]) => void; disabled?: boolean }) {
  const [state, setState] = useState<'대기' | '준비 중' | '준비' | '실패'>('대기')
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => {
    let alive = true
    setState('준비 중')
    masking
      .loadModels(() => undefined, { manifestUrl: '/models/model-manifest.json' })
      .then(() => alive && setState('준비'))
      .catch(() => alive && setState('실패'))
    return () => {
      alive = false
    }
  }, [])
  // 미리보기 주소는 화면을 떠날 때 지운다
  useEffect(() => () => value.forEach((v) => URL.revokeObjectURL(v.preview)), []) // eslint-disable-line react-hooks/exhaustive-deps

  async function pick(file: File) {
    setError('')
    setBusy('얼굴·번호판을 가리는 중…')
    try {
      const bitmap = await createImageBitmap(file)
      const canvas = document.createElement('canvas')
      canvas.width = bitmap.width
      canvas.height = bitmap.height
      canvas.getContext('2d')!.drawImage(bitmap, 0, 0)
      bitmap.close()
      const masked = await masking.maskCapture(canvas, 'admin-web')
      setBusy('개인정보 검사 뒤 올리는 중…')
      const r = await real.uploadEvidence(issue, masked.blob, masked.report, masking.maskingVersion())
      onChange([...value, { mediaId: r.mediaId, preview: URL.createObjectURL(masked.blob) }])
    } catch (e) {
      setError(e instanceof Error && e.message && !/^[A-Z_]+$/.test(e.message) ? `사진을 올리지 못했습니다. ${e.message}` : '사진을 올리지 못했습니다. 다른 사진으로 다시 해 주세요.')
    } finally {
      setBusy('')
      if (input.current) input.current.value = ''
    }
  }

  return (
    <div className="grid gap-2 text-sm">
      <span className="font-semibold">보수 증빙 사진 (선택, 최대 {MAX}장)</span>
      {value.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {value.map((v) => (
            <span key={v.mediaId} className="relative">
              <img src={v.preview} alt="보수 증빙 사진(가린 사진)" className="h-20 w-28 rounded-lg border border-line object-cover" />
              <button
                type="button"
                className="absolute -top-2 -right-2 grid size-6 place-items-center rounded-full bg-ink text-xs text-white"
                aria-label="이 사진 빼기"
                onClick={() => {
                  URL.revokeObjectURL(v.preview)
                  onChange(value.filter((x) => x.mediaId !== v.mediaId))
                }}
              >
                ✕
              </button>
            </span>
          ))}
        </div>
      )}
      <input ref={input} type="file" accept="image/jpeg,image/png,image/webp" className="hidden" aria-label="보수 증빙 사진 파일" onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" disabled={disabled || state !== '준비' || !!busy || value.length >= MAX} onClick={() => input.current?.click()}>
          사진 고르기
        </Button>
        <span className="text-xs text-mute">
          {busy || (state === '준비' ? '고른 사진은 이 브라우저에서 얼굴·번호판을 가린 뒤 올라갑니다.' : state === '실패' ? '가림 모델을 불러오지 못했습니다. 새로 고침해 주세요.' : '가림 모델을 불러오는 중…')}
        </span>
      </div>
      {error && <p className="m-0 text-xs text-g3-ink">{error}</p>}
      <p className="m-0 text-xs text-mute">개인정보 검사를 거친 사진만 저장되며 판독 실적에는 넣지 않습니다.</p>
    </div>
  )
}
