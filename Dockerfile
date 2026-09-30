# syntax=docker/dockerfile:1.7-labs
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY --parents pom.xml **/pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests dependency:go-offline

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/data && chown -R app:app /app
USER app
WORKDIR /app
COPY --from=build --chown=app:app /src/app-bootstrap/target/app-bootstrap-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", \
               "-XX:MaxRAMPercentage=75.0", \
               "-XX:+ExitOnOutOfMemoryError", \
               "-jar", "/app/app.jar"]
