import { useMemo, useState } from 'react'
import { KIND_NAME, type Kind } from './Photo'
import {
  DECISION_NAME,
  DEPTS,
  DEPT_OF,
  FOLLOW_UP_NAME,
  ISSUE_STATUS_NAME,
  PAST,
  REVIEW_CATEGORY_NAME,
  REVIEW_TYPE_NAME,
  fac,
  pName,
  type Issue,
  type Patrol,
  type ReviewTask,
  type Team,
} from './data'
import { reviewTitle } from './Review'
import { Button, Card, PageHead, Stat, Tabs } from './ui'
import { parseAt } from './util'
import { download, makeXlsx, type Cell } from './xlsx'

// 사업 총괄 · 통계·보고 (API 11절)
// 활동 시간·출결은 담당자 확인 기준. 사진 수 / 점검 시설 수 / 사건 수 / 건너뜀 / 미방문을 따로 센다.

type Period = 'week' | 'two' | 'all'
const RANGE: Record<Period, [string, string, string]> = {
  week: ['10/06', '10/12', '이번 주 (10/06 ~ 10/12)'],
  two: ['09/29', '10/12', '최근 2주 (09/29 ~ 10/12)'],
  all: ['09/01', '10/12', '전체 기간'],
}
const inR = (at: string | undefined, p: Period) => {
  if (!at) return false
  const t = parseAt(at).getTime()
  return t >= parseAt(RANGE[p][0]).getTime() && t <= parseAt(RANGE[p][1] + ' 23:59').getTime()
}

export function Stats({ reviews, issues, patrols, teams }: { reviews: ReviewTask[]; issues: Issue[]; patrols: Patrol[]; teams: Team[] }) {
  const [p, setP] = useState<Period>('two')

  const m = useMemo(() => {
    // 오늘 끝난 순회도 활동 기록에 더한다
    const todayDone = patrols
      .filter((x) => x.date === '10/12' && (x.status === 'COMPLETED' || x.status === 'ABORTED'))
      .map((x) => ({
        date: x.date,
        team: teams.find((t) => t.id === x.teamId)?.name ?? '-',
        members: x.members.map((mb) => pName(mb.id)),
        minutes: 0,
        photos: x.progress.captured,
        facilities: x.progress.captured + x.progress.review,
        skipped: x.progress.skipped,
        notVisited: 0,
        status: x.status as 'COMPLETED' | 'ABORTED',
      }))
    const acts = [...PAST, ...todayDone].filter((a) => inR(a.date, p))
    const rv = reviews.filter((r) => inR(r.at, p))
    const created = issues.filter((i) => inR(i.createdAt, p))
    const repairsDone = issues.flatMap((i) => i.repairs.filter((r) => inR(r.completedAt, p)).map((r) => ({ i, r })))
    const again = issues.flatMap((i) => i.repairs.filter((r) => r.round > 1 && inR(r.requestedAt, p)).map((r) => ({ i, r })))
    const closed = issues.filter((i) => inR(i.closedAt, p))
    return {
      acts,
      patrols: acts.length,
      aborted: acts.filter((a) => a.status === 'ABORTED').length,
      people: acts.reduce((s, a) => s + a.members.length, 0),
      facilities: acts.reduce((s, a) => s + a.facilities, 0),
      skipped: acts.reduce((s, a) => s + a.skipped, 0),
      notVisited: acts.reduce((s, a) => s + a.notVisited, 0),
      rv,
      rvDone: rv.filter((r) => r.status === 'RESOLVED').length,
      rvConfirmed: rv.filter((r) => r.decision?.decision === 'ISSUE_CONFIRMED').length,
      created,
      repairsDone,
      again,
      closed,
    }
  }, [reviews, issues, patrols, teams, p])

  const deptOf = (i: Issue) => i.repairs.at(-1)?.dept ?? DEPT_OF[fac(i.facilityId).kind]
  const byDept = DEPTS.map((d) => {
    const l = issues.filter((i) => deptOf(i) === d)
    return { d, open: l.filter((i) => i.status !== 'CLOSED').length, closed: l.filter((i) => i.status === 'CLOSED').length, again: l.reduce((s, i) => s + Math.max(0, i.repairs.length - 1), 0) }
  }).sort((a, b) => b.open + b.closed - (a.open + a.closed)) // 많은 순
  const dmax = Math.max(1, ...byDept.map((d) => d.open + d.closed))
  const kinds = (Object.keys(KIND_NAME) as Kind[]).map((k) => ({ k, n: issues.filter((i) => fac(i.facilityId).kind === k).length })).filter((x) => x.n > 0).sort((a, b) => b.n - a.n) // 많은 순
  const kmax = Math.max(1, ...kinds.map((k) => k.n))

  // AI /report/narrative 에 넘기는 확정 집계값. AI 장애 시 아래 고정 문장을 그대로 쓴다.
  const items: [string, string, number, string][] = [
    ['patrols', '순회 횟수', m.patrols, '회'],
    ['participant_days', '참여 연인원', m.people, '명'],
    ['facilities', '점검 시설', m.facilities, '곳'],
    ['skipped', '건너뜀', m.skipped, '곳'],
    ['not_visited', '미방문', m.notVisited, '곳'],
    ['issues_new', '신규 사건', m.created.length, '건'],
    ['repairs_done', '보수 완료', m.repairsDone.length, '건'],
    ['issues_closed', '종결', m.closed.length, '건'],
    ['repair_again', '재보수 요청', m.again.length, '건'],
  ]
  const report =
    `${RANGE[p][2]} 동안 ${m.patrols}회 순회에 연인원 ${m.people}명이 참여했습니다. ` +
    `시설 ${m.facilities}곳을 점검했고, 건너뜀 ${m.skipped}곳 · 미방문 ${m.notVisited}곳입니다. ` +
    `신규 사건 ${m.created.length}건, 보수 완료 ${m.repairsDone.length}건, 종결 ${m.closed.length}건이며 재보수 요청은 ${m.again.length}건입니다.`

  function exportXlsx() {
    const [s, e] = RANGE[p]
    const summary: Cell[][] = [['항목', '값', '단위'], ['보고 기간', RANGE[p][2], ''], ...items.map(([, l, v, u]) => [l, v, u] as Cell[]), ['중도 종료 순회', m.aborted, '회']]
    // 5개 시트: 요약 / 점검 내역 / 부서별 집계 / 수리 확인 내역 / 점검원 활동 (API 11.1)
    // 위험도·사진 수·활동 시간 계산은 넣지 않는다(D-07·D-10)
    const inspections: Cell[][] = [
      ['검토 번호', '종류', '일시', '시설', '장소', '내용', '검토 분류', '상태', '판정', '추가 확인', '사유', '연결 문제'],
      ...m.rv.map((r) => {
        const f = fac(r.facilityId)
        return [
          r.id, REVIEW_TYPE_NAME[r.type], r.at, KIND_NAME[f.kind], f.name, reviewTitle(r),
          r.analysis ? REVIEW_CATEGORY_NAME[r.analysis.reviewCategory] : '', r.status === 'RESOLVED' ? '처리됨' : '처리 전',
          r.decision ? DECISION_NAME[r.decision.decision] : '', r.decision?.followUp ? FOLLOW_UP_NAME[r.decision.followUp] : '', r.decision?.reason ?? '', r.decision?.issueIds.join(', ') ?? '',
        ]
      }),
    ]
    const dept: Cell[][] = [['부서', '진행 중', '종결', '재보수 요청'], ...byDept.map((d) => [d.d, d.open, d.closed, d.again])]
    const repairs: Cell[][] = [
      ['문제 번호', '시설', '장소', '내용', '상태', '담당 부서', '접수', '보수 횟수', '마지막 보수 완료', '종결'],
      ...issues.map((i) => {
        const f = fac(i.facilityId)
        return [i.id, KIND_NAME[f.kind], f.name, i.title, ISSUE_STATUS_NAME[i.status], deptOf(i), i.createdAt, i.repairs.length, i.repairs.at(-1)?.completedAt ?? '', i.closedAt ?? '']
      }),
    ]
    // 점검원 활동: 개인별 참여 기록만 (경과 시간·인정 실적 계산 없음)
    const crew: Cell[][] = [
      ['날짜', '조', '참여자', '결과', '점검 시설', '건너뜀', '미방문'],
      ...m.acts.flatMap((a) => a.members.map((name) => [a.date, a.team, name, a.status === 'ABORTED' ? '중도 종료' : '완료', a.facilities, a.skipped, a.notVisited] as Cell[])),
    ]
    download(
      makeXlsx([
        { name: '요약', rows: summary },
        { name: '점검 내역', rows: inspections },
        { name: '부서별 집계', rows: dept },
        { name: '수리 확인 내역', rows: repairs },
        { name: '점검원 활동', rows: crew },
      ]),
      `동네점검단_실적_2026-${s.replace('/', '-')}_2026-${e.replace('/', '-')}.xlsx`,
    )
  }

  return (
    <>
      <PageHead
        title="통계·보고"
        sub={`${RANGE[p][2]} · 시연 데이터 기준`}
        right={
          <>
            <Tabs
              value={p}
              onChange={setP}
              options={[
                ['week', '이번 주'],
                ['two', '최근 2주'],
                ['all', '전체'],
              ]}
            />
            <Button variant="primary" onClick={exportXlsx}>
              엑셀 다운로드
            </Button>
          </>
        }
      />
      <div className="mb-3 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat icon="route" label="순회" value={`${m.patrols}회`} sub={`중도 종료 ${m.aborted}회`} />
        <Stat icon="people" label="참여 연인원" value={`${m.people}명`} sub="담당자 출석 확인 기준" />
        <Stat icon="route" label="점검 시설" value={`${m.facilities}곳`} sub={`건너뜀 ${m.skipped} · 미방문 ${m.notVisited}`} />
        <Stat icon="review" label="검토" value={`처리 ${m.rvDone} / 전체 ${m.rv.length}`} sub={`이상 확인 ${m.rvConfirmed}건`} />
      </div>
      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat icon="alert" label="신규 사건" value={`${m.created.length}건`} tone="amber" />
        <Stat icon="wrench" label="보수 완료" value={`${m.repairsDone.length}건`} tone="green" />
        <Stat icon="check" label="종결" value={`${m.closed.length}건`} tone="green" />
        <Stat icon="repeat" label="재보수 요청" value={`${m.again.length}건`} tone="amber" />
      </div>
      <div className="grid items-start gap-4 xl:grid-cols-2">
        <Card title="부서별 사건 (전체)">
          <div className="grid gap-3 p-4">
            {byDept.map((d) => (
              <div key={d.d} className="grid grid-cols-[96px_1fr_auto] items-center gap-3 text-sm">
                <span className="truncate">{d.d}</span>
                <div className="flex h-4 overflow-hidden rounded bg-bg" role="img" aria-label={`진행 중 ${d.open}, 종결 ${d.closed}`}>
                  <div className="bg-g2" style={{ width: `${(d.open / dmax) * 100}%` }} />
                  <div className="bg-g0" style={{ width: `${(d.closed / dmax) * 100}%` }} />
                </div>
                <span className="text-right font-semibold tabular-nums">
                  {d.open + d.closed}
                  <span className="ml-1.5 text-xs font-normal text-mute">(진행 {d.open} · 종결 {d.closed})</span>
                  {d.again > 0 && <span className="ml-1.5 rounded-full bg-g2-soft px-2 py-0.5 text-xs font-bold text-g2-ink">재보수 {d.again}</span>}
                </span>
              </div>
            ))}
            <div className="flex gap-4 pt-1 text-xs text-mute">
              {[
                ['bg-g2', '진행 중'],
                ['bg-g0', '종결'],
              ].map(([c, l]) => (
                <span key={l} className="flex items-center gap-1.5">
                  <i className={`size-2.5 rounded-sm ${c}`} />
                  {l}
                </span>
              ))}
            </div>
          </div>
        </Card>
        <Card title="시설별 사건 (전체)">
          <div className="grid gap-2.5 p-4">
            {kinds.map(({ k, n }) => (
              <div key={k} className="grid grid-cols-[96px_1fr_32px] items-center gap-3 text-sm">
                <span>{KIND_NAME[k]}</span>
                <div className="h-4 overflow-hidden rounded bg-bg">
                  <div className="h-full bg-brand-600" style={{ width: `${(n / kmax) * 100}%` }} />
                </div>
                <span className="text-right font-semibold tabular-nums">{n}</span>
              </div>
            ))}
          </div>
        </Card>
        <Card title="보고 문장" className="xl:col-span-2">
          <div className="p-4">
            <p className="m-0 rounded-lg bg-bg p-4 text-sm leading-relaxed">{report}</p>
            <p className="m-0 mt-2 text-xs text-mute">
              확정된 집계값만 AI 문장 다듬기에 넘기며, AI를 쓸 수 없으면 이 고정 문장을 씁니다. 엑셀은 요약 · 점검 내역 · 부서별 집계 · 수리 확인 내역 · 점검원 활동 5개 시트입니다.
            </p>
          </div>
        </Card>
      </div>
    </>
  )
}
