// 시니어 앱 화면 둘러보기: 데이터를 바꾸지 않는 범위에서 화면마다 전체 캡처를 남기고 한 장짜리 보기 페이지(shots/tour/index.html)를 만든다.
// 출석 요청·활동 시작은 이미 됐으면 그대로 두고, 건너뛰기·그만하기는 열어 보기만 한다(확정하지 않음).
//   node senior-tour.mjs [시니어 앱 주소]    VIDEO=<.mjpeg>: 카메라에 비출 사진
import { open, report, serviceEnv, SHOTS, adminUrlFor, confirmAttendanceAndDeparture } from './common.mjs'
import { mkdirSync, writeFileSync, readdirSync, rmSync } from 'node:fs'
import path from 'node:path'

const URL_ = process.argv[2] || 'http://127.0.0.1:8180'
const code = serviceEnv().DEMO_OTP_CODE
const OUT = path.join(SHOTS, 'tour')
for (const f of (() => { try { return readdirSync(OUT) } catch { return [] } })()) rmSync(path.join(OUT, f), { force: true })
mkdirSync(OUT, { recursive: true })

const { browser, page, problems } = await open({ mobile: true, video: process.env.VIDEO })
const list = []
let n = 0
// 모든 캡처는 휴대폰 한 화면 크기(390×844)로 같게 찍는다. 내용이 한 화면보다 길면 맨 아래로 내린 화면을 한 장 더 찍는다
async function cap(title, note = '', { scroll = true } = {}) {
  await page.waitForTimeout(700)
  const one = async (t, nt) => {
    const file = `${String(++n).padStart(2, '0')}.png`
    await page.screenshot({ path: path.join(OUT, file) })
    list.push({ file, title: t, note: nt })
    console.log(`  ${file} ${t}`)
  }
  await page.evaluate(() => window.scrollTo(0, 0))
  await one(title, note)
  const long = await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight + 8)
  if (scroll && long) {
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await page.waitForTimeout(500)
    await one(`${title} (아래로 내린 화면)`, '같은 화면을 끝까지 내린 모습')
    await page.evaluate(() => window.scrollTo(0, 0))
  }
}
const tap = (name, exact = false) => page.getByRole('button', { name, exact }).first().click()
const closeSheet = () => page.mouse.click(195, 40)

try {
  await page.goto(URL_, { waitUntil: 'networkidle' })
  await cap('시작', '처음 여는 화면')
  await tap('휴대폰 번호로 시작하기')
  await cap('휴대폰 번호 넣기', '숫자 버튼으로 입력. "이 휴대폰 번호 쓰기"는 시연 번호')
  await tap('이 휴대폰 번호 쓰기')
  await tap('인증번호 받기')
  await page.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of code.slice(0, 3)) await tap(d, true)
  await cap('인증번호 넣기', '6자리를 다 넣으면 자동으로 확인')
  for (const d of code.slice(3)) await tap(d, true)
  await page.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 20000 })
  await page.waitForLoadState('networkidle')
  await cap('오늘 순회 (홈)', '서버 순회: 조·코스·모이는 곳·시각·짝 어르신·찍을 장소')
  await page.getByRole('button', { name: /^효과음/ }).waitFor()
  await page.getByRole('button', { name: /^도움/ }).first().click().catch(() => tap('도움'))
  await cap('도움 (전화 걸기)', '담당 선생님·긴급 전화', { scroll: false })
  await closeSheet()
  await tap('센터에 도착했어요')
  await cap('출석', '출석 요청 → 선생님 확인 → 활동 시작. 출발 확인은 기다리지 않아도 됨')
  if (await page.getByRole('button', { name: '출석 요청하기' }).count()) await tap('출석 요청하기')
  // 활동 시작은 담당자 출석·출발 확인 뒤에만 된다 → 관리자 웹에서 확인해 두고, 시니어 화면이 받을 때까지 기다린다
  await confirmAttendanceAndDeparture(browser, adminUrlFor(URL_))
  await page.getByRole('button', { name: /활동 시작하기|이어서 하기/ }).waitFor({ timeout: 20000 })
  await page.getByRole('button', { name: /활동 시작하기|이어서 하기/ }).first().click()
  for (let i = 0; i < 5 && (await page.getByRole('button', { name: '다음 장소로' }).count()); i++) await tap('다음 장소로')
  await cap('찍을 장소', '진행 점·장소 안내·찍기 버튼. 아래는 잠깐 쉬기·그만하기')
  await page.getByRole('button', { name: /^글씨 크기/ }).click()
  await cap('찍을 장소 (글씨 크게)', '위쪽 "가가" 버튼: 기본 → 크게 → 아주 크게')
  await page.getByRole('button', { name: /^글씨 크기/ }).click()
  await page.getByRole('button', { name: /^글씨 크기/ }).click()
  await tap('이곳은 찍을 수 없어요')
  await cap('찍을 수 없어요 (이유 고르기)', '고르면 건너뜀으로 기록. 여기서는 열어 보기만', { scroll: false })
  await closeSheet()
  await tap('오늘 활동 그만하기')
  await cap('오늘 활동 그만하기', '이유를 고르면 끝남. 여기서는 열어 보기만', { scroll: false })
  await closeSheet()
  await tap('잠깐 쉬기')
  await cap('잠깐 쉬기', '휴대폰 안에서만 멈춤')
  await tap('다시 시작하기')
  await tap('도착했어요 · 사진 찍기')
  await page.waitForFunction(() => !document.querySelector('[aria-label="눌러서 사진 찍기"]')?.hasAttribute('disabled'), null, { timeout: 60000 }).catch(() => problems.push('셔터가 60초 안에 켜지지 않음'))
  await cap('사진 찍기 (전체 화면)', '위 안내, 아래 취소·셔터. 얼굴·번호판은 휴대폰에서 먼저 가림', { scroll: false })
  await tap('취소')
} catch (e) {
  problems.push(`둘러보기 중단: ${e.message.split('\n')[0]}`)
  await cap('중단된 화면').catch(() => {})
} finally {
  await browser.close()
}

// 앞서 실제로 보낸 촬영 결과 캡처(senior.mjs --shoot)가 있으면 함께 보여 준다
const sent = readdirSync(SHOTS).find((f) => f.endsWith('촬영-결과.png'))
const items = [...list, ...(sent ? [{ file: `../${sent}`, title: '사진을 보냈어요 (결과)', note: '실제로 찍어 보낸 뒤 화면 (앞선 시험)' }] : [])]
writeFileSync(
  path.join(OUT, 'index.html'),
  `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>시니어 앱 화면</title>
<style>body{margin:0;padding:24px;background:#eef1f5;font-family:system-ui,'Malgun Gothic',sans-serif;color:#191f28}h1{margin:0 0 4px;font-size:22px}p.top{margin:0 0 20px;color:#4e5968}
.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:20px;align-items:start}figure{margin:0;background:#fff;border-radius:16px;padding:12px;box-shadow:0 2px 10px rgba(0,0,0,.06)}
figure img{width:100%;border-radius:10px;border:1px solid #e5e8eb;display:block}figcaption{margin-top:10px;font-weight:700}figcaption small{display:block;font-weight:400;color:#6b7684;margin-top:2px}</style>
<h1>시니어 앱 화면</h1><p class="top">${URL_} · 휴대폰 크기(390×844) · ${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · <a href="../admin/index.html">관리자 웹 화면 보기</a></p><div class="g">
${items.map((x, i) => `<figure><a href="${x.file}" target="_blank"><img src="${x.file}" alt="${x.title}"></a><figcaption>${i + 1}. ${x.title}<small>${x.note}</small></figcaption></figure>`).join('\n')}
</div></html>`,
)
console.log(`보기 페이지: ${path.join(OUT, 'index.html')}`)
report(problems)
