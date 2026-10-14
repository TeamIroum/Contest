// 영상 제작용 디자인 에셋: 서비스 로고·팀 로고·컬러 표·서버/AI 아이콘·'확인 필요' 태그를 투명 배경 PNG와 SVG로 만든다.
// 앱과 같은 색(service/senior/src/index.css)과 글꼴(Pretendard)로 그린다.
//   node make-assets.mjs        # 결과: shots/에셋/ (Git 제외)
// 팀 로고는 GitHub TeamIroum 계정 이미지를 받는다.
import { chromium } from 'playwright'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const OUT = path.join(HERE, 'shots', '에셋')
mkdirSync(OUT, { recursive: true })
const FONT = '<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/pretendard@1.3.9/dist/web/variable/pretendardvariable.min.css">'
const FAMILY = "'Pretendard Variable', Pretendard, 'Malgun Gothic', sans-serif"

// 앱 색 (시니어 앱 index.css의 --color-*)
const css = readFileSync(path.join(HERE, '..', 'senior', 'src', 'index.css'), 'utf8')
const COLORS = [...css.matchAll(/--color-([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => ({ name: m[1], hex: m[2].toUpperCase() }))
const C = Object.fromEntries(COLORS.map((c) => [c.name, c.hex]))
const NOTE = {
  brand: '주색 (버튼·강조·아이콘 바탕)', 'brand-700': '주색 진하게 (눌림)', 'brand-600': '주색 밝게 (테두리 강조)', 'brand-100': '주색 연하게', 'brand-50': '주색 아주 연하게 (선택 배경)',
  ink: '본문 글자', sub: '보조 글자', mute: '흐린 글자', line: '구분선', 'line-strong': '진한 구분선', bg: '화면 바탕', surface: '카드 바탕',
  mint: '하늘·풍경 보조', sky: '하늘·풍경 보조', g3: '긴급(빨강)', 'g3-soft': '긴급 배경', 'g3-ink': '긴급 글자',
  g2: '확인 필요(주황)', 'g2-soft': '확인 필요 배경', 'g2-ink': '확인 필요 글자', g1: '관찰(보라)', 'g1-soft': '관찰 배경', 'g1-ink': '관찰 글자',
  g0: '정상·완료(초록)', 'g0-soft': '정상 배경', 'g0-ink': '정상 글자',
}

// 앱 아이콘과 같은 모양: 파란 둥근 사각형(위→아래 그라데이션) + 흰 선 그림 (24x24 좌표계)
const tile = (glyph, size = 512) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 512 512">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3d78f0"/><stop offset="1" stop-color="#2659d6"/></linearGradient></defs>
<rect width="512" height="512" rx="116" fill="url(#g)"/>
<g transform="translate(76 76) scale(15)" fill="none" stroke="#fff" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">${glyph}</g></svg>`
const GLYPH = {
  camera: '<path d="M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z"/><circle cx="12" cy="13" r="3.6"/>',
  // 서버: 두 칸 랙 + 표시등
  server: '<rect x="3.5" y="3.5" width="17" height="7" rx="1.6"/><rect x="3.5" y="13.5" width="17" height="7" rx="1.6"/><path d="M7 7h.01M7 17h.01M11 7h6M11 17h6"/>',
  // AI 판독: 반짝이는 별 (큰 별 + 작은 별, 흰색으로 채움)
  ai: '<path d="M10 3.5c.7 4 2.5 5.8 6.5 6.5-4 .7-5.8 2.5-6.5 6.5-.7-4-2.5-5.8-6.5-6.5 4-.7 5.8-2.5 6.5-6.5Z" fill="#fff" stroke="none"/><path d="M17.5 13.5c.35 2 1.25 2.9 3.25 3.25-2 .35-2.9 1.25-3.25 3.25-.35-2-1.25-2.9-3.25-3.25 2-.35 2.9-1.25 3.25-3.25Z" fill="#fff" stroke="none"/>',
}

const browser = await chromium.launch()
const page = await browser.newPage({ deviceScaleFactor: 1 })
/** HTML 조각을 투명 배경 PNG로 (scale배 해상도) */
async function png(name, body, scale = 3) {
  await page.setViewportSize({ width: 1600, height: 900 })
  await page.setContent(`<!doctype html><html><head><meta charset="utf-8">${FONT}<style>html,body{margin:0;background:transparent}
    #a{display:inline-block;font-family:${FAMILY}}</style></head><body><div id="a">${body}</div></body></html>`)
  await page.evaluate(() => document.fonts.ready)
  const el = page.locator('#a')
  const box = await el.boundingBox()
  const p2 = await browser.newPage({ deviceScaleFactor: scale, viewport: { width: Math.ceil(box.width) + 2, height: Math.ceil(box.height) + 2 } })
  await p2.setContent(await page.content())
  await p2.evaluate(() => document.fonts.ready)
  await p2.locator('#a').screenshot({ path: path.join(OUT, name), omitBackground: true })
  await p2.close()
  console.log('  ', name)
}

console.log('▶ 서비스 로고')
writeFileSync(path.join(OUT, '시니어동네점검단_아이콘.svg'), tile(GLYPH.camera))
await png('시니어동네점검단_아이콘.png', tile(GLYPH.camera, 512), 2)
for (const [suffix, ink] of [['', C.ink], ['_흰글자', '#FFFFFF']]) {
  await png(`시니어동네점검단_로고${suffix}.png`, `<div style="display:flex;align-items:center;gap:22px;padding:8px">
    ${tile(GLYPH.camera, 120)}<span style="font-size:76px;font-weight:800;letter-spacing:-0.04em;color:${ink}">시니어 동네 점검단</span></div>`, 3)
}

console.log('▶ 팀 로고 (GitHub TeamIroum)')
const avatar = await (await fetch('https://avatars.githubusercontent.com/u/336828617?v=4&s=1024')).arrayBuffer()
writeFileSync(path.join(OUT, '이로움_로고.png'), Buffer.from(avatar))
console.log('   이로움_로고.png')

console.log('▶ 서버·AI 판독 아이콘')
for (const [k, label] of [['server', '서버'], ['ai', 'AI판독']]) {
  writeFileSync(path.join(OUT, `아이콘_${label}.svg`), tile(GLYPH[k]))
  await png(`아이콘_${label}.png`, tile(GLYPH[k], 512), 2)
}

console.log("▶ '확인 필요' 태그 (관리자 웹 Pill amber와 같은 모양)")
const tag = (text, bg, fg, scale) => `<span style="display:inline-block;border-radius:999px;padding:${2 * scale}px ${10 * scale}px;font-size:${12 * scale}px;line-height:${16 * scale}px;font-weight:700;background:${bg};color:${fg};white-space:nowrap">${text}</span>`
await png('태그_확인필요.png', tag('확인 필요', C['g2-soft'], C['g2-ink'], 4), 2)
await png('태그_확인필요_진한주황.png', tag('확인 필요', C.g2, '#FFFFFF', 4), 2)
writeFileSync(path.join(OUT, '태그_확인필요.svg'), `<svg xmlns="http://www.w3.org/2000/svg" width="176" height="64" viewBox="0 0 176 64">
<rect width="176" height="64" rx="32" fill="${C['g2-soft']}"/>
<text x="88" y="42" text-anchor="middle" font-family="Pretendard, 'Malgun Gothic', sans-serif" font-size="28" font-weight="700" fill="${C['g2-ink']}">확인 필요</text></svg>`)

console.log('▶ 컬러 표')
writeFileSync(path.join(OUT, '컬러.md'), ['# 시니어 동네 점검단 앱·웹 컬러 (HEX)', '', '| 이름 | HEX | 쓰임 |', '|---|---|---|',
  ...COLORS.map((c) => `| ${c.name} | ${c.hex} | ${NOTE[c.name] ?? ''} |`), '', '글꼴: Pretendard (없으면 맑은 고딕)', ''].join('\n'))
await png('컬러표.png', `<div style="display:grid;grid-template-columns:repeat(4,240px);gap:14px;padding:20px;background:#fff;border-radius:16px">
  ${COLORS.map((c) => `<div style="border:1px solid #e8ebf0;border-radius:12px;overflow:hidden"><div style="height:70px;background:${c.hex}"></div>
  <div style="padding:8px 10px;font-size:15px;color:#191f28"><b>${c.hex}</b><br><span style="color:#4e5968">${NOTE[c.name] ?? c.name}</span></div></div>`).join('')}</div>`, 2)

await browser.close()
console.log(`완료: ${OUT}`)
