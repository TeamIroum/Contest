"""프롬프트와 출력 스키마를 taxonomy.json에서 조립한다.

어떤 모델(provider)을 쓰든 같은 프롬프트와 같은 스키마를 쓴다.
모델별 차이(호출 방식)는 providers/ 안에만 둔다.
"""
import hashlib
import json

from config import BASE_DIR, facility, issue_label, taxonomy

ANALYZE_FILE = "analyze"
VERIFY_FILE = "verify"

MODE_INSTRUCTIONS = {
    "repair": (
        "담당자가 이 문제를 '처리 완료'로 보고했다. 이번 사진에서 지적된 문제가 실제로 고쳐졌는지 확인한다.\n"
        'result 값: 고쳐졌으면 "fixed", 그대로이거나 일부만 고쳐졌으면 "not_fixed", 판단할 수 없으면 "unclear".'
    ),
    "change": (
        "아직 처리되지 않은 문제다. 이전 사진과 비교해 상태가 어떻게 변했는지 확인한다.\n"
        'result 값: 나빠졌으면 "worse", 비슷하면 "same", 좋아졌으면 "better", 판단할 수 없으면 "unclear".'
    ),
}

RESULT_ENUMS = {
    "repair": ["fixed", "not_fixed", "unclear"],
    "change": ["worse", "same", "better", "unclear"],
}


def _load(name: str) -> str:
    return (BASE_DIR / "prompts" / f"{name}.txt").read_text(encoding="utf-8")


def _fingerprint(name: str) -> str:
    """프롬프트 파일 내용으로 만든 짧은 식별값. 파일을 고치면 자동으로 바뀌어,
    평가 결과가 어떤 프롬프트로 나온 것인지 따로 번호를 붙이지 않아도 구분된다."""
    digest = hashlib.sha1(_load(name).encode("utf-8")).hexdigest()[:7]
    return f"{name}-{digest}"


ANALYZE_VERSION = _fingerprint(ANALYZE_FILE)
VERIFY_VERSION = _fingerprint(VERIFY_FILE)


def _severity_lines(f: dict) -> str:
    lines = ["  0: 문제 없음"]
    for level in ("1", "2", "3"):
        lines.append(f"  {level}: {f['severity_guide'][level]}")
    return "\n".join(lines)


def facility_guide(pilot_only: bool = False) -> str:
    blocks = []
    for ftype, f in taxonomy()["facilities"].items():
        if pilot_only and not f.get("pilot"):
            continue
        issues = ", ".join(f"{k}({v})" for k, v in f["issues"].items())
        blocks.append(
            f"■ {ftype} ({f['label']})\n"
            f"  문제 유형: {issues}\n"
            f"  정도 기준:\n{_severity_lines(f)}"
        )
    return "\n\n".join(blocks)


def analyze_prompt(pilot_only: bool = False) -> str:
    return _load(ANALYZE_FILE).replace("{{FACILITY_GUIDE}}", facility_guide(pilot_only))


def verify_prompt(prior: dict, mode: str = "repair") -> str:
    """prior: {facility_type, issue_type, severity, bbox, evidence}"""
    f = facility(prior["facility_type"])
    if f is None:
        raise ValueError(f"알 수 없는 시설: {prior['facility_type']}")
    if mode not in MODE_INSTRUCTIONS:
        raise ValueError(f"mode는 repair 또는 change: {mode}")
    values = {
        "{{FACILITY_LABEL}}": f["label"],
        "{{FACILITY_TYPE}}": prior["facility_type"],
        "{{ISSUE_LABEL}}": issue_label(prior["facility_type"], prior["issue_type"]),
        "{{ISSUE_TYPE}}": prior["issue_type"],
        "{{PRIOR_SEVERITY}}": str(prior.get("severity", "?")),
        "{{PRIOR_BBOX}}": json.dumps(prior.get("bbox")),
        "{{PRIOR_EVIDENCE}}": prior.get("evidence") or "(없음)",
        "{{MODE_INSTRUCTION}}": MODE_INSTRUCTIONS[mode],
        "{{SEVERITY_GUIDE}}": _severity_lines(f),
    }
    text = _load(VERIFY_FILE)
    for k, v in values.items():
        text = text.replace(k, v)
    return text


def analyze_schema() -> dict:
    """모델이 반드시 지켜야 할 판독 출력 형식 (JSON Schema)."""
    ftypes = list(taxonomy()["facilities"].keys()) + ["unknown"]
    issue_types = sorted({i for f in taxonomy()["facilities"].values() for i in f["issues"]})
    return {
        "type": "object",
        "properties": {
            "facility_type": {"type": "string", "enum": ftypes},
            "facility_visible": {"type": "boolean"},
            "framing_ok": {"type": "boolean"},
            "is_night_photo": {"type": "boolean"},
            "issues": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "type": {"type": "string", "enum": issue_types},
                        "severity": {"type": "integer", "minimum": 1, "maximum": 3},
                        "bbox": {
                            "type": "array",
                            "items": {"type": "number", "minimum": 0, "maximum": 1},
                            "minItems": 4,
                            "maxItems": 4,
                        },
                        "evidence": {"type": "string"},
                    },
                    "required": ["type", "severity", "bbox", "evidence"],
                },
            },
            "overall_severity": {"type": "integer", "minimum": 0, "maximum": 3},
            "certainty": {"type": "string", "enum": ["high", "medium", "low"]},
            "privacy_leaks": {
                "type": "array",
                "description": "마스킹 후에도 알아볼 수 있는 얼굴·번호판",
                "items": {
                    "type": "object",
                    "properties": {
                        "kind": {"type": "string", "enum": ["face", "plate"]},
                        "bbox": {"type": "array", "items": {"type": "number", "minimum": 0, "maximum": 1},
                                 "minItems": 4, "maxItems": 4},
                    },
                    "required": ["kind", "bbox"],
                },
            },
        },
        "required": [
            "facility_type", "facility_visible", "framing_ok", "is_night_photo",
            "issues", "overall_severity", "certainty", "privacy_leaks",
        ],
    }


def verify_schema(mode: str = "repair") -> dict:
    return {
        "type": "object",
        "properties": {
            "same_facility": {"type": "string", "enum": ["yes", "no", "unclear"]},
            "clearly_visible": {"type": "boolean"},
            "result": {"type": "string", "enum": RESULT_ENUMS[mode]},
            "current_severity": {"type": "integer", "minimum": 0, "maximum": 3},
            "evidence": {"type": "string"},
            "certainty": {"type": "string", "enum": ["high", "medium", "low"]},
        },
        "required": ["same_facility", "clearly_visible", "result", "current_severity", "evidence", "certainty"],
    }
