// 공무원 계정(OPS-07·08): 시스템 관리자(demo-admin)가 계정 발급 → 임시 비밀번호 한 번 표시 → 새 계정이 첫 로그인에 비밀번호 변경 →
// 5번 틀려 잠김 → 잠금 해제 → 비밀번호 초기화(새 임시 비밀번호) → 사용 중지하면 로그인 안 됨
//   node admin-officers.mjs [관리자 웹 주소]
// 실제 데이터를 바꾼다 (끝나면 시연 데이터 초기화 필요)
import { chromium } from 'playwright'
import path from 'node:path'
import { report, serviceEnv, SHOTS } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const stamp = String(Date.now() % 100000).padStart(5, '0')
const LOGIN = `test.${stamp}`
const NEW_PW = `Test-${stamp}!a`
const problems = []
const expected = (p) => / 401 | 423 | 429 |status of 401|status of 423|status of 429/.test(p)
const browser = await chromium.launch()
let n = 0
const snap = async (page, name) => {
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(SHOTS, `${String(++n).padStart(2, '0')}-계정-${name}.png`) })
  console.log(`  캡처 ${name}`)
}
const fresh = async (who) => {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' })
  const page = await ctx.newPage()
  page.on('pageerror', (e) => problems.push(`${who} 화면 오류: ${e.message.slice(0, 160)}`))
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) problems.push(`${who} API ${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()} `)
  })
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  return { ctx, page }
}
const tryLogin = async (page, id, pw) => {
  await page.getByLabel('아이디').fill(id)
  await page.getByLabel('비밀번호').fill(pw)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(800)
}
try {
  const { page: a } = await fresh('demo-admin')
  await tryLogin(a, 'demo-admin', env.ADMIN_PASSWORD)
  await a.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /공무원 계정/ }).click()
  await a.locator('main').getByText('demo-activity').waitFor({ timeout: 15000 })
  if (await a.getByText('예시 화면입니다').count()) problems.push('공무원 계정 화면에 아직 예시 안내가 있음')
  await snap(a, '목록')

  console.log(`▶ 계정 발급: ${LOGIN} (시설 담당자)`)
  await a.getByRole('button', { name: '+ 계정 발급' }).click()
  let d = a.getByRole('dialog', { name: '공무원 계정 발급' })
  await d.getByLabel('이름').fill('시험 담당자')
  await d.getByLabel('아이디').fill(LOGIN)
  await d.getByText('시설 담당자', { exact: true }).click()
  await snap(a, '발급창')
  await d.getByRole('button', { name: '발급', exact: true }).click()
  const temp1 = (await a.getByTestId('temp-password').innerText({ timeout: 10000 })).trim()
  console.log(`  임시 비밀번호 표시: ${temp1 ? '예' : '아니오'} (${temp1.length}자)`)
  await snap(a, '임시비밀번호')
  await a.getByRole('button', { name: '전달했어요 · 닫기' }).click()
  if (!(await a.locator('main tr').filter({ hasText: LOGIN }).filter({ hasText: '임시 비밀번호' }).count())) problems.push('발급한 계정이 목록에 임시 비밀번호 상태로 보이지 않음')

  console.log('▶ 새 계정 첫 로그인 → 비밀번호 변경')
  let { ctx: c1, page: u } = await fresh(LOGIN)
  await tryLogin(u, LOGIN, temp1)
  await u.locator('input[autocomplete="new-password"]').first().waitFor({ timeout: 10000 })
  await u.locator('input[autocomplete="new-password"]').nth(0).fill(NEW_PW)
  await u.locator('input[autocomplete="new-password"]').nth(1).fill(NEW_PW)
  await u.getByRole('button', { name: '변경하고 시작하기' }).click()
  await u.waitForLoadState('networkidle')
  await u.waitForTimeout(1000)
  if (!(await u.getByRole('navigation', { name: '주 메뉴' }).count())) await tryLogin(u, LOGIN, NEW_PW)
  const inside = await u.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /검토 목록/ }).count()
  console.log(`  새 비밀번호로 로그인, 검토 목록 보임: ${inside ? '예' : '아니오'}`)
  if (!inside) problems.push('새 계정이 비밀번호를 바꾼 뒤 로그인하지 못함')
  await c1.close()

  console.log('▶ 5번 틀려 잠김 → 시스템 관리자가 잠금 해제')
  ;({ ctx: c1, page: u } = await fresh(LOGIN))
  for (let i = 0; i < 5; i++) await tryLogin(u, LOGIN, 'Wrong-pass1!')
  await c1.close()
  await a.reload({ waitUntil: 'networkidle' })
  if (await a.getByLabel('아이디').count()) await tryLogin(a, 'demo-admin', env.ADMIN_PASSWORD) // 새로 고치면 다시 로그인
  await a.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /공무원 계정/ }).click()
  const row = a.locator('main tr').filter({ hasText: LOGIN })
  await row.getByText('잠김').waitFor({ timeout: 10000 }).catch(() => problems.push('5번 틀린 계정이 잠김으로 보이지 않음'))
  await snap(a, '잠김')
  await row.getByRole('button', { name: '잠금 해제' }).click()
  d = a.getByRole('dialog', { name: /잠금 해제/ })
  await d.getByLabel('사유').fill('시험: 본인 확인')
  await d.getByRole('button', { name: '잠금 해제', exact: true }).click()
  await a.waitForLoadState('networkidle')
  await a.waitForTimeout(800)
  if (await row.getByText('잠김').count()) problems.push('잠금 해제 뒤에도 잠김')
  ;({ ctx: c1, page: u } = await fresh(LOGIN))
  await tryLogin(u, LOGIN, NEW_PW)
  if (!(await u.getByRole('navigation', { name: '주 메뉴' }).count())) problems.push('잠금 해제 뒤 로그인되지 않음')
  await c1.close()

  console.log('▶ 비밀번호 초기화 → 새 임시 비밀번호, 이전 비밀번호는 안 됨')
  await row.getByRole('button', { name: '비밀번호 초기화' }).click()
  d = a.getByRole('dialog', { name: /비밀번호 초기화/ })
  await d.getByLabel('사유').fill('시험: 분실 신고')
  await d.getByRole('button', { name: '비밀번호 초기화', exact: true }).click()
  const temp2 = (await a.getByTestId('temp-password').innerText({ timeout: 10000 })).trim()
  if (!temp2 || temp2 === temp1) problems.push('초기화한 임시 비밀번호가 새로 만들어지지 않음')
  await a.getByRole('button', { name: '전달했어요 · 닫기' }).click()
  ;({ ctx: c1, page: u } = await fresh(LOGIN))
  await tryLogin(u, LOGIN, NEW_PW)
  if (await u.getByRole('navigation', { name: '주 메뉴' }).count()) problems.push('초기화 뒤에도 이전 비밀번호로 로그인됨')
  await tryLogin(u, LOGIN, temp2)
  if (!(await u.locator('input[autocomplete="new-password"]').count())) problems.push('새 임시 비밀번호로 들어가 변경 화면이 나오지 않음')
  await c1.close()

  console.log('▶ 사용 중지 → 로그인 안 됨')
  await row.getByRole('button', { name: '사용 중지' }).click()
  d = a.getByRole('dialog', { name: /사용 중지/ })
  await d.getByLabel('사유').fill('시험: 부서 이동')
  await d.getByRole('button', { name: '사용 중지', exact: true }).click()
  await a.waitForLoadState('networkidle')
  await a.waitForTimeout(800)
  if (!(await row.getByText('사용 중지', { exact: true }).count())) problems.push('목록 상태가 사용 중지로 바뀌지 않음')
  await snap(a, '사용중지')
  ;({ ctx: c1, page: u } = await fresh(LOGIN))
  await tryLogin(u, LOGIN, temp2)
  if (await u.getByRole('navigation', { name: '주 메뉴' }).count() || (await u.locator('input[autocomplete="new-password"]').count())) problems.push('사용 중지한 계정이 로그인됨')
  await c1.close()

  if (await a.locator('main tr').filter({ hasText: 'demo-admin' }).getByRole('button', { name: '비밀번호 초기화' }).count())
    problems.push('본인 계정에 비밀번호 초기화 버튼이 보임')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems.filter((p) => !expected(p)))
}
