# ============================================================
# Gying Movie Backend - Multi-stage Docker Build
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S -g 10001 gying && adduser -S -D -u 10001 -G gying gying \
    && mkdir -p /app/logs /app/data && chown -R gying:gying /app

COPY --from=build /app/target/*.jar app.jar

USER gying
EXPOSE 8880

# JVM tuning for container environments
ENV TZ="Asia/Shanghai"
ENV JAVA_OPTS="-Duser.timezone=Asia/Shanghai -Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
