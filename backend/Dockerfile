# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
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
