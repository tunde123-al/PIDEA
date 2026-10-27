# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
# Pull the patched brotli-libs (CVE-2026-111497) until the pinned base image digest is refreshed.
RUN apk upgrade --no-cache brotli-libs
RUN addgroup -S -g 2000 pidea-files \
    && adduser -S -G pidea-files pidea \
    && mkdir -p /var/lib/pidea/uploads \
    && chown pidea:pidea-files /var/lib/pidea/uploads \
    && chmod 2770 /var/lib/pidea/uploads
WORKDIR /app
COPY --from=build /src/target/pidea-backend-*.jar app.jar
USER pidea
EXPOSE 8080
# MaxRAMPercentage keeps the JVM inside the container memory limit
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
