"""시니어 동네 점검단 입구 페이지를 GitHub Pages(TeamIroum/service 저장소 맨 위)에 올린다.

앱 자체는 이 PC(Caddy + Tailscale Funnel)에서 서비스한다. 깃페이지에는 앱으로 넘겨 주는 정적 페이지만 둔다.
화면과 업무 API가 같은 주소여야 로그인 쿠키(__Host-, SameSite)와 CSRF가 동작하기 때문이다(service/README.md).

  https://teamiroum.github.io/service/          버튼 두 개 (시니어 앱 / 관리자 웹)
  https://teamiroum.github.io/service/senior    바로 시니어 앱으로
  https://teamiroum.github.io/service/admin     바로 관리자 웹으로

index.html·senior/·admin/만 새로 쓴다. 예전 시험판(test/: 시험용 PWA, test/senior/ 시니어 시험판)은 건드리지 않는다.
(이 저장소는 예전 이름이 pwa-test였다. 2026-10-14 service로 바꾸며 시험판을 test/로 옮김)

실행:  python service/web/pages/deploy_pages.py --out <폴더>    # 만들기만 (올리지 않음)
       python service/web/pages/deploy_pages.py                 # 만들어서 올림
       --senior https://<PC>.ts.net  --admin https://<PC>.ts.net:10000   앱 주소 (기본: 이 PC의 Funnel 주소)
"""
from __future__ import annotations

import argparse
import html
import shutil
import subprocess
import tempfile
from pathlib import Path

REPO = "https://github.com/TeamIroum/service.git"
PAGES_URL = "https://teamiroum.github.io/service/"
SENIOR = "https://desktop-iv9p5jl.tail3f4b5a.ts.net/"
ADMIN = "https://desktop-iv9p5jl.tail3f4b5a.ts.net:10000/"

STYLE = """
:root{--bg:#f4f6fa;--ink:#191f28;--sub:#4e5968;--mute:#8b95a1;--brand:#2f6beb;--card:#fff;--line:#e5e8eb}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--ink);
font-family:Pretendard,-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif}
main{max-width:440px;margin:0 auto;padding:56px 20px 40px;min-height:100dvh;display:flex;flex-direction:column;gap:14px}
.logo{width:72px;height:72px;border-radius:22px;background:var(--brand);display:grid;place-items:center;box-shadow:0 10px 24px rgba(47,107,235,.35)}
h1{margin:18px 0 0;font-size:2rem;letter-spacing:-.04em;line-height:1.2}
p.sub{margin:6px 0 18px;color:var(--sub);font-size:1.05rem;line-height:1.55}
a.go{display:flex;align-items:center;gap:14px;padding:20px;border-radius:22px;background:var(--card);color:var(--ink);
text-decoration:none;box-shadow:0 2px 10px rgba(0,0,0,.06);border:1px solid var(--line)}
a.go:active{transform:scale(.98)}a.go.primary{background:var(--brand);color:#fff;border-color:var(--brand)}
a.go b{display:block;font-size:1.25rem}a.go span{display:block;margin-top:2px;font-size:.95rem;opacity:.8}
a.go .arrow{margin-left:auto;font-size:1.5rem;opacity:.7}
footer{margin-top:auto;padding-top:24px;color:var(--mute);font-size:.85rem;line-height:1.6}
"""
CAMERA = ('<svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" '
          'stroke-linejoin="round" aria-hidden="true"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>')


def page(title: str, body: str, head: str = "") -> str:
    return (f'<!doctype html><html lang="ko"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
            f'<meta name="theme-color" content="#f4f6fa"><title>{html.escape(title)}</title>{head}'
            f'<style>{STYLE}</style></head><body><main>{body}</main></body></html>\n')


def entry(senior: str, admin: str) -> str:
    return page("시니어 동네 점검단", f"""
<div class="logo">{CAMERA}</div>
<h1>시니어 동네 점검단</h1>
<p class="sub">어르신 점검원이 동네 시설을 사진으로 살피고,<br>담당 공무원이 AI 판독을 참고해 보수까지 관리합니다.</p>
<a class="go primary" href="{html.escape(senior)}"><div><b>시니어 앱</b><span>점검원 어르신 · 휴대폰으로 열어 주세요</span></div><span class="arrow">›</span></a>
<a class="go" href="{html.escape(admin)}"><div><b>관리자 웹</b><span>활동 담당자 · 시설 담당자 · PC 브라우저</span></div><span class="arrow">›</span></a>
<footer>팀 이로움 · 앱은 운영 서버에서 열립니다</footer>""")


def redirect(title: str, to: str) -> str:
    t = html.escape(to)
    return page(title, f"""
<div class="logo">{CAMERA}</div>
<h1>{html.escape(title)}</h1>
<p class="sub">앱으로 이동하고 있어요.</p>
<a class="go primary" href="{t}"><div><b>바로 열기</b><span>넘어가지 않으면 눌러 주세요</span></div><span class="arrow">›</span></a>
<script>location.replace({to!r})</script>""", head=f'<meta http-equiv="refresh" content="0; url={t}">')


def build(out: Path, senior: str, admin: str) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "index.html").write_text(entry(senior, admin), encoding="utf-8")
    for sub, title, to in (("senior", "시니어 앱", senior), ("admin", "관리자 웹", admin)):
        (out / sub).mkdir(exist_ok=True)
        (out / sub / "index.html").write_text(redirect(title, to), encoding="utf-8")


def git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, help="만들기만 할 폴더 (올리지 않음)")
    ap.add_argument("--senior", default=SENIOR)
    ap.add_argument("--admin", default=ADMIN)
    a = ap.parse_args()
    if a.out:
        build(a.out, a.senior, a.admin)
        print(f"만듦: {a.out}")
        return
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "service"
        git("clone", "--depth", "1", REPO, str(work), cwd=Path(tmp))
        for sub in ("senior", "admin"):
            shutil.rmtree(work / sub, ignore_errors=True)
        build(work, a.senior, a.admin)
        git("add", "-A", "index.html", "senior", "admin", cwd=work)
        if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=work).returncode == 0:
            print("바뀐 것이 없습니다")
            return
        git("commit", "-q", "-m", f"시니어 동네 점검단 입구 페이지 (시니어 앱 {a.senior}, 관리자 웹 {a.admin})", cwd=work)
        git("push", "-q", "origin", "HEAD:main", cwd=work)
    print(f"올림: {PAGES_URL} (반영까지 1~2분)")


if __name__ == "__main__":
    main()
