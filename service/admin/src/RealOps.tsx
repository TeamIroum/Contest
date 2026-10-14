// 운영 화면 (실제 모드): 알림(OPS-05), 감사 이력(OPS-06), 기관·거점(OPS-01).
import { useEffect, useState } from 'react'
import { Button, Card, Empty, Field, Modal, PageHead, Pill, inputCls } from './ui'
import * as real from './api/real'

/* ---------------- 알림 (머리줄 버튼) ---------------- */

const NOTICE_VIEW: Record<string, string> = {
  REVIEW_REQUESTED: 'review',
  ATTENDANCE_REQUESTED: 'today',
  RETURN_REQUESTED: 'today',
  REINSPECTION_REQUESTED: 'assign',
  FACILITY_REMOVED: 'facilities',
}

/** 화면 안 알림. 20초마다 새로 받는다. 누르면 읽음으로 바꾸고 그 화면으로 간다 */
export function NotificationMenu({ onGo }: { onGo: (view: string) => void }) {
  const [open, setOpen] = useState(false)
  const [box, setBox] = useState<{ items: real.AppNotification[]; unread: number }>({ items: [], unread: 0 })
  const load = () =>
    real
      .loadNotifications()
      .then(setBox)
      .catch(() => undefined)
  useEffect(() => {
    load()
    const t = setInterval(load, 20000)
    return () => clearInterval(t)
  }, [])
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-label={`알림 ${box.unread}건`}
        className={`flex h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${box.unread ? 'border-g3 bg-g3 text-white' : 'border-line-strong bg-surface text-sub'}`}
      >
        알림 {box.unread}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} aria-hidden />
          <div className="absolute right-0 z-40 mt-2 w-[340px] rounded-[18px] bg-surface p-3 shadow-pop" role="dialog" aria-label="알림">
            <div className="mb-2 flex items-center justify-between px-1">
              <p className="m-0 text-sm font-extrabold text-ink">알림</p>
              {box.unread > 0 && (
                <button type="button" className="text-xs font-semibold text-brand" onClick={() => real.readAllNotifications().then(load)}>
                  모두 읽음
                </button>
              )}
            </div>
            {box.items.length === 0 ? (
              <p className="m-0 px-1 py-3 text-sm text-mute">새 알림이 없습니다.</p>
            ) : (
              <ul className="m-0 grid max-h-[360px] list-none gap-1 overflow-y-auto p-0">
                {box.items.map((n) => (
                  <li key={n.id}>
                    <button
                      type="button"
                      className={`w-full rounded-xl px-3 py-2 text-left text-sm hover:bg-bg ${n.readAt ? 'text-mute' : 'font-semibold text-ink'}`}
                      onClick={async () => {
                        if (!n.readAt) await real.readNotification(n.id).catch(() => undefined)
                        setOpen(false)
                        load()
                        if (NOTICE_VIEW[n.type]) onGo(NOTICE_VIEW[n.type])
                      }}
                    >
                      {!n.readAt && <span className="mr-1.5 inline-block size-2 rounded-full bg-g3" aria-label="안 읽음" />}
                      {n.message}
                      <span className="block text-xs font-normal text-mute">{real.mdhm(n.createdAt)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <p className="m-0 mt-2 px-1 text-xs text-mute">화면 안 알림만 보냅니다. 문자·푸시 알림은 쓰지 않습니다.</p>
          </div>
        </>
      )}
    </div>
  )
}

/* ---------------- 감사 이력 ---------------- */

const ACTION_NAME: Record<string, string> = {
  PARTICIPANT_CREATED: '참여자 등록',
  PARTICIPANT_UPDATED: '참여자 수정',
  PARTICIPANT_STATUS_CHANGED: '참여자 상태 변경',
  TEAM_CREATED: '조 만들기',
  TEAM_UPDATED: '조 변경',
  TEAM_REASSIGNED: '순회 조원 재편성',
  ROUTE_CREATED: '코스 만들기',
  ROUTE_VERSION_PUBLISHED: '코스 새 버전',
  ROUTE_UPDATED: '코스 수정',
  PATROL_ASSIGNED: '순회 배정',
  PATROL_ROUTE_CHANGED: '순회 코스 변경',
  REINSPECTION_SLOT_ADDED: '재점검 구도 추가',
  REINSPECTION_ASSIGNED: '재점검 배정',
  REINSPECTION_ASSIGNMENT_CANCELLED: '재점검 배정 취소',
  OFFICER_ACCOUNT_ISSUED: '공무원 계정 발급',
  OFFICER_ROLES_CHANGED: '역할 변경',
  OFFICER_PASSWORD_RESET: '비밀번호 초기화',
  OFFICER_UNLOCKED: '잠금 해제',
  OFFICER_STATUS_CHANGED: '계정 상태 변경',
  ORGANIZATION_CREATED: '기관 만들기',
  ORGANIZATION_UPDATED: '기관 수정',
  FACILITY_CREATED: '시설 등록',
  FACILITY_UPDATED: '시설 수정',
  ISSUE_INSPECTION_UNLINKED: '사진 연결 해제',
  REPORT_GENERATED: '보고 문장 생성',
  EXPORT_DOWNLOADED: '엑셀 내려받기',
}
const actionName = (a: string) => ACTION_NAME[a] ?? a
const brief = (v: unknown) => (v == null ? '' : JSON.stringify(v).replace(/[{}"]/g, '').replace(/,/g, ', ').slice(0, 160))

export function RealAudit() {
  const [action, setAction] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<{ items: real.AuditEvent[]; totalElements: number } | null>(null)
  const [actions, setActions] = useState<{ action: string; count: number }[]>([])
  useEffect(() => {
    real
      .loadAuditActions()
      .then(setActions)
      .catch(() => undefined)
  }, [])
  useEffect(() => {
    real
      .loadAudit({ action: action || undefined, from: from || undefined, to: to || undefined, page })
      .then(setData)
      .catch(() => undefined)
  }, [action, from, to, page])
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / 30)) : 1
  return (
    <>
      <PageHead
        title="감사 이력"
        sub="누가 언제 무엇을 바꿨는지 봅니다."
        help="계정·참여자·조·코스·순회·재점검·보고 처리의 수행자, 시각, 사유, 변경 전후가 남습니다. 전화번호·비밀번호·토큰·주소는 기록하지 않고, 남아 있어도 가려서 보여 줍니다."
      />
      <Card>
        <div className="grid gap-3 border-b border-line p-4 sm:grid-cols-3">
          <Field label="작업">
            <select
              value={action}
              onChange={(e) => {
                setAction(e.target.value)
                setPage(0)
              }}
              className={inputCls}
            >
              <option value="">전체</option>
              {actions.map((a) => (
                <option key={a.action} value={a.action}>
                  {actionName(a.action)} ({a.count})
                </option>
              ))}
            </select>
          </Field>
          <Field label="시작일">
            <input type="date" value={from} onChange={(e) => (setFrom(e.target.value), setPage(0))} className={inputCls} />
          </Field>
          <Field label="종료일">
            <input type="date" value={to} onChange={(e) => (setTo(e.target.value), setPage(0))} className={inputCls} />
          </Field>
        </div>
        {!data ? (
          <Empty>불러오는 중입니다.</Empty>
        ) : data.items.length === 0 ? (
          <Empty>기록이 없습니다.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                  {['시각', '수행자', '작업', '사유', '변경 전', '변경 후'].map((h) => (
                    <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr key={e.id} className="border-b border-line align-top last:border-0">
                    <td className="px-4 py-2.5 whitespace-nowrap tabular-nums">{real.mdhm(e.at)}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap">{e.actorName ?? '시스템'}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap">
                      <Pill tone="gray">{actionName(e.action)}</Pill>
                    </td>
                    <td className="px-4 py-2.5 text-sub">{e.reason ?? ''}</td>
                    <td className="max-w-[220px] px-4 py-2.5 text-xs break-all text-mute">{brief(e.before)}</td>
                    <td className="max-w-[260px] px-4 py-2.5 text-xs break-all text-sub">{brief(e.after)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex items-center justify-between border-t border-line px-4 py-2.5 text-xs text-mute">
          <span>총 {data?.totalElements ?? 0}건</span>
          <span className="flex items-center gap-2">
            <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>
              이전
            </Button>
            {page + 1} / {pages}
            <Button size="sm" variant="ghost" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
              다음
            </Button>
          </span>
        </div>
      </Card>
    </>
  )
}

/* ---------------- 기관·거점 ---------------- */

const TYPE_NAME: Record<real.Org['type'], string> = { AGENCY: '수행기관', MEETING_POINT: '집결 거점', DEPARTMENT: '관리 부서' }
const PARENT_TYPE: Record<real.Org['type'], real.Org['type'] | null> = { AGENCY: 'AGENCY', MEETING_POINT: 'AGENCY', DEPARTMENT: 'DEPARTMENT' }

export function RealOrgs() {
  const [list, setList] = useState<real.Org[]>([])
  const [edit, setEdit] = useState<real.Org | 'new' | null>(null)
  const load = () =>
    real
      .loadOrgs()
      .then(setList)
      .catch(() => undefined)
  useEffect(() => {
    load()
    return real.onRealChanged(load)
  }, [])
  return (
    <>
      <PageHead
        title="기관·거점"
        sub="수행기관·집결 거점·관리 부서와 상하위 관계를 관리합니다."
        help="집결 거점은 수행기관 아래에, 수행기관과 관리 부서는 같은 유형 아래에만 둘 수 있습니다. 사용 중지해도 지난 순회·사건 기록은 그대로입니다."
        right={
          <Button variant="primary" onClick={() => setEdit('new')}>
            + 기관 추가
          </Button>
        }
      />
      <Card>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                {['이름', '유형', '상위 기관', '역할 받은 사람', '상태', ''].map((h) => (
                  <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {list.map((o) => (
                <tr key={o.id} className="border-b border-line last:border-0">
                  <td className="px-4 py-2.5 font-semibold">{o.name}</td>
                  <td className="px-4 py-2.5">
                    <Pill tone="gray">{TYPE_NAME[o.type]}</Pill>
                  </td>
                  <td className="px-4 py-2.5 text-sub">{o.parentName ?? '-'}</td>
                  <td className="px-4 py-2.5 tabular-nums">{o.people}명</td>
                  <td className="px-4 py-2.5">
                    <Pill tone={o.status === 'ACTIVE' ? 'green' : 'gray'}>{o.status === 'ACTIVE' ? '사용 중' : '사용 중지'}</Pill>
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <Button size="sm" onClick={() => setEdit(o)}>
                      고치기
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      {edit && <OrgModal org={edit === 'new' ? null : edit} list={list} onClose={() => setEdit(null)} />}
    </>
  )
}

function OrgModal({ org, list, onClose }: { org: real.Org | null; list: real.Org[]; onClose: () => void }) {
  const [type, setType] = useState<real.Org['type']>(org?.type ?? 'MEETING_POINT')
  const [name, setName] = useState(org?.name ?? '')
  const [parentId, setParentId] = useState<string>(org?.parentId ?? '')
  const [status, setStatus] = useState<real.Org['status']>(org?.status ?? 'ACTIVE')
  const parents = list.filter((o) => o.type === PARENT_TYPE[type] && o.id !== org?.id)
  const needParent = type === 'MEETING_POINT'
  const ok = name.trim() && (!needParent || parentId)
  return (
    <Modal
      title={org ? `${org.name} 고치기` : '기관 추가'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button
            variant="primary"
            disabled={!ok}
            onClick={async () => {
              const done = await real.run(() =>
                org
                  ? real.updateOrg(org, { name: name.trim(), status, parentId: parentId || null, changeParent: (parentId || null) !== org.parentId })
                  : real.createOrg({ type, name: name.trim(), parentId: parentId || null }),
              )
              if (done) onClose()
            }}
          >
            저장
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="유형" required>
          <select value={type} disabled={!!org} onChange={(e) => (setType(e.target.value as real.Org['type']), setParentId(''))} className={inputCls}>
            {(Object.keys(TYPE_NAME) as real.Org['type'][]).map((t) => (
              <option key={t} value={t}>
                {TYPE_NAME[t]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="이름" required>
          <input value={name} onChange={(e) => setName(e.target.value)} className={inputCls} maxLength={100} />
        </Field>
        <Field label={needParent ? '상위 수행기관' : '상위 기관 (선택)'} required={needParent}>
          <select value={parentId} onChange={(e) => setParentId(e.target.value)} className={inputCls}>
            {!needParent && <option value="">없음 (최상위)</option>}
            {needParent && !parentId && <option value="">고르세요</option>}
            {parents.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </Field>
        {org && (
          <Field label="상태">
            <select value={status} onChange={(e) => setStatus(e.target.value as real.Org['status'])} className={inputCls}>
              <option value="ACTIVE">사용 중</option>
              <option value="INACTIVE">사용 중지</option>
            </select>
          </Field>
        )}
      </div>
    </Modal>
  )
}

/* ---------------- 시설 대장 (FAC-01) ---------------- */

const FACILITY_TYPES: [string, string][] = [
  ['SIDEWALK', '보도블록'],
  ['DRAIN', '빗물받이'],
  ['BENCH', '벤치'],
  ['SAFETY_FENCE', '안전 펜스'],
  ['EXERCISE_EQUIPMENT', '운동기구'],
  ['PARK_EQUIPMENT', '공원 시설'],
  ['SNOW_REMOVAL_BIN', '제설함'],
  ['SHADE_STRUCTURE', '그늘막'],
]
const facilityTypeName = (t: string) => FACILITY_TYPES.find(([k]) => k === t)?.[1] ?? t

export function RealFacilities() {
  const [list, setList] = useState<real.RealFacility[]>([])
  const [depts, setDepts] = useState<{ id: string; name: string; mine: boolean }[]>([])
  const [edit, setEdit] = useState<real.RealFacility | 'new' | null>(null)
  const load = () =>
    real
      .loadFacilities()
      .then(setList)
      .catch(() => undefined)
  useEffect(() => {
    load()
    real
      .loadFacilityDepartments()
      .then(setDepts)
      .catch(() => undefined)
    return real.onRealChanged(load)
  }, [])
  return (
    <>
      <PageHead
        title="시설 대장"
        sub="우리 부서가 관리하는 시설을 등록하고 고칩니다."
        help="어르신이 '시설이 없어졌어요'로 기록하면 알림이 옵니다. 현장을 확인하고 사용 중지하거나 위치를 고쳐 주세요. 관리 부서를 다른 부서로 넘기면 그 뒤 검토·사건은 새 부서가 봅니다. AI가 추정한 부서로 대장이 바뀌지는 않습니다."
        right={
          <Button variant="primary" onClick={() => setEdit('new')}>
            + 시설 등록
          </Button>
        }
      />
      <Card>
        {list.length === 0 ? (
          <Empty>등록된 시설이 없습니다.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                  {['코드', '종류', '이름', '구역', '좌표', '진행 중 사건', '상태', ''].map((h) => (
                    <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {list.map((f) => (
                  <tr key={f.id} className="border-b border-line last:border-0">
                    <td className="px-4 py-2.5 tabular-nums">{f.code}</td>
                    <td className="px-4 py-2.5">{facilityTypeName(f.type)}</td>
                    <td className="px-4 py-2.5 font-semibold">{f.name}</td>
                    <td className="px-4 py-2.5 text-sub">{f.areaLabel ?? '-'}</td>
                    <td className="px-4 py-2.5 text-xs text-mute tabular-nums">{f.latitude != null ? `${f.latitude}, ${f.longitude}` : '-'}</td>
                    <td className="px-4 py-2.5 tabular-nums">{f.openIssues}건</td>
                    <td className="px-4 py-2.5">
                      <Pill tone={f.status === 'ACTIVE' ? 'green' : 'gray'}>{f.status === 'ACTIVE' ? '사용 중' : '사용 중지'}</Pill>
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <Button size="sm" onClick={() => setEdit(f)}>
                        고치기
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {edit && <FacilityModal facility={edit === 'new' ? null : edit} depts={depts} onClose={() => setEdit(null)} />}
    </>
  )
}

function FacilityModal({ facility, depts, onClose }: { facility: real.RealFacility | null; depts: { id: string; name: string; mine: boolean }[]; onClose: () => void }) {
  const mine = depts.filter((d) => d.mine)
  const [code, setCode] = useState('')
  const [type, setType] = useState('SIDEWALK')
  const [name, setName] = useState(facility?.name ?? '')
  const [area, setArea] = useState(facility?.areaLabel ?? '')
  const [lat, setLat] = useState(facility?.latitude != null ? String(facility.latitude) : '')
  const [lng, setLng] = useState(facility?.longitude != null ? String(facility.longitude) : '')
  const [status, setStatus] = useState<real.RealFacility['status']>(facility?.status ?? 'ACTIVE')
  const [dept, setDept] = useState(facility?.departmentId ?? mine[0]?.id ?? '')
  const [reason, setReason] = useState('')
  useEffect(() => {
    if (!dept && mine[0]) setDept(mine[0].id)
  }, [dept, mine])
  const moving = !!facility && dept !== facility.departmentId
  const coordsOk = (lat === '' && lng === '') || (lat !== '' && lng !== '' && !isNaN(Number(lat)) && !isNaN(Number(lng)))
  const ok = name.trim() && dept && coordsOk && (facility || /^[A-Za-z0-9-]{2,30}$/.test(code.trim())) && (!moving || reason.trim())
  async function save() {
    const latitude = lat === '' ? null : Number(lat)
    const longitude = lng === '' ? null : Number(lng)
    const done = await real.run(() =>
      facility
        ? real.updateFacility(facility, {
            name: name.trim(),
            areaLabel: area,
            status,
            latitude,
            longitude,
            changeCoordinates: latitude !== facility.latitude || longitude !== facility.longitude,
            ...(moving ? { managingDepartmentId: dept, reason: reason.trim() } : {}),
          })
        : real.createFacility({
            facilityCode: code.trim().toUpperCase(),
            type,
            name: name.trim(),
            areaLabel: area || undefined,
            managingDepartmentId: dept,
            ...(latitude != null && longitude != null ? { latitude, longitude } : {}),
          }),
    )
    if (done) onClose()
  }
  return (
    <Modal
      title={facility ? `${facility.name} 고치기` : '시설 등록'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!ok} onClick={save}>
            저장
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        {facility ? (
          <>
            <Field label="시설 코드">
              <input value={facility.code} disabled className={inputCls} />
            </Field>
            <Field label="종류">
              <input value={facilityTypeName(facility.type)} disabled className={inputCls} />
            </Field>
          </>
        ) : (
          <>
            <Field label="시설 코드" required hint="예: SW-0002 (바꿀 수 없음)">
              <input value={code} onChange={(e) => setCode(e.target.value)} className={inputCls} maxLength={30} />
            </Field>
            <Field label="종류" required>
              <select value={type} onChange={(e) => setType(e.target.value)} className={inputCls}>
                {FACILITY_TYPES.map(([k, l]) => (
                  <option key={k} value={k}>
                    {l}
                  </option>
                ))}
              </select>
            </Field>
          </>
        )}
        <Field label="이름" required>
          <input value={name} onChange={(e) => setName(e.target.value)} className={inputCls} maxLength={100} placeholder="예: 시장 앞 보도블록" />
        </Field>
        <Field label="구역">
          <input value={area} onChange={(e) => setArea(e.target.value)} className={inputCls} maxLength={100} placeholder="예: ○○동 1구역" />
        </Field>
        <Field label="위도">
          <input value={lat} onChange={(e) => setLat(e.target.value)} className={inputCls} inputMode="decimal" placeholder="37.000100" />
        </Field>
        <Field label="경도">
          <input value={lng} onChange={(e) => setLng(e.target.value)} className={inputCls} inputMode="decimal" placeholder="127.000100" />
        </Field>
        <Field label="관리 부서" required>
          <select value={dept} onChange={(e) => setDept(e.target.value)} className={inputCls}>
            {(facility ? depts : mine).map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
                {d.mine ? '' : ' (다른 부서로 넘기기)'}
              </option>
            ))}
          </select>
        </Field>
        {facility && (
          <Field label="상태">
            <select value={status} onChange={(e) => setStatus(e.target.value as real.RealFacility['status'])} className={inputCls}>
              <option value="ACTIVE">사용 중</option>
              <option value="INACTIVE">사용 중지 (새 코스에 넣지 않음)</option>
            </select>
          </Field>
        )}
      </div>
      {moving && (
        <Field label="넘기는 이유" required hint="처리 기록에 남습니다. 넘긴 뒤에는 이 목록에서 빠집니다">
          <input value={reason} onChange={(e) => setReason(e.target.value)} className={inputCls} maxLength={500} placeholder="예: 관리 주체 변경" />
        </Field>
      )}
    </Modal>
  )
}
