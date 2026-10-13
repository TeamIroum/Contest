// 관리자 웹에서 실제로 판정하기: 하수 담당(demo-sewer)이 검토 목록 첫 항목을 열어 "이상 확인"으로 저장 → 사건·보수에 생기는지
//   node admin-decide.mjs [관리자 웹 주소]
import { open, report, serviceEnv } from './common.mjs'

const URL_ = process.argv[2] || 'http://127.0.0.1:8181'
const password = serviceEnv().ADMIN_PASSWORD
const LOGIN = process.env.LOGIN || 'demo-sewer'

const { browser, page, problems, shot } = await open()
const menu = (label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: label }).click()
try {
  const env = serviceEnv()
  const signIn = async (pw) => {
    await page.goto(URL_, { waitUntil: 'networkidle' })
    await page.getByLabel('아이디').fill(LOGIN)
    await page.getByLabel('비밀번호').fill(pw)
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(800)
  }
  await signIn(password)
  // 처음 로그인하는 계정이면 임시 비밀번호로 들어가 바로 바꾼다 (첫 로그인 비밀번호 변경 화면 시험)
  if (await page.getByText('아이디 또는 비밀번호를 확인해 주세요').count()) {
    await signIn(env.DEMO_ADMIN_TEMP_PASSWORD)
    await page.getByText('비밀번호 변경').first().waitFor({ timeout: 10000 })
    await shot('첫로그인-비밀번호변경')
    await page.locator('input[autocomplete="new-password"]').nth(0).fill(password)
    await page.locator('input[autocomplete="new-password"]').nth(1).fill(password)
    await page.getByRole('button', { name: '변경하고 시작하기' }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)
    if (!(await page.getByRole('navigation', { name: '주 메뉴' }).count())) await signIn(password)
  }
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await menu('검토 목록')
  await page.waitForLoadState('networkidle')
  await shot('판정-검토목록')
  const row = page.locator('main').getByRole('button').filter({ hasText: /보도블록|빗물받이|벤치|펜스|운동기구/ }).first()
  if (!(await row.count())) throw new Error('처리할 검토 항목이 없습니다')
  await row.click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1500) // 사진 불러오기
  await shot('판정-상세')
  await page.getByText('이상 확인', { exact: true }).first().click()
  await page.locator('textarea').first().fill('화면 시험: 사진으로 이상 확인')
  await shot('판정-입력')
  await page.getByRole('button', { name: '판정 저장' }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1500)
  await shot('판정-저장뒤')
  await menu('사건·보수')
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1000)
  await shot('판정-사건목록')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
  await shot('중단-화면').catch(() => {})
} finally {
  await browser.close()
  report(problems)
}
