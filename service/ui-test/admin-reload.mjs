// 관리자 웹: 로그인 화면에 시연 계정 목록이 없는지, 로그인한 뒤 새로 고침해도 로그인이 유지되는지 (데이터 안 바꿈)
//   node admin-reload.mjs [관리자 웹 주소]
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const { browser, page, problems, shot } = await open()
try {
  await page.goto(ADMIN, { waitUntil: 'load' })
  await page.getByLabel('아이디').waitFor({ timeout: 15000 })
  if (await page.getByText(/시연 계정|demo-activity/).count()) problems.push('로그인 화면에 시연 계정 목록이 보임')
  await shot('로그인화면')
  await page.getByLabel('아이디').fill('demo-activity')
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  for (let i = 1; i <= 2; i++) {
    await page.reload({ waitUntil: 'load' })
    const kept = await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 }).then(() => true, () => false)
    console.log(`  ${i}번째 새로 고침 뒤 로그인 유지: ${kept ? '예' : '아니오'}`)
    if (!kept) problems.push(`${i}번째 새로 고침 뒤 로그인이 풀림`)
  }
  await shot('새로고침뒤')
  // 로그아웃하면 새로 고침해도 로그인 화면
  await page.getByRole('button', { name: '로그아웃' }).click()
  await page.waitForLoadState('networkidle')
  await page.reload({ waitUntil: 'load' })
  if (!(await page.getByLabel('아이디').waitFor({ timeout: 15000 }).then(() => true, () => false))) problems.push('로그아웃한 뒤 새로 고침했는데 로그인 화면이 아님')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  // 처음 들어올 때 되살릴 로그인이 없으면 갱신 요청이 401 (정상)
  report(problems.filter((p) => !p.includes('/auth/token/refresh → 401') && !p.includes('status of 401')))
}
