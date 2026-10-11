"""실제 사진으로 마스킹 성능을 잰다. 시범 운영 전 반드시 통과해야 하는 평가.

준비: 시범 구역에서 찍은 사진에 얼굴·번호판 위치를 표시한 라벨 파일 (JSON Lines, 한 줄에 사진 하나)
  {"image": "photos/0001.jpg", "boxes": [{"kind": "face", "box": [x1, y1, x2, y2]}, ...]}
  box는 사진 크기 대비 비율(0~1). Label Studio·CVAT에서 사각형으로 표시한 뒤 이 형식으로 바꾸면 된다.
  얼굴·번호판이 없는 사진도 섞는다 (잘못 가리는 정도를 재기 위해).

사용: python eval/privacy/run_mask_eval.py eval/privacy/labels.jsonl
지표
  - 가림 성공률(종류별): 정답 상자의 90% 이상이 가려진 비율. 목표: 번호판·얼굴 모두 98% 이상
    (차량 전체 가림으로 덮인 번호판도 가려진 것으로 센다)
  - 놓친 사진 목록: 하나라도 못 가린 사진 (직접 열어 원인을 본다)
  - 과잉 가림: 정답이 없는 곳을 가린 면적 비율의 평균. 시설이 가려지면 판독을 망치므로 낮을수록 좋다
  - 처리 시간: 사진당 평균·최대
"""
import json
import statistics
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import images  # noqa: E402
import privacy  # noqa: E402

COVER = 0.9


def to_px(box, W, H):
    """비율 상자를 픽셀로. 가장자리(1.0)에서 시작해도 최소 1px은 남겨 빈 영역(NaN)이 되지 않게 한다."""
    x1, y1, x2, y2 = box
    px1, py1 = min(int(x1 * W), W - 1), min(int(y1 * H), H - 1)
    return px1, py1, max(px1 + 1, min(int(x2 * W), W)), max(py1 + 1, min(int(y2 * H), H))


def main(label_path: str):
    lp = Path(label_path)
    rows = [json.loads(line) for line in lp.read_text(encoding="utf-8").splitlines() if line.strip()]
    stats = {"face": [0, 0], "plate": [0, 0]}   # 다른 종류(예: vehicle)가 라벨에 있으면 따로 센다
    missed, over, times = [], [], []
    for row in rows:
        img = images.decode_upload((lp.parent / row["image"]).read_bytes())
        H, W = img.shape[:2]
        t = time.time()
        _, rep = privacy.mask_image(img)
        times.append(time.time() - t)
        mask = np.zeros((H, W), bool)
        for b in rep.boxes:
            x1, y1, x2, y2 = to_px(b["box"], W, H)
            mask[y1:y2, x1:x2] = True
        gt_mask = np.zeros((H, W), bool)
        miss_here = []
        for g in row.get("boxes", []):
            x1, y1, x2, y2 = to_px(g["box"], W, H)
            gt_mask[y1:y2, x1:x2] = True
            ok = mask[y1:y2, x1:x2].mean() >= COVER
            s = stats.setdefault(g["kind"], [0, 0])
            s[0] += ok
            s[1] += 1
            if not ok:
                miss_here.append(g["kind"])
        if miss_here:
            missed.append({"image": row["image"], "missed": miss_here})
        over.append(float((mask & ~gt_mask).mean()))

    print(f"사진 {len(rows)}장, 검출기: {rep.detectors if rows else '-'}")
    for k, (ok, n) in stats.items():
        print(f"  {k}: {ok}/{n} 가림" + (f" ({100 * ok / n:.1f}%)" if n else ""))
    print(f"  과잉 가림 면적 평균: {100 * statistics.mean(over):.2f}%" if over else "")
    print(f"  처리 시간: 평균 {statistics.mean(times):.2f}초, 최대 {max(times):.2f}초" if times else "")
    if missed:
        print("  놓친 사진:")
        for m in missed[:50]:
            print(f"    {m['image']}: {', '.join(m['missed'])}")


if __name__ == "__main__":
    main(sys.argv[1])
