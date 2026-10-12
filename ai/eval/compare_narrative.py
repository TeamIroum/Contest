"""보고 문장(/report/narrative)을 Ollama와 Claude로 같은 집계값·같은 프롬프트로 써 보고 나란히 비교한다.

사진은 보내지 않는다. 보내는 것은 아래 CASES의 항목 이름·숫자·단위뿐이다.
운영 코드(providers/)에는 Claude를 넣지 않고, 이 시험 스크립트 안에서만 쓴다.

준비: ai/.env에 ANTHROPIC_API_KEY=... 한 줄 (Git에 올라가지 않음). Ollama가 켜져 있어야 함.
사용:
  python eval/compare_narrative.py                 # 사례 4개 × 3번씩
  python eval/compare_narrative.py --repeat 1      # 빠르게
  python eval/compare_narrative.py --only claude   # 한쪽만
결과: eval/results/<시각>_narrative_compare/ 에 summary.md, results.json
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(Path(__file__).resolve().parent.parent / ".env")
os.environ["AI_TIMEOUT_S"] = os.environ.get("NARRATIVE_TIMEOUT_S", "120")  # 판독용 12초는 긴 문장에 짧다

import anthropic  # noqa: E402

from providers import get_provider  # noqa: E402
from report import narrative_ok, template_narrative, validate_items, write_narrative  # noqa: E402

CLAUDE_MODEL = "claude-opus-5-5"
PRICE_IN, PRICE_OUT = 4.00, 20.00  # 달러 / 100만 토큰 (claude-opus-5-5)


def item(key, label, value, unit="건"):
    return {"key": key, "label": label, "value": value, "unit": unit}


CASES = {
    "주간 기본": [
        item("attend", "참여자 출석", 24, "명"),
        item("patrols", "순회 회차", 6, "회"),
        item("photos", "촬영 사진", 312, "장"),
        item("facilities", "점검한 고유 시설", 87, "곳"),
        item("suspect", "이상 의심 사진", 41, "장"),
        item("cases_new", "새로 연 사건", 9),
        item("alerts", "긴급 알림", 2),
        item("review_wait", "담당자 검토 대기", 5),
        item("fix_suggest", "수리 확인 제안", 3),
    ],
    "0이 많은 주": [
        item("attend", "참여자 출석", 8, "명"),
        item("patrols", "순회 회차", 2, "회"),
        item("photos", "촬영 사진", 46, "장"),
        item("cases_new", "새로 연 사건", 0),
        item("alerts", "긴급 알림", 0),
        item("review_wait", "담당자 검토 대기", 1),
    ],
    "큰 숫자(분기)": [
        item("photos", "촬영 사진", 12480, "장"),
        item("facilities", "점검한 고유 시설", 1093, "곳"),
        item("suspect", "이상 의심 사진", 1517, "장"),
        item("cases_new", "새로 연 사건", 214),
        item("closed", "담당자가 종결한 사건", 168),
    ],
    "헷갈리는 항목 이름": [
        item("trio", "3인 조 순회", 4, "회"),
        item("fix_suggest", "수리 확인 제안", 6),
        item("fix_done", "담당자 수리 완료 확정", 2),
        item("no_issue", "이상 미발견(표본 검토)", 27, "장"),
        item("retake", "현장 재촬영 안내", 11),
    ],
}


class ClaudeText:
    """write_narrative가 부르는 generate_text만 갖춘 시험용 공급자."""
    name = "claude"
    model = CLAUDE_MODEL

    def __init__(self):
        self.client = anthropic.Anthropic()
        self.usage = None

    def generate_text(self, prompt):
        resp = self.client.beta.messages.create(
            model=CLAUDE_MODEL,
            max_tokens=16000,
            output_config={"effort": "medium"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",  # 안전 분류기가 거절하면 서버가 다른 모델로 다시 씀
            messages=[{"role": "user", "content": prompt}],
        )
        self.usage = resp.usage
        if resp.stop_reason == "refusal":
            raise RuntimeError("Claude가 요청을 거절함")
        return "".join(b.text for b in resp.content if b.type == "text").strip()


class Recorder:
    """공급자를 감싸 검사 전 원문과 걸린 시간을 남긴다 (write_narrative는 실패하면 템플릿만 돌려줌)."""

    def __init__(self, inner):
        self.inner, self.name, self.model = inner, inner.name, inner.model
        self.raw, self.seconds = None, None

    def generate_text(self, prompt):
        t0 = time.time()
        try:
            self.raw = self.inner.generate_text(prompt)
            return self.raw
        finally:
            self.seconds = round(time.time() - t0, 1)


def run_one(provider, items):
    rec = Recorder(provider)
    out = write_narrative(items, rec)
    row = {"source": out["source"], "text": out["text"], "raw": rec.raw, "seconds": rec.seconds,
           "note": out.get("note"), "numbers_ok": bool(rec.raw) and narrative_ok(rec.raw, validate_items(items),
                                                                                 template_narrative(validate_items(items)))}
    usage = getattr(provider, "usage", None)
    if usage is not None:
        row["tokens_in"], row["tokens_out"] = usage.input_tokens, usage.output_tokens
        row["cost_usd"] = round(usage.input_tokens / 1e6 * PRICE_IN + usage.output_tokens / 1e6 * PRICE_OUT, 5)
        provider.usage = None
    return row


def write_summary(results, providers, path):
    lines = ["# 보고 문장 비교: Ollama vs Claude", ""]
    lines += ["| 공급자 | 모델 | 숫자 검사 통과 | 평균 시간(초) | 비용(달러) |", "|---|---|---|---|---|"]
    for p in providers:
        rows = [r for c in results.values() for r in c["runs"].get(p.name, [])]
        if not rows:
            continue
        ok = sum(r["numbers_ok"] for r in rows)
        secs = [r["seconds"] for r in rows if r["seconds"] is not None]
        cost = sum(r.get("cost_usd", 0) for r in rows)
        avg = f"{sum(secs) / len(secs):.1f}" if secs else "-"
        lines.append(f"| {p.name} | {p.model} | {ok}/{len(rows)} | {avg} | {cost:.3f} |")
    lines += ["", "숫자 검사: 항목 숫자를 모두, 순서대로, 새 숫자 없이 썼는지 (`report.narrative_ok`). "
              "통과하지 못하면 운영에서는 고정 문장(템플릿)이 나간다.", ""]
    for name, c in results.items():
        lines += [f"## {name}", "", "**집계값 (고정 문장)**", "", "```", c["template"], "```", ""]
        for pname, rows in c["runs"].items():
            for i, r in enumerate(rows, 1):
                mark = "통과" if r["numbers_ok"] else "**탈락**"
                lines += [f"**{pname} #{i}** · {mark} · {r['seconds']}초", "", "> " + (r["raw"] or f"(생성 실패: {r['note']})").replace("\n", "\n> "), ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeat", type=int, default=3, help="사례마다 몇 번씩 쓸지 (같은 입력에도 문장이 달라짐)")
    ap.add_argument("--only", choices=["ollama", "claude"])
    args = ap.parse_args()

    providers = []
    if args.only != "claude":
        providers.append(get_provider("ollama"))
    if args.only != "ollama":
        if not os.environ.get("ANTHROPIC_API_KEY"):
            sys.exit("ANTHROPIC_API_KEY가 없습니다. ai/.env에 ANTHROPIC_API_KEY=... 를 넣으세요.")
        providers.append(ClaudeText())

    results = {}
    for name, items in CASES.items():
        results[name] = {"template": template_narrative(validate_items(items)), "runs": {}}
        for p in providers:
            for i in range(args.repeat):
                print(f"{name} · {p.name} #{i + 1} ...", end=" ", flush=True)
                row = run_one(p, items)
                results[name]["runs"].setdefault(p.name, []).append(row)
                print("통과" if row["numbers_ok"] else "탈락", f"{row['seconds']}초")

    out = Path(__file__).resolve().parent / "results" / f"{time.strftime('%Y%m%d_%H%M%S')}_narrative_compare"
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    write_summary(results, providers, out / "summary.md")
    print(f"\n결과: {out / 'summary.md'}")


if __name__ == "__main__":
    main()
