// 시연 영상 촬영: 어르신 휴대폰 두 대(010-0000-0001 김○○, 010-0000-0002 박○○)와 PC(관리자 웹)를 동시에 녹화하며 전체 흐름을 진행한다.
// 두 분 모두 자기 휴대폰으로 로그인·출석 요청을 하고(박○○ 조작은 김○○와 동시에), 촬영은 김○○ 휴대폰으로 한다. 영상에는 김○○ 휴대폰만 나온다. 카메라는 가짜 장치에 보도블록 사진(VIDEO)을 비춘다. 이름은 시연 데이터 그대로(○○).
//   node demo-film.mjs [시니어 주소] [관리자 주소]      VIDEO=<짝수 크기 mjpeg> 필요
// 결과: shots/film/phone.webm(김○○), phone2.webm(박○○), admin.webm, steps.json(단계 자막 시각·빨리 감을 대기 구간), NN-*.png(단계 캡처) → make-film.py가 MP4로 합친다
// 실제 데이터를 바꾼다 (오늘 순회 필요, 끝나면 초기화)
import { chromium } from 'playwright'
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, renameSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { SHOTS, serviceEnv } from './common.mjs'

const SENIOR = process.argv[2] || 'http://127.0.0.1:8180'
const ADMIN = process.argv[3] || 'http://127.0.0.1:8181'
const env = serviceEnv()
const OUT = path.join(SHOTS, 'film')
mkdirSync(OUT, { recursive: true })
// 지난 촬영에서 이 스크립트·make-film.py가 만든 파일만 지운다 (사람이 이름을 바꿔 둔 영상 등은 남김)
const MADE = /^(\d\d-.*\.png|_.*|cap\d\d_\d\.txt|phone2?\.webm|admin\.webm|steps\.json|index\.html|이로움_시연\.mp4)$/
for (const f of readdirSync(OUT)) if (MADE.test(f)) rmSync(path.join(OUT, f), { force: true })
if (!process.env.VIDEO) throw new Error('VIDEO(카메라에 비출 mjpeg)가 필요합니다')

const browser = await chromium.launch({
  args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', `--use-file-for-fake-video-capture=${process.env.VIDEO}`],
})
const phoneOpts = {
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
  locale: 'ko-KR',
  timezoneId: 'Asia/Seoul',
  permissions: ['camera'],
  recordVideo: { dir: OUT, size: { width: 390, height: 844 } }, // 화면 크기와 같게 (크게 잡으면 귀퉁이에 작게 녹화됨). 키우기는 make-film.py가
}
const phoneCtx = await browser.newContext(phoneOpts)
const phone2Ctx = await browser.newContext(phoneOpts)
const adminCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul', recordVideo: { dir: OUT, size: { width: 1440, height: 900 } } })
// 누른 곳에 파란 물결 (영상에서 어디를 눌렀는지 보이게)
const RIPPLE = `addEventListener('pointerdown', (e) => {
  const d = document.createElement('div')
  Object.assign(d.style, { position: 'fixed', left: e.clientX - 24 + 'px', top: e.clientY - 24 + 'px', width: '48px', height: '48px',
    borderRadius: '50%', background: 'rgba(59,130,246,.30)', border: '3px solid rgba(59,130,246,.95)', pointerEvents: 'none',
    zIndex: 2147483647, transition: 'transform .6s ease-out, opacity .6s ease-out' })
  document.documentElement.appendChild(d)
  d.getBoundingClientRect()
  d.style.transform = 'scale(2)'
  d.style.opacity = '0'
  setTimeout(() => d.remove(), 700)
}, true)`
await phoneCtx.addInitScript(RIPPLE)
// 가짜 카메라: window.__camUrl의 사진을 가로 화면(1280x960)에 꽉 차게 그린 캔버스 영상을 카메라처럼 돌려준다.
// 장소·구도마다 사진을 바꿔 끼운다 (Chromium 가짜 장치는 실행할 때 고른 영상 하나만 비춘다)
await phoneCtx.addInitScript(() => {
  const md = navigator.mediaDevices
  if (!md?.getUserMedia) return
  const orig = md.getUserMedia.bind(md)
  md.getUserMedia = async (c) => {
    if (!c?.video || !window.__camUrl) return orig(c)
    const canvas = Object.assign(document.createElement('canvas'), { width: 1280, height: 960 })
    const ctx = canvas.getContext('2d')
    let cur = null
    let img = null
    const draw = () => {
      if (window.__camUrl !== cur) {
        cur = window.__camUrl
        const next = new Image()
        next.onload = () => (img = next)
        next.src = cur
      }
      if (!img) return
      const k = Math.max(canvas.width / img.width, canvas.height / img.height)
      const w = img.width * k
      const h = img.height * k
      ctx.drawImage(img, (canvas.width - w) / 2, (canvas.height - h) / 2, w, h)
    }
    draw()
    setInterval(draw, 50)
    return canvas.captureStream(20)
  }
})
await phone2Ctx.addInitScript(RIPPLE)
await adminCtx.addInitScript(RIPPLE)
const s = await phoneCtx.newPage()
const s2 = await phone2Ctx.newPage()
const a = await adminCtx.newPage()
const t0 = Date.now()
const steps = []
const idles = [] // 화면이 멈춰 기다리는 구간 [시작, 끝] (초). make-film.py가 빨리 감는다
let n = 0
const now = () => (Date.now() - t0) / 1000
/** 기다리는 동안을 빨리 감을 구간으로 적는다 */
async function waiting(fn) {
  const st = now()
  try {
    return await fn()
  } finally {
    if (now() - st > 3) idles.push([st + 0.5, now() - 0.5])
  }
}
/** 단계 자막 시작 + 두 화면 캡처 */
async function step(text) {
  steps.push({ t: (Date.now() - t0) / 1000, text })
  console.log(`▶ ${text}`)
}
/** 관리자 화면의 사진이 다 뜰 때까지 ('사진 불러오는 중'이 보이면 영상에 그대로 찍힌다) */
const photosReady = () =>
  a.waitForFunction(() => !document.body.innerText.includes('사진 불러오는 중'), null, { timeout: 30000 }).catch(() => {})
async function shot(name) {
  await photosReady()
  n++
  const id = String(n).padStart(2, '0')
  await s.screenshot({ path: path.join(OUT, `${id}-휴대폰-${name}.png`) }).catch(() => {})
  await s2.screenshot({ path: path.join(OUT, `${id}-휴대폰2-${name}.png`) }).catch(() => {})
  await a.screenshot({ path: path.join(OUT, `${id}-관리자-${name}.png`) }).catch(() => {})
}
const pause = (ms = 1500) => s.waitForTimeout(ms)
const PHOTOS = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'ai', 'eval', 'photos')
const photo = (name) => 'data:image/jpeg;base64,' + readFileSync(path.join(PHOTOS, name)).toString('base64')
/** 카메라에 사진을 비추고 찍는다. 서버가 '다시 찍기'를 요청하면 retry 사진으로 한 번 더 */
const camera = (name) => s.evaluate((u) => (window.__camUrl = u), photo(name))
async function shoot(name, retry) {
  await camera(name)
  await waiting(() => s.waitForFunction(() => !document.querySelector('[aria-label="눌러서 사진 찍기"]')?.hasAttribute('disabled'), null, { timeout: 90000 }))
  await pause(2500)
  await s.getByRole('button', { name: '눌러서 사진 찍기' }).click()
  const done = s.getByRole('button', { name: /다음|센터로 돌아가기|다시 찍기/ }).first()
  await waiting(() => done.waitFor({ timeout: 90000 }))
  if (retry && (await s.getByRole('button', { name: /다시 찍기/ }).count())) {
    console.log(`  다시 찍기 요청 → ${retry}`)
    await pause(2000)
    await s.getByRole('button', { name: /다시 찍기/ }).first().click()
    return shoot(retry)
  }
  await pause(3000)
}
const tap = (name, page = s) => page.getByRole('button', { name }).first().click()
/** 어르신 휴대폰 로그인 (시연 번호 · 인증번호) */
async function seniorLogin(page, phone) {
  await page.getByRole('button', { name: '휴대폰 번호로 시작하기' }).click()
  await page.waitForTimeout(800)
  // '이 휴대폰 번호 쓰기'는 시연 번호(010-0000-0001)를 채운다. 다른 번호는 직접 누른다
  const same = page.getByRole('button', { name: /이 휴대폰 번호 쓰기/ })
  if (phone === '01000000001' && (await same.count())) {
    await same.click()
  } else {
    for (const d of phone) await page.getByRole('button', { name: d, exact: true }).click()
  }
  await page.waitForTimeout(800)
  await page.getByRole('button', { name: '인증번호 받기' }).click()
  await page.getByText('문자로 받은 숫자 6개를 넣어 주세요').waitFor({ timeout: 15000 })
  for (const d of env.DEMO_OTP_CODE) {
    await page.getByRole('button', { name: d, exact: true }).click()
    await page.waitForTimeout(250)
  }
  await page.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 20000 })
}
/** 목록 항목을 눌러 상세가 열릴 때까지 (다시 그려지는 중이면 한 번 더) */
async function openRow(re, opened) {
  for (let i = 0; i < 4; i++) {
    await a.locator('main').getByRole('button').filter({ hasText: re }).first().click()
    if (await a.getByRole('button', { name: opened, exact: true }).first().waitFor({ timeout: 4000 }).then(() => true, () => false)) return
  }
  throw new Error(`상세가 열리지 않음: ${opened}`)
}
const menu = (label) => a.getByRole('navigation', { name: '주 메뉴' }).getByRole('button', { name: new RegExp(label) }).click()
async function adminLogin(id) {
  if (await a.getByRole('button', { name: '로그아웃' }).count()) {
    await a.getByRole('button', { name: '로그아웃' }).click()
    await a.getByLabel('아이디').waitFor({ timeout: 10000 })
  }
  await a.getByLabel('아이디').fill(id)
  await a.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
  await a.getByRole('button', { name: '로그인', exact: true }).click()
  await a.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await a.waitForTimeout(1200)
}

try {
  await a.goto(ADMIN, { waitUntil: 'load' })
  await s.goto(SENIOR, { waitUntil: 'load' })
  await s2.goto(SENIOR, { waitUntil: 'load' })
  await step('① 어르신 휴대폰 로그인 (시연 번호 · 인증번호)')
  await pause()
  await Promise.all([seniorLogin(s, '01000000001'), seniorLogin(s2, '01000000002')])
  await pause(2000)
  await shot('오늘순회')

  await step('② 센터에 도착 → 출석 요청')
  await Promise.all(
    [s, s2].map(async (page) => {
      await tap('센터에 도착했어요', page)
      await page.waitForTimeout(1000)
      if (await page.getByRole('button', { name: '출석 요청하기' }).count()) await tap('출석 요청하기', page)
    }),
  )
  await pause(1500)
  await shot('출석요청')

  await step('③ 활동 담당자: 두 분 출석 확인 → 출발 확인 (2인 1조)')
  await adminLogin('demo-activity')
  await a.locator('main').getByText(/\d+조 · /).first().waitFor({ timeout: 15000 })
  await a.waitForTimeout(1500)
  for (let i = 0; i < 2; i++) {
    await a.locator('main').getByRole('button', { name: '출석 확인', exact: true }).first().click()
    await a.waitForTimeout(1500)
  }
  await a.locator('main').getByRole('button', { name: '출발 확인', exact: true }).first().click()
  await a.waitForTimeout(2000)
  await shot('출석출발확인')

  await step('④ 어르신: 출발 확인을 받으면 조 전체 활동 시작')
  await s.getByRole('button', { name: '활동 시작하기' }).waitFor({ timeout: 20000 })
  await pause(1200)
  await tap('활동 시작하기')
  // 조 전체 시작: 박○○ 휴대폰도 같은 순회로 넘어간다
  await s2.getByRole('button', { name: '도착했어요 · 사진 찍기' }).waitFor({ timeout: 15000 }).catch(() => s2.reload({ waitUntil: 'load' }))
  await pause(2000)
  await shot('첫장소안내')

  await step('⑤ 1번 보도블록 촬영: 전체 화면 카메라 → 사진을 보내면 서버가 품질을 확인')
  await camera('internet/sidewalk_011.jpg')
  await tap('도착했어요 · 사진 찍기')
  await shoot('internet/sidewalk_011.jpg')
  await shot('보도블록')

  await step('⑥ 2번 빗물받이: 덮개와 주변 바닥 두 장 (사람 얼굴·차 번호판은 앱에서 먼저 마스킹한 뒤 전송)')
  await s.getByRole('button', { name: /다음 장소로/ }).first().click()
  await pause(1500)
  await camera('internet/drain_004.jpg')
  await tap('도착했어요 · 사진 찍기')
  await shoot('internet/drain_004.jpg') // 낙엽이 덮인 덮개
  await camera('internet/drain_001.jpg')
  await s.getByRole('button', { name: /다음 사진 찍기/ }).first().click()
  await shoot('internet/drain_001.jpg', 'internet/drain_003.jpg') // 한 걸음 물러나 주변 보도까지 (정상 덮개)
  await pause(1500)
  await shot('빗물받이')

  await step('⑦ 3번 벤치 촬영 → 장소마다 관리 부서(도로관리과·하수과·공원사업과)로 나뉘어 감')
  await s.getByRole('button', { name: /다음 장소로/ }).first().click()
  await pause(1500)
  await camera('internet/bench_001.jpg')
  await tap('도착했어요 · 사진 찍기')
  await shoot('internet/bench_001.jpg')
  await shot('벤치')

  await step('⑧ 센터로 돌아와 복귀 알림 → 담당자 복귀 확인을 기다림')
  if (await s.getByRole('button', { name: '센터로 돌아가기' }).count()) await tap('센터로 돌아가기')
  await s.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 10000 })
  await tap('센터에 도착했어요')
  // 박○○ 휴대폰: 함께 돌아와 복귀 알림
  await s2.reload({ waitUntil: 'load' })
  if (await s2.getByRole('button', { name: '센터에 도착했어요' }).waitFor({ timeout: 8000 }).then(() => true, () => false)) {
    await tap('센터에 도착했어요', s2)
  }
  await pause(2000)
  await shot('복귀대기')

  await step('⑨ 활동 담당자: 두 분 복귀 확인 → 어르신 활동 끝 (사진 확인은 담당 부서가 따로)')
  await adminLogin('demo-activity')
  for (const name of ['김○○', '박○○']) {
    await a.waitForTimeout(3000)
    await a.locator(`xpath=//main//*[normalize-space(text())='${name}']/following::button[normalize-space()='복귀 확인'][1]`).click()
    const d = a.getByRole('dialog', { name: `${name} 복귀 확인` })
    await d.waitFor({ timeout: 5000 })
    await a.waitForTimeout(800)
    await d.getByRole('button', { name: '복귀 확인', exact: true }).click()
    await a.waitForTimeout(1500)
  }
  await waiting(() => s.getByText('복귀를 확인하면 끝나요').waitFor({ state: 'detached', timeout: 20000 }).catch(() => {}))
  // 박○○ 휴대폰도 끝 화면으로 (복귀 대기 화면이 아니면 새로 고쳐 최신 상태로)
  if (!(await s2.getByText(/수고하셨어요|오늘 활동을 마쳤어요|활동 끝/).count())) await s2.reload({ waitUntil: 'load' }).catch(() => {})
  await pause(2500)
  await shot('활동끝')

  await step('⑩ 도로관리과 담당자: 알림 → 검토 목록 → AI 판독 근거 확인')
  await adminLogin('demo-road')
  await waiting(() => a.waitForTimeout(16000)) // 알림은 15초마다 만들어진다
  await a.reload({ waitUntil: 'load' })
  await a.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
  await a.waitForTimeout(1500)
  await a.getByRole('button', { name: /^알림 \d+건$/ }).click()
  await a.waitForTimeout(2500)
  await shot('알림')
  await a.mouse.click(10, 10)
  await menu('검토 목록')
  await a.waitForTimeout(1500)
  await openRow(/보도블록/, '판정 저장')
  await photosReady()
  await a.waitForTimeout(3500)
  await shot('검토상세')

  await step('⑪ 담당자 판정: 이상 확인 → 사건 등록 → 보수 요청')
  await a.getByText('이상 확인', { exact: true }).first().click()
  await a.locator('textarea').first().fill('사진으로 보도블록 균열 확인')
  await a.waitForTimeout(1200)
  await a.getByRole('button', { name: '판정 저장' }).click()
  await a.waitForTimeout(2500)
  await menu('사건·보수')
  await a.waitForTimeout(1500)
  await openRow(/보도블록/, '보수 요청')
  await photosReady()
  await a.waitForTimeout(2000)
  await a.getByRole('button', { name: '보수 요청', exact: true }).click()
  await a.waitForTimeout(2500)
  await shot('보수요청')

  await step('⑫ 보수 완료 입력 · 증빙 사진에 찍힌 차 번호판도 관리자 웹에서 마스킹한 뒤 올림 → 재점검 요청 생성')
  await a.getByRole('button', { name: '보수 시작으로 변경', exact: true }).click()
  await a.waitForTimeout(2000)
  await a.getByPlaceholder('예: 블록 3장 교체').fill('블록 3장 교체')
  await a.getByRole('button', { name: '사진 고르기' }).and(a.locator(':enabled')).waitFor({ timeout: 90000 })
  await a.getByLabel('보수 증빙 사진 파일').setInputFiles(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'ai', 'eval', 'photos', 'internet', 'sidewalk_014.jpg')) // 보수한 보도 · 차가 함께 찍힘 → 번호판 가림
  await a.getByAltText('보수 증빙 사진(가린 사진)').waitFor({ timeout: 60000 })
  // 가린 결과(차 번호판)를 크게 열어 보여 준다
  await a.waitForTimeout(1000)
  await a.getByRole('button', { name: '보수 증빙 사진 크게 보기' }).first().click()
  await a.getByAltText('보수 증빙 사진 크게(가린 사진)').waitFor({ timeout: 10000 })
  await a.waitForTimeout(5500)
  await a.screenshot({ path: path.join(OUT, `${String(n + 1).padStart(2, '0')}-관리자-증빙사진-가림-크게.png`) }).catch(() => {})
  await a.keyboard.press('Escape')
  await a.waitForTimeout(1000)
  await a.getByRole('button', { name: '보수 완료', exact: true }).click()
  await a.waitForTimeout(1000)
  await photosReady()
  await a.waitForTimeout(3500)
  await shot('보수완료')

  await step('⑬ 하수과 담당자: 빗물받이 사진은 하수과로 · 낙엽 덮임을 AI가 근거와 함께 표시 (판정은 담당자가)')
  await adminLogin('demo-sewer')
  await menu('검토 목록')
  await a.waitForTimeout(1500)
  // AI가 이상 없다고 본 사진은 '처리할 것'에 없을 수 있다 → '전체'에서 찾는다
  if (!(await a.locator('main').getByRole('button').filter({ hasText: /빗물받이/ }).count())) {
    await a.getByRole('button', { name: '전체', exact: true }).first().click().catch(() => {})
    await a.waitForTimeout(1500)
  }
  await openRow(/빗물받이/, '판정 저장').catch((e) => console.log(`  하수과 빗물받이 사진: ${e.message}`))
  await photosReady()
  await a.waitForTimeout(5000)
  await shot('하수과')

  await step('⑭ 재점검: 보수 확인 요청을 다음 순회에 배정 (지난 사진을 겹쳐 보며 같은 구도로)')
  await adminLogin('demo-activity')
  await menu('코스·순회 배정')
  await a.waitForTimeout(2000)
  await a.getByRole('button', { name: '+ 순회 배정' }).click()
  const pd = a.getByRole('dialog', { name: '순회 배정' })
  await pd.getByLabel('코스').selectOption({ label: '마을회관 코스' })
  await a.waitForTimeout(1200)
  await pd.getByRole('button', { name: '배정', exact: true }).click()
  await a.waitForTimeout(2000)
  await a.locator('main tr').filter({ hasText: '보수 확인' }).first().getByRole('button', { name: '순회에 배정' }).click()
  const rd = a.getByRole('dialog', { name: '재점검 순회 배정' })
  await rd.getByText(/번째 장소/).first().waitFor({ timeout: 10000 })
  await a.waitForTimeout(2500)
  await shot('재점검배정')
  await rd.getByRole('button', { name: '배정', exact: true }).click()
  await a.waitForTimeout(2500)

  await step('⑮ 사업 총괄: 통계 · AI 보고 문장 (확정 집계값만 넘기고 숫자를 검증)')
  await adminLogin('demo-program')
  await menu('통계·보고')
  await a.getByTestId('report-sentence').waitFor({ timeout: 15000 })
  await a.waitForTimeout(2000)
  await a.getByRole('button', { name: 'AI로 보고 문장 만들기' }).click()
  await waiting(() => a.getByTestId('ai-narrative').waitFor({ timeout: 120000 }))
  await a.getByTestId('ai-narrative').scrollIntoViewIfNeeded()
  await a.waitForTimeout(4000)
  await shot('AI보고문장')
  await step('끝')
} catch (e) {
  console.log(`촬영 중단: ${e.message.split('\n')[0]}`)
  await shot('중단').catch(() => {})
  process.exitCode = 1
} finally {
  const total = (Date.now() - t0) / 1000
  const pv = s.video()
  const av = a.video()
  const pv2 = s2.video()
  await phoneCtx.close()
  await phone2Ctx.close()
  await adminCtx.close()
  await browser.close()
  renameSync(await pv.path(), path.join(OUT, 'phone.webm'))
  renameSync(await pv2.path(), path.join(OUT, 'phone2.webm'))
  renameSync(await av.path(), path.join(OUT, 'admin.webm'))
  writeFileSync(path.join(OUT, 'steps.json'), JSON.stringify({ total, steps, idles }, null, 2))
  console.log(`녹화 ${total.toFixed(0)}초, 단계 ${steps.length}개 → ${OUT}`)
}
