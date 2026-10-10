"""시설 대장 매칭과 중복 확인 (코드 규칙).

실제 운영에서는 시가 보유한 시설 대장을 쓴다. 지금은 data/sample_ledger.csv 예시 데이터를 쓴다.
백엔드 담당과 누가 맡을지 정한 뒤 옮겨도 되지만, 판독 결과에 facility_id가 들어가야
중복 묶기와 수리 확인이 되므로 인터페이스는 이 형태를 유지한다.
"""
import csv
import math
from functools import lru_cache

from config import BASE_DIR

MATCH_RADIUS_M = 20.0  # 휴대폰 GPS 오차를 감안한 반경. 시범 구역에서 실측 후 조정


def _haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


@lru_cache
def load_ledger(path: str | None = None) -> tuple[dict, ...]:
    path = path or str(BASE_DIR / "data" / "sample_ledger.csv")
    with open(path, encoding="utf-8") as f:
        rows = []
        for row in csv.DictReader(f):
            row["lat"], row["lon"] = float(row["lat"]), float(row["lon"])
            row["tags"] = [t for t in row["tags"].split(";") if t]
            rows.append(row)
    return tuple(rows)


def match_facility(lat: float | None, lon: float | None, facility_type: str,
                   radius_m: float = MATCH_RADIUS_M) -> dict | None:
    """반경 안에서 같은 종류 시설 중 가장 가까운 것. 후보가 여럿이면 ambiguous로 표시한다."""
    if lat is None or lon is None:
        return None
    cands = []
    for row in load_ledger():
        if row["facility_type"] != facility_type:
            continue
        d = _haversine_m(lat, lon, row["lat"], row["lon"])
        if d <= radius_m:
            cands.append((d, row))
    if not cands:
        return None
    cands.sort(key=lambda x: x[0])
    d, best = cands[0]
    return {
        "facility_id": best["facility_id"],
        "place": best["place"],
        "tags": best["tags"],
        "distance_m": round(d, 1),
        "ambiguous": len(cands) > 1 and cands[1][0] - d < 5.0,  # 5m 안에 다른 후보가 있으면 애매
    }


def find_duplicate(open_cases: list[dict], facility_id: str | None, issue_type: str | None) -> str | None:
    """같은 시설, 같은 문제로 아직 처리되지 않은 건이 있으면 그 case_id."""
    if not facility_id or not issue_type:
        return None
    for case in open_cases or []:
        if case.get("facility_id") == facility_id and case.get("issue_type") == issue_type:
            return case.get("case_id")
    return None
