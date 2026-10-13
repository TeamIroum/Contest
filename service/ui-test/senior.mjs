// 시니어 앱을 휴대폰 크기 실제 브라우저로: 로그인(시연 번호·인증번호) → 오늘 순회 → 출석 → 장소 → 전체 화면 촬영 → 결과
// 카메라는 Chromium 가짜 장치(움직이는 시험 화면)를 쓴다.
//   node senior.mjs [시니어 앱 주소] [--shoot]   (기본: 이 PC의 Caddy http://127.0.0.1:8180, --shoot이면 셔터까지 눌러 실제로 보낸다)
//   VIDEO=<사진을 이어 붙인 .mjpeg>: 카메라에 실제 사진을 비춘다 (없으면 Chromium 기본 시험 화면)
import { open, report, serviceEnv, adminUrlFor, confirmAttendanceAndDeparture } from './common.mjs'

const URL_ = process.argv.slice(2).find((a) => !a.startsWith('--')) || 'http://127.0.0.1:8180'
const SHOOT = process.argv.includes('--shoot')
const code = serviceEnv().DEMO_OTP_CODE
if (!code) throw new Error('service\\.env에 DEMO_OTP_CODE가 필요합니다')

const { browser, page, problems, shot } = await open({ mobile: true, video: process.env.VIDEO })
const tap = (name) => page.getByRole('button', { name, exact: false }).first().click()
try {
  console.log(`시니어 앱 ${URL_}`)
  await page.goto(URL_, { waitUntil: 'networkidle' })
  await shot('시작')
  await tap('휴대폰 번호로 시작하기')
  await tap('이 휴대폰 번호 쓰기')
  await shot('번호-입력')
  await tap('인증번호 받기')
  await page.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of code) await page.getByRole('button', { name: d, exact: true }).click()
  await page.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 20000 })
  await page.waitForLoadState('networkidle')
  await shot('오늘-순회')
  await tap('센터에 도착했어요')
  await shot('출석')
  if (await page.getByRole('button', { name: '출석 요청하기' }).count()) await tap('출석 요청하기')
  // 활동 시작은 담당자 출석·출발 확인 뒤에만 된다 → 관리자 웹에서 확인해 두고, 시니어 화면이 받을 때까지 기다린다
  await confirmAttendanceAndDeparture(browser, adminUrlFor(URL_))
  await page.getByRole('button', { name: /활동 시작하기|이어서 하기/ }).waitFor({ timeout: 20000 })
  await page.getByRole('button', { name: /활동 시작하기|이어서 하기/ }).first().click()
  await page.waitForTimeout(800)
  await shot('장소')
  // 이미 끝난 장소는 다음으로 넘긴다
  for (let i = 0; i < 5 && (await page.getByRole('button', { name: '다음 장소로' }).count()); i++) await tap('다음 장소로')
  await tap('도착했어요 · 사진 찍기')
  // 카메라와 가림 모델이 준비되면 셔터가 켜진다
  const shutter = page.getByRole('button', { name: '눌러서 사진 찍기' })
  await shutter.waitFor()
  await page.waitForFunction(() => !document.querySelector('[aria-label="눌러서 사진 찍기"]')?.hasAttribute('disabled'), null, { timeout: 60000 }).catch(() => problems.push('셔터가 60초 안에 켜지지 않음'))
  await shot('촬영-전체화면')
  if (SHOOT) {
    await shutter.click()
    await page.getByRole('button', { name: /다음|센터로 돌아가기|다시 찍기/ }).first().waitFor({ timeout: 90000 })
    await shot('촬영-결과')
    // 휴대폰 품질 검사에 걸리면 사진이 서버로 가지 않는다 → 뒤의 검토·판정 시험이 할 일이 없어지므로 문제로 남긴다
    if (await page.getByRole('button', { name: /다시 찍기/ }).count())
      problems.push(`촬영 결과가 '다시 찍기'(${(await page.locator('main p').first().innerText()).trim()}) — 사진이 서버로 가지 않음`)
  } else {
    await tap('취소')
    await shot('취소-뒤-장소')
  }
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
  await shot('중단-화면').catch(() => {})
} finally {
  await browser.close()
  report(problems)
}
