#!/bin/bash
# 시연 데이터 초기화(reset-demo.ps1) 보조: WSL 안에서 한 번만 실행된다.
# 백엔드가 시연 DB를 다시 만들면(ai_event_cursors 표가 생기면) 곧바로 AI 동기화 위치를 원래 값(CUR)으로 넣는다.
# 백엔드는 켜진 뒤 15초가 지나야 첫 동기화를 하므로, 그 전에 넣으면 이미 처리한 AI 알림을 다시 읽지 않는다.
# 필요한 환경 변수: PGPASSWORD, CUR (WSLENV로 Windows에서 넘김)
for i in $(seq 1 600); do
  if [ "$(psql -h localhost -U inspection -d inspection -At -c "select to_regclass('public.ai_event_cursors') is not null" 2>/dev/null)" = t ]; then
    psql -h localhost -U inspection -d inspection -At -c "INSERT INTO ai_event_cursors (provider, generation, last_applied_seq) VALUES ('ai-v2','g1',$CUR) ON CONFLICT (provider, generation) DO UPDATE SET last_applied_seq = GREATEST(ai_event_cursors.last_applied_seq, EXCLUDED.last_applied_seq)" > /dev/null
    echo "ok $i"
    exit 0
  fi
  sleep 1
done
echo "timeout"
exit 1
