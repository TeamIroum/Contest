// 조 편성(ACT-02): 활동 담당자(demo-activity)가 참여자 2명을 등록하고 새 조를 만든다 → 카드에 조원이 보임 →
// 1조의 김○○을 데려가는 조를 만들면(1조가 1명이 됨) 거절 → 이미 배정된 순회(오늘 활동 현황)의 조원은 그대로인지
//   node admin-teams.mjs [관리자 웹 주소]
// 실제 데이터를 바꾼다 (끝나면 시연 데이터 초기화 필요)
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const stamp = String(Date.now() % 1000).padStart(3, '0')
const PEOPLE = [`조시험가${stamp}`, `조시험나${stamp}`]

const { page, browser, problems, shot } = await open()
const expected = (p) => p.includes('POST /api/v1/admin/teams → 400') || p.includes('status of 400')
const menu = (label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: label }).click()
try {
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill('demo-activity')
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })

  console.log(`▶ 참여자 2명 등록 (${PEOPLE.join(', ')})`)
  await menu('참여자 계정')
  await page.locator('main').getByText('김○○').first().waitFor({ timeout: 15000 })
  for (const [i, name] of PEOPLE.entries()) {
    await page.getByRole('button', { name: '+ 참여자 등록' }).click()
    const d = page.getByRole('dialog', { name: '참여자 등록' })
    await d.getByLabel('이름').fill(name)
    await d.getByLabel('휴대폰').fill(`010-0000-8${stamp}`.slice(0, 12) + i)
    await d.getByRole('button', { name: '화', exact: true }).click()
    await d.getByRole('button', { name: '등록', exact: true }).click()
    await page.locator('main tr').filter({ hasText: name }).first().waitFor({ timeout: 10000 })
  }

  console.log('▶ 조 편성: 예시 표시 없이 서버의 1조가 보이는지')
  await menu('조 편성')
  await page.locator('main').getByText('김○○, 박○○').waitFor({ timeout: 15000 })
  if (await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /조 편성/ }).getByText('예시').count())
    problems.push('조 편성 메뉴에 아직 "예시" 표시가 있음')
  await shot('조편성-목록')

  console.log('▶ 새 조 만들기 (등록한 2명)')
  await page.getByRole('button', { name: '+ 조 만들기' }).click()
  let d = page.getByRole('dialog', { name: '조 만들기' })
  for (const name of PEOPLE) await d.getByText(name, { exact: true }).click()
  await shot('조편성-만들기창')
  await d.getByRole('button', { name: '저장', exact: true }).click()
  await page.locator('main').getByText(`${PEOPLE[0]}, ${PEOPLE[1]}`).waitFor({ timeout: 10000 }).catch(() => problems.push('새 조 카드에 조원이 보이지 않음'))
  await shot('조편성-만든뒤')

  console.log('▶ 김○○을 데려가는 조 → 1조가 1명이 되므로 거절')
  await page.getByRole('button', { name: '+ 조 만들기' }).click()
  d = page.getByRole('dialog', { name: '조 만들기' })
  await d.getByText('김○○', { exact: true }).click()
  await d.getByText(PEOPLE[0], { exact: true }).click()
  await d.getByRole('button', { name: '저장', exact: true }).click()
  await page.waitForTimeout(1200)
  const refused = await page.getByText(/조에 1명만 남아요/).count()
  console.log(`  거절 안내: ${refused ? '예' : '아니오'}`)
  if (!refused) problems.push('1조가 1명이 되는 편성이 거절되지 않음')
  await shot('조편성-거절')
  if (await d.count()) await d.getByRole('button', { name: '취소' }).click()

  console.log('▶ 오늘 활동 현황: 이미 배정된 순회 조원은 그대로')
  await menu('오늘 활동 현황')
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(800)
  if (await page.locator('main').getByText(PEOPLE[0]).count()) problems.push('조 편성 변경이 이미 배정된 순회 조원을 바꿈')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems.filter((p) => !expected(p)))
}
