# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
RUN apk add --no-cache curl bash
# Download Clojure installer and verify checksum before executing
RUN curl -sSL -o /tmp/clj-install.sh https://download.clojure.org/install/linux-install-1.12.0.1530.sh \
    && echo "2a113e3a4f1005e05f4d6a6dee24ca317b0115cdd7e6ca6155a76f5ffa5ba35b  /tmp/clj-install.sh" | sha256sum -c - \
    && bash /tmp/clj-install.sh \
    && rm /tmp/clj-install.sh
COPY deps.edn build.clj ./
COPY src/ src/
COPY resources/ resources/
RUN clojure -T:build uber

# Custom JRE — only modules Noumenon needs (Alpine/musl-compatible)
RUN jlink --add-modules java.base,java.logging,java.naming,java.sql,java.xml,java.management,jdk.unsupported \
          --strip-debug --no-man-pages --no-header-files \
          --compress=zip-6 --output /custom-jre

# Runtime stage — Alpine for minimal attack surface
FROM alpine:3.21
LABEL org.opencontainers.image.source="https://github.com/leifericf/noumenon"
LABEL org.opencontainers.image.description="Noumenon — Datomic knowledge graph for codebase understanding"
LABEL org.opencontainers.image.licenses="MIT"

# Runtime dependencies
RUN apk add --no-cache git

# Non-root user
RUN addgroup -S noumenon && adduser -S noumenon -G noumenon
RUN mkdir -p /data && chown noumenon:noumenon /data

WORKDIR /app
COPY --from=build /custom-jre /opt/java
COPY --from=build /build/target/noumenon-*.jar noumenon.jar
RUN chown noumenon:noumenon noumenon.jar

ENV PATH="/opt/java/bin:$PATH"
# Set NOUMENON_TOKEN when binding to non-localhost (e.g. 0.0.0.0):
#   docker run -e NOUMENON_TOKEN=<secret> ... --bind 0.0.0.0
ENV NOUMENON_TOKEN=""
USER noumenon
VOLUME /data
EXPOSE 7891

ENTRYPOINT ["java", "-jar", "noumenon.jar"]
# Default: localhost-only (no token required). For external access, override
# CMD with --bind 0.0.0.0 and supply NOUMENON_TOKEN (see above).
CMD ["daemon", "--port", "7891", "--bind", "127.0.0.1", "--db-dir", "/data"]
