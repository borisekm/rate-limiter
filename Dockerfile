# syntax=docker/dockerfile:1

# --- build ---------------------------------------------------------------
# The API is generated from the OpenAPI spec at build time, so the build runs here
# rather than expecting a jar to exist on the host.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when the pom changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package \
 && cp target/rate-limiter-*.jar /build/app.jar

# --- runtime -------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# The bucket store must belong to the node, not to an image layer: /data is a volume.
# Each node needs its own - never point two containers at one directory. On Kubernetes the store
# is off entirely (see application.yml) and nothing here is written to.
RUN addgroup -g 1000 -S app && adduser -u 1000 -S -G app app \
 && mkdir -p /data/rate-limiter && chown -R app:app /data
USER app

COPY --from=build /build/app.jar app.jar

# Local run: 8051 HTTP. On Kubernetes the app moves to 8080 (traffic) and 8081 (actuator) to match
# the platform's Service; 7800/57800 are the JGroups transport and FD_SOCK2 in either case.
EXPOSE 8051 8080 8081 7800 57800
VOLUME ["/data"]

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    RATELIMITER_PERSISTENCE_LOCATION=/data/rate-limiter

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8051/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
