import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { Photo, KIND_NAME } from './Photo'
import { Scenery } from './Scenery'
import { SKY_PRESETS, lookAt, nowHour, type SkyLook } from './sky'
import { LiveCamera, type CameraHandle } from './camera/LiveCamera'
import * as quality from './camera/quality.js'
import * as masking from './masking/masking.js'
import * as outbox from './offline/outbox.js'
import type { OutboxItem, SubmitBody } from './offline/outbox.js'
import { ApiError, REAL_BACKEND, fetchPatrol, fetchTodayPatrol, memberStates, requestAttendance, requestOtp, sendOpts, startActivity, verifyOtp, type ApiPatrol, type OtpChallenge } from './api/client'
import { playNotify, playRetake, playShutter, playSuccess, setSoundEnabled, unlockAudio } from './sounds'
import { ABORT_REASONS, MAX_ATTEMPTS, ME, PARTNER, SKIP_REASONS, STAFF, STOPS, TEAM, applyPatrol, slotDone, stopDone, type Stop } from './data'

/** 서버 기록에 남는 앱 버전 (deviceMaskReport.appVersion) */
const APP_VERSION = 'senior-app-0.1.0'
/** 셔터를 누른 뒤 약 0.3초 동안 몇 장면을 떼어 가장 선명한 것을 쓴다 (시험용 PWA와 같음) */
const BURST_FRAMES = 5
const BURST_GAP_MS = 70
/** 그림 다음에 화면이 실제로 그려질 때까지 기다림 (가리기 계산이 화면을 1~3초 멈추게 하므로 먼저 그려 둔다) */
// 대기함 이름 (처음 쓰기 전에 한 번). 시험용 PWA와 저장소가 섞이지 않게
outbox.configure({ dbName: 'senior-outbox' })

const afterPaint = (ms: number) => new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(res, ms))))

// 시니어 동네 점검단 · 어르신 현장 앱 (PWA)
// 흐름: 오늘 순회 → 출석 요청 → 담당자 출석·출발 확인 → 장소·구도 안내 → 촬영·가림·제출
//      → 다음 구도/장소 → 센터 복귀 → 담당자 복귀 확인 → 활동 완료
// 어르신 화면에는 위험도·이상 여부를 띄우지 않고 재촬영·진행 안내만 보여 준다.
type Screen = 'start' | 'phone' | 'code' | 'home' | 'ready' | 'route' | 'camera' | 'upload' | 'retake' | 'sent' | 'return' | 'returnWait' | 'done'
type Sheet = null | 'help' | 'skip' | 'abort'
/** 장소별 기록: 촬영 완료 / 건너뜀 / 담당자 확인 요청(재촬영 3번 실패) / 못 감(중도 종료) */
type StopResult = 'captured' | 'skipped' | 'review' | 'notVisited'
type Attend = 'none' | 'requested' | 'present'

export default function App() {
  const [screen, setScreen] = useState<Screen>('start')
  /** 글씨 크기: 0 기본(큰 글씨) · 1 크게 · 2 아주 크게 */
  const [size, setSize] = useState<0 | 1 | 2>(0)
  const [sheet, setSheet] = useState<Sheet>(null)
  const [phone, setPhone] = useState('')

  const [myAtt, setMyAtt] = useState<Attend>('none')
  const [partnerAtt, setPartnerAtt] = useState<Attend>('none')
  const [departed, setDeparted] = useState(false)
  // 실제 업무 백엔드(VITE_BACKEND=real)일 때만 쓰는 값
  const [patrol, setPatrol] = useState<ApiPatrol | null>(null)
  const [challenge, setChallenge] = useState<OtpChallenge | null>(null)
  const [codeKey, setCodeKey] = useState(0) // 인증번호가 틀리면 입력 칸을 비운다
  const [notice, setNotice] = useState('')
  useEffect(() => {
    if (!notice) return
    const t = setTimeout(() => setNotice(''), 6000)
    return () => clearTimeout(t)
  }, [notice])
  const say = (e: unknown, fallback: string) => setNotice(e instanceof ApiError ? e.serverMessage || fallback : '연결이 안 돼요. 잠시 뒤 다시 해 주세요.')

  const [idx, setIdx] = useState(0)
  const [slot, setSlot] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [blurNext, setBlurNext] = useState(false)
  const [outcome, setOutcome] = useState<SentOutcome>('ok')
  const [results, setResults] = useState<Record<string, StopResult>>({})
  const [paused, setPaused] = useState(false)
  const [aborted, setAborted] = useState(false)

  const stop = STOPS[Math.min(idx, STOPS.length - 1)]
  /** 효과음 켜기/끄기 (촬영음은 늘 재생) */
  const [sound, setSound] = useState(true)

  /** 시연용 하늘 시각 (SKY_PRESETS 번호, 0 = 휴대폰 시계) */
  const [skyPick, setSkyPick] = useState(0)
  const look = lookAt(useSkyHour(SKY_PRESETS[skyPick][0]))
  // 브라우저 위쪽 막대 색도 하늘에 맞춘다
  useEffect(() => {
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', look.bar)
  }, [look.bar])

  // ---------------- 실제 촬영 준비 (docs/시니어앱_촬영기능_적용요청.md)
  // https(또는 localhost)이면 실제 촬영: 가림 모델을 받아 두고, 셋 다 준비되기 전에는 찍기 버튼을 끈다.
  // http 주소에서만 지금까지의 그림 시연으로 동작한다 (카메라가 https에서만 열리므로).
  const live = typeof window !== 'undefined' && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia
  const [models, setModels] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [modelText, setModelText] = useState('')
  const [camError, setCamError] = useState('')
  const [camKey, setCamKey] = useState(0) // 카메라를 다시 켤 때 바꿈
  const cam = useRef<CameraHandle | null>(null)
  const [stage, setStage] = useState<'mask' | 'send' | 'check' | undefined>(undefined)
  const [flash, setFlash] = useState(0) // 셔터를 누른 순간 화면 번쩍임
  const [shot, setShotRaw] = useState<string | undefined>(undefined)
  const setShot = (u: string | undefined) =>
    setShotRaw((old) => {
      if (old) URL.revokeObjectURL(old) // 앞 사진의 임시 주소는 바로 지운다
      return u
    })
  const [retakeNote, setRetakeNote] = useState<{ title: string; sub: string } | null>(null)
  const [serverMsg, setServerMsg] = useState<string | undefined>(undefined)
  /** 서버가 정한 다음 행동 (Sent 화면에서 "다음"을 누르면 따름) */
  const [next, setNext] = useState<Pick<SubmitBody, 'nextAction' | 'nextStopId'>>({})
  /** 업무 API에서 순회를 받았는지 (못 받으면 예시 데이터로 시연) */
  const [server, setServer] = useState(false)
  const [, setStopsVersion] = useState(0) // STOPS가 서버 순회로 바뀌면 다시 그림
  const [pending, setPending] = useState(0) // 전송 대기 사진 수

  const loggedIn0 = !['start', 'phone', 'code'].includes(screen)
  function prepareModels() {
    setModels('loading')
    const show = () => {
      const s = masking.status
      const part = (['face', 'plate', 'vehicle'] as const).map((k) => [k, s[k]] as const).find(([, v]) => v !== '준비')
      setModelText(part ? `${{ face: '얼굴', plate: '번호판', vehicle: '차량' }[part[0]]} ${part[1]}` : '')
    }
    masking.loadModels(show, { manifestUrl: `${import.meta.env.BASE_URL}models/model-manifest.json` }).then(
      () => setModels('ready'),
      () => setModels('failed'),
    )
  }
  useEffect(() => {
    if (!loggedIn0 || !live) return
    prepareModels()
    navigator.storage?.persist?.().catch(() => {}) // 대기 사진이 지워지지 않게
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loggedIn0])
  const myAttRef = useRef<Attend>('none')
  const departedRef = useRef(false)
  /** 서버 순회를 화면에 반영. 실제 백엔드면 조원 이름·담당자·출석·출발 확인 상태도 */
  const showServerPatrol = (p: ApiPatrol) => {
    applyPatrol(p)
    setStopsVersion((v) => v + 1)
    if (!REAL_BACKEND) return
    setPatrol(p)
    const st = memberStates(p)
    if (st.me) ME.name = st.me.name
    if (st.partner) PARTNER.name = st.partner.name
    if (p.contact?.name) STAFF.name = p.contact.name
    if (p.contact?.phoneNumber) STAFF.phone = p.contact.phoneNumber
    const no = Number(p.teamName?.match(/\d+/)?.[0])
    if (no) TEAM.no = no
    if (st.myAtt === 'present' && myAttRef.current !== 'present') playNotify()
    if (st.departed && !departedRef.current) playNotify()
    myAttRef.current = st.myAtt
    departedRef.current = st.departed
    setMyAtt(st.myAtt)
    setPartnerAtt(st.partnerAtt)
    setDeparted(st.departed)
  }
  // 오늘 순회: 업무 API가 있으면 서버 순회로 바꾼다
  useEffect(() => {
    if (!loggedIn0) return
    fetchTodayPatrol().then(
      (p) => {
        showServerPatrol(p)
        setServer(true)
        // 로그인 전에 쌓인 대기 사진을 이제 보낸다 (실제 백엔드는 로그인 토큰이 있어야 받음)
        if (REAL_BACKEND) outbox.flush(sendOpts()).catch(() => {}).finally(updatePending)
      },
      (e) => {
        setServer(false)
        if (REAL_BACKEND) say(e, '오늘 순회를 불러오지 못했어요.')
      },
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loggedIn0])
  const refreshPatrol = () =>
    server &&
    fetchPatrol(TEAM.patrolId).then(
      (p) => showServerPatrol(p),
      () => {},
    )
  // 실제 백엔드: 출석 요청 뒤 담당자 출석 확인·출발 확인을 기다리는 동안 순회 상태를 4초마다 다시 받는다
  useEffect(() => {
    if (!REAL_BACKEND || !server || screen !== 'ready' || (myAtt === 'present' && partnerAtt === 'present' && departed)) return
    const t = setInterval(() => refreshPatrol(), 4000)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [server, screen, myAtt, partnerAtt, departed])
  const updatePending = () => outbox.count().then(setPending, () => {})
  // 대기 사진은 앱을 다시 볼 때, 연결이 돌아올 때 다시 보낸다
  useEffect(() => {
    const retry = () => {
      if (!navigator.onLine) return
      outbox
        .flush(sendOpts())
        .catch(() => {})
        .finally(updatePending)
    }
    retry()
    const vis = () => document.visibilityState === 'visible' && retry()
    window.addEventListener('online', retry)
    document.addEventListener('visibilitychange', vis)
    return () => {
      window.removeEventListener('online', retry)
      document.removeEventListener('visibilitychange', vis)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => unlockAudio(), [])
  useEffect(() => setSoundEnabled(sound), [sound])

  useEffect(() => {
    document.documentElement.classList.toggle('text-big', size === 1)
    document.documentElement.classList.toggle('text-huge', size === 2)
  }, [size])

  // 시연용: 담당자 출석 확인 → 짝 어르신 출석 → 담당자 출발 확인이 잠시 뒤 자동으로 이루어진다
  useEffect(() => {
    if (REAL_BACKEND || myAtt !== 'requested') return
    const t = setTimeout(() => {
      setMyAtt('present')
      playNotify()
    }, 1800)
    return () => clearTimeout(t)
  }, [myAtt])
  useEffect(() => {
    if (REAL_BACKEND || myAtt === 'none' || partnerAtt === 'present') return
    const t = setTimeout(() => setPartnerAtt('present'), 2400)
    return () => clearTimeout(t)
  }, [myAtt, partnerAtt])
  useEffect(() => {
    if (REAL_BACKEND || myAtt !== 'present' || partnerAtt !== 'present' || departed) return
    const t = setTimeout(() => {
      setDeparted(true)
      playNotify()
    }, 1600)
    return () => clearTimeout(t)
  }, [myAtt, partnerAtt, departed])
  useEffect(() => {
    if (screen !== 'returnWait') return
    const t = setTimeout(() => setScreen('done'), 2800)
    return () => clearTimeout(t)
  }, [screen])

  function goNextStop(result: StopResult) {
    setResults((r) => ({ ...r, [stop.id]: result }))
    setSlot(0)
    setAttempt(0)
    if (idx < STOPS.length - 1) {
      setIdx(idx + 1)
      setScreen('route')
    } else {
      setIdx(STOPS.length)
      setScreen('return')
    }
  }

  function shoot() {
    if (live) return void liveShoot()
    playShutter()
    setScreen('upload')
  }

  function toRetake(title: string, sub: string) {
    setRetakeNote({ title, sub })
    playRetake()
    setScreen('retake')
  }

  // ---------------- 실제 촬영: 연속 5장면 중 가장 선명한 것 → 흐림·어두움 확인 → 기기에서 가리기 → 대기함 저장 → 전송 → 서버 답으로 다음 화면
  async function liveShoot() {
    const c = cam.current
    if (!c || !masking.ready()) return
    playShutter()
    setFlash((f) => f + 1)
    const capturedAt = new Date().toISOString()
    let raw: HTMLCanvasElement | null = null
    let spare: HTMLCanvasElement | null = null
    let best: (quality.Quality & { frame: number }) | null = null
    for (let i = 0; i < BURST_FRAMES; i++) {
      if (i) await new Promise((r) => setTimeout(r, BURST_GAP_MS))
      const f = c.frame(spare)
      if (!f) break
      const q = quality.measure(f)
      if (!best || q.sharpness > best.sharpness) {
        spare = raw
        raw = f
        best = { ...q, frame: i }
      } else spare = f
    }
    if (spare) spare.width = spare.height = 0 // 고르지 않은 장면은 바로 버린다 (원본을 남기지 않음)
    c.stop()
    if (!raw || !best) return
    // 확실히 흐리거나 어두우면 올리지 않고 바로 다시 찍게 한다 (서버도 같은 기준으로 거절하므로 기다림·데이터를 아낌)
    const bad = quality.problem(best)
    if (bad) {
      raw.width = raw.height = 0
      setShot(undefined)
      return bad === 'dark' ? toRetake('사진이 어두워요', '밝은 쪽에서 다시 찍어 주세요.') : toRetake('사진이 흐려요', '휴대폰을 잠깐 멈추고 한 번 더 찍어 주세요.')
    }
    setStage('mask')
    setScreen('upload')
    await afterPaint(250)
    let masked
    try {
      masked = await masking.maskCapture(raw, APP_VERSION) // 원본(raw)은 이 안에서 버려진다
    } catch {
      setShot(undefined)
      return toRetake('한 번 더 찍어 주세요', '사진을 정리하지 못했어요. 계속 안 되면 선생님께 연락해 주세요.')
    }
    // 서버는 가리고 압축한 JPEG로 선명도를 잰다. 압축하면 값이 내려가므로 보낼 사진으로 한 번 더 잰다
    try {
      const bmp = await createImageBitmap(masked.blob)
      const sent = quality.measure(bmp, { exact: true })
      bmp.close()
      const bad2 = quality.problem(sent)
      if (bad2) {
        setShot(undefined)
        return bad2 === 'dark' ? toRetake('사진이 어두워요', '밝은 쪽에서 다시 찍어 주세요.') : toRetake('사진이 흐려요', '휴대폰을 잠깐 멈추고 한 번 더 찍어 주세요.')
      }
    } catch {
      /* 잴 수 없으면 서버 판정에 맡김 */
    }
    setShot(URL.createObjectURL(masked.blob))
    const item: OutboxItem = {
      clientSubmissionId: crypto.randomUUID(),
      attemptId: crypto.randomUUID(),
      patrolId: TEAM.patrolId,
      stopId: stop.id,
      stopName: stop.place,
      captureSlotId: stop.slotIds?.[slot] ?? `${stop.id}-${slot + 1}`,
      capturedAt,
      maskingVersion: masking.maskingVersion(),
      deviceMaskReport: masked.report,
      blob: masked.blob,
    }
    try {
      await outbox.put(item) // 먼저 휴대폰에 저장한 뒤 보낸다
    } catch {
      return toRetake('저장하지 못했어요', '휴대폰 저장 공간을 확인하고 한 번 더 찍어 주세요.')
    }
    setStage('send')
    const r = await outbox.send(item, sendOpts(() => setStage('check')))
    updatePending()
    handleResponse(r)
  }

  /** 서버 답에 따른 화면 (요청서 2장 표) */
  function handleResponse(r: outbox.SendResult) {
    const b = r.body ?? {}
    setServerMsg(b.message ?? undefined) // 서버는 message: null을 보낼 수 있음
    setNext({ nextAction: b.nextAction, nextStopId: b.nextStopId })
    if (!r.done) {
      // 연결 끊김·5xx·처리 중은 접수가 아니다 → 전송 대기 (성공으로 안내하지 않음). 그 밖의 4xx는 보내지 못함
      const later = r.offline || (r.http ?? 0) >= 500 || b.code === 'SUBMISSION_PROCESSING'
      setOutcome(later ? 'queued' : 'error')
      playNotify()
      return setScreen('sent')
    }
    const markSlot = (status: string) => {
      if (!stop.slotStatus) stop.slotStatus = stop.slots.map(() => undefined)
      stop.slotStatus[slot] = status
    }
    // 이미 끝난 구도: 검토로 넘긴 구도는 409 SLOT_RESOLVED, 채택된 구도에 새 사진은 200 + duplicate=true (명세서 6장)
    if (b.code === 'SLOT_RESOLVED' || b.duplicate === true) {
      markSlot('ACCEPTED')
      setOutcome('resolved')
      playNotify()
      return setScreen('sent')
    }
    void refreshPatrol()
    if (b.nextAction === 'RETAKE') {
      setAttempt(attempt + 1)
      if (typeof b.retakesRemaining === 'number') {
        if (!stop.slotRetakes) stop.slotRetakes = stop.slots.map(() => undefined)
        stop.slotRetakes[slot] = b.retakesRemaining
      }
      return toRetake('한 번 더 찍어 주세요', b.message || '다시 한 번 찍어 주세요.')
    }
    if (b.captureStatus === 'REVIEW_REQUESTED') {
      markSlot('REVIEW_REQUESTED')
      setOutcome('review')
      setResults((x) => ({ ...x, [stop.id]: 'review' }))
      playNotify()
    } else {
      markSlot('ACCEPTED')
      setOutcome('ok')
      playSuccess()
    }
    setAttempt(0)
    setScreen('sent')
  }

  // 기기에서 가린 사진을 보낸 뒤 서버가 돌려준 품질 결과만 반영한다
  function afterUpload() {
    const n = attempt + 1
    const fail = blurNext || n <= (stop.blurCount ?? 0)
    setBlurNext(false)
    if (!fail) {
      setAttempt(0)
      setOutcome('ok')
      playSuccess()
      setScreen('sent')
    } else if (n >= MAX_ATTEMPTS) {
      setAttempt(0)
      setOutcome('review')
      playNotify()
      setResults((r) => ({ ...r, [stop.id]: 'review' }))
      setScreen('sent')
    } else {
      setAttempt(n)
      playRetake()
      setScreen('retake')
    }
  }

  function afterSent() {
    const result: StopResult = results[stop.id] === 'review' || outcome === 'review' ? 'review' : 'captured'
    // 실제 촬영: 서버가 정한 다음 행동을 따른다 (전송 대기·보내지 못함이면 다음 구도/장소로)
    if (live && (outcome === 'ok' || outcome === 'review' || outcome === 'resolved')) {
      if (next.nextAction === 'RETURN_TO_CENTER') {
        setResults((r) => ({ ...r, [stop.id]: result }))
        setSlot(0)
        setIdx(STOPS.length)
        return setScreen('return')
      }
      if (next.nextAction === 'NEXT_STOP' && next.nextStopId) {
        const i = STOPS.findIndex((s) => s.id === next.nextStopId)
        if (i >= 0) {
          setResults((r) => ({ ...r, [stop.id]: result }))
          setSlot(0)
          setAttempt(0)
          setIdx(i)
          return setScreen('route')
        }
      }
    }
    // 다음 구도: 아직 안 끝난 구도로
    const nextSlot = stop.slots.findIndex((_, i) => i > slot && !slotDone(stop, i))
    if (nextSlot >= 0) {
      setSlot(nextSlot)
      setScreen('camera')
    } else {
      goNextStop(result)
    }
  }

  function abort() {
    setSheet(null)
    setPaused(false)
    setAborted(true)
    setResults((r) => {
      const next = { ...r }
      STOPS.forEach((s, i) => {
        if (i >= idx && !next[s.id]) next[s.id] = 'notVisited'
      })
      return next
    })
    setIdx(STOPS.length)
    setScreen('return')
  }

  function resetDay() {
    setMyAtt('none')
    setPartnerAtt('none')
    setDeparted(false)
    setIdx(0)
    setSlot(0)
    setAttempt(0)
    setResults({})
    setPaused(false)
    setAborted(false)
    setScreen('home')
  }

  // 풍경이 끝나는 위치: 화면의 첫 카드(또는 data-scene-end 표시)가 시작하는 곳 + 조금 겹침.
  // 글씨를 키워 내용이 길어지면 풍경도 그만큼 아래로 내려가 땅 부분이 늘 첫 카드 바로 위에 온다.
  const mainRef = useRef<HTMLElement>(null)
  const [sceneEnd, setSceneEnd] = useState<number | null>(null)
  useLayoutEffect(() => {
    const main = mainRef.current
    const frame = main?.parentElement
    if (!main || !frame) return
    const measure = () => {
      const el = main.querySelector<HTMLElement>('section, [data-scene-end]')
      setSceneEnd(el ? Math.round(el.getBoundingClientRect().top - frame.getBoundingClientRect().top + 48) : null)
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(main)
    return () => ro.disconnect()
  }, [screen, size, paused])

  const loggedIn = !['start', 'phone', 'code'].includes(screen)
  const inActivity = ['route', 'retake', 'sent'].includes(screen)

  return (
    <div className="min-h-dvh bg-bg">
      {/* data-sky="dark": 해 질 녘·밤처럼 하늘이 어두우면 풍경 위 글씨를 밝게 바꾼다 (index.css) */}
      <div
        data-sky={look.dark && !['camera', 'phone', 'code'].includes(screen) ? 'dark' : 'light'}
        data-bar={look.dark ? 'dark' : 'light'}
        className="relative mx-auto flex min-h-dvh max-w-[440px] flex-col overflow-x-clip bg-bg sm:my-6 sm:min-h-[880px] sm:overflow-hidden sm:rounded-[32px] sm:border sm:border-line sm:shadow-pop"
      >
        {!['camera', 'phone', 'code'].includes(screen) && (
          <SkyBackdrop
            look={look}
            tall={['start', 'home', 'done'].includes(screen)}
            end={sceneEnd}
            ground={!(['sent', 'retake'].includes(screen) && !paused)}
            village={!(['home', 'ready', 'route', 'return'].includes(screen) && !paused)}
          />
        )}
        {loggedIn && <TopBar bar={look.bar} size={size} onSize={() => setSize(((size + 1) % 3) as 0 | 1 | 2)} sound={sound} onSound={() => setSound(!sound)} onHelp={() => setSheet('help')} />}
        {/* 화면마다 새로 그려 살짝 올라오며 나타나게 한다 (screen-in) */}
        <main key={paused ? 'paused' : screen} ref={mainRef} className="screen-in relative flex flex-1 flex-col gap-4 px-5 pt-3 pb-6">
          {paused ? (
            <Paused onResume={() => setPaused(false)} />
          ) : (
            <>
              {screen === 'start' && <Start onNext={() => setScreen('phone')} />}
              {notice && (
                <p className="m-0 rounded-xl bg-g3-soft p-3 text-[0.95rem] text-g3-ink" role="alert">
                  {notice}
                </p>
              )}
              {screen === 'phone' && (
                <PhoneInput
                  value={phone}
                  onChange={setPhone}
                  onNext={() => {
                    if (!REAL_BACKEND) return setScreen('code')
                    requestOtp(phone).then(
                      (c) => {
                        setChallenge(c)
                        setScreen('code')
                      },
                      (e) => say(e, '인증번호를 보내지 못했어요.'),
                    )
                  }}
                />
              )}
              {screen === 'code' && (
                <CodeInput
                  key={codeKey}
                  phone={phone}
                  onBack={() => setScreen('phone')}
                  onResend={() => {
                    if (REAL_BACKEND) requestOtp(phone).then(setChallenge, (e) => say(e, '인증번호를 다시 보내지 못했어요.'))
                  }}
                  onDone={(code) => {
                    if (!REAL_BACKEND) return setScreen('home')
                    if (!challenge) return setScreen('phone')
                    verifyOtp(challenge.challengeId, code).then(
                      () => setScreen('home'),
                      (e) => {
                        say(e, '숫자가 맞지 않아요. 다시 넣어 주세요.')
                        setCodeKey((k) => k + 1)
                      },
                    )
                  }}
                />
              )}
              {screen === 'home' && (
                <Home
                  onGo={() => setScreen('ready')}
                  skyLabel={SKY_PRESETS[skyPick][1]}
                  onSky={() => setSkyPick((skyPick + 1) % SKY_PRESETS.length)}
                  pending={pending}
                />
              )}
              {screen === 'ready' && (
                <Ready
                  myAtt={myAtt}
                  partnerAtt={partnerAtt}
                  departed={departed}
                  onRequest={() => {
                    setMyAtt('requested')
                    if (REAL_BACKEND)
                      requestAttendance(TEAM.patrolId, patrol).then(
                        () => void refreshPatrol(),
                        (e) => {
                          setMyAtt('none')
                          say(e, '출석을 요청하지 못했어요.')
                        },
                      )
                  }}
                  onStart={() => {
                    // 실제 백엔드: 활동 시작을 알린다 (출발 확인을 기다리지 않음. 실패해도 촬영은 이어 감)
                    if (REAL_BACKEND) startActivity(TEAM.patrolId, patrol).catch((e) => say(e, '활동 시작을 알리지 못했어요.'))
                    // 이미 끝난 장소는 건너뛰고 아직 안 끝난 첫 장소부터 (모두 끝났으면 순회 화면이 "모두 마쳤어요"를 보여 줌)
                    const first = STOPS.findIndex((s) => !stopDone(s))
                    setIdx(first < 0 ? 0 : first)
                    const st = STOPS[first < 0 ? 0 : first]
                    setSlot(Math.max(0, st.slots.findIndex((_, i) => !slotDone(st, i))))
                    setScreen('route')
                  }}
                />
              )}
              {screen === 'route' && (
                <Route
                  idx={idx}
                  results={results}
                  onArrive={() => {
                    setCamError('')
                    setScreen('camera')
                  }}
                  onSkip={() => setSheet('skip')}
                  onNextStop={() => goNextStop(results[stop.id] ?? 'captured')}
                  onFinish={() => {
                    setIdx(STOPS.length)
                    setScreen('return')
                  }}
                />
              )}
              {screen === 'camera' && (
                <Camera
                  key={camKey}
                  stop={stop}
                  slot={slot}
                  attempt={attempt}
                  onShoot={shoot}
                  onBlurDemo={() => setBlurNext(true)}
                  blurNext={blurNext}
                  live={live}
                  cam={cam}
                  models={models}
                  modelText={modelText}
                  camError={camError}
                  onLiveError={setCamError}
                  onRetry={() => {
                    if (models === 'failed') prepareModels()
                    setCamError('')
                    setCamKey((k) => k + 1)
                  }}
                />
              )}
              {screen === 'upload' && <Upload onDone={afterUpload} stage={live ? stage : undefined} />}
              {screen === 'retake' && <Retake stop={stop} attempt={attempt} onRetry={() => setScreen('camera')} note={live ? retakeNote : null} shot={live ? shot : undefined} />}
              {screen === 'sent' && <Sent stop={stop} slot={slot} outcome={outcome} last={idx === STOPS.length - 1} onNext={afterSent} message={live ? serverMsg : undefined} shot={live ? shot : undefined} />}
              {screen === 'return' && <Return aborted={aborted} results={results} onBack={() => setScreen('returnWait')} />}
              {screen === 'returnWait' && <ReturnWait results={results} />}
              {screen === 'done' && <Done results={results} aborted={aborted} onHome={resetDay} />}
            </>
          )}
        </main>
        {inActivity && !paused && <ActivityBar onPause={() => setPaused(true)} onAbort={() => setSheet('abort')} />}
        {/* 셔터를 누른 순간 화면이 하얗게 한 번 번쩍인다 (소리와 함께 찍혔다는 것을 눈으로도 알림) */}
        {flash > 0 && <div key={flash} className="shutter-flash pointer-events-none fixed inset-0 z-50 bg-white" aria-hidden />}
      </div>
      {sheet === 'help' && <HelpSheet onClose={() => setSheet(null)} />}
      {sheet === 'skip' && (
        <SkipSheet
          onClose={() => setSheet(null)}
          onSkip={() => {
            setSheet(null)
            goNextStop('skipped')
          }}
        />
      )}
      {sheet === 'abort' && <AbortSheet onClose={() => setSheet(null)} onAbort={abort} />}
    </div>
  )
}

/* ---------------- 공용 조각 ---------------- */

// 화면 위쪽의 풍경 배경 (내용 뒤에 깔림)
// 높이는 첫 카드 위치(end)에 맞춘다. 재기 전이거나 카드가 없는 화면은 글씨 크기(rem)에 비례한 높이를 쓴다.
// 그림은 확대하지 않고 폭에 맞춰 아래에 붙이며, 위쪽 남는 부분은 하늘 위쪽 색으로 이어 칠한다.
// 최소 높이는 그림이 폭에 맞을 때의 높이(폭 × 320/440)라서 옆이 비지 않는다.
// ground=false: 큰 결과 카드가 풍경을 덮어 산·구름 조각만 삐져나오는 화면은 그림 없이 하늘색만 아래로 흐리게 사라지게 한다.
function SkyBackdrop({ tall, end, look, ground = true, village = true }: { tall: boolean; end: number | null; look: SkyLook; ground?: boolean; village?: boolean }) {
  const fade = 'linear-gradient(#000 45%, transparent 85%)'
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-x-0 top-0 overflow-hidden"
      style={{
        height: `max(${end ? `${end}px` : `${tall ? 21 : 15}rem`}, calc(min(100vw, 440px) * 0.7273))`,
        // 하늘색은 시간에 맞춰 바뀐다 (sky.ts)
        background: `linear-gradient(${look.sky[0]} 0%, ${look.sky[1]} 32%, ${look.sky[2]} 68%, ${look.sky[3]} 100%)`,
        ...(ground ? {} : { maskImage: fade, WebkitMaskImage: fade }),
      }}
    >
      {/* 하늘 층: 위쪽 막대 바로 아래에 고정 (막대가 두 줄이 되면 그만큼 아래) */}
      {ground && (
        <div className="absolute inset-x-0 top-[60px] big:top-[96px] max-[429px]:top-[96px]">
          <Scenery part="sky" look={look} className="block h-auto w-full" />
        </div>
      )}
      {/* 땅 층: 첫 카드 바로 위에 고정 */}
      {ground && <Scenery part="ground" look={look} village={village} className="absolute inset-x-0 bottom-0 block h-auto w-full" />}
    </div>
  )
}

/**
 * 하늘 시각: 평소에는 휴대폰 시계를 따르고(1분마다 갱신), 시연용으로 시각을 고르면 그 시각으로 바뀐다.
 * 시각이 바뀔 때는 1.2초 동안 서서히 넘어가 해·하늘색이 툭 바뀌지 않는다.
 */
function useSkyHour(preset: number | null) {
  const [now, setNow] = useState(nowHour)
  useEffect(() => {
    const t = setInterval(() => setNow(nowHour()), 60_000)
    return () => clearInterval(t)
  }, [])
  const target = preset ?? now
  const [shown, setShown] = useState(target)
  const from = useRef(target)
  useEffect(() => {
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    // 24시간이 이어진 원이라 가까운 쪽으로 돈다 (23시 → 1시는 2시간 앞으로)
    let d = target - from.current
    if (d > 12) d -= 24
    if (d < -12) d += 24
    if (reduce || Math.abs(d) < 0.02) {
      from.current = target
      setShown(target)
      return
    }
    const start = from.current
    const t0 = performance.now()
    let raf = 0
    const step = (t: number) => {
      const p = Math.min(1, (t - t0) / 1200)
      const e = p < 0.5 ? 2 * p * p : 1 - (-2 * p + 2) ** 2 / 2
      const h = (start + d * e + 24) % 24
      from.current = h
      setShown(h)
      if (p < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [target])
  return shown
}
const SIZE_NAME = ['기본', '크게', '아주 크게'] as const

function TopBar({ bar, size, onSize, sound, onSound, onHelp }: { bar: string; size: 0 | 1 | 2; onSize: () => void; sound: boolean; onSound: () => void; onHelp: () => void }) {
  return (
    // 화면이 좁거나(430px 미만) 글씨를 키우면 앱 이름이 버튼과 한 줄에 들어가지 않으므로 두 줄로 나눈다
    // 막대 색은 하늘에 맞춰 바뀐다 (반투명)
    <header className="sky-bar sticky top-0 z-30 px-4 py-3 backdrop-blur-md" style={{ backgroundColor: `${bar}bf` }}>
      <div className="flex items-center justify-between gap-2">
      <div className="flex min-w-0 items-center gap-2.5 max-[429px]:flex-none big:flex-none">
        <span className="grid size-[38px] flex-none place-items-center rounded-[14px] bg-brand text-white shadow-card">
          <CameraIcon size={22} />
        </span>
        <div className="min-w-0 leading-tight max-[429px]:hidden big:hidden">
          <p className="m-0 truncate text-[17px] font-extrabold whitespace-nowrap text-ink">동네 점검단</p>
          <p className="m-0 truncate text-[13px] font-semibold whitespace-nowrap text-sub">
            {TEAM.zone} · {TEAM.no}조
          </p>
        </div>
      </div>
      <div className="ml-auto flex flex-none gap-2 max-[379px]:gap-1.5">
        <button
          type="button"
          onClick={onSize}
          aria-label={`글씨 크기: ${SIZE_NAME[size]}. 누르면 더 크게`}
          className="flex h-[46px] flex-none items-center gap-1.5 rounded-full bg-surface px-3 text-ink shadow-card max-[379px]:px-2.5"
        >
          <span className="text-[15px] font-bold whitespace-nowrap">
            가<span className="text-[1.25em]">가</span>
          </span>
          <span className="flex gap-0.5" aria-hidden>
            {[0, 1, 2].map((i) => (
              <i key={i} className={`size-1.5 rounded-full ${i <= size ? 'bg-brand' : 'bg-line-strong'}`} />
            ))}
          </span>
        </button>
        <button
          type="button"
          onClick={onSound}
          aria-pressed={sound}
          aria-label={sound ? '효과음 켜짐. 누르면 끄기' : '효과음 꺼짐. 누르면 켜기'}
          className={`grid size-[46px] flex-none place-items-center rounded-full shadow-card ${sound ? 'bg-surface text-brand' : 'bg-surface/70 text-mute'}`}
        >
          <SoundIcon on={sound} />
        </button>
        <button type="button" onClick={onHelp} className="flex h-[46px] flex-none items-center gap-1.5 rounded-full bg-g3 px-4 text-[17px] max-[379px]:px-3 font-bold whitespace-nowrap text-white shadow-card">
          <PhoneIcon size={18} /> 도움
        </button>
      </div>
      </div>
      <p className="m-0 mt-2 hidden min-w-0 text-[15px] leading-tight whitespace-nowrap max-[429px]:flex big:flex">
        <span className="min-w-0 truncate">
          <b className="font-extrabold text-ink">동네 점검단</b>
          <span className="font-semibold text-sub"> · {TEAM.zone} · {TEAM.no}조</span>
        </span>
      </p>
    </header>
  )
}

function Primary({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex min-h-[4rem] w-full items-center justify-center gap-2.5 rounded-full bg-brand px-5 text-[1.2rem] font-bold huge:px-4 huge:text-[1.05rem] huge:leading-snug text-white shadow-[0_8px_20px_rgba(47,107,235,0.3)] transition-colors active:bg-brand-700 disabled:bg-line-strong disabled:text-mute disabled:shadow-none"
    >
      {children}
    </button>
  )
}

function Secondary({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex min-h-[3.6rem] w-full items-center justify-center gap-2 rounded-full bg-surface px-5 text-[1.05rem] font-bold text-ink shadow-card active:bg-bg"
    >
      {children}
    </button>
  )
}

function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-[1.6rem] bg-surface p-5 shadow-card ${className}`}>{children}</section>
}

/** chip: 풍경 위에서는 설명 문장을 반투명 흰 알약 위에 올려 읽기 쉽게 한다 */
function H1({ children, sub, eyebrow, chip = false }: { children: ReactNode; sub?: ReactNode; eyebrow?: ReactNode; chip?: boolean }) {
  return (
    <div className="on-sky pt-1">
      {eyebrow && <p className="m-0 mb-1 text-[0.92rem] font-bold tracking-[-0.01em] text-brand">{eyebrow}</p>}
      <h1 className="m-0 text-[1.75rem] leading-[1.28] font-extrabold tracking-[-0.035em] break-keep text-balance text-ink">{children}</h1>
      {sub &&
        (chip ? (
          <p className="m-0 mt-2.5 inline-block rounded-2xl bg-surface/90 px-3.5 py-1.5 text-[0.98rem] leading-snug font-semibold tracking-[-0.01em] break-keep text-balance text-sub shadow-card backdrop-blur">{sub}</p>
        ) : (
          <p className="m-0 mt-2 text-[1rem] leading-relaxed font-medium tracking-[-0.01em] break-keep text-balance text-sub">{sub}</p>
        ))}
    </div>
  )
}
function Tag({ purpose }: { purpose: Stop['purpose'] }) {
  return purpose === 'REINSPECTION' ? (
    <span className="rounded-full bg-g2-soft px-2.5 py-0.5 text-[0.85rem] font-bold text-g2-ink">다시 찍기</span>
  ) : (
    <span className="rounded-full bg-brand-50 px-2.5 py-0.5 text-[0.85rem] font-bold text-brand">살펴보기</span>
  )
}

/** sticky: 내용이 길어도 주요 버튼이 늘 화면 아래에 보이도록 붙여 둔다 (위쪽은 배경색으로 흐리게 이어짐) */
function Bottom({ children, sticky = false }: { children: ReactNode; sticky?: boolean }) {
  if (sticky)
    return (
      <div className="sticky bottom-0 z-10 -mx-5 mt-auto -mb-6 grid gap-2.5 bg-gradient-to-t from-bg from-70% to-bg/0 px-5 pt-6 pb-5">{children}</div>
    )
  return <div className="mt-auto grid gap-2.5 pt-2">{children}</div>
}

function Dots() {
  return (
    <div className="flex justify-center gap-2" aria-hidden>
      <i className="pulse-dot size-3 rounded-full bg-brand" />
      <i className="pulse-dot size-3 rounded-full bg-brand [animation-delay:.2s]" />
      <i className="pulse-dot size-3 rounded-full bg-brand [animation-delay:.4s]" />
    </div>
  )
}

/** 동그라미 단계 표시 (출석 스탬프처럼) */
function StepPath({ steps, at }: { steps: string[]; at: number }) {
  return (
    <ol className="m-0 flex list-none items-start p-0" aria-label="진행 단계">
      {steps.map((s, i) => {
        const done = i < at
        const now = i === at
        return (
          <li key={s} className="relative flex flex-1 flex-col items-center gap-1.5 text-center">
            {i > 0 && <i className={`absolute top-[20px] right-1/2 h-1 w-full -translate-y-1/2 rounded-full ${i <= at ? 'bg-brand' : 'bg-line'}`} aria-hidden />}
            <span
              className={`relative z-10 grid size-[40px] place-items-center rounded-full text-[16px] font-extrabold ${
                done ? 'bg-brand text-white' : now ? 'bg-surface text-brand ring-4 ring-brand' : 'bg-surface text-mute ring-2 ring-line'
              }`}
            >
              {done ? <CheckMark size={20} /> : i + 1}
            </span>
            <span className={`text-[0.88rem] leading-tight whitespace-nowrap ${now ? 'font-extrabold text-brand' : done ? 'font-bold text-ink' : 'text-mute'}`}>{s}</span>
          </li>
        )
      })}
    </ol>
  )
}

// 활동 중 아래 고정 막대: 잠깐 쉬기 / 오늘 활동 그만하기 (체크리스트 3-4)
function ActivityBar({ onPause, onAbort }: { onPause: () => void; onAbort: () => void }) {
  return (
    <div className="sticky bottom-0 z-10 grid grid-cols-2 gap-2.5 rounded-t-[1.6rem] bg-surface px-5 pt-3 pb-4 shadow-[0_-6px_20px_rgba(30,60,110,0.08)]">
      <button type="button" onClick={onPause} className="flex min-h-[54px] items-center justify-center gap-2 rounded-full bg-brand-50 text-[16px] font-bold whitespace-nowrap text-brand big:text-[17px]">
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden>
          <rect x="6" y="5" width="4" height="14" rx="1.5" fill="currentColor" />
          <rect x="14" y="5" width="4" height="14" rx="1.5" fill="currentColor" />
        </svg>
        잠깐 쉬기
      </button>
      <button type="button" onClick={onAbort} className="flex min-h-[54px] items-center justify-center gap-2 rounded-full bg-bg text-[16px] font-bold whitespace-nowrap text-ink big:text-[17px]">
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden>
          <rect x="6" y="6" width="12" height="12" rx="2.5" fill="currentColor" />
        </svg>
        오늘 활동 그만하기
      </button>
    </div>
  )
}

/* ---------------- 로그인 ---------------- */

function Start({ onNext }: { onNext: () => void }) {
  return (
    <>
      {/* 아이콘이 언덕 그림에 걸치지 않도록 풍경 아래, 버튼 쪽에 모아 둔다 */}
      <div className="flex flex-1 flex-col justify-end gap-6 pt-[17rem] pb-8">
        <div className="pop-in grid size-20 place-items-center rounded-[1.6rem] bg-brand text-white shadow-[0_10px_24px_rgba(47,107,235,0.35)]">
          <CameraIcon size={40} />
        </div>
        <div>
          <h1 className="m-0 text-[2.2rem] leading-tight font-extrabold tracking-tight">동네 점검단</h1>
          <p className="m-0 mt-2 text-[1.15rem] leading-relaxed font-medium text-sub">
            우리 동네 시설을 사진으로 살피는
            <br />
            어르신 점검원 앱이에요.
          </p>
        </div>
      </div>
      <Bottom>
        <Primary onClick={onNext}>휴대폰 번호로 시작하기</Primary>
        <p className="m-0 text-center text-[0.85rem] text-mute">등록은 담당 선생님이 해 드려요.</p>
      </Bottom>
    </>
  )
}

function fmtPhone(d: string) {
  if (d.length <= 3) return d
  if (d.length <= 7) return `${d.slice(0, 3)}-${d.slice(3)}`
  return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`
}

function Keypad({ onKey, onDelete }: { onKey: (k: string) => void; onDelete: () => void }) {
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', 'del']
  return (
    <div className="grid grid-cols-3 gap-2">
      {keys.map((k, i) =>
        k === '' ? (
          <span key={i} />
        ) : (
          <button
            key={i}
            type="button"
            onClick={() => (k === 'del' ? onDelete() : onKey(k))}
            aria-label={k === 'del' ? '지우기' : k}
            className="h-[3.8rem] rounded-2xl bg-surface text-[1.6rem] font-bold text-ink shadow-card active:bg-brand-50"
          >
            {k === 'del' ? '←' : k}
          </button>
        ),
      )}
    </div>
  )
}

/** 시연 번호. 실제 백엔드의 시연 계정(local 프로필)은 010-0000-0001·0002만 인증번호로 들어갈 수 있다 */
const DEMO_PHONE = REAL_BACKEND ? '010-0000-0001' : ME.phone

function PhoneInput({ value, onChange, onNext }: { value: string; onChange: (v: string) => void; onNext: () => void }) {
  const ok = value.length === 11 && value.startsWith('01')
  return (
    <>
      <div className="pt-8">
        <H1 sub="문자로 인증번호를 보내 드려요.">휴대폰 번호를 넣어 주세요</H1>
      </div>
      <div className="rounded-[1.6rem] bg-surface px-4 py-5 text-center text-[1.9rem] font-extrabold tracking-wide tabular-nums shadow-card ring-2 ring-brand">
        {value ? fmtPhone(value) : <span className="text-line-strong">010-0000-0000</span>}
      </div>
      {!value && (
        <button type="button" onClick={() => onChange(DEMO_PHONE.replace(/-/g, ''))} className="self-center rounded-full bg-brand-50 px-4 py-2 text-[0.95rem] font-semibold text-brand">
          이 휴대폰 번호 쓰기 · {DEMO_PHONE}
        </button>
      )}
      <Bottom>
        <Keypad onKey={(k) => value.length < 11 && onChange(value + k)} onDelete={() => onChange(value.slice(0, -1))} />
        <Primary onClick={onNext} disabled={!ok}>
          인증번호 받기
        </Primary>
      </Bottom>
    </>
  )
}

function CodeInput({ phone, onBack, onDone, onResend }: { phone: string; onBack: () => void; onDone: (code: string) => void; onResend?: () => void }) {
  const [code, setCode] = useState('')
  const [left, setLeft] = useState(180)
  useEffect(() => {
    const t = setInterval(() => setLeft((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(t)
  }, [])
  useEffect(() => {
    if (code.length === 6) {
      const t = setTimeout(() => onDone(code), 400)
      return () => clearTimeout(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code])
  const mm = Math.floor(left / 60)
  const ss = String(left % 60).padStart(2, '0')
  return (
    <>
      <div className="pt-8">
        <H1 sub={`${fmtPhone(phone)}로 보낸 숫자예요.`}>문자로 받은 숫자 6개를 넣어 주세요</H1>
      </div>
      <div className="flex justify-between gap-2" aria-label={`입력한 숫자 ${code.length}개`}>
        {Array.from({ length: 6 }, (_, i) => (
          <div
            key={i}
            className={`grid h-16 flex-1 place-items-center rounded-2xl bg-surface text-[1.8rem] font-extrabold shadow-card ${i === code.length ? 'ring-2 ring-brand' : ''}`}
          >
            {code[i] ?? ''}
          </div>
        ))}
      </div>
      <div className="flex items-center justify-between text-[0.95rem]">
        <span className={left < 30 ? 'font-semibold text-g3' : 'text-sub'}>
          남은 시간 {mm}:{ss}
        </span>
        <button
          type="button"
          onClick={() => {
            setLeft(180)
            setCode('')
            onResend?.()
          }}
          className="font-semibold text-brand underline underline-offset-4"
        >
          인증번호 다시 받기
        </button>
      </div>
      {left === 0 && <p className="m-0 rounded-xl bg-g3-soft p-3 text-[0.95rem] text-g3-ink">시간이 지났어요. 인증번호를 다시 받아 주세요.</p>}
      <p className="m-0 text-[0.85rem] text-mute">{REAL_BACKEND ? '시연용: 담당자가 알려 준 숫자 6개를 넣어 주세요.' : '시연용: 아무 숫자 6개나 넣으면 돼요.'}</p>
      <Bottom>
        <Keypad onKey={(k) => left > 0 && code.length < 6 && setCode(code + k)} onDelete={() => setCode(code.slice(0, -1))} />
        <button type="button" onClick={onBack} className="min-h-11 text-[0.95rem] font-semibold text-sub">
          번호 다시 넣기
        </button>
      </Bottom>
    </>
  )
}

/* ---------------- 오늘 순회 ---------------- */

function Home({ onGo, skyLabel, onSky, pending }: { onGo: () => void; skyLabel: string; onSky: () => void; pending: number }) {
  return (
    <>
      <div className="pt-2">
        {pending > 0 && (
          <p className="m-0 mb-2 inline-flex items-center gap-2 rounded-full bg-g2-soft px-3.5 py-1.5 text-[0.95rem] font-bold text-g2-ink shadow-card" role="status">
            <i className="pulse-dot size-2 rounded-full bg-g2" aria-hidden />
            전송 대기 {pending}장 · 연결되면 자동으로 보내요
          </p>
        )}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-surface/80 px-3 py-1 text-[0.85rem] font-bold text-g0-ink shadow-card">
            <i className="size-2 rounded-full bg-g0" /> 점검원
          </span>
          {/* 시연용: 누를 때마다 지금 시간 → 새벽 → 오전 → 한낮 → 오후 → 해 질 녘 → 밤 하늘로 바뀐다. 실제 서비스에서는 뺀다 */}
          <button
            type="button"
            onClick={onSky}
            aria-label={`시연용 하늘 시간 바꾸기. 지금: ${skyLabel}`}
            className="inline-flex items-center gap-1 rounded-full border border-dashed border-ink/25 bg-surface/70 px-3 py-1 text-[0.8rem] font-semibold text-sub backdrop-blur"
          >
            시연용 하늘 · {skyLabel}
            <span aria-hidden>›</span>
          </button>
        </div>
        <div className="on-sky mt-3">
          <h1 className="m-0 text-[2rem] leading-[1.2] font-extrabold tracking-[-0.04em] text-ink">{ME.name} 어르신</h1>
          <p className="m-0 mt-2.5 inline-block rounded-2xl bg-surface/90 px-3.5 py-1.5 text-[1rem] leading-snug font-semibold tracking-[-0.01em] text-sub shadow-card backdrop-blur">오늘도 반가워요. 함께 동네를 살펴봐요!</p>
        </div>
        <p className="m-0 mt-3 inline-flex items-center gap-2 rounded-full bg-surface/85 py-1.5 pr-4 pl-2 text-[0.98rem] font-semibold text-sub shadow-card backdrop-blur">
          <SunIcon /> 맑음 · 낮 21도 · 겉옷을 챙기세요
        </p>
      </div>

      <Card className="mt-6 big:mt-9">
        <div className="flex items-center justify-between">
          <p className="m-0 text-[1.15rem] font-extrabold">오늘의 순회</p>
          <span className="rounded-full bg-brand-50 px-3 py-1 text-[0.85rem] font-bold text-brand">{TEAM.no}조</span>
        </div>
        <div className="mt-4 grid grid-cols-3 gap-2 text-center big:grid-cols-1">
          {[
            ['센터 출석', TEAM.meetAt],
            ['찍을 장소', `${STOPS.length}곳`],
            ['센터 복귀', TEAM.backBy.replace('오전 ', '')],
          ].map(([l, v]) => (
            <div key={l} className="rounded-2xl bg-bg px-1.5 py-3 big:flex big:items-center big:justify-between big:px-4 big:py-2.5 big:text-left">
              <p className="m-0 text-[0.88rem] font-semibold text-mute">{l}</p>
              <p className="m-0 mt-0.5 text-[1.1rem] font-extrabold whitespace-nowrap big:mt-0">{v}</p>
            </div>
          ))}
        </div>
        <p className="m-0 mt-4 text-[1rem] font-semibold text-sub">{TEAM.center}</p>
        <div className="mt-4 flex items-center gap-3 rounded-2xl bg-mint/70 px-4 py-3 big:flex-col big:items-start big:gap-2">
          <span className="flex -space-x-2" aria-hidden>
            <span className="grid size-9 place-items-center rounded-full bg-brand text-[0.9rem] font-bold text-white ring-2 ring-surface">{ME.name[0]}</span>
            <span className="grid size-9 place-items-center rounded-full bg-g0 text-[0.9rem] font-bold text-white ring-2 ring-surface">{PARTNER.name[0]}</span>
          </span>
          <p className="m-0 text-[1rem] font-semibold">
            {ME.name}(나) · {PARTNER.name} 어르신과 함께 다녀요
          </p>
        </div>
      </Card>

      <Card>
        <p className="m-0 mb-3 text-[1.15rem] font-extrabold">오늘 찍을 장소</p>
        <ol className="m-0 grid list-none gap-0 p-0">
          {STOPS.map((s, i) => (
            <li key={s.id} className="relative flex items-center gap-3 py-2">
              {i < STOPS.length - 1 && <i className="absolute top-[2.6rem] left-[1.15rem] h-[calc(100%-1.6rem)] w-0.5 bg-brand-100" aria-hidden />}
              <span className="relative grid size-[2.3rem] flex-none place-items-center rounded-full bg-brand-50 text-[0.95rem] font-extrabold text-brand ring-2 ring-surface">{i + 1}</span>
              <span className="min-w-0 flex-1">
                <span className="block text-[1.08rem] font-bold">{KIND_NAME[s.kind]}</span>
                <span className="block text-[0.9rem] text-mute">{s.place}</span>{s.purpose === 'REINSPECTION' && (<span className="mt-1 hidden big:block"><Tag purpose={s.purpose} /></span>)}
              </span>
              {s.purpose === 'REINSPECTION' && (<span className="big:hidden"><Tag purpose={s.purpose} /></span>)}
            </li>
          ))}
        </ol>
      </Card>

      <Bottom sticky>
        <Primary onClick={onGo}>센터에 도착했어요</Primary>
      </Bottom>
    </>
  )
}

// 출석 요청 → (담당자 출석 확인) → 각자 활동 시작.
// 담당자 출발 확인과 개인 시작은 서로 기다리지 않는 별개 기록이라, 출석을 요청하면 바로 시작할 수 있다 (D-02)
function Ready({
  myAtt,
  partnerAtt,
  departed,
  onRequest,
  onStart,
}: {
  myAtt: Attend
  partnerAtt: Attend
  departed: boolean
  onRequest: () => void
  onStart: () => void
}) {
  const attText = (a: Attend) => (a === 'present' ? '출석 확인됨' : a === 'requested' ? '선생님 확인 중' : '아직이에요')
  let title = '출석을 요청해 주세요'
  let sub: ReactNode = `요청하면 ${STAFF.name} 선생님이 출석을 확인해요.`
  if (myAtt !== 'none') {
    title = '활동을 시작해도 좋아요'
    sub = '선생님 확인을 기다리지 않고 바로 시작할 수 있어요.'
  }
  return (
    <>
      <H1 chip eyebrow={`오늘 ${TEAM.no}조 출석`} sub={sub}>{title}</H1>
      <Card className="mt-2 !py-3.5 big:mt-6">
        <StepPath steps={['출석 요청', '활동 시작']} at={myAtt === 'none' ? 0 : 1} />
      </Card>
      <Card>
        <p className="m-0 mb-3 text-[1.1rem] font-extrabold">오늘 {TEAM.no}조 출석</p>
        <ul className="m-0 grid list-none grid-cols-2 gap-3 p-0 huge:grid-cols-1">
          {[
            [`${ME.name} (나)`, myAtt],
            [PARTNER.name, partnerAtt],
          ].map(([n, a]) => (
            <li key={n} className={`flex flex-col items-center gap-2 rounded-2xl px-2 py-4 huge:flex-row huge:gap-4 huge:px-4 huge:py-3 ${a === 'present' ? 'bg-g0-soft' : 'bg-bg'}`}>
              <span className={`relative grid size-14 place-items-center rounded-full text-[1.3rem] font-extrabold text-white ${a === 'present' ? 'bg-g0' : 'bg-line-strong'}`}>
                {n[0]}
                {a === 'present' && <span className="pop-in absolute -right-1 -bottom-1 grid size-7 place-items-center rounded-full bg-surface text-g0 shadow-card"><CheckMark size={16} /></span>}
              </span>
              <span className="flex flex-col items-center gap-1 huge:items-start"><span className="text-[1.02rem] font-bold">{n}</span>
              <Check ok={a === 'present'} text={attText(a as Attend)} /></span>
            </li>
          ))}
        </ul>
      </Card>
      {/* 출발 확인은 기다릴 필요가 없는 기록이라, 아직이면 흐리게 보여 준다 */}
      {departed ? (
        <Card className="flex items-center justify-between !py-4">
          <span className="text-[1.05rem] font-bold">선생님 출발 확인</span>
          <Check ok text="확인됨" />
        </Card>
      ) : (
        <p className="m-0 flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 rounded-[1.6rem] bg-surface/60 px-5 py-3.5 text-[0.98rem]">
          <span className="font-bold text-sub">선생님 출발 확인</span>
          <span className="font-semibold text-mute">기다리지 않아도 돼요</span>
        </p>
      )}
      <Bottom sticky>
        {myAtt === 'none' ? (
          <Primary onClick={onRequest}>출석 요청하기</Primary>
        ) : (
          <Primary onClick={onStart}>활동 시작하기</Primary>
        )}
      </Bottom>
    </>
  )
}

function RouteMap({ idx, toCenter = false, results }: { idx: number; toCenter?: boolean; results?: Record<string, StopResult> }) {
  const center = { x: 30, y: 30 }
  const pts = [center, ...STOPS]
  let d = `M${pts[0].x} ${pts[0].y}`
  for (let i = 1; i < pts.length; i++) d += ` H${pts[i].x} V${pts[i].y}`
  const xs = [...new Set(pts.map((p) => p.x))]
  const ys = [...new Set(pts.map((p) => p.y))]
  return (
    <div data-scene-end className="overflow-hidden rounded-[1.6rem] bg-[#EAF3EE] shadow-card big:mt-3">
      <svg viewBox="0 0 260 160" className="block h-auto w-full" role="img" aria-label="오늘 코스 지도">
        <rect x="120" y="66" width="76" height="40" rx="6" fill="#CBE8D5" />
        <rect x="50" y="68" width="40" height="36" rx="6" fill="#E3E6EA" />
        {ys.map((y) => (
          <path key={`h${y}`} d={`M0 ${y} H260`} stroke="#fff" strokeWidth="10" />
        ))}
        {xs.map((x) => (
          <path key={`v${x}`} d={`M${x} 0 V160`} stroke="#fff" strokeWidth="10" />
        ))}
        <path d={d} stroke="#2F6BEB" strokeWidth="3" fill="none" strokeDasharray="6 5" strokeLinecap="round" />
        {/* 출발·복귀 장소: 집 모양 + "센터" 표지 */}
        <g transform={`translate(${center.x - 19} ${center.y - 10})`}>
          <rect width="38" height="20" rx="5" fill={toCenter ? '#E5484D' : '#20A464'} />
          <path d="M5 11.5 L10 7 L15 11.5 M6.5 10.5 V15 H13.5 V10.5" stroke="#fff" strokeWidth="1.6" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          <text x="26" y="13.6" textAnchor="middle" fontSize="8.5" fontWeight="800" fill="#fff">
            센터
          </text>
        </g>
        {STOPS.map((s, i) => {
          // 복귀 화면: 결과가 있으면 찍은 곳 ✓, 건너뛴 곳 –, 못 간 곳은 번호만 흐리게(점선 테두리)
          const r = results?.[s.id]
          const missed = results ? !r || r === 'notVisited' : false
          const done = results ? !missed : i < idx
          const now = i === idx && !toCenter
          const mark = r === 'skipped' ? '–' : '✓'
          return (
            <g key={s.id}>
              <circle
                cx={s.x}
                cy={s.y}
                r={now ? 12 : 9}
                fill={now ? '#2F6BEB' : missed ? '#F2F4F6' : '#fff'}
                stroke={missed ? '#B0B8C1' : done ? '#8B95A1' : '#2F6BEB'}
                strokeWidth="2.5"
                strokeDasharray={missed ? '3 2.5' : undefined}
              />
              <text x={s.x} y={s.y + 4} textAnchor="middle" fontSize="11" fontWeight="700" fill={now ? '#fff' : missed ? '#B0B8C1' : done ? '#8B95A1' : '#2F6BEB'}>
                {done ? mark : i + 1}
              </text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function Progress({ idx }: { idx: number }) {
  const n = Math.min(idx + 1, STOPS.length)
  return (
    // 위: 안내·숫자(왼쪽)와 복귀 시각(오른쪽) / 아래: 진행 점 줄을 화면 폭 전체로.
    // 글씨를 키워도 좁은 칸에 끼지 않도록 두 줄로 나눈다.
    <div className="on-sky grid gap-3 pt-1">
      <div className="flex flex-wrap items-end justify-between gap-x-3 gap-y-2">
        <div>
          <p className="m-0 text-[0.92rem] font-bold tracking-[-0.01em] whitespace-nowrap text-brand">오늘 찍을 장소</p>
          <span className="sr-only">
            {STOPS.length}곳 중 {n}번째
          </span>
          <p className="m-0 text-[2rem] leading-tight font-extrabold tracking-[-0.04em] whitespace-nowrap">
            <span className="text-brand">{n}</span>
            <span className="text-[1.1rem] font-bold text-mute"> / {STOPS.length}곳</span>
          </p>
        </div>
        <span className="mb-1 inline-block rounded-full bg-surface/90 px-3 py-1 text-[0.85rem] font-semibold whitespace-nowrap text-sub shadow-card backdrop-blur">센터 복귀 {TEAM.backBy}</span>
      </div>
      {/* 풍경 위에서도 선이 보이도록 흰 반투명 띠 위에 올린다 */}
      <div className="flex items-center rounded-full bg-surface/80 px-3.5 py-2.5 shadow-card backdrop-blur" aria-hidden>
        {STOPS.map((_, i) => (
          <span key={i} className="flex flex-1 items-center last:flex-none">
            <i className={`size-[14px] flex-none rounded-full ${i < idx ? 'bg-brand' : i === idx ? 'bg-surface ring-4 ring-brand' : 'bg-surface ring-2 ring-line-strong'}`} />
            {i < STOPS.length - 1 && <i className={`h-1 flex-1 ${i < idx ? 'bg-brand' : 'bg-line-strong/60'}`} />}
          </span>
        ))}
      </div>
    </div>
  )
}
function Route({
  idx,
  results,
  onArrive,
  onSkip,
  onNextStop,
  onFinish,
}: {
  idx: number
  results: Record<string, StopResult>
  onArrive: () => void
  onSkip: () => void
  onNextStop: () => void
  onFinish: () => void
}) {
  const s = STOPS[idx]
  const prev = idx > 0 ? results[STOPS[idx - 1].id] : undefined
  // 오늘 순회가 모두 끝났으면 첫 장소를 다시 열지 않는다 (찍어 올려도 '이미 끝난 곳'으로 거절되기만 함)
  if (STOPS.every(stopDone)) {
    return (
      <>
        <H1 chip sub="더 찍을 곳이 없어요. 센터로 돌아가 주세요.">오늘 순회를 모두 마쳤어요</H1>
        <RouteMap idx={STOPS.length} toCenter />
        <Bottom>
          <Primary onClick={onFinish}>센터로 돌아가기</Primary>
        </Bottom>
      </>
    )
  }
  // 이미 끝난 장소는 찍기 버튼을 끈다 (찍어도 사진을 다 올린 뒤에야 '이미 끝난 곳'이라는 답이 옴)
  const done = stopDone(s)
  return (
    <>
      <Progress idx={idx} />
      <RouteMap idx={idx} />
      <Card>
        <div className="flex gap-4 big:flex-col">
          <Photo kind={s.kind} bad={false} {...s.refPhoto} className="w-24 flex-none big:w-40" />
          <div className="min-w-0">
            <Tag purpose={s.purpose} />
            <p className="m-0 mt-1.5 text-[1.5rem] leading-tight font-extrabold">
              {idx + 1}. {KIND_NAME[s.kind]}
            </p>
            <p className="m-0 mt-0.5 text-[1.02rem] font-semibold text-sub">
              {s.place}
              {s.dist && (
                <>
                  {' · '}
                  <span className="whitespace-nowrap">약 {s.dist}</span>
                </>
              )}
            </p>
          </div>
        </div>
        <p className="m-0 mt-4 rounded-2xl bg-bg px-4 py-3 text-[1.02rem] font-medium">{s.instruction}</p>
        {s.slots.length > 1 && <p className="m-0 mt-2 text-[0.98rem] font-bold text-brand">이곳은 사진 {s.slots.length}장을 찍어요</p>}
      </Card>
      {prev === 'skipped' && <p className="m-0 text-[0.9rem] text-mute">앞 장소는 건너뛴 것으로 기록했어요.</p>}
      <p className="m-0 text-[0.9rem] text-mute">{PARTNER.name} 어르신 휴대폰에도 같은 진행이 보여요.</p>
      <Bottom>
        {done ? (
          <>
            <p className="m-0 rounded-2xl bg-g0-soft px-4 py-3 text-center text-[1.02rem] font-bold text-g0-ink">이미 끝난 곳이에요</p>
            <Primary onClick={onNextStop}>다음 장소로</Primary>
          </>
        ) : (
          <>
            <Primary onClick={onArrive}>
              <CameraIcon size={24} /> 도착했어요 · 사진 찍기
            </Primary>
            <button type="button" onClick={onSkip} className="min-h-11 text-[0.95rem] font-semibold text-sub underline underline-offset-4">
              이곳은 찍을 수 없어요
            </button>
          </>
        )}
      </Bottom>
    </>
  )
}

/** 받침에 맞춰 목적격 조사를 붙인다: 보도블록 → 보도블록을, 벤치 → 벤치를 */
function withObj(word: string) {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  if (code < 0 || code > 11171) return `${word}을(를)`
  return `${word}${code % 28 ? '을' : '를'}`
}

function Camera({
  stop,
  slot,
  attempt,
  blurNext,
  onShoot,
  onBlurDemo,
  live,
  cam,
  models,
  modelText,
  camError,
  onLiveError,
  onRetry,
}: {
  stop: Stop
  slot: number
  attempt: number
  blurNext: boolean
  onShoot: () => void
  onBlurDemo: () => void
  /** 실제 촬영(https)인지. http 주소에서만 그림 시연 */
  live: boolean
  cam: RefObject<CameraHandle | null>
  /** 가림 모델 준비 상태. 준비되기 전에는 찍지 않는다 (가리지 못한 사진이 생기지 않게) */
  models: 'loading' | 'ready' | 'failed'
  modelText: string
  camError: string
  onLiveError: (why: string) => void
  /** 모델·카메라 다시 준비하기 */
  onRetry: () => void
}) {
  const recheck = stop.purpose === 'REINSPECTION'
  const [overlay, setOverlay] = useState(true)
  // 실제 카메라는 화면이 나오기 시작해야 찍을 수 있다 (그 전에 누르면 아무 일도 안 일어나는 것을 막음)
  const [camReady, setCamReady] = useState(false)
  const canShoot = !live || (camReady && models === 'ready' && !camError)
  const many = stop.slots.length > 1
  // 남은 다시 찍기 횟수: 서버가 알려 준 값(retakesRemaining)을 쓰고, 없으면 앱에서 센 값
  const left = stop.slotRetakes?.[slot] ?? MAX_ATTEMPTS - attempt
  const retrying = attempt > 0 || stop.slotStatus?.[slot] === 'RETAKE_REQUIRED'
  // 셔터 아래 안내: 찍을 수 없는 이유를 쉬운 말로
  const wait = !live
    ? null
    : camError
      ? camError
      : models === 'failed'
        ? '준비가 안 됐어요. 담당자에게 연락해 주세요.'
        : models === 'loading'
          ? `사진 가리기를 준비하고 있어요${modelText ? ` · ${modelText}` : ''}`
          : !camReady
            ? '카메라를 켜는 중이에요'
            : null
  return (
    <>
      <div>
        {many && (
          <p className="m-0 mb-1.5 inline-block rounded-full bg-brand px-3 py-1 text-[0.9rem] font-bold text-white">
            {stop.slots.length}장 중 {slot + 1}번째 · {stop.slots[slot]}
          </p>
        )}
        <H1 sub={recheck ? (stop.prevDate ? `${stop.prevDate}에 찍은 사진이 겹쳐 보여요.` : '지난번 사진이 겹쳐 보여요.') : stop.instruction}>
          {recheck ? '지난번 사진에 맞춰 찍어 주세요' : `${withObj(KIND_NAME[stop.kind])} 네모 안에 맞춰 주세요`}
        </H1>
      </div>
      <div className="relative grid flex-1 place-items-center overflow-hidden rounded-[1.8rem] bg-[#141a23] p-5 shadow-pop">
        <div className="relative w-full">
          {live ? (
            <LiveCamera handle={cam} onError={onLiveError} onReady={setCamReady} />
          ) : (
            <Photo kind={stop.kind} bad={!!stop.bad} {...stop.shotPhoto} className="w-full opacity-90" />
          )}
          {/* 재점검: 이전 가린 사진을 반투명하게 겹쳐 보기 (SEN-11) */}
          {recheck && overlay && <Photo kind={stop.kind} bad {...stop.refPhoto} className="pointer-events-none absolute inset-0 w-full opacity-35 mix-blend-screen" />}
          <div className="pointer-events-none absolute inset-[8%] rounded-2xl border-[3px] border-white/90" />
        </div>
        {/* 기본 글씨: 사진 틀 위 아래쪽에 / 글씨를 키우면 사진을 가리지 않게 틀 밖으로 */}
        <p className="absolute right-3 bottom-3 left-3 m-0 rounded-full bg-black/55 px-3 py-2 text-center text-[0.82rem] font-semibold text-white big:hidden">
          사람 얼굴과 자동차 번호판은 자동으로 가려요
        </p>
      </div>
      <p className="m-0 -mt-1 hidden text-center text-[0.9rem] font-semibold text-sub big:block">사람 얼굴과 자동차 번호판은 자동으로 가려요</p>
      {recheck && (
        <button type="button" onClick={() => setOverlay(!overlay)} aria-pressed={overlay} className="self-center text-[0.95rem] font-semibold text-brand underline underline-offset-4">
          지난 사진 겹쳐 보기 {overlay ? '끄기' : '켜기'}
        </button>
      )}
      <div className="flex flex-col items-center gap-1 pt-1">
        <button
          type="button"
          onClick={onShoot}
          disabled={!canShoot}
          aria-label="눌러서 사진 찍기"
          className="size-[5.5rem] rounded-full border-[7px] border-surface bg-brand shadow-[0_0_0_3px_rgba(47,107,235,0.35),0_10px_24px_rgba(47,107,235,0.35)] active:scale-95 disabled:bg-line-strong disabled:shadow-none"
        />
        <p className="m-0 text-center text-[1rem] font-bold text-ink" role={wait ? 'status' : undefined}>
          {wait ?? '눌러서 찍기'}
        </p>
        {(camError || models === 'failed') && (
          <button type="button" onClick={onRetry} className="min-h-11 text-[0.95rem] font-semibold text-brand underline underline-offset-4">
            다시 준비하기
          </button>
        )}
        {retrying && !wait && <p className="m-0 text-[0.9rem] text-sub">다시 찍는 중 · {left}번 남았어요</p>}
        {!live && (
          <>
            <button type="button" onClick={onBlurDemo} disabled={blurNext} className="mt-1 text-[0.8rem] text-mute underline underline-offset-2 disabled:no-underline">
              {blurNext ? '시연용: 다음 사진은 흔들리게 찍혀요' : '시연용: 흔들린 사진으로 찍기'}
            </button>
            <p className="m-0 text-[0.75rem] text-mute">시연 화면 · 카메라는 https 주소에서만 켜져요</p>
          </>
        )}
      </div>
    </>
  )
}

/** 실제 촬영 진행 문구: 가리는 중 → 보내는 중(사진 올리기) → 결과 확인 중(서버·AI 처리) */
const UPLOAD_TEXT = {
  mask: ['얼굴과 번호판을 가리고 있어요', '가린 사진만 보내요'],
  send: ['사진을 보내고 있어요', '통신이 끊기면 "전송 대기"로 보관했다가 다시 보내요'],
  check: ['잘 찍혔는지 보고 있어요', '조금만 기다려 주세요'],
} as const

/**
 * 가리기·보내기 진행 화면.
 * stage가 있으면 실제 촬영: 진행 단계를 그대로 보여 주고 끝은 부른 쪽이 정한다.
 * stage가 없으면 시연: 막대가 차오른 뒤 onDone을 부른다.
 */
function Upload({ onDone, stage }: { onDone: () => void; stage?: 'mask' | 'send' | 'check' }) {
  const [p, setP] = useState(0)
  const done = useRef(false)
  useEffect(() => {
    if (stage) return
    const t = setInterval(() => setP((v) => Math.min(100, v + 6)), 80)
    return () => clearInterval(t)
  }, [stage])
  if (stage) {
    return (
      <div className="grid flex-1 place-items-center text-center" role="status">
        <div className="w-full">
          <span className="pop-in mx-auto mb-5 grid size-20 place-items-center rounded-full bg-surface text-brand shadow-card"><CameraIcon size={38} /></span>
          <p className="m-0 text-[1.5rem] font-extrabold tracking-[-0.03em]">{UPLOAD_TEXT[stage][0]}</p>
          <p className="m-0 mt-1 text-[1rem] text-sub">{UPLOAD_TEXT[stage][1]}</p>
          <div className="mt-6">
            <Dots />
          </div>
        </div>
      </div>
    )
  }
  return <UploadDemo p={p} done={done} onDone={onDone} />
}

function UploadDemo({ p, done, onDone }: { p: number; done: { current: boolean }; onDone: () => void }) {
  useEffect(() => {
    if (p >= 100 && !done.current) {
      const t = setTimeout(() => {
        done.current = true
        onDone()
      }, 700)
      return () => clearTimeout(t)
    }
  }, [p, onDone, done])
  const step = p < 40 ? 0 : p < 100 ? 1 : 2
  const titles = ['얼굴과 번호판을 가리고 있어요', '사진을 보내고 있어요', '잘 찍혔는지 보고 있어요']
  return (
    <div className="grid flex-1 place-items-center text-center">
      <div className="w-full">
        <span className="pop-in mx-auto mb-5 grid size-20 place-items-center rounded-full bg-surface text-brand shadow-card"><CameraIcon size={38} /></span>
        <p className="m-0 text-[1.5rem] font-extrabold tracking-[-0.03em]">{titles[step]}</p>
        <p className="m-0 mt-1 text-[1rem] text-sub">
          {step === 0 ? '가린 사진만 보내요' : '통신이 끊기면 "전송 대기"로 보관했다가 다시 보내요'}
        </p>
        <div className="mx-auto mt-6 h-3 w-4/5 overflow-hidden rounded-full bg-surface shadow-card" aria-hidden>
          <div className="h-full rounded-full bg-brand transition-[width]" style={{ width: `${p}%` }} />
        </div>
        {step === 2 && (
          <div className="mt-5">
            <Dots />
          </div>
        )}
      </div>
    </div>
  )
}

// 품질 재촬영: 처음 + 2번까지 (체크리스트 3-1)
// 남은 횟수는 어르신께 부담이 될 수 있어 이 화면에서는 보여 주지 않는다
// note: 실제 촬영에서 서버 안내 문구(message)나 가리기·저장 실패 안내가 있으면 그 문구를 쓴다. shot: 방금 찍은(가린) 사진
function Retake({ stop, onRetry, note, shot }: { stop: Stop; attempt: number; onRetry: () => void; note?: { title: string; sub: string } | null; shot?: string }) {
  return (
    <>
      <div className="rounded-[1.6rem] bg-g2-soft p-5" role="status">
        <p className="m-0 text-[1.65rem] font-extrabold text-g2-ink">{note?.title ?? '사진이 흐려요'}</p>
        <p className="m-0 mt-1 text-[1.05rem] text-ink">{note?.sub ?? '다시 한 번 찍어 주세요.'}</p>
      </div>
      {/* 사진과 안내를 위아래로 두어 안내 문장이 좁은 칸에서 끊기지 않게 */}
      <Card className="grid gap-4">
        <Photo kind={stop.kind} bad={!!stop.bad} {...(shot ? { src: shot } : stop.shotPhoto)} className={`mx-auto w-full max-w-[16rem] ${shot ? '' : 'blur-[2.5px]'}`} />
        <ol className="m-0 grid list-none gap-2 p-0 text-[1.1rem] font-semibold">
          {['휴대폰을 잠깐 멈추고', '한 걸음 가까이 가서', '버튼을 꾹 눌러 주세요'].map((t, i) => (
            <li key={t} className="flex items-center gap-3">
              <span className="grid size-8 flex-none place-items-center rounded-full bg-brand-50 text-[0.95rem] font-extrabold text-brand" aria-hidden>
                {i + 1}
              </span>
              <span className="whitespace-nowrap">{t}</span>
            </li>
          ))}
        </ol>
      </Card>
      <Bottom>
        <Primary onClick={onRetry}>
          <CameraIcon size={24} /> 다시 찍기
        </Primary>
      </Bottom>
    </>
  )
}

// 제출 완료: 판정 결과 없이 접수와 다음 행동만 안내한다 (체크리스트 1-1)
// outcome: ok 접수 / review 선생님 확인 요청 / queued 전송 대기(연결되면 자동 재전송) / error 보내지 못함
// message: 서버 안내 문구가 있으면 그대로 보여 준다 (명세: 서버 message를 그대로 표시)
// shot: 방금 찍은(가린) 사진. 시연에서는 없음
export type SentOutcome = 'ok' | 'review' | 'queued' | 'error' | 'resolved'
const SENT_TEXT: Record<SentOutcome, [string, string]> = {
  ok: ['사진을 보냈어요', '고마워요! 확인은 담당 공무원이 해요.'],
  review: ['선생님께 확인을 부탁했어요', '세 번 찍어도 흐려서 담당 선생님이 확인할게요. 다음으로 가요.'],
  queued: ['전송 대기 중이에요', '연결되면 자동으로 보낼게요. 다음 장소로 가셔도 돼요.'],
  error: ['보내지 못했어요', '사진은 휴대폰에 보관했어요. 담당 선생님께 알려 주세요.'],
  resolved: ['이미 끝난 곳이에요', '다음 장소로 가 주세요.'],
}
function Sent({ stop, slot, outcome, last, onNext, message, shot }: { stop: Stop; slot: number; outcome: SentOutcome; last: boolean; onNext: () => void; message?: string; shot?: string }) {
  const moreSlots = slot < stop.slots.length - 1
  const warn = outcome !== 'ok'
  return (
    <>
      <div className={`flex flex-col items-center rounded-[1.6rem] p-6 text-center ${warn ? 'bg-g2-soft' : 'bg-g0-soft'}`} role="status">
        <span className={`pop-in mb-3 grid size-16 place-items-center rounded-full bg-surface text-[1.8rem] font-extrabold shadow-card ${warn ? 'text-g2-ink' : 'text-g0'}`} aria-hidden>
          {outcome === 'queued' ? <Dots /> : warn ? '!' : <CheckMark size={32} />}
        </span>
        <p className={`m-0 text-[1.65rem] leading-tight font-extrabold ${warn ? 'text-g2-ink' : 'text-g0-ink'}`}>{SENT_TEXT[outcome][0]}</p>
        <p className="m-0 mt-1 text-[1.05rem] text-ink">{message || SENT_TEXT[outcome][1]}</p>
      </div>
      <Card className="flex items-center gap-4 big:flex-col big:items-start">
        <Photo kind={stop.kind} bad={!!stop.bad} {...(shot ? { src: shot } : stop.shotPhoto)} className={`w-28 flex-none big:w-40 ${outcome === 'review' && !shot ? 'blur-[2.5px]' : ''}`} />
        <div>
          <p className="m-0 text-[1.1rem] font-bold">{KIND_NAME[stop.kind]}</p>
          <p className="m-0 text-[0.95rem] text-sub">{stop.place}</p>
          {stop.slots.length > 1 && (
            <p className="m-0 mt-1 text-[0.9rem] text-mute">
              {stop.slots.length}장 중 {slot + 1}장 · {stop.slots[slot]}
            </p>
          )}
        </div>
      </Card>
      <Bottom>
        <Primary onClick={onNext}>{moreSlots ? `다음 사진 찍기 · ${stop.slots[slot + 1]}` : last ? '센터로 돌아가기' : '다음 장소로'}</Primary>
      </Bottom>
    </>
  )
}

function Paused({ onResume }: { onResume: () => void }) {
  return (
    <>
      <div className="flex flex-1 flex-col justify-center gap-4 text-center">
        <span className="pop-in mx-auto grid size-24 place-items-center rounded-full bg-surface text-brand shadow-pop ring-8 ring-brand-50" aria-hidden>
          <svg width="40" height="40" viewBox="0 0 24 24">
            <rect x="6" y="5" width="4" height="14" rx="1.5" fill="currentColor" />
            <rect x="14" y="5" width="4" height="14" rx="1.5" fill="currentColor" />
          </svg>
        </span>
        <H1
          sub={
            <>
              물 한 모금 드시고, 준비되면 다시 시작해요.
              <br />
              선생님 화면에는 '쉬는 중'으로 보여요.
            </>
          }
        >
          잠깐 쉬고 있어요
        </H1>
      </div>
      <Bottom>
        <Primary onClick={onResume}>다시 시작하기</Primary>
      </Bottom>
    </>
  )
}

function count(results: Record<string, StopResult>) {
  const v = Object.values(results)
  return {
    captured: v.filter((r) => r === 'captured').length,
    review: v.filter((r) => r === 'review').length,
    skipped: v.filter((r) => r === 'skipped').length,
    notVisited: v.filter((r) => r === 'notVisited').length,
  }
}

function Summary({ results }: { results: Record<string, StopResult> }) {
  const c = count(results)
  // 칸마다 아이콘을 붙여 글을 읽지 않아도 알아보게 하고, 0인 칸은 흐리게 둔다
  const items: [string, number, ReactNode, string][] = [
    ['찍은 장소', c.captured + c.review, <CameraIcon size={20} />, 'bg-brand-50 text-brand'],
    ['건너뛴 장소', c.skipped, <SkipIcon />, 'bg-g2-soft text-g2-ink'],
    ['선생님 확인 요청', c.review, <span className="text-[1.05rem] leading-none font-extrabold">?</span>, 'bg-g2-soft text-g2-ink'],
    ['못 간 장소', c.notVisited, <FlagIcon />, 'bg-bg text-sub'],
  ]
  return (
    <dl className="m-0 grid grid-cols-2 gap-2 huge:grid-cols-1">
      {items.map(([l, n, icon, tint]) => (
        <div key={l} className={`flex flex-col-reverse gap-0.5 rounded-[1.4rem] bg-surface px-4 py-4 shadow-card ${n === 0 ? 'opacity-60' : ''}`}>
          <dt className="text-[0.92rem] font-semibold text-sub">{l}</dt>
          <dd className="m-0 flex items-center gap-2.5">
            <span className={`grid size-9 flex-none place-items-center rounded-full ${n === 0 ? 'bg-bg text-mute' : tint}`} aria-hidden>
              {icon}
            </span>
            <span className={`text-[1.8rem] leading-tight font-extrabold tabular-nums ${n === 0 ? 'text-mute' : 'text-ink'}`}>{n}</span>
          </dd>
        </div>
      ))}
    </dl>
  )
}

function Return({ aborted, results, onBack }: { aborted: boolean; results: Record<string, StopResult>; onBack: () => void }) {
  return (
    <>
      <H1 chip sub={aborted ? '남은 장소는 다음에 찍어요. 천천히 센터로 와 주세요.' : `${TEAM.backBy}까지 센터로 와 주세요.`}>
        {aborted ? '오늘 활동은 여기까지 해요' : '오늘 장소를 다 돌았어요'}
      </H1>
      <RouteMap idx={STOPS.length} toCenter results={results} />
      <Summary results={results} />
      <Bottom>
        <Primary onClick={onBack}>센터에 도착했어요</Primary>
      </Bottom>
    </>
  )
}

function ReturnWait({ results }: { results: Record<string, StopResult> }) {
  return (
    <>
      {/* 제목이 마을 그림 위에 겹치지 않도록 카드 안에 둔다 */}
      <Card className="mt-24 text-center big:mt-28">
        <h1 className="m-0 text-[1.6rem] leading-[1.3] font-extrabold tracking-[-0.035em] break-keep text-balance text-ink">
          {STAFF.name} 선생님이 확인하고 있어요
        </h1>
        <p className="m-0 mt-1.5 text-[1.02rem] font-semibold text-sub">선생님이 복귀를 확인하면 끝나요.</p>
        <div className="mt-4">
          <Dots />
        </div>
      </Card>
      <Summary results={results} />
    </>
  )
}

function Done({ results, aborted, onHome }: { results: Record<string, StopResult>; aborted: boolean; onHome: () => void }) {
  const c = count(results)
  return (
    <>
      <div className="flex flex-1 flex-col justify-center gap-4 text-center">
        <span className="pop-in mx-auto grid size-24 place-items-center rounded-full bg-surface text-g0 shadow-pop ring-8 ring-g0-soft">
          <svg width="46" height="46" viewBox="0 0 24 24" aria-hidden>
            <path d="M5 12.5 10 17l9-10" stroke="currentColor" strokeWidth="2.8" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
        {/* 인사말이 풍경(땅·나무) 위에 겹치면 읽기 어려워 결과 카드 안에 함께 둔다 */}
        <Card>
          <h1 className="m-0 text-[1.75rem] leading-[1.28] font-extrabold tracking-[-0.035em] break-keep text-balance text-ink">수고하셨어요!</h1>
          <p className="m-0 mt-1 text-[1.1rem] font-semibold text-sub">조심히 들어가세요</p>
          <hr className="mx-auto my-4 w-full border-0 border-t border-line" />
          <p className="m-0 text-[1rem] text-mute">오늘 활동</p>
          <p className="m-0 text-[1.7rem] font-extrabold text-brand">
            {c.captured + c.review}곳 촬영{c.skipped + c.notVisited > 0 ? ` · ${c.skipped + c.notVisited}곳 다음에` : ''}
          </p>
          <p className="m-0 mt-1 text-[0.9rem] text-sub">출석·복귀는 선생님이 확인해서 기록했어요</p>
        </Card>
        {aborted && <p className="m-0 text-[1rem] text-sub">몸이 불편하시면 꼭 {STAFF.name} 선생님께 말씀해 주세요.</p>}
        <p className="m-0 text-[1.05rem]">
          다음 활동은 <b>10월 14일 (수)</b>이에요.
        </p>
      </div>
      <Bottom>
        <Secondary onClick={onHome}>처음 화면으로</Secondary>
      </Bottom>
    </>
  )
}

/* ---------------- 아래에서 올라오는 창 ---------------- */

function SheetFrame({ title, sub, children, onClose }: { title: string; sub?: string; children: ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center bg-ink/50" role="dialog" aria-modal="true" aria-label={title} onClick={onClose}>
      <div className="w-full max-w-[440px] rounded-t-[2rem] bg-surface p-6 pb-8" onClick={(e) => e.stopPropagation()}>
        <div className="mx-auto mb-4 h-1.5 w-10 rounded-full bg-line" />
        <p className="m-0 text-[1.45rem] font-extrabold">{title}</p>
        {sub && <p className="m-0 mt-1 mb-1 text-[1rem] text-sub">{sub}</p>}
        <div className="mt-4 grid gap-2.5">{children}</div>
      </div>
    </div>
  )
}

function CallRow({ href, title, sub, danger }: { href: string; title: string; sub: string; danger?: boolean }) {
  return (
    <a
      href={href}
      className={`flex min-h-[4.4rem] items-center gap-3 rounded-[1.4rem] px-5 no-underline ${danger ? 'bg-g3 text-white' : 'bg-brand-50 text-ink'}`}
    >
      <PhoneIcon size={22} />
      <span className="flex-1">
        <span className="block text-[1.15rem] font-bold">{title}</span>
        <span className={`block text-[0.88rem] ${danger ? 'text-white/85' : 'text-mute'}`}>{sub}</span>
      </span>
    </a>
  )
}

// 연락은 전화로만 (체크리스트 2-4). 조원 전화번호는 노출하지 않음 (1-8)
function HelpSheet({ onClose }: { onClose: () => void }) {
  return (
    <SheetFrame title="도움이 필요하세요?" sub="누르면 바로 전화가 걸려요." onClose={onClose}>
      <CallRow href={`tel:${STAFF.phone}`} title={`${STAFF.name} 선생님`} sub={`${STAFF.org} 담당자`} />
      <CallRow href="tel:119" title="119 긴급 전화" sub="다치거나 위급할 때" danger />
      <Secondary onClick={onClose}>닫기</Secondary>
    </SheetFrame>
  )
}

function ChoiceButton({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="min-h-[3.8rem] rounded-[1.4rem] bg-bg px-5 text-left text-[1.12rem] font-bold active:bg-brand-50">
      {children}
    </button>
  )
}

function SkipSheet({ onClose, onSkip }: { onClose: () => void; onSkip: () => void }) {
  return (
    <SheetFrame title="왜 찍을 수 없나요?" sub="고르면 이 장소는 건너뛰고 기록해 둬요." onClose={onClose}>
      {SKIP_REASONS.map((r) => (
        <ChoiceButton key={r.code} onClick={onSkip}>
          {r.label}
        </ChoiceButton>
      ))}
      <Secondary onClick={onClose}>취소</Secondary>
    </SheetFrame>
  )
}

function AbortSheet({ onClose, onAbort }: { onClose: () => void; onAbort: () => void }) {
  return (
    <SheetFrame title="오늘 활동을 그만할까요?" sub="남은 장소는 '못 감'으로 기록하고 센터로 돌아가요." onClose={onClose}>
      {ABORT_REASONS.map((r) => (
        <ChoiceButton key={r.code} onClick={onAbort}>
          {r.label}
        </ChoiceButton>
      ))}
      <Secondary onClick={onClose}>계속할게요</Secondary>
    </SheetFrame>
  )
}

/* ---------------- 작은 조각 ---------------- */

function Check({ ok, text }: { ok: boolean; text: string }) {
  return <span className={`text-[0.95rem] font-semibold whitespace-nowrap ${ok ? 'text-g0-ink' : 'text-mute'}`}>{ok ? `✓ ${text}` : text}</span>
}

function SoundIcon({ on }: { on: boolean }) {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden>
      <path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4z" fill="currentColor" />
      {on ? (
        <path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" />
      ) : (
        <path d="M16 9.5l5 5M21 9.5l-5 5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      )}
    </svg>
  )
}

function CheckMark({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
      <path d="M5 12.5 10 17l9-10" stroke="currentColor" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function SkipIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M5 6l7 6-7 6M13 6l7 6-7 6" />
    </svg>
  )
}

function FlagIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M5 21V4M5 4h11l-2 4 2 4H5" />
    </svg>
  )
}

function CameraIcon({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
      <path d="M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinejoin="round" />
      <circle cx="12" cy="13" r="3.6" fill="none" stroke="currentColor" strokeWidth="1.9" />
    </svg>
  )
}
function PhoneIcon({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
      <path d="M6.6 3.5 9 6.6c.4.5.3 1.2-.1 1.6L7.6 9.5a11 11 0 0 0 6.9 6.9l1.3-1.3c.4-.4 1.1-.5 1.6-.1l3.1 2.4c.5.4.6 1.2.1 1.7l-1.4 1.5c-.6.6-1.5.9-2.4.6C9.9 19.4 4.6 14.1 2.8 7.2c-.3-.9 0-1.8.6-2.4L4.9 3.4c.5-.5 1.3-.4 1.7.1Z" fill="currentColor" />
    </svg>
  )
}
function SunIcon() {
  return (
    <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden className="flex-none text-brand-600">
      <circle cx="20" cy="20" r="7.5" fill="currentColor" />
      {Array.from({ length: 8 }, (_, i) => (
        <rect key={i} x="19" y="3" width="2.4" height="6" rx="1.2" fill="currentColor" transform={`rotate(${i * 45} 20 20)`} />
      ))}
    </svg>
  )
}
