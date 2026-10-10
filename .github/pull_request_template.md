## 기능 ID

<!-- 기능정의서의 ID. 예: SEN-03, FAC-09. 해당 없으면 '없음' -->

## 바꾼 것

## 확인한 것

<!-- 실행한 테스트, 직접 확인한 화면·API. 수용 기준(ACC-xx)이 있으면 적기 -->

## 계약·문서 영향

- [ ] 업무 API 형식이 바뀜 → `docs/API_명세서.md` 함께 수정
- [ ] DB 구조가 바뀜 → `docs/DB_설계.md`와 마이그레이션 함께 수정
- [ ] AI 응답 형식이 바뀜 → `ai/contract/API_CONTRACT.md` 수정, `contract_version` 올림, 예시 응답 재생성(`python contract/make_fixtures.py`)
- [ ] 해당 없음

## 시연 표시

- [ ] 실제 구현
- [ ] 모의(mock·사전 준비 자료) — 화면·발표에서 실제 기능과 구분해 표시
