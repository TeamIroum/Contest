"""시니어 동네 점검단 체험판을 만들어 GitHub Pages(TeamIroum/service 저장소의 trial/)에 올린다.

체험판은 서버 없이 각자의 브라우저 안에서만 움직인다 (여러 사람이 동시에 써도 서로 섞이지 않는다).
  - 시니어 앱: 바로 체험하기(로그인 없음) → 출석·출발 확인 건너뜀 → 실제 카메라로 찍고 휴대폰에서 얼굴·번호판 마스킹
    (카메라를 못 쓰면 그림 촬영) → 마스킹한 사진은 이 브라우저 안에만 보관
  - 관리자 웹: 예시 데이터 + 같은 브라우저에서 찍은 내 사진을 검토 목록 맨 위에 보여 줌 (AI 판독은 하지 않음)
실제 서비스(이 PC의 업무 백엔드·AI)와는 빌드 설정(.env.trial)으로만 나뉘고, 실제 서비스 화면·규칙은 바뀌지 않는다.

  https://teamiroum.github.io/service/trial/          입구 (시니어 앱 / 관리자 웹, 체험 순서, QR)
  https://teamiroum.github.io/service/trial/senior/   시니어 앱 체험판
  https://teamiroum.github.io/service/trial/admin/    관리자 웹 체험판

trial/만 새로 쓴다. 저장소의 다른 폴더(입구 페이지, test/)는 건드리지 않는다.

실행:  python service/web/pages/deploy_trial.py --out <폴더>    # 만들기만 (올리지 않음). <폴더>/service/trial/ 아래에 생김
       python service/web/pages/deploy_trial.py                 # 만들어서 올림
필요: npm(두 앱 빌드), pip install segno (QR 그림)
"""
from __future__ import annotations

import argparse
import html
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVICE = HERE.parent.parent
REPO = "https://github.com/TeamIroum/service.git"
BASE = "/service/trial/"
URL = "https://teamiroum.github.io/service/trial/"

STYLE = """
:root{--bg:#f4f6fa;--ink:#191f28;--sub:#4e5968;--mute:#8b95a1;--brand:#2f6beb;--brand-soft:#e8f0fe;--card:#fff;--line:#e5e8eb}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--ink);
font-family:Pretendard,-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif}
main{max-width:520px;margin:0 auto;padding:48px 20px 40px;display:flex;flex-direction:column;gap:14px}
.logo{width:64px;height:64px;border-radius:20px;background:var(--brand);display:grid;place-items:center;box-shadow:0 10px 24px rgba(47,107,235,.35)}
.tag{margin:14px 0 0;color:var(--brand);font-weight:700;font-size:.9rem}
h1{margin:4px 0 0;font-size:2rem;letter-spacing:-.04em;line-height:1.2}
p.sub{margin:6px 0 10px;color:var(--sub);font-size:1.02rem;line-height:1.6}
a.go{display:flex;align-items:center;gap:14px;padding:20px;border-radius:22px;background:var(--card);color:var(--ink);
text-decoration:none;box-shadow:0 2px 10px rgba(0,0,0,.06);border:1px solid var(--line)}
a.go:active{transform:scale(.98)}a.go.primary{background:var(--brand);color:#fff;border-color:var(--brand)}
a.go b{display:block;font-size:1.25rem}a.go span{display:block;margin-top:2px;font-size:.92rem;opacity:.85}
a.go .arrow{margin-left:auto;font-size:1.5rem;opacity:.7}
h2{margin:22px 0 4px;font-size:1.05rem}
ol{margin:0;padding:0;list-style:none;display:flex;flex-direction:column;gap:12px}
ol li{display:flex;gap:12px}ol li i{flex:none;width:28px;height:28px;border-radius:50%;background:var(--brand-soft);color:var(--brand);
font-style:normal;font-weight:700;display:grid;place-items:center;font-size:.9rem}
ol li b{display:block}ol li span{color:var(--sub);font-size:.92rem;line-height:1.5}
.box{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px;color:var(--sub);font-size:.92rem;line-height:1.6}
.qr{display:flex;gap:16px;align-items:center}.qr img{width:120px;height:120px;border-radius:12px;background:#fff;border:1px solid var(--line)}
footer{margin-top:18px;color:var(--mute);font-size:.84rem;line-height:1.7}
"""
CAMERA = ('<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" '
          'stroke-linejoin="round" aria-hidden="true"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>')


def entry() -> str:
    steps = [
        ("바로 체험하기 → 센터에 도착했어요", "체험판이라 로그인과 담당자 출석·출발 확인을 건너뜁니다."),
        ("활동 시작하기", "오늘 코스의 장소와 찍을 구도가 차례로 안내됩니다."),
        ("도착했어요 · 사진 찍기", "실제 카메라로 아무 시설이나 찍어 보세요. 사람 얼굴과 자동차 번호판은 휴대폰에서 먼저 마스킹됩니다. "
                              "카메라를 쓸 수 없으면 그림으로 촬영을 흉내 냅니다."),
        ("센터 복귀 → 관리자 웹에서 내 사진 보기", "같은 휴대폰에서 관리자 웹을 열면 방금 찍은 사진이 검토 목록 맨 위에 보입니다."),
    ]
    items = "".join(f"<li><i>{n}</i><div><b>{html.escape(t)}</b><span>{html.escape(d)}</span></div></li>" for n, (t, d) in enumerate(steps, 1))
    body = f"""
<div class="logo">{CAMERA}</div>
<p class="tag">팀 이로움 · 체험판</p>
<h1>시니어 동네 점검단</h1>
<p class="sub">어르신 두 분이 한 조로 동네 시설을 사진으로 살피고, 담당 공무원이 사진을 검토해 보수까지 이어 가는 서비스입니다. 아래에서 두 화면을 직접 눌러 보세요.</p>
<a class="go primary" href="senior/"><div><b>시니어 앱</b><span>어르신 점검원 휴대폰 화면 · 로그인 없이 바로 체험</span></div><span class="arrow">›</span></a>
<a class="go" href="admin/"><div><b>관리자 웹</b><span>담당 공무원 화면 · 로그인 없이 바로 사진 검토와 보수 관리</span></div><span class="arrow">›</span></a>
<h2>시니어 앱에서 해 볼 수 있는 하루</h2>
<ol>{items}</ol>
<h2>관리자 웹</h2>
<div class="box">로그인 없이 시연 관리자로 바로 들어갑니다. 활동 담당자·시설 담당자·사업 총괄 메뉴(오늘 활동 현황, 검토 목록, 사건·보수, 통계)를 모두 볼 수 있습니다.</div>
<h2>다른 사람에게 알려 주기</h2>
<div class="box qr"><img src="qr.png" alt="체험판 주소 QR 코드"><div>휴대폰 카메라로 찍으면 이 페이지가 열립니다.<br><b>{html.escape(URL)}</b></div></div>
<footer>체험판은 서버에 연결하지 않고 예시 데이터로만 움직입니다. 누른 내용과 찍은 사진은 이 브라우저 안에만 남고 어디로도 보내지 않으며, "처음부터 다시 체험하기"를 누르면 지워집니다.<br>
화면 속 이름·전화번호·시설은 모두 가상입니다. 체험판에서는 AI 판독을 하지 않습니다. 실제 서비스에서는 서버의 AI가 시설 종류·이상 유형·판단 근거를 표시합니다.</footer>"""
    return (f'<!doctype html><html lang="ko"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
            f'<meta name="theme-color" content="#f4f6fa"><title>시니어 동네 점검단 체험판</title>'
            f'<style>{STYLE}</style></head><body><main>{body}</main></body></html>\n')


def build_app(name: str, out: Path) -> None:
    app = SERVICE / name
    subprocess.run(f'npx tsc --noEmit && npx vite build --mode trial --base {BASE}{name}/ --outDir "{out}" --emptyOutDir',
                   cwd=app, check=True, shell=True)


def build(trial: Path) -> None:
    import segno

    trial.mkdir(parents=True, exist_ok=True)
    build_app("senior", trial / "senior")
    build_app("admin", trial / "admin")
    (trial / "index.html").write_text(entry(), encoding="utf-8")
    segno.make(URL, error="m").save(str(trial / "qr.png"), scale=12, border=2)
    # GitHub Pages는 _로 시작하는 파일을 숨기므로(Jekyll) 끈다
    (trial / ".nojekyll").write_text("", encoding="utf-8")


def git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, help="만들기만 할 폴더 (올리지 않음). <폴더>/service/trial/ 아래에 만든다")
    a = ap.parse_args()
    if a.out:
        build(a.out / "service" / "trial")
        print(f"만듦: {a.out / 'service' / 'trial'}  (확인: python -m http.server -d {a.out} 8199 → http://127.0.0.1:8199{BASE})")
        return
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "service"
        git("clone", "--depth", "1", REPO, str(work), cwd=Path(tmp))
        shutil.rmtree(work / "trial", ignore_errors=True)
        build(work / "trial")
        if not (work / ".nojekyll").exists():
            (work / ".nojekyll").write_text("", encoding="utf-8")
        git("add", "-A", "trial", ".nojekyll", cwd=work)
        if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=work).returncode == 0:
            print("바뀐 것이 없습니다")
            return
        git("commit", "-q", "-m", "시니어 동네 점검단 체험판 (서버 없이 브라우저 안에서만 동작)", cwd=work)
        git("push", "-q", "origin", "HEAD:main", cwd=work)
    print(f"올림: {URL} (반영까지 1~2분)")


if __name__ == "__main__":
    main()
