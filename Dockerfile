# Multi-stage build for the sample gateway.
#
# The dependency layer is resolved from the POMs alone and cached, so a source-only change
# rebuilds in seconds rather than re-downloading Spring Cloud Gateway every time.

FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
COPY spring-llm-gateway-core/pom.xml spring-llm-gateway-core/
COPY spring-llm-gateway-sample/pom.xml spring-llm-gateway-sample/
# Fails softly: some plugins only resolve during a real build, and that is fine here.
RUN mvn -B -q dependency:go-offline -DskipTests || true

FROM deps AS build
COPY spring-llm-gateway-core/src spring-llm-gateway-core/src
COPY spring-llm-gateway-sample/src spring-llm-gateway-sample/src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Never run as root: a gateway terminates untrusted client traffic.
RUN groupadd --system --gid 1001 app && useradd --system --uid 1001 --gid app app
COPY --from=build /build/spring-llm-gateway-sample/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
