// 재점검 배정(ACT-10): 시설 담당자(demo-road)가 접수된 사건을 보수 요청 → 보수 시작 → 보수 완료(재점검 요청 생성) →
// 활동 담당자(demo-activity)가 내일 순회를 배정하고 재점검 요청을 그 순회에 배정 → 일정에 '재점검 1장' →
// 배정 취소 → 다시 배정(남은 재점검 칸을 다시 씀, 여전히 1장)
//   node admin-reinspect.mjs [관리자 웹 주소]
// 실제 데이터를 바꾼다. 접수(OPEN) 사건이 있어야 함(admin-decide.mjs 뒤에 돌림). 끝나면 시연 데이터 초기화 필요
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
// 보수 증빙 사진(브라우저에서 가린 뒤 올림)
const PHOTO = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'ai', 'eval', 'photos', 'internet', 'sidewalk_011.jpg')
const { browser, page, problems, shot } = await open()
const menu = (label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: label }).click()
const login = async (id) => {
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  if (await page.getByRole('button', { name: '로그아웃' }).count()) {
    await page.getByRole('button', { name: '로그아웃' }).click()
    await page.waitForLoadState('networkidle')
  }
  await page.getByLabel('아이디').fill(id)
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await page.waitForLoadState('networkidle')
}
const save = async (name) => {
  await page.getByRole('button', { name, exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1200)
}
try {
  console.log('▶ 시설 담당자: 사건 보수 요청 → 시작 → 완료 (재점검 요청 생성)')
  await login('demo-road')
  await menu('사건·보수')
  await page.waitForTimeout(800)
  const row = page.locator('main').getByRole('button').filter({ hasText: /보도블록/ }).first()
  await row.click()
  await save('보수 요청')
  await save('보수 시작으로 변경')
  await page.getByPlaceholder('예: 블록 3장 교체').fill('시험: 블록 교체')
  console.log('  증빙 사진 첨부 (가림 모델 준비를 기다림)')
  await page.getByRole('button', { name: '사진 고르기' }).and(page.locator(':enabled')).waitFor({ timeout: 90000 })
  await page.getByLabel('보수 증빙 사진 파일').setInputFiles(PHOTO)
  await page.getByAltText('보수 증빙 사진(가린 사진)').waitFor({ timeout: 60000 }).catch(() => problems.push('증빙 사진이 올라가지 않음'))
  await shot('재점검-증빙첨부')
  // 크게 보기: 썸네일을 누르면 가린 결과가 크게 열리고 Esc로 닫힌다
  await page.getByRole('button', { name: '보수 증빙 사진 크게 보기' }).first().click()
  const big = await page.getByAltText('보수 증빙 사진 크게(가린 사진)').waitFor({ timeout: 5000 }).then(() => true, () => false)
  console.log(`  증빙 사진 크게 보기: ${big ? '예' : '아니오'}`)
  if (!big) problems.push('보수 증빙 사진을 눌러도 크게 열리지 않음')
  await shot('재점검-증빙크게')
  await page.keyboard.press('Escape')
  if (await page.getByAltText('보수 증빙 사진 크게(가린 사진)').waitFor({ state: 'detached', timeout: 5000 }).then(() => false, () => true)) problems.push('크게 보기 창이 Esc로 닫히지 않음')
  await save('보수 완료')
  await page.getByText('증빙 사진 있음').first().waitFor({ timeout: 10000 }).catch(() => problems.push("보수 이력에 '증빙 사진 있음'이 없음"))
  await page.waitForTimeout(1500)
  if (!(await page.getByText('사건의 마지막 사진은 해제할 수 없습니다').count())) problems.push("사건 화면에 연결된 사진(마지막 사진 해제 불가 안내)이 없음")
  await shot('재점검-보수완료')

  console.log('▶ 활동 담당자: 내일 순회 배정 → 재점검 요청을 그 순회에 배정')
  await page.context().clearCookies()
  await login('demo-activity')
  await menu(/코스·순회 배정/)
  await page.locator('main').getByText(/재점검 요청 \d+건/).waitFor({ timeout: 15000 })
  await page.getByRole('button', { name: '+ 순회 배정' }).click()
  const m = page.getByRole('dialog', { name: '순회 배정' })
  await m.getByLabel('코스').selectOption({ label: '마을회관 코스' })
  await m.getByRole('button', { name: '배정', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1000)
  const req = page.locator('main tr').filter({ hasText: '보수 확인' }).first()
  await req.waitFor({ timeout: 10000 })
  await shot('재점검-요청목록')

  const assign = async () => {
    await req.getByRole('button', { name: '순회에 배정' }).click()
    const d = page.getByRole('dialog', { name: '재점검 순회 배정' })
    await d.getByText(/번째 장소/).first().waitFor({ timeout: 10000 })
    const hint = await d.getByText(/지난 사진을 반투명하게|겹쳐 볼 지난 사진이 없어/).first().innerText()
    console.log(`  참고 사진: ${hint.includes('지난 사진을 반투명하게') ? '있음 (겹쳐 보기)' : '없음'}`)
    if (!hint.includes('지난 사진을 반투명하게')) problems.push('재점검 배정에 참고 사진(지난 사진)이 없음')
    await shot('재점검-배정창')
    await d.getByRole('button', { name: '배정', exact: true }).click()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1200)
  }
  await assign()
  if (!(await req.getByText('배정됨').count())) problems.push('재점검 요청이 배정됨으로 바뀌지 않음')
  const slots = () => page.locator('main tr').filter({ hasText: '마을회관 코스' }).filter({ hasText: '1장' }).count()
  if (!(await slots())) problems.push("순회 일정에 '재점검 1장'이 보이지 않음")
  await shot('재점검-배정뒤')

  console.log('▶ 배정 취소 → 다시 배정 (재점검 칸은 다시 써서 여전히 1장)')
  await req.getByRole('button', { name: '배정 취소' }).click()
  const c = page.getByRole('dialog', { name: '재점검 배정 취소' })
  await c.getByLabel('취소 이유').fill('시험: 다른 순회로 옮김')
  await c.getByRole('button', { name: '배정 취소', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1200)
  if (!(await req.getByText('배정 전').count())) problems.push('배정 취소 뒤 배정 전으로 바뀌지 않음')
  await assign()
  if (!(await req.getByText('배정됨').count())) problems.push('다시 배정되지 않음')
  if (!(await slots())) problems.push('다시 배정한 뒤 재점검 칸이 1장이 아님 (빈 칸을 다시 쓰지 않음)')
  await shot('재점검-다시배정')
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
