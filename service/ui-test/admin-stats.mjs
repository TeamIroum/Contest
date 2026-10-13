// 통계·보고(OPS-03): 사업 총괄(demo-program)이 기간 집계를 보고 엑셀을 내려받는다 (데이터 안 바꿈)
//   node admin-stats.mjs [관리자 웹 주소]      EXPECT_DATA=1 이면 사진·검토·사건이 1건 이상이어야 통과 (run-all에서 촬영·판정 뒤)
import { open, report, serviceEnv } from './common.mjs'

const ADMIN = process.argv[2] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const { browser, page, problems, shot } = await open()
try {
  await page.goto(ADMIN, { waitUntil: 'networkidle' })
  await page.getByLabel('아이디').fill('demo-program')
  await page.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: /통계·보고/ }).click()
  const sentence = page.getByTestId('report-sentence')
  await sentence.waitFor({ timeout: 15000 })
  if (await page.getByText('예시 화면입니다').count()) problems.push('통계·보고 화면에 아직 예시 안내가 있음')
  const text = await sentence.innerText()
  console.log(`  보고 문장: ${text}`)
  await shot('통계-최근2주')
  const n = (re) => Number((text.match(re) ?? [])[1] ?? -1)
  const photos = n(/사진 (\d+)장/)
  const issues = n(/신규 사건 (\d+)건/)
  if (photos < 0 || issues < 0) problems.push('보고 문장에 사진·사건 수가 없음')
  if (process.env.EXPECT_DATA && (photos < 1 || issues < 1)) problems.push(`촬영·판정 뒤인데 사진 ${photos}장 · 신규 사건 ${issues}건`)

  await page.getByRole('tab', { name: '전체', exact: true }).click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(600)
  await shot('통계-전체')
  const [file] = await Promise.all([page.waitForEvent('download', { timeout: 10000 }), page.getByRole('button', { name: '엑셀 다운로드' }).click()])
  const name = file.suggestedFilename()
  console.log(`  엑셀: ${name}`)
  if (!/^동네점검단_실적_\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.xlsx$/.test(name)) problems.push(`엑셀 파일 이름이 이상함: ${name}`)
} catch (e) {
  problems.push(`시험 중단: ${e.message.split('\n')[0]}`)
} finally {
  await browser.close()
  report(problems)
}
