FROM eclipse-temurin:21-jre

WORKDIR /app
COPY app.jar /app/app.jar
RUN useradd --system --uid 10001 appuser
USER appuser

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
