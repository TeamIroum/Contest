-- 로컬 시험용: 시연 순회(1조)를 다른 날로 옮긴다. 시드(R__dev_seed_demo_scenario.sql)는 2026-10-15 하루로 고정이라
-- 그 밖의 날에는 출석·활동 시작·사진 제출이 날짜 오류(INVALID_ACTIVITY_TIME, SYNC_DEADLINE_EXCEEDED)로 막힌다.
-- 운영 DB에서는 쓰지 않는다.
--
-- 사용: psql -U inspection -d inspection -v day=2026-10-14 -f tools/e2e/move_demo_date.sql
--   day: 한국 시간 활동일. 예정 시각은 그날 10:00~12:00(KST), 사진 제출 기한은 그날 자정(KST)
\set patrol '\'30000000-0000-4000-8000-000000000001\''

UPDATE patrols
   SET activity_date      = :'day'::date,
       scheduled_start_at = (:'day' || ' 10:00')::timestamp AT TIME ZONE 'Asia/Seoul',
       expected_return_at = (:'day' || ' 12:00')::timestamp AT TIME ZONE 'Asia/Seoul',
       sync_deadline_at   = (:'day'::date + 1)::timestamp AT TIME ZONE 'Asia/Seoul'
 WHERE id = :patrol;

UPDATE patrol_members
   SET activity_date   = :'day'::date,
       scheduled_range = tstzrange((:'day' || ' 10:00')::timestamp AT TIME ZONE 'Asia/Seoul',
                                   (:'day' || ' 12:00')::timestamp AT TIME ZONE 'Asia/Seoul')
 WHERE patrol_id = :patrol;

SELECT id, activity_date, scheduled_start_at, sync_deadline_at FROM patrols WHERE id = :patrol;
