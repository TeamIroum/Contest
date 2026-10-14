// 오프라인 촬영(ACC-06, SYN-04·05): 연결이 끊긴 채 찍으면 휴대폰에 저장하고 '전송 대기'로 안내(성공으로 표시하지 않음) →
// 연결이 끊긴 채 다음 장소를 건너뛰어도 진행되고(기록은 휴대폰에 저장, SYN-04) →
// 연결이 돌아오면 같은 제출 ID로 자동으로 보내 서버에 사진이 한 장만 접수되는지, 건너뛰기 기록도 도착하는지
//   node senior-offline.mjs [시니어 주소] [관리자 주소]      VIDEO=<mjpeg> 로 카메라 사진 지정 (run-all이 넘김)
// 실제 데이터를 바꾼다 (오늘 순회 필요, 끝나면 초기화)
import { chromium } from 'playwright'
import path from 'node:path'
import { report, serviceEnv, confirmAttendanceAndDeparture, SHOTS } from './common.mjs'

const SENIOR = process.argv[2] || 'http://127.0.0.1:8180'
const ADMIN = process.argv[3] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const problems = []
const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream']
if (process.env.VIDEO) args.push(`--use-file-for-fake-video-capture=${process.env.VIDEO}`)
const browser = await chromium.launch({ args })
const snap = async (page, name) => {
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(SHOTS, `offline-${name}.png`) })
  console.log(`  캡처 offline-${name}`)
}
try {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: 'ko-KR', permissions: ['camera'] })
  const s = await ctx.newPage()
  const posts = []
  s.on('request', (r) => r.method() === 'POST' && r.url().includes('/inspections') && posts.push(Date.now()))
  const tap = (name) => s.getByRole('button', { name }).first().click()
  await s.goto(SENIOR, { waitUntil: 'load' })
  await tap('휴대폰 번호로 시작하기')
  for (const d of '01000000001') await s.getByRole('button', { name: d, exact: true }).click()
  await tap('인증번호 받기')
  await s.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of env.DEMO_OTP_CODE) await s.getByRole('button', { name: d, exact: true }).click()
  await s.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 20000 })
  await tap('센터에 도착했어요')
  if (await s.getByRole('button', { name: '출석 요청하기' }).count()) await tap('출석 요청하기')
  await confirmAttendanceAndDeparture(browser, ADMIN)
  await s.getByRole('button', { name: '활동 시작하기' }).waitFor({ timeout: 20000 })
  await tap('활동 시작하기')
  await tap('도착했어요 · 사진 찍기')
  await s.waitForFunction(() => !document.querySelector('[aria-label="눌러서 사진 찍기"]')?.hasAttribute('disabled'), null, { timeout: 90000 })

  console.log('▶ 연결 끊고 촬영')
  await ctx.setOffline(true)
  await s.getByRole('button', { name: '눌러서 사진 찍기' }).click()
  const queued = await s.getByText('전송 대기 중이에요').waitFor({ timeout: 60000 }).then(() => true, () => false)
  console.log(`  '전송 대기 중이에요' 안내: ${queued ? '예' : '아니오'}`)
  if (!queued) problems.push('연결이 끊겼는데 전송 대기로 안내하지 않음')
  if (await s.getByText(/사진을 보냈어요|잘 받았어요/).count()) problems.push('연결이 끊겼는데 성공으로 표시함')
  await snap(s, '1-전송대기')
  const before = posts.length
  const skips = []
  s.on('response', (r) => r.url().includes('/skip-events') && r.status() < 300 && skips.push(Date.now())) // 서버가 받은 것만

  console.log('▶ 연결이 끊긴 채 다음 장소 건너뛰기 → 진행되는지')
  await s.getByRole('button', { name: /다음 장소로|다음/ }).first().click()
  await s.getByRole('button', { name: '이곳은 찍을 수 없어요' }).first().waitFor({ timeout: 10000 })
  const stopBefore = await s.locator('main p').filter({ hasText: /^\d+\. / }).first().innerText().catch(() => '')
  await s.getByRole('button', { name: '이곳은 찍을 수 없어요' }).first().click()
  await s.getByRole('button', { name: '위험해서 못 찍겠어요' }).click()
  const moved = await s
    .waitForFunction((b) => ![...document.querySelectorAll('main p')].some((p) => p.textContent === b), stopBefore, { timeout: 8000 })
    .then(() => true, () => false)
  console.log(`  연결 없이 건너뛰고 다음 장소로: ${moved ? '예' : '아니오'}`)
  if (!moved) problems.push('연결이 끊겼을 때 건너뛰기가 진행되지 않음')
  if (await s.getByText('건너뛴 것을 기록하지 못했어요').count()) problems.push('연결이 끊겼을 때 건너뛰기가 오류로 끝남')
  await snap(s, '1b-연결없이건너뜀')

  console.log('▶ 연결 다시 → 자동 전송')
  await ctx.setOffline(false)
  await s.evaluate(() => window.dispatchEvent(new Event('online')))
  // 대기함이 비면 '전송 대기 N장' 표시가 사라진다
  let sent = false
  for (let i = 0; i < 30 && !sent; i++) {
    await s.waitForTimeout(2000)
    sent = posts.length > before && !(await s.getByText(/전송 대기 \d+(장|건)/).count())
  }
  console.log(`  다시 보냄: ${sent ? '예' : '아니오'} (제출 요청 ${posts.length}번, 건너뛰기 요청 ${skips.length}번)`)
  if (!sent) problems.push('연결이 돌아왔는데 대기 사진을 보내지 않음')
  if (!skips.length) problems.push('연결이 돌아왔는데 대기 중이던 건너뛰기 기록을 보내지 않음')
  await snap(s, '2-재연결뒤')

  console.log('▶ 연결을 끊고 새로 고침 → 앱이 열리고 안내 → 연결되면 이어서')
  await ctx.setOffline(true)
  await s.reload({ waitUntil: 'load' }).catch(() => {})
  const opened = await s.getByText('인터넷 연결이 끊겨 있어요').waitFor({ timeout: 15000 }).then(() => true, () => false)
  console.log(`  연결 없이 앱 열림·안내: ${opened ? '예' : '아니오'}`)
  if (!opened) problems.push('연결이 끊긴 채 새로 고침하면 앱이 열리지 않거나 안내가 없음')
  await snap(s, '3-연결없이새로고침')
  await ctx.setOffline(false)
  await s.evaluate(() => window.dispatchEvent(new Event('online')))
  const resumed = await s.getByText('인터넷 연결이 끊겨 있어요').waitFor({ state: 'detached', timeout: 20000 }).then(() => true, () => false)
  const start = await s.getByRole('button', { name: '휴대폰 번호로 시작하기' }).count()
  console.log(`  연결되면 로그인 그대로 이어짐: ${resumed && !start ? '예' : '아니오'}`)
  if (!resumed || start) problems.push('연결이 돌아왔는데 로그인한 채로 이어지지 않음')
  await snap(s, '4-연결뒤이어짐')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems.filter((p) => !/ERR_INTERNET_DISCONNECTED|net::/.test(p)))
}
