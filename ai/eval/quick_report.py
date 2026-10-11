"""평가 결과를 발표·공유용 한 장(HTML)으로 정리한다.

사용:
  python eval/quick_report.py --analyze eval/results/<시각>_ollama_analyze \\
                              --verify  eval/results/<시각>_ollama_verify \\
                              --mask    eval/mask_check/mask_review.csv \\
                              --out     eval/평가결과.html
셋 중 있는 것만 넣어도 된다.
"""
import argparse
import csv
import html
import json
from pathlib import Path

GRADES = ["정상", "관찰", "주의", "긴급"]


def pct(v):
    return "-" if v is None else f"{v:.0f}%"


def stat(label, value, note=""):
    return (f'<div class="stat"><div class="v">{html.escape(value)}</div><div class="l">{html.escape(label)}</div>'
            + (f'<div class="n">{html.escape(note)}</div>' if note else "") + "</div>")


def mask_score(path: Path):
    rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))

    def n(v):
        try:
            return int(v)
        except (TypeError, ValueError):
            return 0
    out = {"photos": len(rows)}
    for k in ("faces", "plates"):
        total = sum(n(r.get(f"total_{k}")) for r in rows)
        vis = sum(n(r.get(f"visible_{k}")) for r in rows)
        out[k] = (total, total - vis)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--analyze")
    ap.add_argument("--verify")
    ap.add_argument("--mask")
    ap.add_argument("--out", default="eval/평가결과.html")
    a = ap.parse_args()
    sections = []

    if a.analyze:
        s = json.loads((Path(a.analyze) / "summary.json").read_text(encoding="utf-8"))
        conf = s.get("grade_confusion", {})
        rows = "".join(f"<tr><th>{g}</th>" + "".join(f"<td>{conf.get(g, {}).get(p, 0)}</td>" for p in GRADES) + "</tr>"
                       for g in GRADES)
        missed = s.get("urgent_missed_as_normal_or_observe") or []
        sections.append(f"""<section><h2>시설·이상 판독</h2><p class="sub">실제 사진 {s['n_photos']}장 (판독 대상 {s['n_scored']}장)</p>
<div class="stats">{stat('시설 분류 정확도', pct(s['facility_accuracy_pct']))}{stat('문제 유무 정확도', pct(s['issue_presence_accuracy_pct']))}
{stat('등급 일치율', pct(s['grade_agreement_pct']))}{stat('긴급 재현율', pct(s['urgent_recall_pct']), '실제 긴급 중 긴급으로 잡은 비율')}
{stat('담당자 확인으로 넘긴 비율', pct(s['needs_review_pct']))}{stat('평균 처리 시간', f"{s['latency_ms_mean'] / 1000:.1f}초" if s.get('latency_ms_mean') else '-')}</div>
<h3>등급 혼동표 (행: 정답, 열: AI)</h3><table><tr><th></th>{''.join(f'<th>{g}</th>' for g in GRADES)}</tr>{rows}</table>
<p class="sub">{'긴급을 정상·관찰로 놓친 사진: ' + ', '.join(map(html.escape, missed)) if missed else '긴급을 정상·관찰로 놓친 사진 없음'}</p></section>""")

    if a.verify:
        s = json.loads((Path(a.verify) / "summary.json").read_text(encoding="utf-8"))
        sections.append(f"""<section><h2>전후 사진 비교</h2><p class="sub">전후 {s['n_pairs']}쌍</p>
<div class="stats">{stat('처리 방침 정확도', pct(s['action_accuracy_pct']))}{stat('잘못된 재요청', f"{len(s['false_rerequest'])}건", '고쳐졌거나 다른 곳인데 재요청')}
{stat('안 고쳐졌는데 종료', f"{len(s['not_fixed_but_closed'])}건")}{stat('담당자 확인 비율', pct(s['needs_review_pct']))}</div></section>""")

    if a.mask:
        m = mask_score(Path(a.mask))

        def r(t):
            total, ok = t
            return (f"{100 * ok / total:.0f}%", f"{ok}/{total}개") if total else ("-", "개수 미기록")
        fr, pr = r(m["faces"]), r(m["plates"])
        sections.append(f"""<section><h2>개인정보 마스킹</h2><p class="sub">사람·차가 찍힌 실제 사진 {m['photos']}장, 사람이 눈으로 확인</p>
<div class="stats">{stat('얼굴 가림 성공률', fr[0], fr[1])}{stat('번호판 가림 성공률', pr[0], pr[1])}</div></section>""")

    Path(a.out).write_text(f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>실제 사진 평가 결과 | 시니어 동네 점검단</title>
<link href="https://fonts.googleapis.com/css2?family=Do+Hyeon&family=Noto+Sans+KR:wght@400;700&display=swap" rel="stylesheet">
<style>body{{margin:0;font-family:"Noto Sans KR","Malgun Gothic",sans-serif;background:#EEF2F2;color:#13292E;line-height:1.5}}
header{{background:#0E4C5A;color:#fff;padding:36px clamp(20px,4vw,56px) 28px;border-bottom:8px solid #F5C518}}
h1{{margin:0;font-family:"Do Hyeon",sans-serif;font-weight:400;font-size:clamp(36px,5vw,52px)}}header p{{margin:8px 0 0;color:#D5E9EE}}
main{{max-width:1200px;margin:0 auto;padding:28px clamp(20px,4vw,56px);display:flex;flex-direction:column;gap:22px}}
section{{background:#fff;border-radius:14px;padding:24px 28px}}h2{{margin:0;font-family:"Do Hyeon",sans-serif;font-weight:400;font-size:30px}}
h3{{margin:20px 0 8px;font-size:16px;color:#0E4C5A}}.sub{{margin:4px 0 0;color:#52666B}}
.stats{{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:12px;margin-top:16px}}
.stat{{background:#F3F5F5;border-radius:10px;padding:14px 16px}}.v{{font-family:"Do Hyeon",sans-serif;font-size:38px;line-height:1;color:#0E4C5A}}
.l{{margin-top:6px;font-weight:700}}.n{{font-size:13px;color:#52666B}}
table{{border-collapse:collapse}}th,td{{border:1px solid #C9D5D6;padding:6px 14px;text-align:center}}th{{background:#F3F5F5}}
footer{{max-width:1200px;margin:0 auto;padding:0 clamp(20px,4vw,56px) 28px;color:#52666B;font-size:14px}}</style></head><body>
<header><h1>실제 사진 평가 결과</h1><p>시니어 동네 점검단 · 팀이 직접 찍은 사진으로 측정</p></header>
<main>{''.join(sections) or '<section>평가 결과 폴더를 지정하세요.</section>'}</main>
<footer>정답은 팀원이 라벨 기준에 따라 표시했습니다. 시범 운영 전에는 더 많은 사진으로 다시 평가합니다.</footer></body></html>""", encoding="utf-8")
    print(f"저장: {a.out}")


if __name__ == "__main__":
    main()
