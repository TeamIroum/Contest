// 운영 화면(OPS-01·04·05·06·09): 시설 담당자(demo-road) 알림 → 검토 목록으로 이동, 사업 총괄(demo-program) 기관·거점 추가 →
// AI 보고 문장 → 엑셀 내려받기 → 감사 이력에 '기관 만들기'·'엑셀 내려받기'가 남는지, 시스템 관리자(demo-admin)도 감사 이력을 보는지
//   node admin-ops.mjs [관리자 웹 주소]      EXPECT_DATA=1 이면 demo-road 알림이 1건 이상이어야 통과 (run-all에서 촬영 뒤)
// 실제 데이터를 바꾼다 (끝나면 시연 데이터 초기화 필요)
import { chromium } from 'playwright'
import path from 'node:path'
import { report, serviceEnv, SHOTS } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const ORG = `시험 경로당 ${String(Date.now() % 1000).padStart(3, '0')}`
const problems = []
const browser = await chromium.launch()
let n = 0
const login = async (id) => {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR', acceptDownloads: true })
  const page = await ctx.newPage()
  page.on('pageerror', (e) => problems.push(`${id} 화면 오류: ${e.message.slice(0, 160)}`))
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) problems.push(`${id} API ${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()}`)
  })
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill(id)
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await page.waitForLoadState('networkidle')
  return { ctx, page }
}
const snap = async (page, name) => {
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(SHOTS, `${String(++n).padStart(2, '0')}-운영-${name}.png`) })
  console.log(`  캡처 ${name}`)
}
const menu = (page, label) => page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: new RegExp(label) }).click()
try {
  console.log('▶ 시설 담당자 알림 (알림은 15초마다 만들어짐)')
  let { ctx, page } = await login('demo-road')
  await page.waitForTimeout(16000)
  await page.reload({ waitUntil: 'networkidle' })
  if (await page.getByLabel('아이디').count()) {
    await ctx.close()
    ;({ ctx, page } = await login('demo-road'))
  }
  const bell = page.getByRole('button', { name: /^알림 \d+건$/ })
  // 알림 수는 로그인 뒤 따로 받아 온다. 0이 아닌 값이 올 때까지 잠깐 기다린다
  await page.getByRole('button', { name: /^알림 [1-9]\d*건$/ }).waitFor({ timeout: 25000 }).catch(() => {})
  const unread = Number(((await bell.getAttribute('aria-label')) ?? '').match(/\d+/)?.[0] ?? 0)
  console.log(`  안 읽은 알림 ${unread}건`)
  if (process.env.EXPECT_DATA && unread < 1) problems.push('촬영 뒤인데 시설 담당자 알림이 없음')
  await bell.click()
  await snap(page, '알림')
  const first = page.getByRole('dialog', { name: '알림' }).getByRole('listitem').first()
  if (await first.count()) {
    await first.click()
    await page.waitForTimeout(800)
    const onReview = await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /검토 목록/ }).getAttribute('aria-current')
    console.log(`  알림 누르면 검토 목록으로: ${onReview ? '예' : '확인 못 함'}`)
  }
  await ctx.close()

  console.log(`▶ 사업 총괄: 기관·거점 추가 (${ORG})`)
  ;({ ctx, page } = await login('demo-program'))
  await menu(page, '기관·거점')
  await page.locator('main').getByText('옥정동 노인복지관').first().waitFor({ timeout: 10000 })
  await page.getByRole('button', { name: '+ 기관 추가' }).click()
  const d = page.getByRole('dialog', { name: '기관 추가' })
  await d.getByLabel('이름').fill(ORG)
  await d.getByLabel(/상위 수행기관/).selectOption({ label: '옥정동 노인복지관' })
  await d.getByRole('button', { name: '저장', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.locator('main tr').filter({ hasText: ORG }).waitFor({ timeout: 10000 }).catch(() => problems.push('추가한 거점이 목록에 없음'))
  await snap(page, '기관추가')

  console.log('▶ 통계: AI 보고 문장 → 엑셀')
  await menu(page, '통계·보고')
  await page.getByTestId('report-sentence').waitFor({ timeout: 15000 })
  await page.getByRole('button', { name: 'AI로 보고 문장 만들기' }).click()
  const narr = page.getByTestId('ai-narrative')
  await narr.waitFor({ timeout: 120000 }).catch(() => problems.push('AI 보고 문장이 나오지 않음'))
  if (await narr.count()) console.log(`  보고 문장 출처: ${(await narr.innerText()).split('\n')[0]}`)
  await snap(page, '보고문장')
  await Promise.all([page.waitForEvent('download', { timeout: 10000 }), page.getByRole('button', { name: '엑셀 다운로드' }).click()])
  await page.waitForTimeout(1000)

  console.log('▶ 감사 이력: 기관 만들기·엑셀 내려받기')
  await menu(page, '감사 이력')
  await page.locator('main tr').nth(1).waitFor({ timeout: 10000 })
  for (const a of ['기관 만들기', '엑셀 내려받기', '보고 문장 생성']) if (!(await page.locator('main tr').filter({ hasText: a }).count())) problems.push(`감사 이력에 '${a}'이 없음`)
  await snap(page, '감사이력')
  await ctx.close()

  console.log('▶ 시스템 관리자도 감사 이력')
  ;({ ctx, page } = await login('demo-admin'))
  await menu(page, '감사 이력')
  await page.locator('main tr').nth(1).waitFor({ timeout: 10000 }).catch(() => problems.push('시스템 관리자 감사 이력이 비어 있음'))
  await ctx.close()
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
