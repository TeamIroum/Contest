// 수리 확인(현장 확인): 시설 담당자가 '수리 확인 대기' 사건을 열어 현장 확인 기록을 넣고 판정 → 사건이 종결(또는 재보수)되는지
// 실제 데이터를 바꾼다. 수리 확인 대기 사건이 있어야 함
//   node admin-verify.mjs [관리자 웹 주소]    LOGIN=demo-road (기본) · DECISION=종결|재보수|추가 확인 (기본 종결)
import { open, report, serviceEnv } from './common.mjs'

const URL_ = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const LOGIN = process.env.LOGIN || 'demo-road'
const DECISION = process.env.DECISION || '종결'

const { browser, page, problems, shot } = await open()
const menu = (label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: label }).click()
try {
  await page.goto(URL_, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill(LOGIN)
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await menu('사건·보수')
  await page.waitForLoadState('networkidle')
  await page.getByRole('button', { name: /확인 대기/ }).first().click() // 상태 탭
  await page.waitForTimeout(500)
  const row = page.locator('main').getByRole('button').filter({ hasText: /보도블록|빗물받이|벤치|펜스|운동기구|시험/ }).first()
  if (!(await row.count())) throw new Error('수리 확인 대기 사건이 없습니다')
  await row.click()
  await page.waitForLoadState('networkidle')
  await shot('verify-1-수리확인대기')
  await page.getByLabel('현장 확인 (직접 방문)').check()
  await page.getByPlaceholder('방문 일시와 확인한 내용').fill('10/14 오전 현장 방문, 블록 교체 확인')
  await page.getByRole('radio', { name: DECISION, exact: true }).click()
  await page.locator('section input').last().fill(DECISION === '종결' ? '현장에서 수리 확인' : '시험: 사유')
  await shot('verify-2-판정입력')
  await page.getByRole('button', { name: '판정 저장' }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1500)
  await page.getByRole('tab', { name: /전체/ }).or(page.getByRole('button', { name: /전체 \d/ })).first().click().catch(() => {})
  await page.waitForTimeout(500)
  await shot('verify-3-판정뒤')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
  await shot('verify-중단').catch(() => {})
} finally {
  await browser.close()
  report(problems)
}
