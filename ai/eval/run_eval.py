"""테스트셋으로 판독 정확도를 잰다. provider만 바꿔 같은 테스트셋을 돌리면 모델 비교가 된다.

사용:
  python eval/run_eval.py analyze --labels eval/labels.csv --provider ollama
  python eval/run_eval.py verify  --labels eval/pairs.csv  --provider ollama
  (공개 모델끼리 비교할 때는 OLLAMA_MODEL만 바꿔 다시 돌린다)

결과: eval/results/<시각>_<provider>_<task>/ 에 predictions.csv, summary.json, summary.md
라벨 작성법은 eval/LABELING_GUIDE.md 참고.
"""
import argparse
import csv
import json
import statistics
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pipeline  # noqa: E402
import prompts  # noqa: E402
import rules  # noqa: E402
import storage  # noqa: E402
from config import taxonomy  # noqa: E402
from providers import get_provider  # noqa: E402

GRADES = taxonomy()["grades"]


def _tags(s: str) -> list[str]:
    return [t for t in (s or "").replace(",", ";").split(";") if t]


def _int(s, default=None):
    try:
        return int(s)
    except (TypeError, ValueError):
        return default


def _pct(a: int, b: int):
    return round(100 * a / b, 1) if b else None


def expected_grade(row: dict) -> str:
    sev = _int(row["severity"], 0)
    if sev == 0 or not row["issue_type"]:
        return GRADES[0]
    issue = {"type": row["issue_type"], "severity": sev, "evidence": "(라벨)"}
    grade, _ = rules.compute_grade(row["facility_type"], [issue], _tags(row.get("location_tags")))
    return grade


SAMPLE = None  # --sample N: 무료 등급 호출 수를 아끼려고 일부만 빠르게 돌릴 때


def _sample(rows):
    if SAMPLE and SAMPLE < len(rows):
        import random
        return random.Random(42).sample(rows, SAMPLE)
    return rows


def run_analyze(labels: Path, provider, out: Path) -> dict:
    rows = _sample(list(csv.DictReader(labels.open(encoding="utf-8-sig"))))
    preds = []
    for i, row in enumerate(rows, 1):
        img = (labels.parent / row["image_path"]).read_bytes()
        ctx = {
            "photo_id": row["photo_id"],
            "location_tags": _tags(row.get("location_tags")),
            "capture_hour": _int(row.get("capture_hour")),
        }
        r = pipeline.analyze_photo(img, ctx, provider)
        top = max(r["issues"], key=lambda x: x["severity"]) if r["issues"] else None
        preds.append({
            "photo_id": row["photo_id"],
            "label_quality_ok": row.get("quality_ok", "1"),
            "label_facility": row["facility_type"],
            "label_issue": row["issue_type"],
            "label_severity": _int(row["severity"], 0),
            "label_grade": expected_grade(row),
            "pred_status": r["status"],
            "pred_facility": (r["facility"] or {}).get("type"),
            "pred_issue": top["type"] if top else "",
            "pred_severity": r["ai_severity"],
            "pred_grade": r["grade"],
            "pred_action": r["action"],
            "certainty": (r["facility"] or {}).get("certainty"),
            "latency_ms": r["meta"]["latency_ms"],
            "notes": " | ".join(r["notes"]),
        })
        print(f"[{i}/{len(rows)}] {row['photo_id']}: {r['status']} {r['grade']}", flush=True)

    with (out / "predictions.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(preds[0].keys()))
        w.writeheader()
        w.writerows(preds)

    # 품질(재촬영) 판정
    should_retake = [p for p in preds if p["label_quality_ok"] == "0"]
    did_retake = [p for p in preds if p["pred_status"] == "retake"]
    both = [p for p in should_retake if p["pred_status"] == "retake"]

    # 판독 대상: 라벨상 품질 정상이고 예측도 재촬영이 아닌 사진
    scored = [p for p in preds if p["label_quality_ok"] != "0" and p["pred_status"] != "retake"
              and p["pred_grade"] is not None]
    fac_ok = sum(p["pred_facility"] == p["label_facility"] for p in scored)
    has_ok = sum((p["pred_severity"] > 0) == (p["label_severity"] > 0) for p in scored)
    sev_exact = sum(p["pred_severity"] == p["label_severity"] for p in scored)
    sev_near = sum(abs(p["pred_severity"] - p["label_severity"]) <= 1 for p in scored)
    grade_ok = sum(p["pred_grade"] == p["label_grade"] for p in scored)

    urgent_true = [p for p in scored if p["label_grade"] == "긴급"]
    urgent_pred = [p for p in scored if p["pred_grade"] == "긴급"]
    urgent_hit = [p for p in urgent_true if p["pred_grade"] == "긴급"]
    missed = [p["photo_id"] for p in urgent_true if p["pred_grade"] in ("정상", "관찰")]

    confusion = {lg: Counter() for lg in GRADES}
    for p in scored:
        confusion[p["label_grade"]][p["pred_grade"]] += 1

    lat = [p["latency_ms"] for p in preds]
    summary = {
        "task": "analyze",
        "provider": provider.name,
        "model": provider.model,
        "prompt_version": prompts.ANALYZE_VERSION,
        "taxonomy_version": taxonomy()["version"],
        "rules_version": rules.rules()["version"],
        "n_photos": len(preds),
        "n_scored": len(scored),
        "retake_recall_pct": _pct(len(both), len(should_retake)),
        "retake_precision_pct": _pct(len(both), len(did_retake)),
        "facility_accuracy_pct": _pct(fac_ok, len(scored)),
        "issue_presence_accuracy_pct": _pct(has_ok, len(scored)),
        "severity_exact_pct": _pct(sev_exact, len(scored)),
        "severity_within1_pct": _pct(sev_near, len(scored)),
        "grade_agreement_pct": _pct(grade_ok, len(scored)),
        "urgent_recall_pct": _pct(len(urgent_hit), len(urgent_true)),
        "urgent_precision_pct": _pct(len(urgent_hit), len(urgent_pred)),
        "urgent_missed_as_normal_or_observe": missed,
        "needs_review_pct": _pct(sum(p["pred_status"] == "needs_review" for p in preds), len(preds)),
        "latency_ms_mean": int(statistics.mean(lat)) if lat else None,
        "latency_ms_max": max(lat) if lat else None,
        "grade_confusion": {k: dict(v) for k, v in confusion.items()},
    }
    return summary


def run_verify(labels: Path, provider, out: Path) -> dict:
    rows = list(csv.DictReader(labels.open(encoding="utf-8-sig")))
    preds = []
    for i, row in enumerate(rows, 1):
        prior = {
            "case_id": row["pair_id"],
            "facility_type": row["facility_type"],
            "issue_type": row["issue_type"],
            "severity": _int(row["prior_severity"], 2),
            "bbox": json.loads(row["prior_bbox"]) if row.get("prior_bbox") else None,
            "evidence": row.get("prior_evidence", ""),
        }
        before = (labels.parent / row["before_path"]).read_bytes()
        after = (labels.parent / row["after_path"]).read_bytes()
        r = pipeline.verify_photo(before, after, prior, row.get("mode") or "repair", {}, provider)
        preds.append({
            "pair_id": row["pair_id"],
            "expected": row["expected"],
            "pred_status": r["status"],
            "pred_result": r["result"],
            "pred_action": r["action"],
            "same_facility": r["same_facility"],
            "latency_ms": r["meta"]["latency_ms"],
            "evidence": r["evidence"],
        })
        print(f"[{i}/{len(rows)}] {row['pair_id']}: expected={row['expected']} -> {r['action']}", flush=True)

    with (out / "predictions.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(preds[0].keys()))
        w.writeheader()
        w.writerows(preds)

    want = {"fixed": "close_case", "not_fixed": "re_request", "different_place": "retake",
            "worse": "regrade", "same": "keep", "better": "keep"}
    correct = sum(want.get(p["expected"]) == p["pred_action"] for p in preds)
    false_rerequest = [p["pair_id"] for p in preds
                       if p["pred_action"] == "re_request" and p["expected"] != "not_fixed"]
    missed_rerequest = [p["pair_id"] for p in preds
                        if p["expected"] == "not_fixed" and p["pred_action"] == "close_case"]
    lat = [p["latency_ms"] for p in preds]
    return {
        "task": "verify",
        "provider": provider.name,
        "model": provider.model,
        "prompt_version": prompts.VERIFY_VERSION,
        "n_pairs": len(preds),
        "action_accuracy_pct": _pct(correct, len(preds)),
        "false_rerequest": false_rerequest,
        "not_fixed_but_closed": missed_rerequest,
        "needs_review_pct": _pct(sum(p["pred_status"] == "needs_review" for p in preds), len(preds)),
        "latency_ms_mean": int(statistics.mean(lat)) if lat else None,
        "expected_counts": dict(Counter(p["expected"] for p in preds)),
    }


def to_markdown(s: dict) -> str:
    lines = [f"# 평가 결과 ({s['task']})", "",
             f"- provider/model: {s['provider']} / {s['model']}",
             f"- prompt: {s['prompt_version']}", ""]
    for k, v in s.items():
        if k in ("task", "provider", "model", "prompt_version", "grade_confusion"):
            continue
        lines.append(f"- {k}: {v}")
    if "grade_confusion" in s:
        lines += ["", "## 등급 혼동표 (행: 정답, 열: 예측)", "",
                  "| 정답 \\ 예측 | " + " | ".join(GRADES) + " |",
                  "|" + "---|" * (len(GRADES) + 1)]
        for lg in GRADES:
            row = s["grade_confusion"].get(lg, {})
            lines.append(f"| {lg} | " + " | ".join(str(row.get(pg, 0)) for pg in GRADES) + " |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("task", choices=["analyze", "verify"])
    ap.add_argument("--labels", required=True, type=Path)
    ap.add_argument("--provider", default=None, help="mock | ollama (기본: AI_PROVIDER 환경 변수)")
    ap.add_argument("--sample", type=int, default=None, help="일부만 무작위로 돌리기 (기준 문장 다듬을 때 호출 절약)")
    args = ap.parse_args()
    global SAMPLE
    SAMPLE = args.sample

    provider = get_provider(args.provider)
    out = Path(__file__).resolve().parent / "results" / f"{time.strftime('%Y%m%d_%H%M%S')}_{provider.name}_{args.task}"
    out.mkdir(parents=True, exist_ok=True)
    # 평가 중 가린 사진과 판독 결과는 이번 결과 폴더에만 저장한다. 서버 저장소(data/results)를 쓰면
    # 같은 photo_id의 이전 결과를 그대로 돌려줘서(재요청 처리) 프롬프트를 고쳐도 다시 판독하지 않는다.
    storage.set_store(storage.LocalImageStore(out / "images"))
    storage.set_results(storage.LocalResultStore(out / "_results"))
    storage.set_media(storage.LocalMediaStore(out / "_results" / "media"))
    storage.set_events(storage.EventLog(out / "_results" / "_events.jsonl"))

    summary = (run_analyze if args.task == "analyze" else run_verify)(args.labels, provider, out)
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "summary.md").write_text(to_markdown(summary), encoding="utf-8")
    print("\n" + to_markdown(summary))
    print(f"저장: {out}")


if __name__ == "__main__":
    main()
