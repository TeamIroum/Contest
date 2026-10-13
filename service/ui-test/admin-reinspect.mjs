// 재점검 배정(ACT-10): 시설 담당자(demo-road)가 접수된 사건을 보수 요청 → 보수 시작 → 보수 완료(재점검 요청 생성) →
// 활동 담당자(demo-activity)가 내일 순회를 배정하고 재점검 요청을 그 순회에 배정 → 일정에 '재점검 1장' →
// 배정 취소 → 다시 배정(남은 재점검 칸을 다시 씀, 여전히 1장)
//   node admin-reinspect.mjs [관리자 웹 주소]
// 실제 데이터를 바꾼다. 접수(OPEN) 사건이 있어야 함(admin-decide.mjs 뒤에 돌림). 끝나면 시연 데이터 초기화 필요
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const { browser, page, problems, shot } = await open()
const menu = (label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: label }).click()
const login = async (id) => {
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  if (await page.getByRole('button', { name: '로그아웃' }).count()) {
    await page.getByRole('button', { name: '로그아웃' }).click()
    await page.waitForLoadState('networkidle')
  }
  await page.getByLabel('아이디').fill(id)
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await page.waitForLoadState('networkidle')
}
const save = async (name) => {
  await page.getByRole('button', { name, exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1200)
}
try {
  console.log('▶ 시설 담당자: 사건 보수 요청 → 시작 → 완료 (재점검 요청 생성)')
  await login('demo-road')
  await menu('사건·보수')
  await page.waitForTimeout(800)
  const row = page.locator('main').getByRole('button').filter({ hasText: /보도블록/ }).first()
  await row.click()
  await save('보수 요청')
  await save('보수 시작으로 변경')
  await page.getByPlaceholder('예: 블록 3장 교체').fill('시험: 블록 교체')
  await save('보수 완료')
  await shot('재점검-보수완료')

  console.log('▶ 활동 담당자: 내일 순회 배정 → 재점검 요청을 그 순회에 배정')
  await page.context().clearCookies()
  await login('demo-activity')
  await menu(/코스·순회 배정/)
  await page.locator('main').getByText(/재점검 요청 \d+건/).waitFor({ timeout: 15000 })
  await page.getByRole('button', { name: '+ 순회 배정' }).click()
  const m = page.getByRole('dialog', { name: '순회 배정' })
  await m.getByLabel('코스').selectOption({ label: '마을회관 코스' })
  await m.getByRole('button', { name: '배정', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1000)
  const req = page.locator('main tr').filter({ hasText: '보수 확인' }).first()
  await req.waitFor({ timeout: 10000 })
  await shot('재점검-요청목록')

  const assign = async () => {
    await req.getByRole('button', { name: '순회에 배정' }).click()
    const d = page.getByRole('dialog', { name: '재점검 순회 배정' })
    await d.getByText(/번째 장소/).first().waitFor({ timeout: 10000 })
    await shot('재점검-배정창')
    await d.getByRole('button', { name: '배정', exact: true }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1200)
  }
  await assign()
  if (!(await req.getByText('배정됨').count())) problems.push('재점검 요청이 배정됨으로 바뀌지 않음')
  const slots = () => page.locator('main tr').filter({ hasText: '마을회관 코스' }).filter({ hasText: '1장' }).count()
  if (!(await slots())) problems.push("순회 일정에 '재점검 1장'이 보이지 않음")
  await shot('재점검-배정뒤')

  console.log('▶ 배정 취소 → 다시 배정 (재점검 칸은 다시 써서 여전히 1장)')
  await req.getByRole('button', { name: '배정 취소' }).click()
  const c = page.getByRole('dialog', { name: '재점검 배정 취소' })
  await c.getByLabel('취소 이유').fill('시험: 다른 순회로 옮김')
  await c.getByRole('button', { name: '배정 취소', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1200)
  if (!(await req.getByText('배정 전').count())) problems.push('배정 취소 뒤 배정 전으로 바뀌지 않음')
  await assign()
  if (!(await req.getByText('배정됨').count())) problems.push('다시 배정되지 않음')
  if (!(await slots())) problems.push('다시 배정한 뒤 재점검 칸이 1장이 아님 (빈 칸을 다시 쓰지 않음)')
  await shot('재점검-다시배정')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
