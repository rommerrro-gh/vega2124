# syntax=docker/dockerfile:1.7-labs
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
RUN npm install --global pnpm@11.25.0
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm format:check && pnpm test && pnpm build

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY --parents **/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests dependency:go-offline

COPY . .
COPY --from=frontend /src/app-bootstrap/src/main/resources/static/miniapp/bundle.js /src/app-bootstrap/src/main/resources/static/miniapp/bundle.css ./app-bootstrap/src/main/resources/static/miniapp/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/data && chown -R app:app /app
COPY infra/certs/russian_trusted_root_ca.pem /tmp/russian_trusted_root_ca.pem
RUN keytool -importcert -noprompt -trustcacerts \
    -alias russian-trusted-root-ca \
    -file /tmp/russian_trusted_root_ca.pem \
    -cacerts -storepass changeit \
    && rm /tmp/russian_trusted_root_ca.pem
USER app
WORKDIR /app
COPY --from=build --chown=app:app /src/app-bootstrap/target/app-bootstrap-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", \
               "-XX:MaxRAMPercentage=75.0", \
               "-XX:+ExitOnOutOfMemoryError", \
               "-jar", "/app/app.jar"]
