// 통계·보고 (실제 모드, OPS-03): 업무 DB의 기간 집계. 예시 모드는 Stats.tsx가 그린다.
// 사진 수 / 점검 장소 수 / 사건 수 / 출결(담당자 확인)을 따로 센다. 사진 수로 활동 시간을 계산하지 않는다(D-07·D-10).
import { useEffect, useState } from 'react'
import { KIND_NAME } from './Photo'
import { ISSUE_STATUS_NAME, KIND_OF_TYPE, type IssueStatus } from './data'
import { Button, Card, Empty, PageHead, Stat, Tabs } from './ui'
import { download, makeXlsx, type Cell } from './xlsx'
import * as real from './api/real'

type Period = 'week' | 'two' | 'all'
const daysAgo = (n: number) => new Date(Date.now() + 9 * 3600_000 - n * 86400_000).toISOString().slice(0, 10)
const RANGE: Record<Period, () => [string, string]> = {
  week: () => [daysAgo(6), real.todayKst()],
  two: () => [daysAgo(13), real.todayKst()],
  all: () => ['2026-09-01', real.todayKst()],
}
const LABEL: Record<Period, string> = { week: '최근 7일', two: '최근 2주', all: '전체 기간' }
const typeName = (t: string) => KIND_NAME[KIND_OF_TYPE[t]] ?? t

export function RealStats() {
  const [p, setP] = useState<Period>('two')
  const [r, setR] = useState<real.StatsReport | null>(null)
  const [narr, setNarr] = useState<real.Narrative | null>(null)
  const [making, setMaking] = useState(false)
  useEffect(() => {
    let alive = true
    const [from, to] = RANGE[p]()
    real
      .loadStats(from, to)
      .then((x) => {
        if (!alive) return
        setR(x)
        setNarr(null)
      })
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [p])
  const s = r?.summary
  const range = r ? `${real.md(r.from)} ~ ${real.md(r.to)}` : ''
  const dmax = Math.max(1, ...(r?.byDepartment.map((d) => d.open + d.closed) ?? []))
  const kmax = Math.max(1, ...(r?.byFacilityType.map((k) => k.issues) ?? []))
  const report = s
    ? `${LABEL[p]}(${range}) 동안 ${s.patrols}회 순회에 연인원 ${s.participantDays}명이 참여했습니다. ` +
      `사진 ${s.photos}장으로 장소 ${s.facilitiesChecked}곳을 점검했고, 건너뜀 ${s.skipped}곳 · 미방문 ${s.notVisited}곳입니다. ` +
      `신규 사건 ${s.issuesNew}건, 보수 완료 ${s.repairsDone}건, 종결 ${s.issuesClosed}건이며 재보수 요청은 ${s.repairAgain}건입니다.`
    : ''

  function exportXlsx() {
    if (!r || !s) return
    const summary: Cell[][] = [
      ['항목', '값', '단위'],
      ['보고 기간', `${r.from} ~ ${r.to}`, ''],
      ['순회 횟수', s.patrols, '회'],
      ['중도 종료 순회', s.aborted, '회'],
      ['참여 연인원', s.participantDays, '명'],
      ['사진', s.photos, '장'],
      ['점검 장소', s.facilitiesChecked, '곳'],
      ['건너뜀', s.skipped, '곳'],
      ['미방문', s.notVisited, '곳'],
      ['검토', s.reviews, '건'],
      ['검토 처리', s.reviewsResolved, '건'],
      ['이상 확인', s.reviewsConfirmed, '건'],
      ['신규 사건', s.issuesNew, '건'],
      ['보수 완료', s.repairsDone, '건'],
      ['종결', s.issuesClosed, '건'],
      ['재보수 요청', s.repairAgain, '건'],
    ]
    const dept: Cell[][] = [['부서', '진행 중', '종결', '재보수 요청'], ...r.byDepartment.map((d) => [d.department, d.open, d.closed, d.repairAgain] as Cell[])]
    const issues: Cell[][] = [
      ['사건 번호', '시설', '장소', '내용', '상태', '담당 부서', '접수', '보수 요청 횟수', '종결'],
      ...r.issues.map((i) => [
        real.shortId(i.id),
        typeName(i.facilityType),
        i.facilityName,
        i.summary,
        ISSUE_STATUS_NAME[i.status as IssueStatus] ?? i.status,
        i.department,
        real.mdhm(i.openedAt),
        i.repairs,
        i.closedAt ? real.mdhm(i.closedAt) : '',
      ] as Cell[]),
    ]
    // 점검원 활동: 개인별 참여 기록만 (경과 시간·인정 실적 계산 없음)
    const crew: Cell[][] = [
      ['날짜', '조', '코스', '참여자', '결과', '점검 장소', '건너뜀', '미방문'],
      ...r.activities.flatMap((a) => a.members.map((name) => [a.date, a.team, a.route, name, a.aborted ? '중도 종료' : '완료', a.facilitiesChecked, a.skipped, a.notVisited] as Cell[])),
    ]
    download(
      makeXlsx([
        { name: '요약', rows: summary },
        { name: '부서별 집계', rows: dept },
        { name: '사건·보수 내역', rows: issues },
        { name: '점검원 활동', rows: crew },
      ]),
      `동네점검단_실적_${r.from}_${r.to}.xlsx`,
    )
    // 누가 어떤 기간을 내려받았는지 감사 이력에 남긴다 (전화번호는 엑셀에 넣지 않음)
    real.recordExport(r.from, r.to, ['요약', '부서별 집계', '사건·보수 내역', '점검원 활동']).catch(() => undefined)
  }

  return (
    <>
      <PageHead
        title="통계·보고"
        sub={`${LABEL[p]}${range ? ` (${range})` : ''} · 업무 서버 기록 기준`}
        help="담당자가 확인한 출결과 서버에 기록된 사진·장소·사건 수를 따로 셉니다. 사진 수로 활동 시간을 계산하지 않습니다. 부서별·시설별 사건은 기간과 상관없이 전체입니다."
        right={
          <>
            <Tabs
              value={p}
              onChange={setP}
              options={[
                ['week', '최근 7일'],
                ['two', '최근 2주'],
                ['all', '전체'],
              ]}
            />
            <Button variant="primary" onClick={exportXlsx} disabled={!r}>
              엑셀 다운로드
            </Button>
          </>
        }
      />
      {!s ? (
        <Card>
          <Empty>집계를 불러오는 중입니다.</Empty>
        </Card>
      ) : (
        <>
          <div className="mb-3 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat icon="route" label="순회" value={`${s.patrols}회`} sub={`중도 종료 ${s.aborted}회`} />
            <Stat icon="people" label="참여 연인원" value={`${s.participantDays}명`} sub="담당자 출석 확인 기준" />
            <Stat icon="route" label="점검 장소" value={`${s.facilitiesChecked}곳`} sub={`사진 ${s.photos}장 · 건너뜀 ${s.skipped} · 미방문 ${s.notVisited}`} />
            <Stat icon="review" label="검토" value={`처리 ${s.reviewsResolved} / 전체 ${s.reviews}`} sub={`이상 확인 ${s.reviewsConfirmed}건`} />
          </div>
          <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat icon="alert" label="신규 사건" value={`${s.issuesNew}건`} tone="amber" />
            <Stat icon="wrench" label="보수 완료" value={`${s.repairsDone}건`} tone="green" />
            <Stat icon="check" label="종결" value={`${s.issuesClosed}건`} tone="green" />
            <Stat icon="repeat" label="재보수 요청" value={`${s.repairAgain}건`} tone="amber" />
          </div>
          <div className="grid items-start gap-4 xl:grid-cols-2">
            <Card title="부서별 사건 (전체)">
              {r!.byDepartment.length === 0 ? (
                <Empty>아직 사건이 없습니다.</Empty>
              ) : (
                <div className="grid gap-3 p-4">
                  {r!.byDepartment.map((d) => (
                    <div key={d.department} className="grid grid-cols-[120px_1fr_auto] items-center gap-3 text-sm">
                      <span className="truncate">{d.department}</span>
                      <div className="flex h-4 overflow-hidden rounded bg-bg" role="img" aria-label={`진행 중 ${d.open}, 종결 ${d.closed}`}>
                        <div className="bg-g2" style={{ width: `${(d.open / dmax) * 100}%` }} />
                        <div className="bg-g0" style={{ width: `${(d.closed / dmax) * 100}%` }} />
                      </div>
                      <span className="text-right font-semibold tabular-nums">
                        {d.open + d.closed}
                        <span className="ml-1.5 text-xs font-normal text-mute">
                          (진행 {d.open} · 종결 {d.closed})
                        </span>
                        {d.repairAgain > 0 && <span className="ml-1.5 rounded-full bg-g2-soft px-2 py-0.5 text-xs font-bold text-g2-ink">재보수 {d.repairAgain}</span>}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </Card>
            <Card title="시설별 사건 (전체)">
              {r!.byFacilityType.length === 0 ? (
                <Empty>아직 사건이 없습니다.</Empty>
              ) : (
                <div className="grid gap-2.5 p-4">
                  {r!.byFacilityType.map((k) => (
                    <div key={k.facilityType} className="grid grid-cols-[96px_1fr_32px] items-center gap-3 text-sm">
                      <span>{typeName(k.facilityType)}</span>
                      <div className="h-4 overflow-hidden rounded bg-bg">
                        <div className="h-full bg-brand-600" style={{ width: `${(k.issues / kmax) * 100}%` }} />
                      </div>
                      <span className="text-right font-semibold tabular-nums">{k.issues}</span>
                    </div>
                  ))}
                </div>
              )}
            </Card>
            <Card title="보고 문장" className="xl:col-span-2">
              <div className="p-4">
                <p className="m-0 rounded-lg bg-bg p-4 text-sm leading-relaxed" data-testid="report-sentence">
                  {report}
                </p>
                <p className="m-0 mt-2 text-xs text-mute">집계값으로 만든 고정 문장입니다. 엑셀은 요약 · 부서별 집계 · 사건·보수 내역 · 점검원 활동 4개 시트입니다.</p>
                <div className="mt-4 flex flex-wrap items-center gap-2">
                  <Button
                    disabled={making}
                    onClick={async () => {
                      setMaking(true)
                      let made: real.Narrative | null = null
                      await real.run(async () => {
                        made = await real.makeNarrative(r!.from, r!.to)
                      })
                      setNarr(made)
                      setMaking(false)
                    }}
                  >
                    {making ? 'AI가 문장을 쓰는 중…' : 'AI로 보고 문장 만들기'}
                  </Button>
                  <span className="text-xs text-mute">확정 집계값만 AI에 넘기고, 숫자가 하나라도 다르면 고정 문장을 씁니다.</span>
                </div>
                {narr && (
                  <div className="mt-3 rounded-lg border border-line p-4" data-testid="ai-narrative">
                    <p className="m-0 mb-2 flex items-center gap-2 text-xs text-mute">
                      <span className={`rounded-full px-2 py-0.5 font-bold ${narr.source === 'llm' ? 'bg-brand-50 text-brand' : 'bg-bg text-sub'}`}>
                        {narr.source === 'llm' ? 'AI 문장 · 숫자 검증 통과' : '고정 문장'}
                      </span>
                      {real.mdhm(narr.generatedAt)} · {narr.revision}번째
                      {narr.note && <span>· {narr.note}</span>}
                    </p>
                    <p className="m-0 text-sm leading-relaxed whitespace-pre-line">{narr.text}</p>
                  </div>
                )}
              </div>
            </Card>
          </div>
        </>
      )}
    </>
  )
}
