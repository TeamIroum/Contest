// 깃페이지 입구(https://teamiroum.github.io/service/)에서 시작해 앱으로 넘어가 로그인까지. 데이터는 바꾸지 않는다(로그인만)
import { chromium } from 'playwright'
import { serviceEnv, SHOTS, report } from './common.mjs'
import path from 'node:path'
const env = serviceEnv()
const GH = 'https://teamiroum.github.io/service'
const b = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] })
const problems = []
const watch = (p, who) => p.on('response', async (r) => r.url().includes('/api/') && r.status() >= 400 && problems.push(`${who} ${new URL(r.url()).pathname} ${r.status()} ${(await r.text().catch(() => '')).slice(0, 90)}`))
const snap = (p, n) => p.screenshot({ path: path.join(SHOTS, `gh-${n}.png`) }).then(() => console.log(`  캡처 gh-${n}.png`))
try {
  const s = await (await b.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR' })).newPage()
  watch(s, '시니어')
  await s.goto(`${GH}/`, { waitUntil: 'networkidle' })
  await snap(s, '1-입구')
  await s.getByRole('link', { name: /시니어 앱/ }).click()
  await s.waitForURL(/ts\.net\/$/, { timeout: 15000 })
  await s.waitForLoadState('networkidle')
  console.log('  시니어 앱 버튼 →', s.url())
  await s.getByRole('button', { name: '휴대폰 번호로 시작하기' }).click()
  await s.getByRole('button', { name: /이 휴대폰 번호 쓰기/ }).click()
  await s.getByRole('button', { name: '인증번호 받기' }).click()
  await s.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of env.DEMO_OTP_CODE) await s.getByRole('button', { name: d, exact: true }).click()
  await s.waitForTimeout(3500)
  await snap(s, '2-시니어-로그인뒤')

  const a = await (await b.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' })).newPage()
  watch(a, '관리자')
  await a.goto(`${GH}/admin`)
  await a.waitForURL(/ts\.net:10000/, { timeout: 15000 })
  console.log('  /service/admin →', a.url())
  await a.getByLabel('아이디').fill('demo-activity')
  await a.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await a.getByRole('button', { name: '로그인', exact: true }).click()
  await a.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await a.waitForLoadState('networkidle')
  await snap(a, '3-관리자-로그인뒤')

  const r = await (await b.newContext()).newPage()
  await r.goto(`${GH}/senior`)
  await r.waitForURL(/ts\.net\/$/, { timeout: 15000 })
  console.log('  /service/senior →', r.url())
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await b.close()
  report(problems)
}
