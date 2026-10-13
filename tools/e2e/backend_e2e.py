"""업무 백엔드(:8080) ↔ AI 판독 모듈(:8000) 연결을 실제 API로 확인하는 시험 도구. 파이썬 기본 기능만 쓴다.

  python tools/e2e/backend_e2e.py smoke          # 시니어 로그인 → 출석 → 담당자 확인 → 활동 시작 → 사진 1장 → AI 결과 → 검토 목록
  python tools/e2e/backend_e2e.py confirm        # 시니어 앱으로 시험할 때: 담당자 대신 출석(2명)·출발을 확인

필요한 것: 백엔드가 local 프로필로 떠 있고(시연 로그인 켜짐), AI 판독 모듈이 떠 있을 것. 자세한 순서는 README.md.
환경 변수
  BACKEND_URL               기본 http://localhost:8080
  DEMO_OTP_CODE             백엔드에 넣은 시연 인증번호 6자리 (smoke)
  DEMO_ADMIN_TEMP_PASSWORD  백엔드에 넣은 시연 관리자 임시 비밀번호
  ADMIN_PASSWORD            첫 로그인 때 바꿀 새 비밀번호 (8자 이상, 영문·숫자·기호). 이미 바꿨으면 이 값으로 로그인
                            (demo-activity·demo-road 둘 다 같은 값으로 바꾼다)
  PHOTO                     보낼 사진 (기본 ai/eval/sample/s01.jpg)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/") + "/api/v1"
ROOT = Path(__file__).resolve().parents[2]
KST = timezone(timedelta(hours=9))
PATROL = "30000000-0000-4000-8000-000000000001"
SENIORS = [("010-0000-0001", "20000000-0000-4000-8000-000000000001"), ("010-0000-0002", "20000000-0000-4000-8000-000000000002")]
ACTIVITY_LOGIN = "demo-activity"  # 출석·출발 확인은 활동 담당자만 할 수 있음
REVIEW_LOGIN = "demo-road"  # 검토 목록은 시설 담당자(도로)만 볼 수 있음


class Client:
    """쿠키(CSRF·갱신·비밀번호 변경)와 Bearer 토큰을 직접 다룬다. 백엔드 쿠키는 Secure라서 http에서는 urllib가 보내지 않기 때문"""

    def __init__(self, name):
        self.name, self.cookies, self.token, self.csrf = name, {}, None, None

    def call(self, method, path, body=None, files=None, headers=None, ok=(200, 201, 202, 204)):
        h = {"Accept": "application/json", **(headers or {})}
        data = None
        if files is not None:
            boundary = uuid.uuid4().hex
            parts = []
            for k, v in (body or {}).items():
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
            for k, (fname, content, ctype) in files.items():
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"; filename="{fname}"\r\nContent-Type: {ctype}\r\n\r\n'.encode() + content + b"\r\n")
            data = b"".join(parts) + f"--{boundary}--\r\n".encode()
            h["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        elif body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if method != "GET":
            if not self.csrf:
                self.csrf = self.call("GET", "/auth/csrf")["csrfToken"]
            h["X-CSRF-Token"] = self.csrf
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        if self.cookies:
            h["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, raw, setc = r.status, r.read(), r.headers.get_all("Set-Cookie") or []
        except urllib.error.HTTPError as e:
            status, raw, setc = e.code, e.read(), e.headers.get_all("Set-Cookie") or []
        for c in setc:
            k, _, v = c.split(";", 1)[0].partition("=")
            self.cookies[k.strip()] = v.strip()
        out = json.loads(raw) if raw.strip() else {}
        if status not in ok:
            raise RuntimeError(f"[{self.name}] {method} {path} → {status} {out.get('code', '')} {out.get('message', '')} {out.get('details', '')}".strip())
        return out


def step(msg):
    print(f"\n▶ {msg}")


def senior_login(phone):
    code = os.environ.get("DEMO_OTP_CODE")
    if not code:
        sys.exit("DEMO_OTP_CODE가 없습니다 (백엔드에 넣은 시연 인증번호 6자리)")
    c = Client(f"시니어 {phone}")
    ch = c.call("POST", "/auth/demo/otp-requests", {"phoneNumber": phone})
    tok = c.call("POST", "/auth/demo/otp-verifications", {"challengeId": ch["challengeId"], "code": code, "clientId": "e2e-" + phone, "deviceLabel": "e2e"})
    c.token = tok["accessToken"]
    print(f"  {tok['user']['name']} 로그인")
    return c


def admin_login(login=ACTIVITY_LOGIN):
    temp, new = os.environ.get("DEMO_ADMIN_TEMP_PASSWORD"), os.environ.get("ADMIN_PASSWORD")
    if not (temp or new):
        sys.exit("DEMO_ADMIN_TEMP_PASSWORD 또는 ADMIN_PASSWORD가 필요합니다")
    a = Client(f"관리자 {login}")
    body = lambda pw: {"loginId": login, "password": pw, "clientId": "e2e-admin", "deviceLabel": "e2e"}
    for pw in [p for p in (new, temp) if p]:
        try:
            r = a.call("POST", "/auth/admin/sessions", body(pw))
        except RuntimeError as e:
            if " 401 " in str(e):
                continue
            raise
        if r.get("passwordChangeRequired"):
            if not new:
                sys.exit("첫 로그인이라 비밀번호를 바꿔야 합니다. ADMIN_PASSWORD(새 비밀번호)를 넣어 주세요")
            a.call("POST", "/auth/password-changes", {"currentPassword": pw, "newPassword": new})
            print("  임시 비밀번호를 ADMIN_PASSWORD로 바꿈")
            a = Client(f"관리자 {login}")
            r = a.call("POST", "/auth/admin/sessions", body(new))
        a.token = r["accessToken"]
        print(f"  {r['user']['name']}({login}) 로그인")
        return a
    sys.exit("관리자 로그인 실패: 비밀번호를 확인해 주세요")


def confirm(a):
    """담당자 대신 출석(2명)과 출발을 확인한다. 이미 확인된 것은 건너뜀"""
    p = a.call("GET", f"/admin/patrols/{PATROL}")
    v = p["version"]
    for m in p.get("members", []):
        if m.get("attendance") == "PRESENT":
            print(f"  {m['name']}: 이미 출석")
            continue
        body = {"attendance": "PRESENT", "version": v}
        if not m.get("attendanceRequestedAt"):
            body["proxyReason"] = "시험: 담당자가 대신 출석 확인"
        v = a.call("PUT", f"/admin/patrols/{PATROL}/attendance/{m['participantId']}", body)["version"]
        print(f"  {m['name']}: 출석 확인")
    r = a.call("POST", f"/admin/patrols/{PATROL}/departure-confirmations", {"version": v}, headers={"Idempotency-Key": str(uuid.uuid4())})
    print(f"  출발 확인: {r.get('departureConfirmedAt')}")


def event():
    return {"clientEventId": str(uuid.uuid4()), "occurredAt": datetime.now(KST).isoformat(timespec="seconds")}


def smoke():
    today = datetime.now(KST).date().isoformat()
    step("1. 시니어 로그인과 오늘 순회")
    s = senior_login(SENIORS[0][0])
    items = s.call("GET", f"/me/patrols?date={today}").get("items", [])
    if not items:
        sys.exit(f"  {today}에 배정된 순회가 없습니다. 시연 순회를 오늘로 옮기세요 (README: move_demo_date.sql)")
    p = s.call("GET", f"/patrols/{items[0]['id']}")
    stop, slot = p["stops"][0], p["stops"][0]["captureSlots"][0]
    print(f"  {p['teamName']} · {p['routeName']} · 장소 {len(p['stops'])}곳 → 첫 장소 '{stop['name']}' 구도 '{slot['label']}'")

    step("2. 출석 요청 → 담당자 출석·출발 확인 → 활동 시작")
    s.call("POST", f"/patrols/{p['id']}/attendance-requests", event())
    print("  출석 요청 보냄")
    confirm(admin_login())
    r = s.call("POST", f"/patrols/{p['id']}/activity-starts", event())
    print(f"  활동 시작: 조 상태 {r.get('activityStatus')}")

    step("3. 사진 제출 → AI 판독")
    photo = Path(os.environ.get("PHOTO", ROOT / "ai" / "eval" / "sample" / "s01.jpg"))
    fields = {
        "clientSubmissionId": str(uuid.uuid4()), "captureSlotId": slot["id"], "attemptId": str(uuid.uuid4()),
        "capturedAt": datetime.now(KST).isoformat(timespec="seconds"), "maskingVersion": "e2e-1", "deviceMasked": "true",
        "deviceMaskReport": json.dumps({"faces": 0, "plates": 0, "vehicles": 0, "detector": "e2e", "appVersion": "e2e"}),
    }
    t0 = time.time()
    r = s.call("POST", f"/patrols/{p['id']}/stops/{stop['id']}/inspections", fields, files={"file": (photo.name, photo.read_bytes(), "image/jpeg")})
    print(f"  접수 {r.get('receiptStatus')} · 구도 상태 {r.get('captureStatus')} · 다음 {r.get('nextAction')} ({time.time() - t0:.1f}초)")
    insp = r.get("inspectionId")
    for _ in range(30):
        if r.get("captureStatus") not in (None, "PROCESSING", "RECEIVED", "ANALYZING"):
            break
        time.sleep(2)
        r = s.call("GET", f"/inspections/{insp}/capture-status")
    print(f"  최종 구도 상태: {r.get('captureStatus')} {r.get('message') or ''}")

    step("4. 시설 담당자 검토 목록")
    a = admin_login(REVIEW_LOGIN)
    rows = a.call("GET", "/admin/review-tasks").get("items", [])
    print(f"  검토 작업 {len(rows)}건" + (f" · 최근: {json.dumps(rows[0], ensure_ascii=False)[:200]}" if rows else ""))
    print("\n✔ 백엔드 ↔ AI 연결 흐름 끝")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        if cmd == "smoke":
            smoke()
        elif cmd == "confirm":
            confirm(admin_login())
        else:
            print(__doc__)
    except RuntimeError as e:
        sys.exit(f"✘ {e}")
