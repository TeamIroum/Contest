// 체험판 관리자 웹: 같은 브라우저의 체험판 시니어 앱이 찍어 둔 사진(마스킹본)을 검토 목록 맨 위에 넣는다.
// 서버가 없으므로 AI 판독은 하지 않고, 체험판이라는 안내만 붙인다. 사진은 이 브라우저 밖으로 나가지 않는다.
import { listPhotos, TRIAL } from '../../senior/src/trial/store'
import { FACILITIES, type ReviewTask } from './data'
import type { Kind } from './Photo'

export { TRIAL }

const pad = (n: number) => String(n).padStart(2, '0')
const label = (iso: string) => {
  const d = new Date(iso)
  return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 체험판 시니어 앱에서 찍은 사진 → 검토 작업 */
export async function trialReviews(): Promise<ReviewTask[]> {
  if (!TRIAL) return []
  const photos = await listPhotos()
  return photos.map((p) => {
    const kind = p.kind as Kind
    const facility = FACILITIES.find((f) => f.kind === kind) ?? FACILITIES[0]
    const at = label(p.at)
    const hidden = p.masked.faces + p.masked.plates + p.masked.vehicles
    return {
      id: `trial-${p.id}`,
      type: 'INSPECTION',
      status: 'OPEN',
      trigger: 'AI_NEEDS_REVIEW',
      facilityId: facility.id,
      at,
      team: '3조', // 체험판 시니어 앱의 예시 조와 같게
      members: ['김○○', '박○○'],
      photos: [{ slot: p.slot || '전체 모습', bad: false, src: URL.createObjectURL(p.blob) }],
      // 체험판은 AI 판독을 하지 않는다 (판독 결과 칸을 비워 'AI가 이상을 찾지 못함'으로 오해되지 않게)
      classification: { source: 'AI', kind, issueTypes: [], version: 0, correctionId: null },
      corrections: [],
      note: '체험판에서 찍은 사진이에요. 실제 서비스에서는 서버의 AI가 시설 종류·이상 유형·판단 근거를 함께 표시해요.',
      logs: [
        {
          at,
          who: '체험판',
          text: hidden ? `이 휴대폰에서 찍은 사진 · 얼굴·번호판·차량 ${hidden}곳 마스킹` : '이 휴대폰에서 찍은 사진 · 앱에서 마스킹 검사 완료',
        },
      ],
    }
  })
}
