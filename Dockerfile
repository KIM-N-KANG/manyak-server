FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY . .

RUN chmod +x ./gradlew
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runtime-base

WORKDIR /app

RUN addgroup -S app && adduser -S app -G app

USER app

EXPOSE 8080

# 컨테이너 자체 헬스체크 (alpine busybox wget). ALB/compose 상태 판단에 사용.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# CI는 테스트와 같은 Gradle 실행에서 만든 JAR를 사용해 재컴파일하지 않는다.
FROM runtime-base AS ci-runtime
COPY build/libs/app.jar app.jar

# 마지막 target은 로컬 docker build .용이며 기존처럼 소스에서 bootJar를 만든다.
FROM runtime-base AS runtime
COPY --from=builder /app/build/libs/app.jar app.jar
