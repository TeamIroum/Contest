# 마스킹 모델

| 파일 | 용도 | 출처 | 라이선스 |
|---|---|---|---|
| `face_yunet_s.onnx` | 얼굴 검출 (OpenCV `FaceDetectorYN`으로 실행) | ShiqiYu/libfacedetection.train 의 `onnx/yunet_s_640_640.onnx` | BSD 3-Clause |
| (자동 다운로드) `yolo-v9-s-608-license-plate-end2end` | 번호판 검출, 여러 나라 번호판으로 사전 학습 | open-image-models 패키지가 처음 쓸 때 캐시(`~/.cache/open-image-models`)에 내려받음 | 패키지 MIT. 모델 가중치의 학습 데이터 출처와 원 모델(YOLOv9) 라이선스는 도입 전 저장소에서 다시 확인 |
| (자동 다운로드) `rf-detr-nano-384-coco` | 차량(자동차·버스·트럭·오토바이) 검출, COCO 사전 학습 | 위와 같음 | RF-DETR(Apache-2.0) 기반. 도입 전 다시 확인 |
| (없음) `plate_ko.onnx` | 한국 번호판으로 추가 학습한 모델 | 사전 학습 모델로 부족할 때만 팀이 만들어 넣음. `PLATE_MODEL`에 경로 지정 | 학습 데이터 이용 조건 확인 |

얼굴 모델 파일이 없으면 서버는 OpenCV 기본 Haar 검출기로 대신 동작하고, `/health`와 로그에 그 사실이 표시됩니다. 운영에서는 반드시 YuNet이 켜져 있는지 확인합니다.

사전 학습 모델은 인터넷이 없는 서버에서는 내려받을 수 없으므로, Docker 이미지를 만들 때 미리 받아 둡니다(`Dockerfile` 참고). 추가 학습 모델을 넣는 방법은 `MASKING_AND_SERVER.md`의 "번호판 검출 모델 만들기"를 따릅니다.
