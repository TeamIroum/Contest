// 관리자 웹을 실제 브라우저로: 시설 담당자(demo-road) 검토 목록·상세·사건, 활동 담당자(demo-activity) 오늘 활동 현황
//   node admin.mjs [관리자 웹 주소]   (기본: 이 PC의 Caddy http://127.0.0.1:8181)
import { open, report, serviceEnv } from './common.mjs'

const URL_ = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const password = env.ADMIN_PASSWORD
if (!password) throw new Error('service\\.env에 ADMIN_PASSWORD(바꾼 관리자 비밀번호)가 필요합니다')

async function login(page, loginId) {
  await page.goto(URL_, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill(loginId)
  await page.getByLabel('비밀번호').fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await page.waitForLoadState('networkidle')
}
const menu = (page, label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: label }).click()

const { browser, page, problems, shot } = await open()
try {
  console.log(`관리자 웹 ${URL_}`)
  await page.goto(URL_, { waitUntil: 'networkidle' })
  await shot('로그인-화면')

  console.log('▶ 시설 담당자(demo-road)')
  await login(page, 'demo-road')
  await shot('시설담당-첫화면')
  await menu(page, '검토 목록')
  await page.waitForLoadState('networkidle')
  await shot('검토-목록')
  const firstRow = page.locator('main').getByRole('button').filter({ hasText: /보도블록|빗물받이|벤치|펜스|운동기구/ }).first()
  if (await firstRow.count()) {
    await firstRow.click()
    await page.waitForLoadState('networkidle')
    await shot('검토-상세')
  } else console.log('  검토 항목을 찾지 못함')
  await menu(page, '사건·보수')
  await page.waitForLoadState('networkidle')
  await shot('사건-목록')
  const issueRow = page.locator('main').getByRole('button').filter({ hasText: /보도블록|빗물받이|벤치|펜스|운동기구/ }).first()
  if (await issueRow.count()) {
    await issueRow.click()
    await page.waitForLoadState('networkidle')
    await shot('사건-상세')
  }

  console.log('▶ 활동 담당자(demo-activity)')
  await page.context().clearCookies()
  await login(page, 'demo-activity')
  await shot('오늘-활동-현황')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
  await shot('중단-화면').catch(() => {})
} finally {
  await browser.close()
  report(problems)
}
