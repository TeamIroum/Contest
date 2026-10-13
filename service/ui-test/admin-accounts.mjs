// 시연 관리자 계정 6개가 관리자 웹에 로그인되고 역할에 맞는 메뉴가 보이는지 (데이터 안 바꿈)
//   node admin-accounts.mjs [관리자 웹 주소]
import { chromium } from 'playwright'
import path from 'node:path'
import { report, serviceEnv, SHOTS } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const ACCOUNTS = [
  ['demo-activity', '오늘 활동 현황'],
  ['demo-road', '검토 목록'],
  ['demo-sewer', '검토 목록'],
  ['demo-park', '검토 목록'],
  ['demo-program', '통계·보고'],
  ['demo-admin', '공무원 계정'],
]
const problems = []
const browser = await chromium.launch()
try {
  for (const [login, menu] of ACCOUNTS) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' })
    const page = await ctx.newPage()
    page.on('pageerror', (e) => problems.push(`${login} 화면 오류: ${e.message.slice(0, 160)}`))
    page.on('response', async (r) => {
      if (r.url().includes('/api/') && r.status() >= 400) problems.push(`${login} API ${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()}`)
    })
    await page.goto(ADMIN, { waitUntil: 'networkidle' })
    await page.getByLabel('아이디').fill(login)
    await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    const nav = page.getByRole('navigation', { name: '주 메뉴' })
    await nav.waitFor({ timeout: 15000 }).catch(() => problems.push(`${login} 로그인 실패`))
    await page.waitForLoadState('networkidle')
    const ok = await nav.getByRole('button', { name: new RegExp(menu) }).count()
    console.log(`  ${login}: ${ok ? `'${menu}' 메뉴 보임` : `'${menu}' 메뉴 없음`}`)
    if (!ok) problems.push(`${login}에 '${menu}' 메뉴가 없음`)
    await page.screenshot({ path: path.join(SHOTS, `계정-${login}.png`) })
    await ctx.close()
  }
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
