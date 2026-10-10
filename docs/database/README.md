# DB 초안과 트랜잭션 검증 자료

[DB_설계.md](../DB_설계.md)가 링크하는 파일을 두는 곳입니다.

- `core-schema-draft.sql`: 핵심 테이블 DDL 초안
- `transaction-tests/`: 참고 트랜잭션 회귀 검사(README, results.json)

설계 검토용 자료입니다. 실제 적용할 마이그레이션은 `backend/src/main/resources/db/migration/`에 둡니다. 두 곳의 스키마가 달라지면 마이그레이션 쪽이 실제 기준이고, 이 초안은 설계 문서와 함께 고칩니다.
