# 시니어 동네 점검단 · AI 판독 모듈 (초안)

기획안 3장 "AI 활용 계획" 표를 실제로 작동하는 코드로 옮긴 초안입니다. 지금 상태로도 mock 모드로 끝까지 돌아가고, 실제 판독은 자체 서버의 공개 모델(Ollama)로 합니다. 사진을 외부 AI API로 보내지 않습니다.

## 바로 실행해 보기

```bash
pip install -r requirements.txt

python -m pytest -q                                           # 테스트 135개 (규칙, 마스킹, 저장 원칙, 서버 처리, 활동 기록, 백엔드 연동, 점검 수정 확인)
AI_PROVIDER=mock uvicorn server:app --reload --port 8000      # 백엔드 연동 시험용 서버 (http://localhost:8000/docs)
python eval/run_eval.py analyze --labels eval/labels_sample.csv --provider mock   # 평가 스크립트 실행 확인

# 실제 사진 평가 (빠른 순서)
python eval/make_labels.py eval/photos                         # 사진 폴더 → 라벨 표 자동 생성 (문제 유형·위험도만 채움)
python eval/run_eval.py analyze --labels eval/photos/labels.csv --provider ollama
python eval/run_eval.py verify  --labels eval/photos/pairs.csv  --provider ollama
python eval/privacy/contact_sheet.py eval/photos/마스킹         # 가린 사진 확인 화면 + 기록표
python eval/privacy/contact_sheet.py --score eval/mask_check/mask_review.csv
python eval/quick_report.py --analyze eval/results/<..._analyze> --verify eval/results/<..._verify> --mask eval/mask_check/mask_review.csv

# 마스킹 점검
python eval/privacy/synth_plates.py                            # 합성 번호판 회귀 점검
python eval/privacy/run_mask_eval.py eval/privacy/labels.jsonl # 실제 라벨 사진 평가 (시범 전 필수)

# 공개 모델 비교 (Ollama에 모델을 받아 두고 OLLAMA_MODEL만 바꿔 같은 사진으로 실행)
ollama pull qwen3-vl:8b-instruct
python eval/run_eval.py analyze --labels eval/photos/labels.csv --provider ollama --sample 20   # 기준 문장 다듬을 때
OLLAMA_MODEL=qwen3-vl:8b python eval/run_eval.py analyze --labels eval/photos/labels.csv --provider ollama
```

### Windows에서 실제 판독 서버 실행 (Ollama)

모델별 설정(mock·Ollama)과 검증 콘솔 실행, 자주 나는 문제는 [AI 실행 가이드](../docs/AI_실행_가이드.md)에 정리되어 있습니다.

1. 프로젝트를 **한글이 없는 경로**에 둡니다 (예: `C:\contest\ai`). 경로에 한글이 있으면 OpenCV가 얼굴 검출 모델을 읽지 못해 서버가 시작되지 않습니다.
2. `.env.example`을 복사해 `.env`를 만들고 값을 채웁니다. `.env`는 `.gitignore`에 들어 있어 GitHub에 올라가지 않습니다. `API_KEYS` 같은 비밀값은 코드나 채팅에 붙이지 말고 `.env`에만 둡니다.
   ```
   AI_PROVIDER=ollama
   OLLAMA_MODEL=qwen3-vl:8b-instruct
   AI_TIMEOUT_S=12
   ```
   Ollama 설치와 모델 받기는 [AI 실행 가이드](../docs/AI_실행_가이드.md) 4.2절을 따릅니다.
3. 이 명령으로 실행합니다 (`.env`를 읽는 `python-dotenv`는 `requirements.txt`에 들어 있습니다).
   ```powershell
   python -m uvicorn server:app --port 8000 --env-file .env
   ```
4. 다른 팀원이 같은 와이파이에서 접속하게 하려면 `--host 0.0.0.0`을 붙이고, 이때는 `.env`에 `API_KEYS=팀용_비밀값`을 넣어 인증을 켭니다.

판독이 실패하면 서버 로그에 `AI 판독 실패 photo_id=...: <오류 종류> ...` 줄이 찍힙니다. `ConnectError`는 Ollama가 꺼져 있음, `ReadTimeout`은 모델이 아직 GPU에 올라가지 않았거나 12초 안에 답하지 못함, 404는 `OLLAMA_MODEL` 이름이 받아 둔 모델과 다름을 뜻합니다.

마스킹과 AI 서버 처리의 자세한 설계는 **`MASKING_AND_SERVER.md`**에 있습니다. 배포는 `Dockerfile`, 설정 값은 `.env.example`을 봅니다.

`eval/sample/`의 사진은 스크립트 실행 확인용 합성 이미지입니다. 정확도 수치는 실제 사진으로 만든 `eval/labels.csv`에서만 의미가 있습니다.

## AI가 하는 일 4가지와 코드

| 기획안 문구 | 구현 | 코드 |
|---|---|---|
| 1. 촬영 단계 자동 마스킹 | 앱이 가린 사진만 받고, 서버는 저장 전에 가림 상태를 확인해 남은 부분을 가린 수를 기록(`server_added`, 0이 정상). 담당자 표본 점검 목록과 보류 처리 | `pipeline.ingest`, `privacy.py`, `activity.masking_audit_sample`, `contract/APP_MASKING_GUIDE.md` |
| 2. 시설·이상 자동 판독 | 5종 시설·이상·위험도 판독, 규칙으로 담당 부서 지정, 확신 낮으면 담당자 확인 | `prompts/analyze.txt`, `rules.py`, `taxonomy.json` |
| 3. 전후 사진 AI 비교 | 같은 장소 확인 → 수리 확인·변화 비교, 미수리 재요청, 악화 시 등급 상향 | `pipeline.verify_photo`, `prompts/verify.txt` |
| 4. 활동 기록과 주간 보고 | 참여자별 활동 실적(첫·마지막 촬영 시각, 이동 경로 없음), 시설별 이력, 주간 보고와 우선 처리 목록 | `activity.py`, `report.py` |

이전 사진을 흐리게 겹쳐 보여 주는 재촬영 화면, 재요청 전달, 우선순위 정렬 화면은 앱·백엔드가 맡습니다.

### 업무 백엔드 연동용 기능

| 기능 | 입구 | 코드 |
|---|---|---|
| 참고·보수 사진의 개인정보 단독 검사 (판독·실적에 안 들어감) | `POST /privacy/mask`, `GET /privacy/{media_id}` | `pipeline.mask_media`, `storage.LocalMediaStore` |
| 백엔드가 확정한 집계값으로 보고 문장만 생성 (새 숫자·누락 숫자 검사) | `POST /report/narrative` | `report.write_narrative` |
| 사진 버전과 변경 알림 (다시 가림·보류·재판독 결과) | `image_revision`, `X-Image-Revision`, `GET /events` | `storage.EventLog`, `LocalImageStore.revision` |
| 재판독을 더 할 수 없으면 대기를 끝내고 담당자 확인 | `retry.gave_up`, `retry.reason` | `jobs.RetryQueue`, `pipeline.give_up_retry` |

백엔드가 맞춰야 할 점(위치는 `location_tags`로, 마스킹 보고 키는 snake_case 등)은 `contract/API_CONTRACT.md` 마지막 절에 있습니다.

## AI 학습 단계

이 시스템에서 학습이 필요한 AI와 필요 없는 AI는 다음과 같습니다.

| AI | 학습 | 방법 | 시기 |
|---|---|---|---|
| 번호판 검출 (마스킹) | **처음에는 불필요** | 여러 나라 번호판으로 이미 학습된 공개 모델(open-image-models, MIT)을 바로 씀. 번호판을 못 찾은 차량은 사전 학습 차량 검출기로 통째로 가림. 실제 한국 사진 평가에서 기준(98%)에 못 미치면, 이 모델에서 출발해 한국 사진 100~300장으로 **추가 학습** | 시범 전 실제 사진 평가는 필수, 추가 학습은 평가 결과에 따라 |
| 얼굴 검출 (마스킹) | 불필요 | 이미 학습된 공개 모델 YuNet (서버와 시니어 PWA가 같은 파일 사용) | — |
| 시설·이상 판독, 전후 비교 | 불필요 (처음에는) | 이미 학습된 공개 멀티모달 AI를 자체 서버에 올려 그대로 쓰고, 기준표·프롬프트·예시 사진으로 맞춤. 담당자가 고친 판정을 모아 평가하고 기준표를 고치는 순환 | 시범 기간 내내 |
| 자체 판독 모델 | 선택 | 시범으로 라벨이 수천 장 쌓이면, 양주시 시설에 맞춘 정확도와 더 작고 싼 모델로 비용 절감을 위해 미세조정을 검토 | 3단계 고도화 |

기획안의 "고친 판정이 학습·검증 자료로 쌓인다"는 처음에는 재학습이 아니라 평가와 기준표 개선을 뜻하고, 데이터가 충분히 쌓인 뒤에 실제 학습으로 이어집니다.

## 점검 대상 (5종)

사진으로 확인할 수 있는 이상만 다룹니다. 목록에 없는 대상은 AI가 `unknown`으로 돌려주고 담당자 확인으로 넘어갑니다.

| 점검 대상 | 사진으로 확인할 이상 (`taxonomy.json` 키) | 담당 |
|---|---|---|
| 보도블록 `sidewalk` | 들뜸 `lifted` · 파손 `broken` · 꺼짐 `sunken` | 도로 담당 |
| 빗물받이 `drain` | 낙엽·쓰레기로 덮임 `covered` · 덮개 파손 `cover_damaged` · 덮개 이탈 `cover_missing` | 하수 담당 |
| 벤치 `bench` | 좌판·등받이 파손 `board_broken` · 부품 이탈 `part_detached` | 공원 담당 |
| 안전펜스 `fence` | 기울어짐 `tilted` · 파손 `broken` · 연결부 이탈 `joint_detached` | 도로 담당 |
| 운동기구 `exercise` | 부품 파손 `broken` · 부품 이탈 `part_detached` · 변형 `deformed` | 공원 담당 |

정도(0~3) 기준 문장은 `taxonomy.json`의 `severity_guide`에 있습니다. 문제 유형에 따른 최소 등급은 `rules.json`에 있습니다(빗물받이 덮개 이탈은 긴급, 벤치·운동기구 부품 이탈과 펜스 연결부 이탈은 최소 주의).

## 구조와 역할 분담

```
ai/
├─ taxonomy.json          시설·문제 유형·정도 기준·담당 분야·어르신 안내 문구 (팀 공통 기준)
├─ rules.json             위험도 규칙표: 위치 가중치, 최소 등급 등 (팀 합의 대상)
├─ prompts/
│   ├─ analyze.txt        판독 프롬프트
│   └─ verify.txt         전후 비교 프롬프트 (repair / change 모드)
├─ prompts.py             taxonomy로 프롬프트와 출력 스키마 조립 (모델 공통)
├─ providers/             ← 모델 교체 지점
│   ├─ base.py            공통 인터페이스: analyze(), verify(), generate_text()
│   ├─ ollama_provider.py 자체 서버의 공개 비전 모델 (운영, 사진이 밖으로 안 나감)
│   └─ mock_provider.py   API 없이 개발·테스트용
├─ images.py              업로드 검증, 회전 보정, 메타데이터 제거, 축소
├─ quality.py             흐림·어두움 확인 (OpenCV, AI 호출 전)
├─ privacy.py             얼굴(YuNet)·번호판 검출과 가림 (저장·외부 전송 전)
├─ models/                마스킹 모델 (얼굴 YuNet, 번호판 모델 자리)
├─ storage.py             가린 사진 저장소(버전 기록), 판독 결과·개인정보 검사 기록, 변경 알림 (원본은 저장하지 않음)
├─ jobs.py                AI 동시 호출 제한, 실패 건 재판독 대기열
├─ activity.py            참여자 활동 기록, 시설별 이력, 마스킹 표본 점검
├─ rules.py               등급 계산 (코드 규칙)
├─ ledger.py              시설 대장 GPS 매칭, 중복 확인
├─ pipeline.py            받기(검증·마스킹·저장) → 판단(AI·규칙) → 결과 저장
├─ report.py              주간 보고 집계, 생성 문장 숫자 검증
├─ server.py              판독·비교·결과·사진·활동·이력·보고·점검 API
├─ MASKING_AND_SERVER.md  마스킹·서버 처리 설계서
├─ Dockerfile, .env.example
├─ contract/              팀에 넘길 것: 계약서(백엔드), 앱 마스킹 구현 안내서(앱), 경우별 예시 응답 24개
├─ eval/                  판독 평가, 마스킹 평가(eval/privacy/), 라벨 기준, 샘플
├─ data/sample_ledger.csv 시설 대장 예시 (가짜 좌표)
└─ tests/                 규칙·마스킹·저장·서버 처리 테스트
```

AI가 하는 일과 코드가 하는 일은 기획안대로 나눴습니다.

| 기획안 단계 | 담당 | 파일 |
|---|---|---|
| 사진 품질 확인 | 코드(흐림·어두움) + AI(틀 밖·시설 안 보임) | `quality.py`, `pipeline.py` |
| 시설·문제 인식 | AI | `prompts/analyze.txt` |
| 위험도 판정 | AI(정도 0~3) + 코드(위치 가중치·최소 등급). 업무 시스템은 등급을 쓰지 않고 원문 결과에만 보관(`docs/AI_기능_설계.md`, 2026-10-11 결정) | `rules.py`, `rules.json` |
| 위치·중복 확인 | 코드 | `ledger.py` |
| 담당자 배정 | 코드 | `taxonomy.json`의 `dept` |
| 변화 비교 | AI(change 모드) + 코드(재등급) | `pipeline.verify_photo` |
| 수리 확인 | AI(repair 모드) + 코드(종료·재요청) | `pipeline.verify_photo` |
| 안내·보고 작성 | 어르신 안내는 고정 문구, 보고는 AI + 숫자 검증 | `taxonomy.json`, `report.py` |

## 개인정보: 서버에는 가린 사진만 남습니다

| 단계 | 원본 | 가린 사진 |
|---|---|---|
| 휴대폰 | 앱 안에서만 다루고 가린 뒤 지움. 휴대폰 밖으로 나가지 않음 | 앱이 1차로 가림 |
| 휴대폰 → 서버 전송 | — | 암호화 연결(HTTPS)로 가린 사진만 전송 |
| 서버 처리 | — | 저장 전에 가림 상태를 확인하고, 남은 부분이 있으면 저장·AI 판독 전에 가리고 수를 기록 (앱 마스킹이 없는 시험 단계에서는 서버가 마스킹을 맡음) |
| 저장 | **저장하지 않음** | `data/images/<photo_id>_masked.jpg` |
| AI 판독 | 보내지 않음 | 이것만 보냄 |
| 담당자 화면·수리 확인 | 볼 방법 없음 | `GET /images/...`, `/verify`의 `before_photo_id` |

가리는 과정에서 오류가 나면 저장도, AI 전송도 하지 않습니다. AI가 가림이 덜 된 곳을 보고하면 저장된 사진을 다시 가립니다. 자세한 내용과 번호판 검출의 현재 한계는 `MASKING_AND_SERVER.md` 3장에 있습니다.

## 사진 한 장의 흐름 (analyze_photo)

1. **받기**: JPEG/PNG·15MB 이하 확인, 회전 보정, 메타데이터 제거. 같은 `photo_id` 재요청이면 저장된 결과를 그대로 돌려줌
2. **품질 확인**: 흐리거나 어두우면 AI를 부르지 않고 바로 재촬영 안내 (1초 이내). 이때는 아무것도 저장하지 않음
3. **마스킹·저장**: 얼굴·번호판을 가린 사진만 저장하고 `masked_image_url`로 돌려줌. 실패하면 저장·AI 전송 없이 다시 찍기
4. **AI 판독**: 동시 호출 4개 제한. 시설, 문제, 정도, 근거 문장, 확신도, 가림 누락을 정해진 형식으로 받음. 오류·지연이면 담당자 확인으로 돌려주고 저장된 가린 사진으로 나중에 다시 판독
5. **2차 확인**: 가림 누락은 다시 가리고, 문제 위치가 가림 영역과 겹치면 담당자 확인
6. **사진 상태**: 틀 밖이면 재촬영, 목록에 없는 대상이면 담당자 확인 (재촬영 아님)
7. **문제 정리**: 시설과 맞지 않는 문제 유형 제외 (예: 빗물받이에 '들뜸')
8. **대장 매칭**: GPS 반경 20m 안 같은 종류 시설 중 가장 가까운 것, 5m 안에 다른 후보가 있으면 '애매'
9. **등급 계산**: 정도 → 기본 등급, 정도 2 이상이면 위치 가중치 +1, 악화면 +1, 문제 유형별 최소 등급
10. **확신 낮음**: 담당자 확인으로. 잠정 긴급이면 `danger_keep_away` 문구를 내지만, 업무 백엔드는 어르신에게 보여 주지 않음(2026-10-11 팀 결정)
11. **중복 확인**: 같은 시설·같은 문제가 미처리로 남아 있으면 기존 건에 묶음
12. **결과 저장**: `GET /results/{photo_id}`로 최신 결과 조회

수리 확인(verify_photo)은 같은 시설인지, 문제 부위가 잘 보이는지를 먼저 확인합니다. 둘 중 하나라도 아니면 재촬영으로 보내고, 절대 '미수리 재요청'을 보내지 않습니다.

## 구현 순서와 현재 상태

| 단계 | 할 일 | 상태 |
|---|---|---|
| 1 | 분류 체계(`taxonomy.json`), 규칙표(`rules.json`) | 초안 완료 → **팀 검토 필요** |
| 2 | 응답 형식 확정, 예시 응답·mock 서버를 백엔드 담당에게 전달 | 초안 완료 → **백엔드 담당과 합의 필요** |
| 3 | 테스트셋 촬영 (5종 × 50장 + 전후 30쌍) | 할 일 |
| 4 | 라벨링, 사람끼리 일치율 측정 (`eval/LABELING_GUIDE.md`) | 할 일 |
| 5 | 판독 프롬프트 평가·개선 (버전 올리며 같은 테스트셋으로 비교) | 프롬프트 초안 완료, 평가는 할 일 |
| 6 | 전후 비교 평가 | 프롬프트 초안 완료, 평가는 할 일 |
| 7 | 마스킹: 얼굴 YuNet, 번호판 사전 학습 모델·차량 안전망 적용, 실제 사진 평가 | 적용 완료, **실사진 평가는 할 일**. 기준 미달이면 추가 학습 (`MASKING_AND_SERVER.md` 4장) |
| 8 | 앱 연동, 주간 보고 연결 | 서버 완료, 연동은 할 일 |
| 9 | 후보 모델 2~3개 비교 (`providers/`에 파일 추가) | 할 일 |

### 프롬프트를 고칠 때

`prompts/analyze.txt`를 바로 고치면 됩니다. 응답과 평가 결과의 `prompt_version`에는 파일 내용으로 만든 식별값(예: `analyze-75c0e69`)이 자동으로 기록되므로, 따로 번호를 붙이지 않아도 어떤 수정이 효과가 있었는지 비교할 수 있습니다. 예전 문장으로 되돌릴 일에 대비해 고치기 전 파일은 팀 저장소(Git)에 남겨 두세요. 잘 틀리는 사진 유형이 보이면 기준표 문장(`severity_guide`)을 구체적으로 고치는 것이 가장 효과가 큽니다. 그다음 방법은 정상·문제 예시 사진을 함께 보내는 few-shot입니다.

### 다른 모델을 붙일 때

`providers/`에 `Provider`를 상속한 파일을 하나 만들고 `providers/__init__.py`의 `get_provider`에 이름을 추가합니다. 프롬프트와 스키마는 `pipeline.py`가 넘겨주니 그대로 쓰면 됩니다. 나머지 코드는 바꾸지 않습니다. 국내 클라우드나 자체 구축 모델도 같은 방식으로 붙입니다.

## 평가 지표

`eval/run_eval.py`가 계산해 `summary.md`로 남깁니다.

- 시설 분류 정확도, 문제 유무 정확도
- 정도 정확 일치율, ±1 일치율
- 등급 일치율과 등급 혼동표
- **긴급 재현율** (실제 긴급 중 AI가 긴급으로 잡은 비율), 그리고 긴급을 정상·관찰로 놓친 사진 목록
- 재촬영 판정 재현율·정밀도, 담당자 확인 비율, 응답 시간
- 수리 확인: 처리 방침 정확도, **잘못된 재요청 건수**, 안 고쳐졌는데 종료한 건수

발표에서는 긴급 재현율과 잘못된 재요청 건수를 따로 강조합니다. 가장 위험한 실수와 담당자 신뢰를 무너뜨리는 실수를 각각 얼마나 막았는지 보여주는 수치이기 때문입니다.

## 팀에 전달할 것

**백엔드 담당**: `contract/API_CONTRACT.md`, `contract/fixtures/`, mock 서버 실행법. 앱은 AI를 직접 부르지 않고 백엔드가 부릅니다. 계약서 끝의 "백엔드 담당에게 부탁할 것"과 "백엔드 연동 시 꼭 맞출 것"을 함께 이야기합니다.

**앱 담당**: `contract/APP_MASKING_GUIDE.md`. 기기 마스킹, 앱 안 카메라, 재촬영 시 이전 사진 겹쳐 보기가 앱이 할 일입니다.

**기획·발표 담당**: 평가 후 `summary.md` 한 장, 자주 틀리는 사례 2~3개와 대응책, 아래 '팀이 결정할 것'.

**팀 전체**: `taxonomy.json`, `rules.json`, `eval/LABELING_GUIDE.md`. 규칙표 수치는 혼자 바꾸지 않고 팀 합의 후 `version`을 올립니다.

## 팀이 결정할 것

1. **기획안 점검 대상 문구**: 점검 대상을 사진으로 확인할 수 있는 5종(보도블록, 빗물받이, 벤치, 안전펜스, 운동기구)으로 바꿨습니다. 기획안의 보안등·놀이터·맨홀·도로표지판 언급과 시설-담당 분야 그림을 이 5종에 맞춰 고쳐야 합니다.
2. **위치 가중치 강도**: 지금은 정도 2 이상일 때만 +1입니다. 정도 1도 통학로면 올릴지 정합니다.
3. **잠정 긴급 처리**: 결정됨(2026-10-11). 업무 시스템은 AI 위험도 등급과 등급에서 나오는 action(`alert_now`, `human_review_urgent` 등)을 화면·판정·알림·우선순위에 쓰지 않습니다. 검토 분류는 `status`와 `issues`로 정합니다. AI 계약은 바꾸지 않으므로 AI는 지금처럼 등급을 계산해 원문 결과에 남깁니다(`/verify`의 `prior_json.severity` 입력용). `danger_keep_away` 안내도 어르신에게 보여 주지 않습니다.
4. **기획안 문구 조정**: "고친 판정이 학습 자료로 쌓인다"는 LLM 방식에서는 재학습이 아니라 기준표·예시 사례 개선을 뜻합니다. 발표에서 정확히 설명할 수 있게 문구를 다듬습니다.
5. **가린 사진 보존 기간**: 지금은 지우지 않고 계속 쌓입니다. 정상 판정 사진은 몇 개월, 처리된 건의 사진은 시설 이력용으로 몇 년처럼 기간을 정하고 자동 삭제를 붙여야 합니다.
6. **업로드 실패 시**: 가린 사진을 기기에 보관했다가 다시 보내는 방식은 업무 백엔드의 오프라인 계약을 따릅니다. 원본은 기기에도 남기지 않습니다. 기기 보관 사진의 암호화와 보관 기한은 백엔드 담당과 정합니다.
7. **실제 운영 시 모델**: 결정됨(2026-10-11). 처음부터 공개 모델을 자체 서버에 올려 운영합니다(사진이 외부 API로 나가지 않음). 어떤 공개 모델을 쓸지는 같은 라벨 사진으로 평가해 정합니다(현재 시험 모델 `qwen3-vl:8b-instruct`). Gemini·Claude provider는 없앴습니다(코드는 `archive/ai-external-providers/`). GPU 서버는 활동 시간에만 켭니다(`docs/AI_실행_가이드.md` 4.6절).

## 알려진 한계 (초안)

- 변경 알림(`GET /events`)과 재판독 대기열은 서버 작업자 1개 기준입니다. 작업자를 늘리려면 둘 다 DB나 Redis로 옮겨야 합니다.
- `/images`는 API 키로 막혀 있어 웹 화면 `<img>`로 바로 띄울 수 없습니다. 운영 단계에서 서명 주소 방식으로 바꿔야 합니다.
- 번호판은 사전 학습 모델 + 규칙 기반 + 차량 안전망으로 가립니다. 합성 점검 65%는 참고치일 뿐이고, 실제 한국 사진 평가를 통과하기 전에는 시범 운영을 하면 안 됩니다.
- `quality.py`의 흐림·어두움 기준값은 임시값입니다. 테스트셋의 재촬영 판정 결과를 보고 조정합니다.
- `data/sample_ledger.csv`는 가짜 좌표입니다. 실제 시설 대장을 받을 수 없으면 공공데이터포털에서 양주시 도시공원·어린이보호구역 같은 공개 데이터가 있는지 찾아보고, 시범 구역은 팀이 직접 좌표를 찍어 만들어도 됩니다.
- AI가 내는 `certainty`는 모델의 자기 평가라 완벽하지 않습니다. 담당자 확인 비율과 실제 오답 비율을 비교해 신뢰할 만한지 확인합니다.
- 규칙 기반 번호판 검출(`heuristic`)이 보도블록 줄눈·점자블록 무늬, 간판 글자 등을 번호판으로 잡는 경우가 있습니다. 거리 사진 테스트에서 사진의 25%가 가려진 사례가 있어, 판독에 필요한 부분이 가려지지 않는지 확인하고 기준 점수를 조정해야 합니다.
