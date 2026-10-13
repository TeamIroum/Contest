// 공무원 계정 (실제 모드, OPS-07·08): 발급, 역할·관할 변경, 비밀번호 초기화, 잠금 해제, 사용 중지.
// 임시 비밀번호는 서버 응답에 한 번만 오고, 이 화면도 닫으면 버린다(다시 볼 수 없음). 예시 모드는 Admins.tsx가 그린다.
import { useEffect, useState } from 'react'
import { ROLE_NAME, type Role } from './data'
import { Button, Card, Field, Modal, PageHead, Pill, inputCls } from './ui'
import * as real from './api/real'

const ROLES = ['ACTIVITY_MANAGER', 'FACILITY_MANAGER', 'PROGRAM_MANAGER', 'SYSTEM_ADMIN'] as Role[]
const ROLE_HINT: Record<string, string> = {
  ACTIVITY_MANAGER: '참여자·조·순회, 출석·출발·복귀 확인 (기관과 아래 거점)',
  FACILITY_MANAGER: '사진 검토, 분류 정정, 문제·보수 (그 부서가 관리하는 시설)',
  PROGRAM_MANAGER: '사업 운영, 전체 통계',
  SYSTEM_ADMIN: '공무원 계정 발급·초기화·잠금 해제만',
}

export function RealAdmins({ me }: { me: string }) {
  const [list, setList] = useState<real.OfficerAccount[]>([])
  const [orgs, setOrgs] = useState<{ id: string; name: string; type: string }[]>([])
  const [edit, setEdit] = useState<real.OfficerAccount | 'new' | null>(null)
  const [ask, setAsk] = useState<{ kind: 'reset' | 'unlock' | 'suspend' | 'resume'; account: real.OfficerAccount } | null>(null)
  const [issued, setIssued] = useState<{ title: string; loginId: string; password: string } | null>(null)
  useEffect(() => {
    let alive = true
    const load = () =>
      real
        .loadOfficers()
        .then((l) => alive && setList(l))
        .catch(() => undefined)
    load()
    real
      .loadOfficerOrgs()
      .then((o) => alive && setOrgs(o))
      .catch(() => undefined)
    const off = real.onRealChanged(load)
    return () => {
      alive = false
      off()
    }
  }, [])

  return (
    <>
      <PageHead
        title="공무원 계정"
        sub="공무원 계정 발급·비밀번호 초기화·잠금 해제를 합니다."
        help="시스템 관리자만 쓸 수 있는 메뉴입니다. 계정을 발급하거나 비밀번호를 초기화하면 임시 비밀번호가 한 번만 표시되고, 처음 로그인할 때 영문·숫자·기호를 포함한 8자 이상 비밀번호로 바꿉니다. 비밀번호를 5번 연속 틀리면 10분 동안 잠깁니다. 본인 계정의 초기화·사용 중지는 다른 시스템 관리자가 합니다."
        right={
          <Button variant="primary" onClick={() => setEdit('new')}>
            + 계정 발급
          </Button>
        }
      />
      <Card>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[900px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                {['이름', '아이디', '역할 · 관할', '상태', ''].map((h) => (
                  <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {list.map((a) => {
                const self = a.loginId === me
                return (
                  <tr key={a.id} className="border-b border-line last:border-0">
                    <td className="px-4 py-2.5 font-semibold whitespace-nowrap">
                      {a.name} {self && <span className="text-xs font-normal text-mute">(나)</span>}
                    </td>
                    <td className="px-4 py-2.5 text-sub">{a.loginId}</td>
                    <td className="px-4 py-2.5">
                      <span className="grid gap-0.5">
                        {a.roles.map((r) => (
                          <span key={`${r.role}-${r.organizationId}`}>
                            <Pill tone="gray">{ROLE_NAME[r.role as Role] ?? r.role}</Pill> <span className="text-xs text-sub">{r.organizationName}</span>
                          </span>
                        ))}
                      </span>
                    </td>
                    <td className="px-4 py-2.5">
                      <span className="flex flex-wrap gap-1">
                        <Pill tone={a.status === 'ACTIVE' ? 'green' : 'gray'}>{a.status === 'ACTIVE' ? '사용 중' : '사용 중지'}</Pill>
                        {a.passwordChangeRequired && <Pill tone="amber">임시 비밀번호</Pill>}
                        {a.locked && <Pill tone="red">잠김</Pill>}
                      </span>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="flex justify-end gap-1.5">
                        <Button size="sm" onClick={() => setEdit(a)}>
                          역할
                        </Button>
                        {!self && (
                          <Button size="sm" onClick={() => setAsk({ kind: 'reset', account: a })}>
                            비밀번호 초기화
                          </Button>
                        )}
                        {a.locked && (
                          <Button size="sm" onClick={() => setAsk({ kind: 'unlock', account: a })}>
                            잠금 해제
                          </Button>
                        )}
                        {!self && (
                          <Button size="sm" variant="ghost" onClick={() => setAsk({ kind: a.status === 'ACTIVE' ? 'suspend' : 'resume', account: a })}>
                            {a.status === 'ACTIVE' ? '사용 중지' : '다시 사용'}
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <p className="m-0 border-t border-line px-4 py-2.5 text-xs text-mute">
          사용 중지·비밀번호 초기화를 하면 그 계정의 모든 로그인이 끊깁니다. 처리 내용과 사유는 감사 이력에 남고, 임시 비밀번호는 어디에도 저장되지 않습니다.
        </p>
      </Card>
      {edit && (
        <AccountModal
          account={edit === 'new' ? null : edit}
          orgs={orgs}
          onClose={() => setEdit(null)}
          onIssued={(i) => {
            setEdit(null)
            setIssued({ title: `${i.account.name} 님 계정을 발급했습니다`, loginId: i.account.loginId, password: i.temporaryPassword })
          }}
        />
      )}
      {ask && (
        <ReasonModal
          ask={ask}
          onClose={() => setAsk(null)}
          onIssued={(i) => {
            setAsk(null)
            setIssued({ title: `${i.account.name} 님 비밀번호를 초기화했습니다`, loginId: i.account.loginId, password: i.temporaryPassword })
          }}
        />
      )}
      {issued && (
        <Modal
          title={issued.title}
          onClose={() => setIssued(null)}
          footer={
            <Button variant="primary" onClick={() => setIssued(null)}>
              전달했어요 · 닫기
            </Button>
          }
        >
          <div className="grid gap-2 rounded-xl border border-line bg-bg/60 p-4">
            <p className="m-0 text-sm text-sub">
              아이디 <b className="text-ink">{issued.loginId}</b>
            </p>
            <p className="m-0 text-sm text-sub">
              임시 비밀번호 <b className="font-mono text-lg tracking-wide text-ink" data-testid="temp-password">{issued.password}</b>
            </p>
          </div>
          <p className="m-0 text-sm text-g3-ink">이 창을 닫으면 임시 비밀번호를 다시 볼 수 없습니다. 본인에게 직접 전달하고, 처음 로그인할 때 바꾸게 해 주세요.</p>
        </Modal>
      )}
    </>
  )
}

function AccountModal({
  account,
  orgs,
  onClose,
  onIssued,
}: {
  account: real.OfficerAccount | null
  orgs: { id: string; name: string; type: string }[]
  onClose: () => void
  onIssued: (i: real.Issued) => void
}) {
  const [name, setName] = useState(account?.name ?? '')
  const [loginId, setLoginId] = useState(account?.loginId ?? '')
  // 역할마다 관할 기관 하나 (시설 담당자는 부서, 나머지는 수행기관)
  const defaultOrg = (r: string) => (r === 'FACILITY_MANAGER' ? orgs.find((o) => o.type === 'DEPARTMENT') : orgs.find((o) => o.type === 'AGENCY'))?.id ?? orgs[0]?.id ?? ''
  const [roles, setRoles] = useState<real.RoleInput[]>(account?.roles.map((r) => ({ role: r.role, organizationId: r.organizationId })) ?? [])
  const has = (r: string) => roles.find((x) => x.role === r)
  const ok = name.trim() && /^[A-Za-z0-9._-]{4,50}$/.test(loginId.trim()) && roles.length > 0 && roles.every((r) => r.organizationId)
  async function save() {
    let result: real.Issued | null = null
    const done = await real.run(async () => {
      if (account) await real.changeOfficerRoles(account, roles)
      else result = await real.issueOfficer({ name: name.trim(), loginId: loginId.trim(), roles })
    })
    if (!done) return
    if (result) onIssued(result)
    else onClose()
  }
  return (
    <Modal
      title={account ? `${account.name} 역할 변경` : '공무원 계정 발급'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!ok} onClick={save}>
            {account ? '저장' : '발급'}
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="이름" required>
          <input value={name} onChange={(e) => setName(e.target.value)} disabled={!!account} className={inputCls} maxLength={50} />
        </Field>
        <Field label="아이디" required hint="영문·숫자·.-_ 4자 이상">
          <input value={loginId} onChange={(e) => setLoginId(e.target.value)} disabled={!!account} className={inputCls} maxLength={50} />
        </Field>
      </div>
      <fieldset className="m-0 border-0 p-0">
        <legend className="mb-1.5 text-sm font-semibold">역할과 관할 (하나 이상)</legend>
        <div className="grid gap-1.5">
          {ROLES.map((r) => {
            const cur = has(r)
            return (
              <div key={r} className={`grid gap-2 rounded-lg border px-3 py-2 text-sm ${cur ? 'border-brand bg-brand-50' : 'border-line'}`}>
                <label className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={!!cur}
                    onChange={() => setRoles(cur ? roles.filter((x) => x.role !== r) : [...roles, { role: r, organizationId: defaultOrg(r) }])}
                  />
                  <b className="font-semibold">{ROLE_NAME[r]}</b>
                  <span className="text-xs text-mute">{ROLE_HINT[r]}</span>
                </label>
                {cur && (
                  <select
                    aria-label={`${ROLE_NAME[r]} 관할`}
                    value={cur.organizationId}
                    onChange={(e) => setRoles(roles.map((x) => (x.role === r ? { ...x, organizationId: e.target.value } : x)))}
                    className={inputCls}
                  >
                    {orgs.map((o) => (
                      <option key={o.id} value={o.id}>
                        {o.name}
                      </option>
                    ))}
                  </select>
                )}
              </div>
            )
          })}
        </div>
      </fieldset>
      {!account && <p className="m-0 text-xs text-mute">발급하면 임시 비밀번호가 한 번만 표시됩니다. 처음 로그인할 때 본인 비밀번호로 바꿉니다.</p>}
    </Modal>
  )
}

const ASK: Record<'reset' | 'unlock' | 'suspend' | 'resume', [string, string, string]> = {
  reset: ['비밀번호 초기화', '새 임시 비밀번호를 만들고, 그 계정의 모든 로그인을 끊습니다. 잠금도 함께 풀립니다.', '예: 비밀번호 분실 신고'],
  unlock: ['잠금 해제', '비밀번호를 여러 번 틀려 잠긴 계정을 바로 풉니다. 비밀번호는 그대로입니다.', '예: 본인 확인 후 해제'],
  suspend: ['사용 중지', '그 계정의 모든 로그인을 끊고 다시 로그인하지 못하게 합니다.', '예: 부서 이동'],
  resume: ['다시 사용', '사용 중지한 계정을 다시 로그인할 수 있게 합니다.', '예: 복귀'],
}

function ReasonModal({ ask, onClose, onIssued }: { ask: { kind: keyof typeof ASK; account: real.OfficerAccount }; onClose: () => void; onIssued: (i: real.Issued) => void }) {
  const [reason, setReason] = useState('')
  const [title, note, placeholder] = ASK[ask.kind]
  const a = ask.account
  async function save() {
    let result: real.Issued | null = null
    const done = await real.run(async () => {
      if (ask.kind === 'reset') result = await real.resetOfficerPassword(a, reason.trim())
      else if (ask.kind === 'unlock') await real.unlockOfficer(a, reason.trim())
      else await real.changeOfficerStatus(a, ask.kind === 'suspend' ? 'SUSPENDED' : 'ACTIVE', reason.trim())
    })
    if (!done) return
    if (result) onIssued(result)
    else onClose()
  }
  return (
    <Modal
      title={`${a.name} ${title}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant={ask.kind === 'suspend' ? 'danger' : 'primary'} disabled={!reason.trim()} onClick={save}>
            {title}
          </Button>
        </>
      }
    >
      <p className="m-0 text-sm text-sub">{note}</p>
      <Field label="사유" required hint="감사 이력에 남습니다">
        <input value={reason} onChange={(e) => setReason(e.target.value)} className={inputCls} maxLength={500} placeholder={placeholder} />
      </Field>
    </Modal>
  )
}
