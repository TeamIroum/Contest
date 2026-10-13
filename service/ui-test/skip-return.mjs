// 건너뛰기·그만하기·복귀 확인: 시니어 앱에서 장소를 건너뛰고 활동을 그만한 뒤 센터 도착 →
// 관리자(활동 담당자)가 복귀를 확인하면 시니어 앱이 '끝' 화면으로 넘어가는지. 서버 기록도 확인한다.
// 실제 데이터를 바꾼다 (오늘 순회가 있어야 함. 끝나면 시연 데이터 초기화 필요)
//   node skip-return.mjs [시니어 앱 주소] [관리자 웹 주소]     PHONE=01000000002 이면 박○○ (기본 김○○)
import { chromium } from 'playwright'
import path from 'node:path'
import { report, serviceEnv, SHOTS } from './common.mjs'

const SENIOR = process.argv[2] || 'http://127.0.0.1:8180'
const ADMIN = process.argv[3] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const PHONE = (process.env.PHONE || '01000000001').replace(/-/g, '')
const NAME = PHONE.endsWith('2') ? '박○○' : '김○○'
const problems = []
const browser = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] })
const watch = (page, who) => {
  page.on('pageerror', (e) => problems.push(`${who} 화면 오류: ${e.message.slice(0, 160)}`))
  page.on('response', async (r) => {
    if (!r.url().includes('/api/') || r.status() < 400 || r.url().endsWith('/auth/admin/sessions')) return
    if (r.url().endsWith('/departure-confirmations') && r.status() === 409) return // 출석 1명일 때 출발 확인 거절: 시험이 일부러 만든 경우
    problems.push(`${who} API ${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()} ${(await r.text().catch(() => '')).slice(0, 140)}`)
  })
}
const snap = async (page, name) => {
  await page.waitForTimeout(700)
  await page.screenshot({ path: path.join(SHOTS, `skip-${name}.png`) })
  console.log(`  캡처 skip-${name}.png`)
}
try {
  const s = await (await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR' })).newPage()
  const a = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' })).newPage()
  watch(s, '시니어'), watch(a, '관리자')
  const tap = (name) => s.getByRole('button', { name }).first().click()

  console.log('▶ 시니어: 로그인 → 출석 요청 (출발 확인 전에는 시작 버튼이 막혀야 함)')
  await s.goto(SENIOR, { waitUntil: 'networkidle' })
  await tap('휴대폰 번호로 시작하기')
  for (const d of PHONE) await s.getByRole('button', { name: d, exact: true }).click()
  await tap('인증번호 받기')
  await s.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of env.DEMO_OTP_CODE) await s.getByRole('button', { name: d, exact: true }).click()
  await s.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 20000 })
  await tap('센터에 도착했어요')
  if (await s.getByRole('button', { name: '출석 요청하기' }).count()) await tap('출석 요청하기')
  const wait = s.getByRole('button', { name: '출발 확인을 기다리는 중' })
  await wait.waitFor({ timeout: 15000 })
  if (!(await wait.isDisabled())) problems.push('출발 확인 전인데 시작 버튼이 눌림')
  await snap(s, '0-출발확인전-시작막힘')

  console.log('▶ 관리자(demo-activity): 출석 1명만 확정하고 출발 확인 → 거절되어야 함 → 나머지 조원 대리 출석 → 출발 확인')
  await a.goto(ADMIN, { waitUntil: 'networkidle' })
  await a.getByLabel('아이디').fill('demo-activity')
  await a.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await a.getByRole('button', { name: '로그인', exact: true }).click()
  await a.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await a.waitForLoadState('networkidle')
  const rowOf = (name) => a.getByText(name, { exact: true }).first().locator('xpath=ancestor::*[.//button][1]')
  await rowOf(NAME).getByRole('button', { name: '출석 확인', exact: true }).click()
  await a.waitForLoadState('networkidle')
  await a.waitForTimeout(1200)
  await a.getByRole('button', { name: '출발 확인', exact: true }).click()
  await a.waitForTimeout(1500)
  const refused = await a.getByText('출석이 확인된 조원이 2명 이상이어야').count()
  console.log(`  출석 1명일 때 출발 확인 거절: ${refused ? '예' : '아니오'}`)
  if (!refused) problems.push('출석 1명인데 출발 확인이 거절되지 않음')
  await snap(a, '0-출석1명-출발거절')
  const other = NAME === '김○○' ? '박○○' : '김○○'
  const otherRow = rowOf(other)
  if (await otherRow.getByRole('button', { name: '출석 확인', exact: true }).count()) await otherRow.getByRole('button', { name: '출석 확인', exact: true }).click()
  else {
    await otherRow.getByRole('button', { name: '대리 출석', exact: true }).click()
    const d = a.getByRole('dialog', { name: `${other} 대리 출석` })
    await d.locator('input, textarea').first().fill('시험: 센터에서 직접 확인')
    await d.getByRole('button', { name: '대리 출석', exact: true }).click()
  }
  await a.waitForLoadState('networkidle')
  await a.waitForTimeout(1200)
  await a.getByRole('button', { name: '출발 확인', exact: true }).click()
  await a.waitForLoadState('networkidle')
  await a.waitForTimeout(1000)

  console.log('▶ 시니어: 출발 확인이 오면 시작 버튼이 열림 → 활동 시작 (조 전체)')
  await s.getByRole('button', { name: '활동 시작하기' }).waitFor({ timeout: 15000 })
  await snap(s, '0-출발확인뒤-시작가능')
  await tap('활동 시작하기')
  await s.getByRole('button', { name: /도착했어요 · 사진 찍기|다음 장소로/ }).first().waitFor({ timeout: 10000 })
  const before = await s.locator('main p').filter({ hasText: /^\d+\. / }).first().innerText()
  console.log(`▶ 시니어: '${before}' 건너뛰기 (위험해서)`)
  await tap('이곳은 찍을 수 없어요')
  await s.getByRole('button', { name: '위험해서 못 찍겠어요' }).click()
  await s.waitForFunction((b) => ![...document.querySelectorAll('main p')].some((p) => p.textContent === b), before, { timeout: 10000 })
  await snap(s, '1-건너뛴뒤-다음장소')

  console.log('▶ 시니어: 오늘 활동 그만하기 (몸이 안 좋아요) → 센터 도착')
  await tap('오늘 활동 그만하기')
  await s.getByRole('button', { name: '몸이 안 좋아요' }).click()
  await s.getByText('오늘 활동은 여기까지 해요').waitFor({ timeout: 10000 })
  await snap(s, '2-그만하기')
  await tap('센터에 도착했어요')
  await s.getByText('복귀를 확인하면 끝나요').waitFor({ timeout: 10000 })
  await snap(s, '3-복귀확인-기다림')

  console.log('▶ 관리자(demo-activity): 오늘 활동 현황 → 복귀 확인')
  await a.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: '오늘 활동 현황' }).click()
  await a.waitForTimeout(5500) // 관리자 화면은 5초마다 새로 받는다
  await a.waitForLoadState('networkidle')
  await snap(a, '4-관리자-복귀요청')
  // 이 어르신 이름 바로 뒤의 첫 '복귀 확인' 버튼 (출석·출발 확인은 앞 단계에서 끝남)
  await a.locator(`xpath=//main//*[normalize-space(text())='${NAME}']/following::button[normalize-space()='복귀 확인'][1]`).click()
  const dialog = a.getByRole('dialog', { name: `${NAME} 복귀 확인` })
  await dialog.waitFor({ timeout: 5000 })
  await snap(a, '5-관리자-복귀확인창')
  await dialog.getByRole('button', { name: '복귀 확인', exact: true }).click()
  await a.waitForLoadState('networkidle')
  await snap(a, '6-관리자-복귀확인뒤')

  console.log('▶ 시니어: 끝 화면으로 넘어가는지 (최대 15초)')
  await s.getByText('복귀를 확인하면 끝나요').waitFor({ state: 'detached', timeout: 15000 })
  await snap(s, '7-끝')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
