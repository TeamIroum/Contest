// 시설 대장(FAC-01): 도로 시설 담당자(demo-road)가 시설을 등록 → 목록에 보임 → 사용 중지 → 다른 부서 시설은 목록에 없음
//   node admin-facilities.mjs [관리자 웹 주소]
// 실제 데이터를 바꾼다 (끝나면 시연 데이터 초기화 필요)
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const CODE = `SW-T${String(Date.now() % 10000).padStart(4, '0')}`
const { browser, page, problems, shot } = await open()
try {
  await page.goto(ADMIN, { waitUntil: 'load' })
  await page.getByLabel('아이디').fill('demo-road')
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /시설 대장/ }).click()
  await page.locator('main').getByText('SW-0001').waitFor({ timeout: 15000 })
  if (await page.locator('main').getByText('DR-0001').count()) problems.push('도로 담당자 시설 대장에 하수 시설(DR-0001)이 보임')
  await shot('시설-목록')

  console.log(`▶ 시설 등록 ${CODE}`)
  await page.getByRole('button', { name: '+ 시설 등록' }).click()
  const d = page.getByRole('dialog', { name: '시설 등록' })
  await d.getByLabel('시설 코드').fill(CODE)
  await d.getByLabel('이름').fill('시험 보도블록')
  await d.getByLabel('구역').fill('○○동 2구역')
  await d.getByLabel('위도').fill('37.000500')
  await d.getByLabel('경도').fill('127.000500')
  await shot('시설-등록창')
  await d.getByRole('button', { name: '저장', exact: true }).click()
  const row = page.locator('main tr').filter({ hasText: CODE })
  await row.waitFor({ timeout: 10000 }).catch(() => problems.push('등록한 시설이 목록에 없음'))

  console.log('▶ 같은 코드 다시 등록 → 거절')
  await page.getByRole('button', { name: '+ 시설 등록' }).click()
  await d.getByLabel('시설 코드').fill(CODE)
  await d.getByLabel('이름').fill('중복')
  await d.getByRole('button', { name: '저장', exact: true }).click()
  await page.waitForTimeout(1000)
  if (!(await page.getByText('이미 쓰고 있는 시설 코드예요').count())) problems.push('같은 시설 코드가 거절되지 않음')
  if (await d.count()) await d.getByRole('button', { name: '취소' }).click()

  console.log('▶ 사용 중지')
  await row.getByRole('button', { name: '고치기' }).click()
  const e = page.getByRole('dialog', { name: /고치기/ })
  await e.getByLabel('상태').selectOption('INACTIVE')
  await e.getByRole('button', { name: '저장', exact: true }).click()
  await page.waitForTimeout(1200)
  if (!(await row.getByText('사용 중지').count())) problems.push('사용 중지가 목록에 반영되지 않음')
  await shot('시설-사용중지')
} catch (err) {
  problems.push(`시험 중단: ${err.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems.filter((p) => !p.includes('POST /api/v1/admin/facilities → 400') && !p.includes('status of 400')))
}
