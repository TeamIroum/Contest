// 참여자 계정(ACT-01): 활동 담당자(demo-activity)가 관리자 웹에서 어르신을 등록 → 목록에 가린 번호로 보임 →
// 같은 번호 다시 등록은 거절 → 일시 중지(사유 필수) → 새 어르신이 시니어 앱에 로그인하지 못하는지 확인
//   node admin-participants.mjs [관리자 웹 주소] [시니어 앱 주소]
// 실제 데이터를 바꾼다 (끝나면 시연 데이터 초기화 필요)
import path from 'node:path'
import { open, report, serviceEnv, SHOTS } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const SENIOR = process.argv[3] || 'http://127.0.0.1:8180'
const env = serviceEnv()
const NAME = '시험 어르신'
const PHONE = `010-0000-9${String(Date.now() % 1000).padStart(3, '0')}` // 시연 번호(0001·0002)와 겹치지 않게

const { browser, page, problems, shot } = await open()
// 같은 번호 다시 등록(400)은 일부러 만든 경우
const expected = (p) => p.includes('POST /api/v1/admin/participants → 400') || p.includes('status of 400')
try {
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill('demo-activity')
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: '참여자 계정' }).click()
  await page.locator('main').getByText('김○○').first().waitFor({ timeout: 15000 })
  if (await page.locator('main').getByText('예시', { exact: true }).count()) problems.push('참여자 계정 화면에 아직 "예시" 표시가 있음')
  await shot('참여자-목록')

  const register = async () => {
    await page.getByRole('button', { name: '+ 참여자 등록' }).click()
    const d = page.getByRole('dialog', { name: '참여자 등록' })
    await d.getByLabel('이름').fill(NAME)
    await d.getByLabel('휴대폰').fill(PHONE)
    await d.getByRole('button', { name: '월', exact: true }).click()
    await d.getByRole('button', { name: '수', exact: true }).click()
    await shot('참여자-등록창')
    await d.getByRole('button', { name: '등록', exact: true }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(800)
    return d
  }
  console.log(`▶ 등록 ${NAME} ${PHONE}`)
  await register()
  const masked = PHONE.replace(/^(\d{3})-\d{4}-(\d{4})$/, '$1-****-$2')
  const row = page.locator('main tr').filter({ hasText: masked })
  await row.first().waitFor({ timeout: 10000 }).catch(() => problems.push(`목록에 가린 번호(${masked})가 보이지 않음`))
  if (!(await row.filter({ hasText: '월 · 수' }).count())) problems.push('목록에 활동 가능 요일(월 · 수)이 보이지 않음')
  await shot('참여자-등록뒤')

  console.log('▶ 같은 번호 다시 등록 → 거절')
  const d = await register()
  const dup = await page.getByText('이미 등록된 휴대폰 번호').count()
  console.log(`  거절 안내: ${dup ? '예' : '아니오'}`)
  if (!dup) problems.push('같은 번호 등록이 거절되지 않음')
  await shot('참여자-중복거절')
  if (await d.count()) await d.getByRole('button', { name: '취소' }).click()

  console.log('▶ 일시 중지 (사유 없으면 버튼이 막힘)')
  await row.first().getByRole('button', { name: '상태 변경' }).click()
  const s = page.getByRole('dialog', { name: `${NAME} 상태 변경` })
  await s.getByText('일시 중지', { exact: true }).click()
  const go = s.getByRole('button', { name: /로 변경$/ })
  if (!(await go.isDisabled())) problems.push('사유 없이 상태를 바꿀 수 있음')
  await s.getByLabel('바꾸는 이유').fill('시험: 2주 쉼')
  await shot('참여자-상태변경창')
  await go.click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(800)
  if (!(await row.filter({ hasText: '일시 중지' }).count())) problems.push('목록 상태가 일시 중지로 바뀌지 않음')
  await shot('참여자-중지뒤')

  console.log('▶ 시니어 앱: 중지된 번호로는 로그인 안 됨')
  const m = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR' })
  const sp = await m.newPage()
  await sp.goto(SENIOR, { waitUntil: 'networkidle' })
  await sp.getByRole('button', { name: '휴대폰 번호로 시작하기' }).first().click()
  for (const c of PHONE.replace(/-/g, '')) await sp.getByRole('button', { name: c, exact: true }).click()
  await sp.getByRole('button', { name: '인증번호 받기' }).first().click()
  await sp.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const c of env.DEMO_OTP_CODE) await sp.getByRole('button', { name: c, exact: true }).click()
  await sp.waitForTimeout(2500)
  const inside = await sp.getByRole('button', { name: /센터에 도착했어요|활동 시작하기|이어서 하기/ }).count()
  console.log(`  로그인 거절: ${inside ? '아니오' : '예'}`)
  if (inside) problems.push('일시 중지된 어르신이 시니어 앱에 로그인됨')
  await sp.screenshot({ path: path.join(SHOTS, '08-참여자-중지-시니어로그인.png') })
  await m.close()
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems.filter((p) => !expected(p)))
}
