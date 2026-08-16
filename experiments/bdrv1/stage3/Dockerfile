FROM maven:3.9.11-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 python3-yaml ripgrep \
    && rm -rf /var/lib/apt/lists/*

ENTRYPOINT []

LABEL org.opencontainers.image.title="BDRv1 Apache Commons CSV evaluation environment" \
      org.opencontainers.image.description="Maven and Java 17 with offline dependencies supplied by a separate mount"
