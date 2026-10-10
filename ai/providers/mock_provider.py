"""API 키 없이 앱 개발과 테스트를 하기 위한 가짜 provider.

- 같은 사진에는 항상 같은 결과를 준다 (사진 바이트 해시로 시나리오 선택)
- next_scenario를 지정하면 그 시나리오를 한 번 돌려준다 (테스트, 시연용)
실제 판독 능력은 없다. 정확도 평가에는 쓰지 않는다.
"""
import hashlib

from providers.base import Provider

ANALYZE_SCENARIOS = {
    "sidewalk_danger": {
        "facility_type": "sidewalk", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [{"type": "lifted", "severity": 3, "bbox": [0.31, 0.42, 0.55, 0.70],
                    "evidence": "보행 동선 가운데 블록 한 장이 주변보다 들려 단차가 보임"}],
        "overall_severity": 3, "certainty": "high",
    },
    "fence_tilted": {
        "facility_type": "fence", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [{"type": "tilted", "severity": 2, "bbox": [0.30, 0.20, 0.70, 0.85],
                    "evidence": "안전펜스 기둥 두 개가 보도 쪽으로 눈에 띄게 기울어 있음"}],
        "overall_severity": 2, "certainty": "high",
    },
    "bench_broken": {
        "facility_type": "bench", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [{"type": "board_broken", "severity": 3, "bbox": [0.20, 0.40, 0.75, 0.65],
                    "evidence": "벤치 좌판 나무 한 장이 가운데에서 부러져 아래로 꺼져 있음"}],
        "overall_severity": 3, "certainty": "high",
    },
    "drain_normal": {
        "facility_type": "drain", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [], "overall_severity": 0, "certainty": "high",
    },
    "drain_minor": {
        "facility_type": "drain", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [{"type": "covered", "severity": 1, "bbox": [0.20, 0.30, 0.80, 0.75],
                    "evidence": "덮개 위에 낙엽이 조금 있으나 구멍 대부분은 뚫려 있음"}],
        "overall_severity": 1, "certainty": "high",
    },
    "exercise_part": {
        "facility_type": "exercise", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [{"type": "part_detached", "severity": 3, "bbox": [0.45, 0.10, 0.70, 0.60],
                    "evidence": "허리돌리기 기구의 발판 한쪽이 축에서 빠져 기울어져 있음"}],
        "overall_severity": 3, "certainty": "high",
    },
    "sidewalk_uncertain": {
        "facility_type": "sidewalk", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
        "issues": [{"type": "sunken", "severity": 2, "bbox": [0.10, 0.50, 0.60, 0.90],
                    "evidence": "블록 일부가 낮아 보이나 그림자 때문에 단차 크기가 분명하지 않음"}],
        "overall_severity": 2, "certainty": "low",
    },
    "out_of_frame": {
        "facility_type": "drain", "facility_visible": True, "framing_ok": False, "is_night_photo": False,
        "issues": [], "overall_severity": 0, "certainty": "low",
    },
    "unknown_facility": {
        "facility_type": "unknown", "facility_visible": False, "framing_ok": True, "is_night_photo": False,
        "issues": [], "overall_severity": 0, "certainty": "medium",
    },
}

ANALYZE_SCENARIOS["plate_leak"] = {
    "facility_type": "drain", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
    "issues": [], "overall_severity": 0, "certainty": "high",
    "privacy_leaks": [{"kind": "plate", "bbox": [0.05, 0.05, 0.30, 0.15]}],
}
ANALYZE_SCENARIOS["issue_under_mask"] = {
    "facility_type": "sidewalk", "facility_visible": True, "framing_ok": True, "is_night_photo": False,
    "issues": [{"type": "broken", "severity": 2, "bbox": [0.40, 0.40, 0.60, 0.60],
                "evidence": "블록 일부가 깨져 보임"}],
    "overall_severity": 2, "certainty": "medium",
}
for _v in ANALYZE_SCENARIOS.values():
    _v.setdefault("privacy_leaks", [])

VERIFY_SCENARIOS = {
    "fixed": {"same_facility": "yes", "clearly_visible": True, "result": "fixed", "current_severity": 0,
              "evidence": "들렸던 블록이 주변 높이에 맞춰 다시 깔려 단차가 보이지 않음", "certainty": "high"},
    "not_fixed": {"same_facility": "yes", "clearly_visible": True, "result": "not_fixed", "current_severity": 2,
                  "evidence": "펜스 기울기가 이전 사진과 같음", "certainty": "high"},
    "different_place": {"same_facility": "no", "clearly_visible": True, "result": "unclear", "current_severity": 0,
                        "evidence": "배경 건물과 경계석 모양이 달라 다른 장소로 보임", "certainty": "high"},
    "worse": {"same_facility": "yes", "clearly_visible": True, "result": "worse", "current_severity": 3,
              "evidence": "이전보다 블록 들뜸이 커지고 깨진 조각이 생김", "certainty": "high"},
}


class MockProvider(Provider):
    name = "mock"
    model = "mock"

    def __init__(self):
        self.next_scenario: str | None = None

    def _pick(self, scenarios: dict, data: bytes) -> dict:
        if self.next_scenario:
            key, self.next_scenario = self.next_scenario, None
            return dict(scenarios[key])
        keys = sorted(scenarios)
        idx = int(hashlib.md5(data).hexdigest(), 16) % len(keys)
        return dict(scenarios[keys[idx]])

    def analyze(self, image_jpeg, prompt, schema):
        return self._pick(ANALYZE_SCENARIOS, image_jpeg)

    def verify(self, before_jpeg, after_jpeg, prompt, schema):
        result = self._pick(VERIFY_SCENARIOS, before_jpeg + after_jpeg)
        allowed = schema["properties"]["result"]["enum"]
        if result["result"] not in allowed:  # 모드에 맞게 값만 바꿔 준다
            result["result"] = {"worse": "not_fixed", "fixed": "better", "not_fixed": "same"}.get(
                result["result"], "unclear")
        return result

    def generate_text(self, prompt):
        return "(mock) 문장 생성은 실제 provider에서 동작합니다."
