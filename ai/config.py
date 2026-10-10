"""taxonomy.json과 rules.json을 읽어 다른 모듈에 제공한다.

두 파일은 팀 전체의 기준이므로 코드 안에 시설 이름이나 등급 숫자를 직접 쓰지 않는다.
"""
import json
from functools import lru_cache
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent


@lru_cache
def taxonomy() -> dict:
    return json.loads((BASE_DIR / "taxonomy.json").read_text(encoding="utf-8"))


@lru_cache
def rules() -> dict:
    return json.loads((BASE_DIR / "rules.json").read_text(encoding="utf-8"))


def grade_index(grade: str) -> int:
    return taxonomy()["grades"].index(grade)


def grade_name(index: int) -> str:
    grades = taxonomy()["grades"]
    return grades[max(0, min(index, len(grades) - 1))]


def facility(ftype: str) -> dict | None:
    return taxonomy()["facilities"].get(ftype)


def issue_label(ftype: str, itype: str) -> str:
    f = facility(ftype)
    return (f or {}).get("issues", {}).get(itype, itype)


def senior_message(key: str) -> dict:
    return {"key": key, "text": taxonomy()["senior_messages"][key]}


def all_issue_types() -> list[str]:
    types = set()
    for f in taxonomy()["facilities"].values():
        types.update(f["issues"].keys())
    return sorted(types)
