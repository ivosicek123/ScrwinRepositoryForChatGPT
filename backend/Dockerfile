# Bank iD backend (Kotlin/Ktor) – hardened runtime image.
# Build artefakt předem (CI / lokálně): ./gradlew :backend:installDist
# Pak: docker build -f backend/Dockerfile -t elfall-bankid:local .
# Base image pinovaný na digest (distroless, bez shellu a správce balíčků, uživatel nonroot 65532).
FROM gcr.io/distroless/java17-debian12:nonroot@sha256:06484c2a9dcc9070aeafbc0fe752cb9f73bc0cea5c311f6a516e9010061998ad
WORKDIR /app
COPY --chown=65532:65532 backend/build/install/backend/lib/ /app/lib/
USER 65532:65532
ENV BIND=0.0.0.0 PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/lib/*", "cz.faltyn.elfall.backend.bankid.AppKt"]
