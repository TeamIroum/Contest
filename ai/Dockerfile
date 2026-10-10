FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
# 사전 학습 마스킹 모델을 이미지에 미리 받아 둔다 (운영 서버가 외부에서 내려받지 않도록)
RUN python -c "import privacy; privacy.plate_detectors(); privacy.vehicle_detector()"
# 가린 사진·결과는 컨테이너 밖 볼륨에 둔다. 원본은 어디에도 저장되지 않는다.
VOLUME ["/app/data/images", "/app/data/images_revisions", "/app/data/results"]
ENV AI_PROVIDER=mock
EXPOSE 8000
# 작업자 1개: 재판독 대기열이 프로세스 안에 있으므로. 늘리려면 대기열을 외부(Redis 등)로 옮긴다.
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
