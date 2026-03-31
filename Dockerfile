# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

RUN mkdir -p /data/auction-images && chown -R appuser:appuser /data/auction-images
VOLUME /data/auction-images

USER appuser

EXPOSE 8123

ENTRYPOINT ["java", "-jar", "app.jar"]
