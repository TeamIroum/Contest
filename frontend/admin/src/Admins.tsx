import { useState } from 'react'
import { ADMINS, ORGS, ROLE_NAME, type AdminAccount, type Role } from './data'
import { Button, Card, Field, Modal, PageHead, Pill, Toast, inputCls } from './ui'

// 시스템 관리자 · 공무원 계정 (D-04: 발급·비밀번호 초기화·잠금 해제는 SYSTEM_ADMIN만)

function tempPw() {
  const c = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
  return Array.from({ length: 8 }, () => c[Math.floor(Math.random() * c.length)]).join('')
}
const ROLES = Object.keys(ROLE_NAME) as Role[]

export function Admins() {
  const [list, setList] = useState<AdminAccount[]>(ADMINS)
  const [edit, setEdit] = useState<AdminAccount | 'new' | null>(null)
  const [toast, setToast] = useState('')
  const patch = (id: string, p: Partial<AdminAccount>) => setList((l) => l.map((x) => (x.id === id ? { ...x, ...p } : x)))

  return (
    <>
      <PageHead
        title="공무원 계정"
        sub="공무원 계정 발급·비밀번호 초기화·잠금 해제를 합니다." help="시스템 관리자만 쓸 수 있는 메뉴입니다. 계정을 발급하면 임시 비밀번호가 한 번만 표시되고, 처음 로그인할 때 영문·숫자·기호를 포함한 8자 이상 비밀번호로 바꿉니다. 비밀번호를 5번 연속 틀리면 10분 동안 잠깁니다. 한 계정에 여러 역할을 줄 수 있습니다."
        right={
          <Button variant="primary" onClick={() => setEdit('new')}>
            + 계정 발급
          </Button>
        }
      />
      <Card>
        {/* 좁은 화면: 계정당 카드 한 장 */}
        <ul className="m-0 grid list-none gap-0 p-0 md:hidden">
          {list.map((a) => (
            <li key={a.id} className="grid gap-2 border-b border-line px-4 py-3 last:border-0">
              <div className="flex flex-wrap items-center gap-1.5">
                <b className="font-bold">{a.name}</b>
                <span className="text-sm text-mute">{a.loginId}</span>
                <Pill tone={a.status === 'ACTIVE' ? 'green' : 'gray'}>{a.status === 'ACTIVE' ? '사용 중' : '사용 중지'}</Pill>
                {a.temp && <Pill tone="amber">임시 비밀번호</Pill>}
                {a.locked && <Pill tone="red">잠김</Pill>}
              </div>
              <p className="m-0 text-sm text-sub">{a.org}</p>
              <div className="flex flex-wrap gap-1">
                {a.roles.map((r) => (
                  <Pill key={r} tone="gray">
                    {ROLE_NAME[r]}
                  </Pill>
                ))}
              </div>
              <div className="flex flex-wrap gap-1.5">
                <Button size="sm" onClick={() => setEdit(a)}>
                  역할
                </Button>
                <Button
                  size="sm"
                  onClick={() => {
                    const pw = tempPw()
                    patch(a.id, { temp: true, locked: false })
                    setToast(`${a.name} 님 비밀번호를 초기화했습니다. 임시 비밀번호: ${pw} (처음 로그인할 때 변경)`)
                  }}
                >
                  비밀번호 초기화
                </Button>
                {a.locked && (
                  <Button size="sm" onClick={() => patch(a.id, { locked: false })}>
                    잠금 해제
                  </Button>
                )}
                <Button size="sm" variant="ghost" onClick={() => patch(a.id, { status: a.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' })}>
                  {a.status === 'ACTIVE' ? '사용 중지' : '다시 사용'}
                </Button>
              </div>
            </li>
          ))}
        </ul>
        <div className="hidden overflow-x-auto md:block">
          <table className="w-full min-w-[860px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                {['이름', '소속', '아이디', '역할', '상태', ''].map((h) => (
                  <th key={h} scope="col" className="px-4 py-2.5 font-semibold">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {list.map((a) => (
                <tr key={a.id} className="border-b border-line last:border-0">
                  <td className="px-4 py-2.5 font-semibold">{a.name}</td>
                  <td className="px-4 py-2.5">{a.org}</td>
                  <td className="px-4 py-2.5 text-sub">{a.loginId}</td>
                  <td className="px-4 py-2.5">
                    <span className="flex flex-wrap gap-1">
                      {a.roles.map((r) => (
                        <Pill key={r} tone="gray">
                          {ROLE_NAME[r]}
                        </Pill>
                      ))}
                    </span>
                  </td>
                  <td className="px-4 py-2.5">
                    <span className="flex flex-wrap gap-1">
                      <Pill tone={a.status === 'ACTIVE' ? 'green' : 'gray'}>{a.status === 'ACTIVE' ? '사용 중' : '사용 중지'}</Pill>
                      {a.temp && <Pill tone="amber">임시 비밀번호</Pill>}
                      {a.locked && <Pill tone="red">잠김</Pill>}
                    </span>
                  </td>
                  <td className="px-4 py-2.5">
                    <div className="flex justify-end gap-1.5">
                      <Button size="sm" onClick={() => setEdit(a)}>
                        역할
                      </Button>
                      <Button
                        size="sm"
                        onClick={() => {
                          const pw = tempPw()
                          patch(a.id, { temp: true, locked: false })
                          setToast(`${a.name} 님 비밀번호를 초기화했습니다. 임시 비밀번호: ${pw} (처음 로그인할 때 변경)`)
                        }}
                      >
                        비밀번호 초기화
                      </Button>
                      {a.locked && (
                        <Button size="sm" onClick={() => patch(a.id, { locked: false })}>
                          잠금 해제
                        </Button>
                      )}
                      <Button size="sm" variant="ghost" onClick={() => patch(a.id, { status: a.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' })}>
                        {a.status === 'ACTIVE' ? '사용 중지' : '다시 사용'}
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="m-0 border-t border-line px-4 py-2.5 text-xs text-mute">사용 중지하면 그 계정의 모든 로그인 세션이 끊깁니다. 비밀번호를 여러 번 틀려 잠긴 계정은 잠금 해제 또는 비밀번호 초기화로 풉니다.</p>
      </Card>
      {edit && (
        <AccountModal
          account={edit === 'new' ? null : edit}
          onClose={() => setEdit(null)}
          onSave={(a, isNew) => {
            if (isNew) {
              const pw = tempPw()
              setList((l) => [...l, a])
              setToast(`${a.name} 님 계정을 발급했습니다. 아이디 ${a.loginId} · 임시 비밀번호 ${pw}`)
            } else patch(a.id, { roles: a.roles, org: a.org })
            setEdit(null)
          }}
        />
      )}
      {toast && (
        <Toast tone="done" onClose={() => setToast('')}>
          <p className="m-0 text-sm">{toast}</p>
        </Toast>
      )}
    </>
  )
}

function AccountModal({ account, onClose, onSave }: { account: AdminAccount | null; onClose: () => void; onSave: (a: AdminAccount, isNew: boolean) => void }) {
  const [name, setName] = useState(account?.name ?? '')
  const [org, setOrg] = useState(account?.org ?? ORGS[0])
  const [loginId, setLoginId] = useState(account?.loginId ?? '')
  const [roles, setRoles] = useState<Role[]>(account?.roles ?? [])
  const ok = name.trim() && loginId.trim().length >= 4 && roles.length > 0
  return (
    <Modal
      title={account ? `${account.name} 역할 변경` : '공무원 계정 발급'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button
            variant="primary"
            disabled={!ok}
            onClick={() => onSave({ id: account?.id ?? `a${Date.now()}`, name: name.trim(), org, loginId: loginId.trim(), roles, status: account?.status ?? 'ACTIVE', temp: account?.temp ?? true, locked: account?.locked ?? false }, !account)}
          >
            {account ? '저장' : '발급'}
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="이름" required>
          <input value={name} onChange={(e) => setName(e.target.value)} disabled={!!account} className={inputCls} />
        </Field>
        <Field label="아이디" required>
          <input value={loginId} onChange={(e) => setLoginId(e.target.value)} disabled={!!account} className={inputCls} />
        </Field>
        <Field label="소속">
          <select value={org} onChange={(e) => setOrg(e.target.value)} className={inputCls}>
            {ORGS.map((o) => (
              <option key={o}>{o}</option>
            ))}
          </select>
        </Field>
      </div>
      <fieldset className="m-0 border-0 p-0">
        <legend className="mb-1.5 text-sm font-semibold">역할 (하나 이상)</legend>
        <div className="grid gap-1.5">
          {ROLES.map((r) => (
            <label key={r} className="flex items-center gap-2 rounded-lg border border-line px-3 py-2 text-sm">
              <input type="checkbox" checked={roles.includes(r)} onChange={() => setRoles(roles.includes(r) ? roles.filter((x) => x !== r) : [...roles, r])} />
              <b className="font-semibold">{ROLE_NAME[r]}</b>
              <span className="text-xs text-mute">
                {r === 'ACTIVITY_MANAGER' ? '참여자·조·순회, 출석·출발·복귀 확인' : r === 'FACILITY_MANAGER' ? '사진 검토, 분류 정정, 문제·보수' : r === 'PROGRAM_MANAGER' ? '사업 운영 관리, 전체 통계' : '공무원 계정 발급·초기화·잠금 해제만'}
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      {!account && <p className="m-0 text-xs text-mute">발급하면 임시 비밀번호가 만들어집니다. 처음 로그인할 때 본인 비밀번호로 바꿉니다.</p>}
    </Modal>
  )
}
