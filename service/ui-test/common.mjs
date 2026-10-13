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
  page.on('console', (m) => m.type() === 'error' && problems.push(`콘솔: ${m.text().slice(0, 200)}`))
  page.on('pageerror', (e) => problems.push(`화면 오류: ${e.message.slice(0, 200)}`))
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) {
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
