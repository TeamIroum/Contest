"""시연 데이터 초기화(reset-demo.ps1) 보조: 시연 관리자 계정 6개의 첫 로그인 비밀번호 변경을 마쳐 둔다.

초기화하면 관리자 계정이 임시 비밀번호(DEMO_ADMIN_TEMP_PASSWORD) 상태로 돌아가므로, 발표 때 바로 들어갈 수 있게
ADMIN_PASSWORD로 바꿔 둔다. tools/e2e/backend_e2e.py의 로그인 함수를 그대로 쓴다.
필요한 환경 변수: BACKEND_URL, DEMO_ADMIN_TEMP_PASSWORD, ADMIN_PASSWORD
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "e2e"))
import backend_e2e as e  # noqa: E402

for login in ("demo-activity", "demo-road", "demo-sewer", "demo-park", "demo-program", "demo-admin"):
    e.admin_login(login)
