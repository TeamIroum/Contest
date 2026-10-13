// 개인정보 검토: 시설 담당자가 검토 상세에서 '가림 부족'으로 사진을 보류 → '가림 확인 · 공개 허용' → AI 확인 뒤 다시 공개되는지
// 실제 데이터를 바꾼다. 검토 작업(사진)이 하나 이상 있어야 함
//   node admin-privacy.mjs [관리자 웹 주소]    LOGIN=demo-road (기본)
import { open, report, serviceEnv } from './common.mjs'

const URL_ = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const LOGIN = process.env.LOGIN || 'demo-road'

const { browser, page, problems, shot } = await open()
const nav = page.getByRole('navigation', { name: '주 메뉴' })
async function openTask() {
  await nav.getByRole('button', { name: '검토 목록' }).click()
  await page.waitForLoadState('networkidle')
  await page.locator('main').getByText('전체', { exact: true }).first().click()
  await page.waitForTimeout(500)
  await page.locator('main').getByRole('button').filter({ hasText: /보도블록|빗물받이|벤치|펜스|운동기구/ }).first().click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1000)
}
try {
  await page.goto(URL_, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill(LOGIN)
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await nav.waitFor({ timeout: 15000 })
  await openTask()
  await shot('privacy-1-공개사진')

  console.log('▶ 가림 부족 → 보류')
  await page.getByRole('button', { name: '얼굴·번호판 가림이 부족해요' }).click()
  await page.getByLabel('개인정보 검토 사유').fill('시험: 왼쪽 사람 얼굴이 가려지지 않음')
  await shot('privacy-2-보류입력')
  await page.getByRole('button', { name: '보류하기' }).click()
  await page.waitForLoadState('networkidle')
  await page.getByText('개인정보 보류 중인 사진').waitFor({ timeout: 15000 })
  await shot('privacy-3-보류됨')

  console.log('▶ 가림 확인 · 공개 허용')
  await page.getByLabel('개인정보 검토 사유').fill('시험: 다시 확인하니 얼굴이 모두 가려짐')
  await page.getByRole('button', { name: '가림 확인 · 공개 허용' }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1500)
  await shot('privacy-4-공개허용뒤')

  console.log('▶ AI 확인 뒤 다시 공개되는지 (최대 90초, 관리자 화면은 5초마다 새로 받음)')
  await page.getByText('개인정보 보류 중인 사진').waitFor({ state: 'detached', timeout: 90000 })
  await shot('privacy-5-다시공개')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
  await shot('privacy-중단').catch(() => {})
} finally {
  await browser.close()
  report(problems)
}
