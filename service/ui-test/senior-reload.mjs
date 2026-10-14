// 시니어 앱: 로그인한 뒤 새로 고침해도 시작 화면으로 돌아가지 않고 홈이 나오는지 (데이터 안 바꿈)
//   node senior-reload.mjs [시니어 앱 주소]
import { open, report, serviceEnv } from './common.mjs'

const SENIOR = process.argv[2] || 'http://127.0.0.1:8180'
const env = serviceEnv()
const { browser, page, problems, shot } = await open({ mobile: true })
const tap = (name) => page.getByRole('button', { name }).first().click()
try {
  await page.goto(SENIOR, { waitUntil: 'load' })
  await tap('휴대폰 번호로 시작하기')
  for (const d of '01000000001') await page.getByRole('button', { name: d, exact: true }).click()
  await tap('인증번호 받기')
  await page.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of env.DEMO_OTP_CODE) await page.getByRole('button', { name: d, exact: true }).click()
  // 로그인 뒤 홈 (오늘 순회가 없으면 '오늘 순회 없음' 화면도 홈이다): 시작 화면 버튼이 사라질 때까지
  const start = page.getByRole('button', { name: '휴대폰 번호로 시작하기' })
  await page.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ state: 'detached', timeout: 20000 })
  await page.waitForTimeout(1500)
  const before = (await page.locator('main').innerText().catch(() => '')).slice(0, 40).replace(/\s+/g, ' ')
  console.log(`  로그인 뒤 화면: ${before}`)
  for (let i = 1; i <= 2; i++) {
    await page.reload({ waitUntil: 'load' })
    await page.getByText('불러오는 중이에요').waitFor({ state: 'detached', timeout: 20000 }).catch(() => {})
    await page.waitForTimeout(2500)
    const back = await start.count()
    console.log(`  ${i}번째 새로 고침 뒤 시작 화면으로 돌아감: ${back ? '예' : '아니오'}`)
    if (back) problems.push(`${i}번째 새로 고침 뒤 시작 화면으로 돌아감`)
  }
  await shot('시니어-새로고침뒤')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
