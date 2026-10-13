# 업무 백엔드 실행 이미지. service/backend의 bootJar(service/backend/build/libs/*.jar)를 app.jar로 넣는다.
#   cp service/backend/build/libs/inspection-backend-*.jar service/docker/app.jar && docker build -t inspection-backend service/docker
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
    && mkdir -p /var/lib/inspection/media && chown app:app /var/lib/inspection/media
WORKDIR /app
COPY app.jar /app/app.jar
USER app
ENV TZ=Asia/Seoul JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
