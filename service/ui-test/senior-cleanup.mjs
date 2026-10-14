// 휴대폰 자료 정리(SYN-06): 앱을 열 때
//   - 보관 기한(활동일 +2일 00:00 KST)이 지난 대기 사진·대기 이벤트를 지우고, 기한 안의 것은 남기는지 (경계: 그저께 23:59 → 지움, 어제 00:01 → 남김)
//   - 예전 방식의 거절 표시(permanent) 사진을 지우는지, 7일 지난 보낸 기록을 지우고 최근 기록은 남기는지
//   - 로그인 전 권한 없음(403 FORBIDDEN)으로 못 보낸 사진·이벤트를 '최종 거절'로 보고 지우지 않는지
//   node senior-cleanup.mjs [시니어 주소]
// 서버 데이터는 바꾸지 않는다 (로그인하지 않음)
import { chromium } from 'playwright'
import path from 'node:path'
import { report, SHOTS } from './common.mjs'

const SENIOR = process.argv[2] || 'http://127.0.0.1:8180'
const problems = []
const browser = await chromium.launch()
try {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: 'ko-KR' })
  const s = await ctx.newPage()
  await s.goto(SENIOR, { waitUntil: 'load' })
  await s.getByRole('button', { name: '휴대폰 번호로 시작하기' }).waitFor({ timeout: 20000 })

  console.log('▶ 휴대폰 저장소에 오래된 자료와 최근 자료 넣기')
  await s.evaluate(async () => {
    const DAY = 864e5
    const KST = 9 * 36e5
    const today = Math.floor((Date.now() + KST) / DAY) * DAY - KST // 오늘 00:00 KST
    const iso = (t) => new Date(t).toISOString()
    const photo = (id, t, extra = {}) => ({
      clientSubmissionId: id, attemptId: id + '-a', patrolId: 'cleanup-test', stopId: 'cleanup-stop', stopName: '정리 시험',
      captureSlotId: id + '-slot', capturedAt: iso(t), maskingVersion: 'test', deviceMaskReport: {},
      bytes: new Uint8Array([0xff, 0xd8, 0xff, 0xd9]).buffer, type: 'image/jpeg', ...extra,
    })
    const db = await new Promise((res, rej) => {
      const r = indexedDB.open('senior-outbox', 1)
      r.onupgradeneeded = () => {
        r.result.createObjectStore('outbox', { keyPath: 'clientSubmissionId' })
        r.result.createObjectStore('results', { keyPath: 'clientSubmissionId' })
      }
      r.onsuccess = () => res(r.result)
      r.onerror = () => rej(r.error)
    })
    await new Promise((res, rej) => {
      const t = db.transaction(['outbox', 'results'], 'readwrite')
      const o = t.objectStore('outbox')
      o.put(photo('old-photo', today - DAY - 60e3)) // 그저께 23:59 KST → 기한(오늘 00:00) 지남
      o.put(photo('recent-photo', today - DAY + 60e3)) // 어제 00:01 KST → 기한 내일 00:00
      o.put(photo('legacy-permanent', Date.now(), { permanent: true, lastError: '400 BAD_REQUEST' }))
      const r = t.objectStore('results')
      const rec = (id, t) => ({ clientSubmissionId: id, stopId: 'cleanup-stop', capturedAt: iso(t), http: 200, body: {}, at: iso(t) })
      r.put(rec('old-record', Date.now() - 8 * DAY))
      r.put(rec('recent-record', Date.now() - DAY))
      t.oncomplete = res
      t.onerror = () => rej(t.error)
    })
    db.close()
    const ev = (id, t) => ({ path: '/patrols/cleanup-test/skip-events', body: { clientEventId: id }, queuedAt: iso(t) })
    localStorage.setItem('senior-event-queue', JSON.stringify([ev('old-event', today - DAY - 60e3), ev('recent-event', today - DAY + 60e3)]))
  })

  console.log('▶ 앱 다시 열기 (정리 → 대기 자료 전송 시도: 로그인 전이라 서버가 받지 않음)')
  await s.reload({ waitUntil: 'load' })
  await s.getByRole('button', { name: '휴대폰 번호로 시작하기' }).waitFor({ timeout: 20000 })
  await s.waitForTimeout(4000)
  const left = await s.evaluate(async () => {
    const db = await new Promise((res, rej) => {
      const r = indexedDB.open('senior-outbox')
      r.onsuccess = () => res(r.result)
      r.onerror = () => rej(r.error)
    })
    const keys = (store) =>
      new Promise((res) => {
        const q = db.transaction(store).objectStore(store).getAllKeys()
        q.onsuccess = () => res(q.result.sort())
      })
    const out = { photos: await keys('outbox'), records: await keys('results') }
    db.close()
    out.events = JSON.parse(localStorage.getItem('senior-event-queue') ?? '[]').map((e) => e.body.clientEventId)
    localStorage.removeItem('senior-event-queue')
    indexedDB.deleteDatabase('senior-outbox')
    return out
  })
  await s.screenshot({ path: path.join(SHOTS, 'cleanup-1-정리뒤.png') })
  console.log(`  남은 대기 사진: ${left.photos.join(', ') || '없음'}`)
  console.log(`  남은 보낸 기록: ${left.records.join(', ') || '없음'}`)
  console.log(`  남은 대기 이벤트: ${left.events.join(', ') || '없음'}`)
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
  if (left.photos.includes('old-photo')) problems.push('보관 기한이 지난 대기 사진을 지우지 않음')
  if (left.photos.includes('legacy-permanent')) problems.push('예전 방식의 거절 표시 사진을 지우지 않음')
  if (!left.photos.includes('recent-photo')) problems.push('기한 안의 대기 사진을 지움 (로그인 전 403을 최종 거절로 봤을 수 있음)')
  if (!same(left.records, ['recent-record'])) problems.push(`보낸 기록 정리가 맞지 않음: ${left.records.join(', ')}`)
  if (!same(left.events, ['recent-event'])) problems.push(`대기 이벤트 정리가 맞지 않음: ${left.events.join(', ')}`)
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
