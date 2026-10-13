// 코스·순회 배정 (실제 모드): 순회 일정(오늘부터 2주), 날짜별 순회 배정(ACT-04), 코스 만들기·새 버전(ACT-03).
// 예시 모드는 Activity.tsx의 Assign이 그대로 그린다.
import { useEffect, useState } from "react";
import { KIND_NAME } from "./Photo";
import { KIND_OF_TYPE } from "./data";
import {
  Button,
  Card,
  Empty,
  Field,
  Modal,
  PageHead,
  Pill,
  inputCls,
} from "./ui";
import * as real from "./api/real";

const STATUS: Record<string, [string, "gray" | "brand" | "green" | "amber"]> = {
  ASSIGNED: ["출발 전", "gray"],
  IN_PROGRESS: ["활동 중", "brand"],
  RETURNING: ["복귀 중", "amber"],
  ENDED: ["끝남", "green"],
};
const typeName = (t: string) => KIND_NAME[KIND_OF_TYPE[t]] ?? t;
const TIMES = Array.from(
  { length: 27 },
  (_, i) =>
    `${String(7 + Math.floor(i / 2)).padStart(2, "0")}:${i % 2 ? "30" : "00"}`,
);

/** 'YYYY-MM-DD' → '10/16 (금)', 오늘이면 '오늘 · …' */
function dayLabel(date: string) {
  const d = new Date(`${date}T00:00:00+09:00`);
  const w = "일월화수목금토"[new Date(d.getTime() + 9 * 3600_000).getUTCDay()];
  const md = real.md(date);
  return date === real.todayKst() ? `오늘 · ${md} (${w})` : `${md} (${w})`;
}

export function RealAssign() {
  const [schedule, setSchedule] = useState<real.ScheduledPatrol[]>([]);
  const [routes, setRoutes] = useState<real.RealRoute[]>([]);
  const [teams, setTeams] = useState<real.RealTeam[]>([]);
  const [options, setOptions] = useState<real.RouteOptions>({
    bases: [],
    facilities: [],
  });
  const [newPatrol, setNewPatrol] = useState(false);
  const [editRoute, setEditRoute] = useState<real.RealRoute | "new" | null>(
    null,
  );
  useEffect(() => {
    let alive = true;
    const load = () =>
      Promise.all([
        real.loadSchedule(),
        real.loadRoutes(),
        real.loadTeams(),
        real.loadParticipants(),
      ])
        .then(([s, r, t]) => {
          if (!alive) return;
          setSchedule(s);
          setRoutes(r);
          setTeams(t);
        })
        .catch(() => undefined);
    load();
    real
      .loadRouteOptions()
      .then((o) => alive && setOptions(o))
      .catch(() => undefined);
    const off = real.onRealChanged(load);
    return () => {
      alive = false;
      off();
    };
  }, []);
  const byDate: [string, real.ScheduledPatrol[]][] = [
    ...new Set(schedule.map((p) => p.activityDate)),
  ].map((d) => [d, schedule.filter((p) => p.activityDate === d)]);
  const active = routes.filter((r) => r.status === "ACTIVE" && r.current);

  return (
    <>
      <PageHead
        title="코스·순회 배정"
        sub="코스를 만들고, 날짜별로 조와 코스를 순회에 배정합니다."
        help="배정할 때 그날의 조원과 코스를 순회에 그대로 저장합니다. 뒤에 조나 코스를 바꿔도 이미 배정된 순회는 바뀌지 않습니다."
        right={
          <div className="flex gap-2">
            <Button onClick={() => setEditRoute("new")}>+ 코스 만들기</Button>
            <Button
              variant="primary"
              onClick={() => setNewPatrol(true)}
              disabled={!active.length || !teams.length}
            >
              + 순회 배정
            </Button>
          </div>
        }
      />
      <div className="grid grid-cols-[minmax(0,1fr)] gap-4">
        <Card title="순회 일정 · 오늘부터 2주">
          {schedule.length === 0 ? (
            <Empty>배정된 순회가 없습니다.</Empty>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[720px] border-collapse text-sm">
                <thead>
                  <tr className="border-b border-line bg-bg/60 text-left text-[14px] text-mute">
                    {["조", "코스", "시간", "조원", "재점검", "상태"].map(
                      (h) => (
                        <th
                          key={h}
                          scope="col"
                          className="px-4 py-2.5 font-semibold"
                        >
                          {h}
                        </th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {byDate.map(([date, list]) => [
                    <tr key={`h-${date}`} className="border-b border-line">
                      <th
                        scope="colgroup"
                        colSpan={6}
                        className="bg-bg/70 px-4 py-2 text-left text-[13px] font-bold text-sub"
                      >
                        {dayLabel(date)} · {list.length}개 조
                      </th>
                    </tr>,
                    ...list.map((p) => (
                      <tr
                        key={p.id}
                        className="border-b border-line last:border-0"
                      >
                        <td className="px-4 py-2.5 font-semibold whitespace-nowrap">
                          {p.teamName}
                        </td>
                        <td className="px-4 py-2.5 whitespace-nowrap">
                          {p.routeName}{" "}
                          <span className="text-xs text-mute">
                            v{p.routeVersionNo} · {p.stops}곳
                          </span>
                        </td>
                        <td className="px-4 py-2.5 whitespace-nowrap tabular-nums">
                          {real.hhmm(p.scheduledStartAt)}~
                          {real.hhmm(p.expectedReturnAt)}
                        </td>
                        <td className="px-4 py-2.5">{p.members.join(", ")}</td>
                        <td className="px-4 py-2.5">
                          {p.reinspectionSlots
                            ? `${p.reinspectionSlots}장`
                            : "-"}
                        </td>
                        <td className="px-4 py-2.5">
                          <Pill tone={STATUS[p.activityStatus]?.[1] ?? "gray"}>
                            {STATUS[p.activityStatus]?.[0] ?? p.activityStatus}
                          </Pill>
                        </td>
                      </tr>
                    )),
                  ])}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <div className="grid items-start gap-4 xl:grid-cols-3">
          {routes.map((r) => (
            <Card
              key={r.id}
              title={
                <span className="flex items-center gap-2">
                  <span>{r.name}</span>
                  {r.current && (
                    <span className="text-xs font-normal text-mute">
                      v{r.current.versionNo}
                    </span>
                  )}
                  {r.status === "INACTIVE" && <Pill tone="gray">닫음</Pill>}
                </span>
              }
              action={
                <span className="flex gap-1">
                  {r.status === "ACTIVE" && (
                    <Button size="sm" onClick={() => setEditRoute(r)}>
                      고치기
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      real.run(() =>
                        real.updateRoute(r, {
                          status: r.status === "ACTIVE" ? "INACTIVE" : "ACTIVE",
                        }),
                      )
                    }
                  >
                    {r.status === "ACTIVE" ? "닫기" : "다시 열기"}
                  </Button>
                </span>
              }
            >
              {r.current && (
                <>
                  <p className="m-0 px-4 pt-3 text-xs text-mute">
                    출발 {r.current.departureBase.name} · 복귀{" "}
                    {r.current.returnBase.name}
                  </p>
                  <ol className="m-0 grid list-none gap-0 p-0 pb-1">
                    {r.current.stops.map((s, i) => (
                      <li
                        key={i}
                        className="flex gap-3 border-b border-line px-4 py-2.5 text-sm last:border-0"
                      >
                        <span className="grid size-6 flex-none place-items-center rounded-full bg-bg text-xs font-bold text-sub">
                          {i + 1}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block">
                            {typeName(s.facilityType)} · {s.facilityName}
                          </span>
                          <span className="block text-xs text-mute">
                            사진 {s.slots.length}장 (
                            {s.slots.map((x) => x.label).join(", ")})
                          </span>
                        </span>
                      </li>
                    ))}
                  </ol>
                </>
              )}
            </Card>
          ))}
        </div>
      </div>
      {newPatrol && (
        <NewPatrolModal
          teams={teams}
          routes={active}
          onClose={() => setNewPatrol(false)}
        />
      )}
      {editRoute && (
        <RouteModal
          route={editRoute === "new" ? null : editRoute}
          options={options}
          onClose={() => setEditRoute(null)}
        />
      )}
    </>
  );
}

function NewPatrolModal({
  teams,
  routes,
  onClose,
}: {
  teams: real.RealTeam[];
  routes: real.RealRoute[];
  onClose: () => void;
}) {
  const tomorrow = new Date(Date.now() + 33 * 3600_000)
    .toISOString()
    .slice(0, 10);
  const [date, setDate] = useState(tomorrow);
  const [teamId, setTeamId] = useState(teams[0]?.id ?? "");
  const [routeId, setRouteId] = useState(routes[0]?.id ?? "");
  const [start, setStart] = useState("10:00");
  const [end, setEnd] = useState("12:00");
  const route = routes.find((r) => r.id === routeId);
  const ok = date >= real.todayKst() && teamId && route?.current && end > start;
  const names = (t: real.RealTeam) =>
    t.memberIds.map((id) => real.participantName(id)).join(", ");
  return (
    <Modal
      title="순회 배정"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button
            variant="primary"
            disabled={!ok}
            onClick={async () => {
              if (
                await real.run(() =>
                  real.assignPatrol({
                    date,
                    teamId,
                    routeVersionId: route!.current!.id,
                    start,
                    end,
                  }),
                )
              )
                onClose();
            }}
          >
            배정
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="날짜" required hint={dayLabel(date)}>
          <input
            type="date"
            value={date}
            min={real.todayKst()}
            onChange={(e) => setDate(e.target.value)}
            className={inputCls}
          />
        </Field>
        <Field label="조" required>
          <select
            value={teamId}
            onChange={(e) => setTeamId(e.target.value)}
            className={inputCls}
          >
            {teams.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} ({names(t)})
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="코스"
          required
          hint={
            route?.current
              ? `v${route.current.versionNo} · ${route.current.stops.length}곳`
              : undefined
          }
        >
          <select
            value={routeId}
            onChange={(e) => setRouteId(e.target.value)}
            className={inputCls}
          >
            {routes.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="출발">
            <select
              value={start}
              onChange={(e) => {
                const v = e.target.value;
                setStart(v);
                if (end <= v)
                  setEnd(
                    TIMES[Math.min(TIMES.indexOf(v) + 4, TIMES.length - 1)],
                  );
              }}
              className={inputCls}
            >
              {TIMES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Field>
          <Field label="복귀 예정">
            <select
              value={end}
              onChange={(e) => setEnd(e.target.value)}
              className={inputCls}
            >
              {TIMES.filter((t) => t > start).map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Field>
        </div>
      </div>
      <p className="m-0 text-xs text-mute">
        그날의 조원(활동 중인 참여자)과 코스의 현재 버전이 순회에 그대로
        저장됩니다. 같은 시간에 다른 순회에 배정된 조원이 있으면 배정되지
        않습니다.
      </p>
    </Modal>
  );
}

type StopDraft = { facilityId: string; instruction: string; slots: string };

function RouteModal({
  route,
  options,
  onClose,
}: {
  route: real.RealRoute | null;
  options: real.RouteOptions;
  onClose: () => void;
}) {
  const cur = route?.current;
  const firstBase = options.bases[0]?.id ?? "";
  const [name, setName] = useState(route?.name ?? "");
  const [dep, setDep] = useState(cur?.departureBase.id ?? firstBase);
  const [ret, setRet] = useState(cur?.returnBase.id ?? firstBase);
  const [stops, setStops] = useState<StopDraft[]>(
    cur?.stops.map((s) => ({
      facilityId: s.facilityId,
      instruction: s.instruction ?? "",
      slots: s.slots.map((x) => x.label).join(", "),
    })) ?? [
      {
        facilityId: options.facilities[0]?.id ?? "",
        instruction: "",
        slots: "전체 모습",
      },
    ],
  );
  const labels = (s: StopDraft) =>
    s.slots
      .split(",")
      .map((x) => x.trim())
      .filter(Boolean);
  const ok =
    (route || name.trim()) &&
    dep &&
    ret &&
    stops.length > 0 &&
    stops.every(
      (s) => s.facilityId && labels(s).length >= 1 && labels(s).length <= 5,
    );
  const set = (i: number, patch: Partial<StopDraft>) =>
    setStops((l) => l.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  const move = (i: number, d: -1 | 1) =>
    setStops((l) => {
      const n = [...l];
      [n[i], n[i + d]] = [n[i + d], n[i]];
      return n;
    });
  async function save() {
    const body: real.RouteInput = {
      departureBaseId: dep,
      returnBaseId: ret,
      stops: stops.map((s) => ({
        facilityId: s.facilityId,
        instruction: s.instruction.trim() || undefined,
        slots: labels(s).map((label) => ({ label, required: true })),
      })),
    };
    // 수행기관: 거점 목록의 수행기관(관할 기관) — 새 코스는 담당자 관할 기관에 만든다
    const orgs = await real.loadParticipantOrgs().catch(() => []);
    const ok = await real.run(() =>
      route
        ? real.publishRouteVersion(route, body)
        : real.createRoute({
            ...body,
            organizationId: orgs[0]?.id ?? "",
            name: name.trim(),
          }),
    );
    if (ok) onClose();
  }
  return (
    <Modal
      title={route ? `${route.name} 고치기` : "코스 만들기"}
      onClose={onClose}
      width="max-w-2xl"
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={!ok} onClick={save}>
            {route ? `v${(cur?.versionNo ?? 0) + 1}로 공개` : "만들기"}
          </Button>
        </>
      }
    >
      {route ? (
        <p className="m-0 text-sm text-sub">
          공개된 코스는 고치지 않고 새 버전으로 공개합니다. 이미 배정된 순회는
          원래 버전 그대로입니다.
        </p>
      ) : (
        <Field label="코스 이름" required>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className={inputCls}
            placeholder="예: 시장·경로당 코스"
            maxLength={100}
          />
        </Field>
      )}
      <div className="grid grid-cols-2 gap-3">
        <Field label="출발 거점" required>
          <select
            value={dep}
            onChange={(e) => setDep(e.target.value)}
            className={inputCls}
          >
            {options.bases.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="복귀 거점" required>
          <select
            value={ret}
            onChange={(e) => setRet(e.target.value)}
            className={inputCls}
          >
            {options.bases.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <fieldset className="m-0 grid gap-2 border-0 p-0">
        <legend className="mb-1.5 text-sm font-semibold">
          장소 순서{" "}
          <span className="font-normal text-mute">
            ({stops.length}곳 · 같은 시설을 다시 넣어도 됩니다)
          </span>
        </legend>
        {stops.map((s, i) => (
          <div
            key={i}
            className="grid gap-2 rounded-xl border border-line p-3"
            aria-label={`${i + 1}번째 장소`}
            role="group"
          >
            <div className="flex items-center gap-2">
              <span className="grid size-6 flex-none place-items-center rounded-full bg-bg text-xs font-bold text-sub">
                {i + 1}
              </span>
              <select
                value={s.facilityId}
                onChange={(e) => set(i, { facilityId: e.target.value })}
                className={inputCls}
                aria-label={`${i + 1}번째 장소 시설`}
              >
                {options.facilities.map((f) => (
                  <option key={f.id} value={f.id}>
                    {typeName(f.type)} · {f.name} ({f.code})
                  </option>
                ))}
              </select>
              <Button
                size="sm"
                variant="ghost"
                disabled={i === 0}
                onClick={() => move(i, -1)}
              >
                위로
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={i === stops.length - 1}
                onClick={() => move(i, 1)}
              >
                아래로
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={stops.length === 1}
                onClick={() => setStops((l) => l.filter((_, j) => j !== i))}
              >
                빼기
              </Button>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Field label="찍을 구도 (쉼표로 구분, 1~5개)">
                <input
                  value={s.slots}
                  onChange={(e) => set(i, { slots: e.target.value })}
                  className={inputCls}
                  placeholder="전체 모습, 주변 바닥"
                />
              </Field>
              <Field label="안내 (선택)">
                <input
                  value={s.instruction}
                  onChange={(e) => set(i, { instruction: e.target.value })}
                  className={inputCls}
                  maxLength={500}
                />
              </Field>
            </div>
          </div>
        ))}
        <Button
          disabled={stops.length >= 30}
          onClick={() =>
            setStops((l) => [
              ...l,
              {
                facilityId: options.facilities[0]?.id ?? "",
                instruction: "",
                slots: "전체 모습",
              },
            ])
          }
        >
          + 장소 추가
        </Button>
      </fieldset>
    </Modal>
  );
}
