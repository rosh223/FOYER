# syntax=docker/dockerfile:1.7
# One build stage compiles every module once; each service is a small runtime target:
#   docker build --target queue-service -t foyer/queue-service .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY foyer-core/pom.xml foyer-core/
COPY contracts/pom.xml contracts/
COPY foyer-guard/pom.xml foyer-guard/
COPY demo-shop/pom.xml demo-shop/
COPY services/room-service/pom.xml services/room-service/
COPY services/queue-service/pom.xml services/queue-service/
COPY services/insight-service/pom.xml services/insight-service/
COPY services/edge-gateway/pom.xml services/edge-gateway/
COPY foyer-core/src foyer-core/src
COPY contracts/src contracts/src
COPY foyer-guard/src foyer-guard/src
COPY demo-shop/src demo-shop/src
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS edge-gateway
COPY --from=build /src/services/edge-gateway/target/edge-gateway-1.0.0.jar /app/app.jar
EXPOSE 8080

FROM runtime AS room-service
COPY --from=build /src/services/room-service/target/room-service-1.0.0.jar /app/app.jar
EXPOSE 8301

FROM runtime AS queue-service
COPY --from=build /src/services/queue-service/target/queue-service-1.0.0.jar /app/app.jar
EXPOSE 8302

FROM runtime AS insight-service
COPY --from=build /src/services/insight-service/target/insight-service-1.0.0.jar /app/app.jar
EXPOSE 8303

FROM runtime AS demo-shop
COPY --from=build /src/demo-shop/target/demo-shop-1.0.0.jar /app/app.jar
EXPOSE 8304
