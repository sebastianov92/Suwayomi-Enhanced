# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src

ARG SUWAYOMI_COMMIT_COUNT=0
ENV SUWAYOMI_COMMIT_COUNT=${SUWAYOMI_COMMIT_COUNT}

COPY gradle ./gradle
COPY gradlew ./gradlew
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc ./buildSrc
COPY AndroidCompat ./AndroidCompat
COPY server ./server
COPY scripts ./scripts

ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1g -Dkotlin.daemon.jvmargs=-Xmx5g"

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --max-workers=2 :server:shadowJar

FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends tini ca-certificates gosu && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data

COPY --from=builder /src/server/build/Suwayomi-Server-*.jar /opt/suwayomi/server.jar

COPY <<'EOF' /usr/local/bin/entrypoint.sh
#!/bin/sh
set -e

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

# Make sure data + any extra mount points are writable by the runtime user.
for path in /data /data/downloads /data/local /data/backups; do
    [ -d "$path" ] || mkdir -p "$path" 2>/dev/null || true
    chown "${PUID}:${PGID}" "$path" 2>/dev/null || true
done
chown -R "${PUID}:${PGID}" /data 2>/dev/null || true

exec gosu "${PUID}:${PGID}" java $JAVA_OPTS \
    -Dsuwayomi.tachidesk.config.server.rootDir=/data \
    -jar /opt/suwayomi/server.jar
EOF

RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /data
VOLUME ["/data"]
EXPOSE 4567

ENV JAVA_OPTS="" \
    SUWAYOMI_DISABLE_KCEF=1 \
    PUID=1000 \
    PGID=1000

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
