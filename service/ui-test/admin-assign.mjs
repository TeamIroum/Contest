// 코스·순회 배정(ACT-03·04): 활동 담당자(demo-activity)가 코스를 만들고(장소 2곳) → 내일 13:00~15:00 순회 배정 →
// 일정에 보임 → 같은 시간 다시 배정은 거절 → 코스를 고치면 v2로 공개되고 배정된 순회는 v1 그대로
//   node admin-assign.mjs [관리자 웹 주소]
// 실제 데이터를 바꾼다 (끝나면 시연 데이터 초기화 필요)
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const ROUTE = `시험 코스 ${String(Date.now() % 1000).padStart(3, '0')}`

const { page, browser, problems, shot } = await open()
const expected = (p) => p.includes('POST /api/v1/admin/patrols → 409') || p.includes('status of 409')
try {
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill('demo-activity')
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /코스·순회 배정/ }).click()
  await page.locator('main').getByText('마을회관 코스').first().waitFor({ timeout: 15000 })
  if (await page.getByText('예시 화면입니다').count()) problems.push('코스·순회 배정 화면에 아직 예시 안내가 있음')
  await shot('배정-처음')

  console.log(`▶ 코스 만들기: ${ROUTE} (장소 2곳)`)
  await page.getByRole('button', { name: '+ 코스 만들기' }).click()
  let d = page.getByRole('dialog', { name: '코스 만들기' })
  await d.getByLabel('코스 이름').fill(ROUTE)
  await d.getByRole('button', { name: '+ 장소 추가' }).click()
  const second = d.getByRole('group', { name: '2번째 장소' })
  const sel = second.getByLabel('2번째 장소 시설')
  const opts = await sel.locator('option').allTextContents()
  await sel.selectOption({ label: opts.find((o) => o.includes('빗물받이')) })
  await second.getByLabel(/찍을 구도/).fill('덮개 전체, 주변 바닥')
  await shot('배정-코스만들기창')
  await d.getByRole('button', { name: '만들기', exact: true }).click()
  const card = page.locator('main').getByText(ROUTE, { exact: true })
  await card.first().waitFor({ timeout: 10000 }).catch(() => problems.push('만든 코스가 보이지 않음'))
  await shot('배정-코스만든뒤')

  const assign = async () => {
    await page.getByRole('button', { name: '+ 순회 배정' }).click()
    const m = page.getByRole('dialog', { name: '순회 배정' })
    await m.getByLabel('코스').selectOption({ label: ROUTE })
    await m.getByLabel('출발').selectOption('13:00')
    await m.getByLabel('복귀 예정').selectOption('15:00')
    await m.getByRole('button', { name: '배정', exact: true }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)
    return m
  }
  console.log('▶ 내일 13:00~15:00 순회 배정')
  await assign()
  const row = page.locator('main tr').filter({ hasText: ROUTE })
  await row.first().waitFor({ timeout: 10000 }).catch(() => problems.push('배정한 순회가 일정에 보이지 않음'))
  if (!(await row.filter({ hasText: '13:00~15:00' }).count())) problems.push('일정의 시간이 13:00~15:00이 아님')
  await shot('배정-배정뒤')

  console.log('▶ 같은 시간 다시 배정 → 거절')
  const m = await assign()
  const refused = await page.getByText('같은 시간에 다른 순회에 배정돼 있어요').count()
  console.log(`  거절 안내: ${refused ? '예' : '아니오'}`)
  if (!refused) problems.push('시간이 겹치는 배정이 거절되지 않음')
  await shot('배정-겹침거절')
  if (await m.count()) await m.getByRole('button', { name: '취소' }).click()

  console.log('▶ 코스 고치기 → v2 공개, 배정된 순회는 v1')
  const routeCard = page.locator('main').getByText(ROUTE, { exact: true }).last().locator('xpath=ancestor::*[.//button[normalize-space()="고치기"]][1]')
  await routeCard.getByRole('button', { name: '고치기' }).click()
  d = page.getByRole('dialog', { name: `${ROUTE} 고치기` })
  await d.getByRole('group', { name: '2번째 장소' }).getByRole('button', { name: '빼기' }).click()
  await d.getByRole('button', { name: 'v2로 공개' }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1000)
  if (!(await page.locator('main').getByText('v2', { exact: true }).count())) problems.push('고친 코스가 v2로 보이지 않음')
  if (!(await row.filter({ hasText: 'v1 · 2곳' }).count())) problems.push('배정된 순회가 v1(2곳) 그대로가 아님')
  await shot('배정-새버전')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems.filter((p) => !expected(p)))
}
