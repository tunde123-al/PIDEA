# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0 AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
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
