"""마스킹 결과를 한 화면에 모아 눈으로 빠르게 확인한다 (상자 라벨링 없이 하는 간이 평가).

1) 가리기:  python eval/privacy/contact_sheet.py eval/photos/마스킹 --out eval/mask_check
   → eval/mask_check/index.html (가린 사진만 격자로 표시), mask_review.csv
2) 확인:    index.html을 열어 사진마다 '아직 알아볼 수 있는 얼굴·번호판'이 있는지 보고
            mask_review.csv의 visible_faces, visible_plates 칸에 개수를 적는다 (없으면 0).
            total_faces, total_plates 칸에는 원래 사진에 있던 얼굴·번호판 수를 적는다.
3) 점수:    python eval/privacy/contact_sheet.py --score eval/mask_check/mask_review.csv

index.html에는 가린 사진만 들어가므로 팀 안에서 공유해도 된다. 원본 폴더는 공유하지 않는다.
"""
import argparse
import csv
import html
import sys
import time
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import images  # noqa: E402
import privacy  # noqa: E402

EXT = {".jpg", ".jpeg", ".png"}


def run(src: Path, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    (out / "img").mkdir(exist_ok=True)
    rows, cards, times = [], [], []
    files = sorted(f for f in src.rglob("*") if f.suffix.lower() in EXT)
    for i, f in enumerate(files, 1):
        try:
            img = images.decode_upload(f.read_bytes())
        except images.ImageRejected as e:
            print(f"건너뜀 {f.name}: {e}")
            continue
        t = time.time()
        masked, rep = privacy.mask_image(img)
        times.append(time.time() - t)
        name = f"{i:04d}.jpg"
        cv2.imwrite(str(out / "img" / name), images.resize_max(masked, 1200), [cv2.IMWRITE_JPEG_QUALITY, 85])
        rows.append({"no": i, "file": f.name, "faces_masked": rep.faces, "plates_masked": rep.plates,
                     "vehicles_masked": rep.vehicles, "total_faces": "", "total_plates": "",
                     "visible_faces": "", "visible_plates": "", "note": ""})
        cards.append(f'<figure><img src="img/{name}" loading="lazy" alt="{i}번 가린 사진">'
                     f'<figcaption><b>{i}</b> 얼굴 {rep.faces} · 번호판 {rep.plates} · 차량 {rep.vehicles}</figcaption></figure>')
        print(f"[{i}/{len(files)}] {f.name}: 얼굴 {rep.faces}, 번호판 {rep.plates}, 차량 {rep.vehicles}", flush=True)
    with (out / "mask_review.csv").open("w", encoding="utf-8-sig", newline="") as fp:
        w = csv.DictWriter(fp, fieldnames=list(rows[0].keys()) if rows else ["no"])
        w.writeheader()
        w.writerows(rows)
    avg = sum(times) / len(times) if times else 0
    vd = privacy.vehicle_detector()
    dets = (f"얼굴 {type(privacy.face_detector()).__name__}, 번호판 "
            f"{'+'.join(type(d).__name__ for d in privacy.plate_detectors())}, 차량 {type(vd).__name__ if vd else '없음'}")
    (out / "index.html").write_text(f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>마스킹 확인</title>
<style>body{{margin:0;font-family:"Noto Sans KR","Malgun Gothic",sans-serif;background:#EEF2F2;color:#13292E}}
header{{background:#0E4C5A;color:#fff;padding:20px 28px;border-bottom:6px solid #F5C518}}h1{{margin:0;font-size:24px}}
p{{margin:6px 0 0}}main{{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px;padding:20px 28px}}
figure{{margin:0;background:#fff;border-radius:10px;overflow:hidden}}img{{width:100%;display:block}}
figcaption{{padding:8px 10px;font-size:14px}}</style></head><body>
<header><h1>마스킹 확인: 사진 {len(rows)}장</h1><p>사진마다 아직 알아볼 수 있는 얼굴이나 읽을 수 있는 번호판이 있는지 보고
mask_review.csv에 적어 주세요. 평균 처리 시간 {avg:.2f}초. 검출기: {html.escape(dets)}</p></header>
<main>{''.join(cards)}</main></body></html>""", encoding="utf-8")
    print(f"\n확인 화면: {out / 'index.html'}\n기록 파일: {out / 'mask_review.csv'}")


def score(path: Path):
    rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))

    def n(v):
        try:
            return int(v)
        except (TypeError, ValueError):
            return None

    for kind in ("faces", "plates"):
        total = sum(n(r[f"total_{kind}"]) or 0 for r in rows if n(r[f"total_{kind}"]) is not None)
        visible = sum(n(r[f"visible_{kind}"]) or 0 for r in rows if n(r[f"visible_{kind}"]) is not None)
        label = "얼굴" if kind == "faces" else "번호판"
        if total:
            print(f"{label}: 전체 {total}개 중 {total - visible}개 가림 ({100 * (total - visible) / total:.1f}%), "
                  f"알아볼 수 있게 남은 것 {visible}개")
        else:
            print(f"{label}: total_{kind} 칸이 비어 있어 계산할 수 없습니다")
    missed = [r["no"] for r in rows if (n(r["visible_faces"]) or 0) + (n(r["visible_plates"]) or 0) > 0]
    if missed:
        print("놓친 사진 번호:", ", ".join(missed))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src", nargs="?")
    ap.add_argument("--out", default="eval/mask_check")
    ap.add_argument("--score")
    a = ap.parse_args()
    if a.score:
        score(Path(a.score))
    elif a.src:
        run(Path(a.src), Path(a.out))
    else:
        ap.print_help()
