// 체험판(서버 없음) 처음부터 끝까지: 입구 → 시니어 앱 바로 체험하기 → 출석·출발 확인 없이 활동 시작 →
// 실제 카메라(가짜 장치)로 장소마다 촬영 → 휴대폰에서 마스킹 → 센터 복귀 → 관리자 웹에서 내 사진이 검토 목록 맨 위에 보이는지.
// 그리고 서버로 나가는 요청이 없는지(/api 요청 0건)도 확인한다.
//   python service/web/pages/deploy_trial.py --out <폴더> 로 만들고 python -m http.server -d <폴더> 8199 로 띄운 뒤
//   node trial.mjs [입구 주소, 기본 http://127.0.0.1:8199/service/trial/]      VIDEO=<mjpeg> 로 카메라 사진 지정
import { chromium } from 'playwright'
import path from 'node:path'
import { report, SHOTS } from './common.mjs'

const ENTRY = process.argv[2] || 'http://127.0.0.1:8199/service/trial/'
const problems = []
const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream']
if (process.env.VIDEO) args.push(`--use-file-for-fake-video-capture=${process.env.VIDEO}`)
const browser = await chromium.launch({ args })
const shot = (page, name) => page.screenshot({ path: path.join(SHOTS, `trial-${name}.png`) }).catch(() => {})
try {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: 'ko-KR', permissions: ['camera'] })
  const page = await ctx.newPage()
  const apiCalls = []
  page.on('request', (r) => new URL(r.url()).pathname.includes('/api/') && apiCalls.push(r.url()))
  const tap = (name) => page.getByRole('button', { name }).first().click()

  console.log('▶ 입구 → 시니어 앱')
  await page.goto(ENTRY, { waitUntil: 'load' })
  await shot(page, '0-입구')
  await page.getByRole('link', { name: /시니어 앱/ }).click()
  await page.getByRole('button', { name: '바로 체험하기' }).waitFor({ timeout: 15000 })
  await shot(page, '1-시작')
  await tap('바로 체험하기')

  console.log('▶ 로그인 없이 홈 → 센터 도착 → 바로 활동 시작 (출석·출발 확인 건너뜀)')
  await page.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 10000 })
  if (!(await page.getByText('체험판').count())) problems.push("위쪽에 '체험판' 표시가 없음")
  await tap('센터에 도착했어요')
  const start = page.getByRole('button', { name: /활동 시작하기/ })
  const ready = await start.and(page.locator(':enabled')).waitFor({ timeout: 5000 }).then(() => true, () => false)
  console.log(`  출석 없이 시작 버튼 열림: ${ready ? '예' : '아니오'}`)
  if (!ready) problems.push('체험판인데 활동 시작 버튼이 바로 열리지 않음 (출석·출발 확인을 기다림)')
  await shot(page, '2-시작가능')
  await start.click()

  console.log('▶ 장소마다 촬영 (휴대폰에서 마스킹)')
  let photos = 0
  for (let i = 0; i < 20; i++) {
    if (await page.getByRole('button', { name: '센터로 돌아가기' }).count()) break
    if (await page.getByRole('button', { name: '도착했어요 · 사진 찍기' }).count()) {
      await tap('도착했어요 · 사진 찍기')
      continue
    }
    const shutter = page.getByRole('button', { name: '눌러서 사진 찍기' })
    if (await shutter.count()) {
      await page.waitForFunction(() => !document.querySelector('[aria-label="눌러서 사진 찍기"]')?.hasAttribute('disabled'), null, { timeout: 120000 })
      await shutter.click()
      await page.getByRole('button', { name: /다음|센터로 돌아가기|다시 찍기/ }).first().waitFor({ timeout: 90000 })
      if (await page.getByRole('button', { name: /다시 찍기/ }).count()) {
        await tap('다시 찍기')
        continue
      }
      photos++
      if (photos === 1) await shot(page, '3-첫사진')
      continue
    }
    const next = page.getByRole('button', { name: /다음 사진 찍기|다음 장소로/ })
    if (await next.count()) {
      await next.first().click()
      continue
    }
    await page.waitForTimeout(500)
  }
  console.log(`  찍은 사진: ${photos}장`)
  if (!photos) problems.push('사진을 한 장도 찍지 못함')

  console.log('▶ 센터 복귀 → 끝 → 관리자 웹')
  await tap('센터로 돌아가기')
  await page.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 10000 })
  await tap('센터에 도착했어요')
  await page.getByRole('button', { name: '관리자 웹에서 내 사진 보기' }).waitFor({ timeout: 15000 })
  await shot(page, '4-끝')
  await tap('관리자 웹에서 내 사진 보기')
  await page.waitForURL(/\/admin\//, { timeout: 15000 }).catch(() => problems.push(`관리자 웹으로 넘어가지 않음 (${page.url()})`))

  const admin = page
  // 체험판 관리자 웹은 로그인 없이 바로 들어가고, 내 사진이 있으면 검토 목록부터 열린다
  if (await admin.getByRole('button', { name: '로그인', exact: true }).count()) problems.push('체험판 관리자 웹에 로그인 화면이 나옴')
  const mine = admin.locator('main').getByRole('button').filter({ hasText: /체험판|확인 필요/ })
  await admin.getByRole('heading', { name: '검토 목록' }).waitFor({ timeout: 15000 })
  await admin.waitForTimeout(1500)
  // 맨 위 항목을 열어 내 사진(체험판 안내)이 보이는지
  await admin.locator('main').getByRole('button').filter({ hasText: /확인 필요/ }).first().click().catch(() => {})
  await admin.waitForTimeout(1500)
  const noted = await admin.getByText('체험판에서 찍은 사진이에요').count()
  console.log(`  검토 목록에 내 사진: ${noted ? '예' : '아니오'} (목록 항목 ${await mine.count()}개)`)
  if (!noted) problems.push('관리자 웹 검토 목록에 체험판에서 찍은 사진이 없음')
  await shot(admin, '5-관리자-내사진')

  console.log(`▶ 서버로 나간 요청: ${apiCalls.length}건`)
  if (apiCalls.length) problems.push(`체험판인데 서버 요청이 있음: ${apiCalls.slice(0, 3).join(', ')}`)
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
  for (const pg of browser.contexts().flatMap((c) => c.pages())) await shot(pg, '중단')
} finally {
  await browser.close()
  report(problems)
}
