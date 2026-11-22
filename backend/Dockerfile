# Multi-stage build for the Spring Boot backend.
# Stage 1 builds the jar with the Maven Wrapper (the same wrapper the team uses),
# so the committed wrapper version is what produces the shipped artefact.
# Stage 2 runs on a small JRE image.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Dependencies first: this layer is cached and only re-runs when the pom changes.
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run as a non-root user. The container needs no write access to the app files.
RUN addgroup -S fuel && adduser -S fuel -G fuel
COPY --from=build /build/target/fuel-platform-*.jar app.jar
RUN chown -R fuel:fuel /app
USER fuel

EXPOSE 8080

# The readiness probe is separate from liveness on purpose: the backend can be
# alive while the simulator is unreachable, and that degraded state must not
# trigger a restart loop.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
