// 체험판 사진 보관함 (서버 없이 이 브라우저 안에만).
// 체험판 시니어 앱이 마스킹한 사진을 여기에 두고, 같은 주소 아래의 체험판 관리자 웹이 읽어 검토 목록에 보여 준다.
// 두 앱이 같은 출처(예: https://teamiroum.github.io)에 있어야 같은 저장소를 본다. 어디로도 보내지 않는다.

/** 체험판 빌드인지 (VITE_TRIAL=1). 실제 서비스 빌드에서는 늘 false */
export const TRIAL = import.meta.env.VITE_TRIAL === '1'

export type TrialPhoto = {
  id: string
  /** 찍은 시각 (ISO) */
  at: string
  /** 시설 종류 (sidewalk·drain·bench·fence·exercise 등) */
  kind: string
  /** 장소 이름 */
  place: string
  /** 구도 이름 */
  slot: string
  /** 마스킹한 사진 (원본은 저장하지 않음) */
  blob: Blob
  /** 가린 개수 */
  masked: { faces: number; plates: number; vehicles: number }
}

const DB = 'iroum-trial'
const STORE = 'photos'

function open(): Promise<IDBDatabase> {
  return new Promise((res, rej) => {
    const r = indexedDB.open(DB, 1)
    r.onupgradeneeded = () => r.result.createObjectStore(STORE, { keyPath: 'id' })
    r.onsuccess = () => res(r.result)
    r.onerror = () => rej(r.error)
  })
}

async function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T> | void): Promise<T | undefined> {
  const db = await open()
  return new Promise((res, rej) => {
    const t = db.transaction(STORE, mode)
    const req = fn(t.objectStore(STORE))
    t.oncomplete = () => {
      db.close()
      res(req ? req.result : undefined)
    }
    t.onerror = () => rej(t.error)
  })
}

/** 사진 하나 넣기. 바이트로 넣는다 (아이폰 사파리는 IndexedDB에 Blob을 넣다 실패하는 경우가 있음) */
export async function addPhoto(p: Omit<TrialPhoto, 'id' | 'at'>): Promise<void> {
  const bytes = await p.blob.arrayBuffer()
  const row = { ...p, blob: undefined, bytes, type: p.blob.type || 'image/jpeg', id: crypto.randomUUID(), at: new Date().toISOString() }
  await run('readwrite', (s) => s.put(row))
}

/** 모든 사진 (최근 것 먼저) */
export async function listPhotos(): Promise<TrialPhoto[]> {
  try {
    const rows = ((await run('readonly', (s) => s.getAll())) ?? []) as (Omit<TrialPhoto, 'blob'> & { bytes: ArrayBuffer; type: string })[]
    return rows
      .map(({ bytes, type, ...r }) => ({ ...r, blob: new Blob([bytes], { type }) }))
      .sort((a, b) => b.at.localeCompare(a.at))
  } catch {
    return [] // 사생활 보호 모드 등으로 저장소를 못 쓰면 빈 목록
  }
}

/** 체험 처음부터: 사진을 모두 지운다 */
export async function clearPhotos(): Promise<void> {
  await run('readwrite', (s) => s.clear()).catch(() => undefined)
}
