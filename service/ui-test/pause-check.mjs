// 잠깐 쉬기 확인: 시니어 앱에서 쉬기 → 관리자 웹(활동 담당자) 오늘 활동 현황에 "쉬는 중" → 다시 시작하면 사라짐. 도움 시트도 캡처
//   node pause-check.mjs [시니어 앱 주소] [관리자 웹 주소]
import { chromium } from 'playwright'
import path from 'node:path'
import { report, serviceEnv, SHOTS, confirmAttendanceAndDeparture } from './common.mjs'

const SENIOR = process.argv[2] || 'http://127.0.0.1:8180'
const ADMIN = process.argv[3] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const problems = []
const browser = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] })
const watch = (page, who) => {
  page.on('pageerror', (e) => problems.push(`${who} 화면 오류: ${e.message.slice(0, 160)}`))
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400 && !r.url().endsWith('/auth/admin/sessions'))
      problems.push(`${who} API ${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()} ${(await r.text().catch(() => '')).slice(0, 120)}`)
  })
}
const snap = async (page, name) => {
  await page.waitForTimeout(800)
  await page.screenshot({ path: path.join(SHOTS, `pause-${name}.png`) })
  console.log(`  캡처 pause-${name}.png`)
}
try {
  const s = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR', permissions: ['camera'] })).newPage()
  const a = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' })).newPage()
  watch(s, '시니어'), watch(a, '관리자')
  const tap = (name) => s.getByRole('button', { name }).first().click()

  // 시니어: 로그인 → 활동 화면
  await s.goto(SENIOR, { waitUntil: 'networkidle' })
  await tap('휴대폰 번호로 시작하기')
  await tap('이 휴대폰 번호 쓰기')
  await tap('인증번호 받기')
  await s.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of env.DEMO_OTP_CODE) await s.getByRole('button', { name: d, exact: true }).click()
  await s.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 20000 })
  await s.getByRole('button', { name: /^도움/ }).first().click()
  await snap(s, '1-도움')
  await s.mouse.click(195, 40)
  await tap('센터에 도착했어요')
  if (await s.getByRole('button', { name: '출석 요청하기' }).count()) await tap('출석 요청하기')
  // 활동 시작은 담당자 출석·출발 확인 뒤에만 된다
  await confirmAttendanceAndDeparture(browser, ADMIN)
  await s.getByRole('button', { name: /활동 시작하기|이어서 하기/ }).waitFor({ timeout: 20000 })
  await s.getByRole('button', { name: /활동 시작하기|이어서 하기/ }).first().click()
  await tap('잠깐 쉬기')
  await s.getByText('잠깐 쉬고 있어요').waitFor()
  await s.waitForLoadState('networkidle')
  await snap(s, '2-시니어-쉬는중')

  // 관리자: 활동 담당자 오늘 활동 현황
  await a.goto(ADMIN, { waitUntil: 'networkidle' })
  await a.getByLabel('아이디').fill('demo-activity')
  await a.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await a.getByRole('button', { name: '로그인', exact: true }).click()
  await a.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await a.waitForLoadState('networkidle')
  await snap(a, '3-관리자-쉬는중')
  const shown = await a.getByText('쉬는 중', { exact: true }).count()
  console.log(`  관리자 화면 "쉬는 중" 표시 ${shown}개`)
  if (!shown) problems.push('관리자 화면에 쉬는 중이 보이지 않음')

  // 시니어: 다시 시작 → 관리자 화면(5초마다 새로 받음)에서 사라짐
  await tap('다시 시작하기')
  await s.waitForLoadState('networkidle')
  await a.waitForTimeout(6500)
  await snap(a, '4-관리자-다시시작뒤')
  const left = await a.getByText('쉬는 중', { exact: true }).count()
  console.log(`  다시 시작 뒤 "쉬는 중" 표시 ${left}개`)
  if (left) problems.push('다시 시작했는데 쉬는 중이 남아 있음')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
