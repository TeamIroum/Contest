// 화면 시험 공통: service\.env 읽기, 브라우저 열기, 캡처·오류 모으기
import { chromium } from 'playwright'
import { readFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
export const SHOTS = path.join(here, 'shots')
mkdirSync(SHOTS, { recursive: true })

/** service\.env 값 (따옴표·export 처리) */
export function serviceEnv() {
  const out = {}
  for (const line of readFileSync(path.join(here, '..', '.env'), 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*(?:export\s+)?([A-Z0-9_]+)\s*=(.*)$/)
    if (!m) continue
    let v = m[2].trim()
    const q = v.match(/^'(.*)'$/) || v.match(/^"(.*)"$/)
    out[m[1]] = q ? q[1] : v.replace(/\s+#.*$/, '')
  }
  return out
}

/** 휴대폰(시니어) 또는 PC(관리자) 크기로 브라우저를 연다. 콘솔 오류·실패한 API 응답을 모은다 */
export async function open({ mobile = false, video } = {}) {
  const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream']
  if (video) args.push(`--use-file-for-fake-video-capture=${video}`)
  const browser = await chromium.launch({ args })
  const context = await browser.newContext(
    mobile
      ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR', timezoneId: 'Asia/Seoul', permissions: ['camera'] }
      : { viewport: { width: 1440, height: 900 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul' },
  )
  const page = await context.newPage()
  const problems = []
  // TensorFlow Lite가 오류 채널로 남기는 안내(INFO: Created TensorFlow Lite XNNPACK delegate)는 오류가 아니다
  // 로그인 되살리기(새로 고침·계정 바꾸기) 때 갱신 쿠키가 없으면 /auth/token/refresh가 401이다. 정상 동작이라 세지 않는다
  const refresh401 = (url) => url.includes('/auth/token/refresh')
  page.on('console', (m) => m.type() === 'error' && !m.text().startsWith('INFO:') && !refresh401(m.location()?.url ?? '') && problems.push(`콘솔: ${m.text().slice(0, 200)}`))
  page.on('pageerror', (e) => problems.push(`화면 오류: ${e.message.slice(0, 200)}`))
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400 && !(r.status() === 401 && refresh401(r.url()))) {
      const body = await r.text().catch(() => '')
      problems.push(`API ${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()} ${body.slice(0, 160)}`)
    }
  })
  let n = 0
  const shot = async (name) => {
    await page.waitForTimeout(600)
    const file = path.join(SHOTS, `${String(++n).padStart(2, '0')}-${name}.png`)
    await page.screenshot({ path: file, fullPage: false })
    console.log(`  캡처 ${path.basename(file)}`)
  }
  return { browser, page, problems, shot }
}

export function report(problems) {
  if (!problems.length) return console.log('문제 없음')
  console.log(`문제 ${problems.length}건:`)
  for (const p of [...new Set(problems)]) console.log('  - ' + p)
}

/** 시니어 앱 주소에서 관리자 웹 주소를 정한다 (Caddy :8180 → :8181, 공개 주소 → :10000) */
export function adminUrlFor(seniorUrl) {
  const u = new URL(seniorUrl)
  u.port = u.port === '8180' ? '8181' : '10000'
  return u.origin
}

/**
 * 담당자(demo-activity)가 오늘 순회의 출석을 모두 확정(요청이 있으면 출석 확인, 없으면 대리 출석)하고 출발을 확인한다.
 * 활동 시작은 출발 확인 뒤에만 되므로(기능정의서 SEN-13·ACC-02) 시니어 화면 시험이 시작 전에 부른다.
 */
export async function confirmAttendanceAndDeparture(browser, adminUrl) {
  const env = serviceEnv()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' })
  const a = await ctx.newPage()
  try {
    await a.goto(adminUrl, { waitUntil: 'networkidle' })
    await a.getByLabel('아이디').fill('demo-activity')
    await a.getByLabel('비밀번호').fill(env.ADMIN_PASSWORD)
    await a.getByRole('button', { name: '로그인', exact: true }).click()
    await a.getByRole('navigation', { name: '주 메뉴' }).waitFor({ timeout: 15000 })
    // 로그인 뒤 순회 목록이 늦게 도착할 수 있으므로 순회 카드('1조 · …')가 보일 때까지 기다린다
    await a.locator('main').getByText(/\d+조 · /).first().waitFor({ timeout: 15000 })
    await a.waitForLoadState('networkidle')
    const button = (name) => a.locator('main').getByRole('button', { name, exact: true }).first()
    for (let i = 0; i < 3 && (await button('출석 확인').count()); i++) {
      await button('출석 확인').click()
      await a.waitForLoadState('networkidle')
      await a.waitForTimeout(1000)
    }
    for (let i = 0; i < 3 && (await button('대리 출석').count()); i++) {
      await button('대리 출석').click()
      const d = a.getByRole('dialog')
      await d.locator('input, textarea').first().fill('시험: 센터에서 직접 확인')
      await d.getByRole('button', { name: '대리 출석', exact: true }).click()
      await a.waitForLoadState('networkidle')
      await a.waitForTimeout(1000)
    }
    if (await button('출발 확인').count()) {
      await button('출발 확인').click()
      await a.waitForLoadState('networkidle')
      await a.waitForTimeout(800)
    }
    await a.screenshot({ path: path.join(SHOTS, 'helper-출석출발확인.png') })
  } finally {
    await ctx.close()
  }
}
