# BRouter routing server (spec §11.4). Built here from the upstream release zip, not from ghcr.io:
# the ghcr.io "latest" image is too old to read the current brouter.de tiles (lookups.dat version
# mismatch). Upgrading: change BROUTER_VERSION and BROUTER_SHA256 together. docs/deploy.md, section 19.
#   docker compose build brouter
ARG JAVA_IMAGE=eclipse-temurin:17-jre-jammy

# Stage 1: download the release zip and check its SHA-256 (the build fails on a mismatch). Keep only
# the server jar and the routing profiles.
FROM ${JAVA_IMAGE} AS fetch
ARG BROUTER_VERSION=1.7.10
# sha256 of https://github.com/abrensch/brouter/releases/download/v1.7.10/brouter-1.7.10.zip
ARG BROUTER_SHA256=023fec3ba997758e8cd7ab9e1bae52e962af3f00b57683e3de86b84ffad01532
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    curl -fsSL -o /tmp/brouter.zip "https://github.com/abrensch/brouter/releases/download/v${BROUTER_VERSION}/brouter-${BROUTER_VERSION}.zip"; \
    echo "${BROUTER_SHA256}  /tmp/brouter.zip" | sha256sum -c -; \
    unzip -q /tmp/brouter.zip \
        "brouter-${BROUTER_VERSION}/brouter-${BROUTER_VERSION}-all.jar" \
        "brouter-${BROUTER_VERSION}/profiles2/*" -d /tmp/x; \
    mkdir -p /stage; \
    mv "/tmp/x/brouter-${BROUTER_VERSION}/brouter-${BROUTER_VERSION}-all.jar" /stage/brouter.jar; \
    mv "/tmp/x/brouter-${BROUTER_VERSION}/profiles2" /stage/profiles2; \
    rm -rf /tmp/x /tmp/brouter.zip

# Stage 2: the runtime image: the JRE, curl for the health check, the jar, the profiles and the script.
FROM ${JAVA_IMAGE}
RUN groupadd --gid 1000 brouter \
 && useradd --uid 1000 --gid brouter --home-dir /app --no-create-home --shell /usr/sbin/nologin brouter \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /app /segments4 /customprofiles \
 && chown brouter:brouter /app /customprofiles
WORKDIR /app
COPY --from=fetch /stage/brouter.jar /app/brouter.jar
COPY --from=fetch /stage/profiles2/ /profiles2/
COPY server.sh /app/server.sh
RUN chmod 0755 /app/server.sh
# Segments (the .rd5 tiles) are a read-only volume mounted at /segments4; custom profiles go in /customprofiles.
ENV JAVA_OPTS="-Xmx256M -Xms128M -DmaxRunningTime=300 -DuseRFCMimeType=false" \
    MAXTHREADS=2 \
    PORT=17777
USER brouter
EXPOSE 17777
# Any HTTP answer counts as alive, a 4xx included: BRouter answers the route-less URL itself.
# Only a refused connection or a timeout fails the check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sS -o /dev/null "http://127.0.0.1:${PORT}/brouter" || exit 1
CMD ["/app/server.sh"]
