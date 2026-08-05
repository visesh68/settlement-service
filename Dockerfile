# syntax=docker/dockerfile:1.7

###############################################################################
# Stage 1 - build
#
# Dependencies are resolved in their own layer so that a source-only change does
# not re-download the world. Tests are skipped here by design: the suite needs a
# PostgreSQL it downloads itself, and a container build is the wrong place to
# run it. CI runs `mvn verify` before anything is built or pushed.
###############################################################################
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

# Explode the fat jar so the runtime image can have one layer per concern.
# Dependencies change rarely and application classes change every commit, so
# splitting them keeps rebuilds, pushes and pulls small. This produces
# extracted/dependencies/lib/*.jar and a thin extracted/application/*.jar whose
# manifest Class-Path points at lib/ - no Spring Boot loader is involved, which
# also means a slightly faster start.
RUN java -Djarmode=tools -jar target/settlement-service-*.jar extract --layers --destination extracted \
 && mv extracted/application/settlement-service-*.jar extracted/application/settlement-service.jar


###############################################################################
# Stage 2 - runtime
#
# JRE only, no Maven, no build tools, no source. Runs as a non-root user that
# owns nothing it does not need to.
###############################################################################
FROM eclipse-temurin:21-jre-alpine AS runtime

# curl is needed by HEALTHCHECK; everything else stays out of the image.
RUN apk add --no-cache curl tzdata \
 && addgroup --system --gid 1001 settlement \
 && adduser  --system --uid 1001 --ingroup settlement --disabled-password --no-create-home settlement

WORKDIR /app

# Dependencies first (rarely change), application last (changes every commit).
# Owned by root and readable by all: the runtime user needs to read these, never
# to write them.
COPY --from=build --chown=root:root /build/extracted/dependencies/ ./
COPY --from=build --chown=root:root /build/extracted/application/ ./

USER settlement:settlement

ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

EXPOSE 8080

# Readiness, not liveness: this is the check that knows whether the instance can
# actually serve traffic, because it verifies the datastore too.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/readyz" || exit 1

ENTRYPOINT ["java", "-jar", "settlement-service.jar"]
