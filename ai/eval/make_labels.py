"""찍은 사진 폴더로 라벨 표(CSV)를 미리 채워 만든다. 사람은 문제 유형과 위험도만 적으면 된다.

폴더 구조 (시설 이름은 영문 키나 한글 모두 가능)
  eval/photos/보도블록/IMG_0001.jpg
  eval/photos/빗물받이/...
  eval/photos/벤치/...   eval/photos/안전펜스/...   eval/photos/운동기구/...
  eval/photos/나쁜사진/...        ← 흐림·어두움·잘림 (재촬영이 정답)
  eval/photos/전후/<쌍이름>/before.jpg, after.jpg   ← 전후 비교용
  eval/photos/마스킹/...          ← 사람·차가 찍힌 사진 (contact_sheet.py용, 여기서는 건너뜀)

사용: python eval/make_labels.py eval/photos
결과: eval/photos/labels.csv, eval/photos/pairs.csv (이미 있으면 덮어쓰지 않고 _new.csv로 만든다)
"""
import csv
import sys
from pathlib import Path

from PIL import Image

FACILITY = {
    "보도블록": "sidewalk", "sidewalk": "sidewalk",
    "빗물받이": "drain", "drain": "drain",
    "벤치": "bench", "bench": "bench",
    "안전펜스": "fence", "펜스": "fence", "fence": "fence",
    "운동기구": "exercise", "exercise": "exercise",
}
BAD = {"나쁜사진", "bad"}
PAIRS = {"전후", "pairs"}
SKIP = {"마스킹", "mask"}  # 마스킹 확인용 폴더는 contact_sheet.py가 따로 쓴다
EXT = {".jpg", ".jpeg", ".png"}


def capture_hour(path: Path):
    try:
        exif = Image.open(path).getexif()
        dt = exif.get(36867) or exif.get_ifd(0x8769).get(36867) or exif.get(306)  # DateTimeOriginal / DateTime
        return int(str(dt)[11:13]) if dt else ""
    except Exception:
        return ""


def out_path(p: Path) -> Path:
    return p.with_name(p.stem + "_new.csv") if p.exists() else p


def main(root: str):
    root = Path(root)
    rows, pairs = [], []
    for d in sorted(x for x in root.iterdir() if x.is_dir()):
        if d.name in SKIP:
            continue
        if d.name in PAIRS:
            for pd in sorted(x for x in d.iterdir() if x.is_dir()):
                imgs = sorted(f for f in pd.iterdir() if f.suffix.lower() in EXT)
                before = next((f for f in imgs if f.stem.lower().startswith(("before", "전"))), imgs[0] if imgs else None)
                after = next((f for f in imgs if f.stem.lower().startswith(("after", "후"))), imgs[-1] if len(imgs) > 1 else None)
                if before and after:
                    pairs.append({"pair_id": pd.name, "before_path": before.relative_to(root).as_posix(),
                                  "after_path": after.relative_to(root).as_posix(), "facility_type": "",
                                  "issue_type": "", "prior_severity": "", "prior_bbox": "", "prior_evidence": "",
                                  "mode": "repair", "expected": ""})
            continue
        ftype = FACILITY.get(d.name, "")
        bad = d.name in BAD
        if not ftype and not bad:
            print(f"건너뜀: {d.name} (시설 폴더 이름을 확인하세요)")
            continue
        for f in sorted(x for x in d.rglob("*") if x.suffix.lower() in EXT):
            rows.append({"photo_id": f"{d.name}_{f.stem}", "image_path": f.relative_to(root).as_posix(),
                         "facility_type": ftype, "issue_type": "", "severity": "0" if bad else "",
                         "quality_ok": "0" if bad else "1", "location_tags": "",
                         "capture_hour": capture_hour(f), "note": ""})
    fields = ["photo_id", "image_path", "facility_type", "issue_type", "severity", "quality_ok",
              "location_tags", "capture_hour", "note"]
    lp = out_path(root / "labels.csv")
    with lp.open("w", encoding="utf-8-sig", newline="") as fp:
        w = csv.DictWriter(fp, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    print(f"{lp}: 사진 {len(rows)}장. issue_type(문제 없으면 비움)과 severity(0~3)를 채우세요.")
    if pairs:
        pp = out_path(root / "pairs.csv")
        with pp.open("w", encoding="utf-8-sig", newline="") as fp:
            w = csv.DictWriter(fp, fieldnames=list(pairs[0].keys()))
            w.writeheader()
            w.writerows(pairs)
        print(f"{pp}: 전후 {len(pairs)}쌍. facility_type, issue_type, prior_severity, expected(fixed/not_fixed/different_place)를 채우세요.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "eval/photos")
