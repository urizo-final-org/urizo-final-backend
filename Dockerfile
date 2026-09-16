# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY --chmod=0555 scripts/import-maven-build-extra-ca.sh /usr/local/bin/import-maven-build-extra-ca
RUN --mount=type=secret,id=maven_build_extra_ca,required=false \
    set -eu; \
    truststore=''; \
    cleanup() { if [ -n "$truststore" ]; then rm -f "$truststore"; fi; }; \
    trap cleanup EXIT; \
    if [ -f /run/secrets/maven_build_extra_ca ]; then \
      truststore="$(mktemp)"; \
      import-maven-build-extra-ca /run/secrets/maven_build_extra_ca "$truststore"; \
      export MAVEN_OPTS="-Djavax.net.ssl.trustStore=$truststore -Djavax.net.ssl.trustStorePassword=changeit"; \
    fi; \
    mvn -B -ntp -DskipTests dependency:go-offline

COPY src/ src/
COPY contracts/ contracts/
RUN --mount=type=secret,id=maven_build_extra_ca,required=false \
    set -eu; \
    truststore=''; \
    cleanup() { if [ -n "$truststore" ]; then rm -f "$truststore"; fi; }; \
    trap cleanup EXIT; \
    if [ -f /run/secrets/maven_build_extra_ca ]; then \
      truststore="$(mktemp)"; \
      import-maven-build-extra-ca /run/secrets/maven_build_extra_ca "$truststore"; \
      export MAVEN_OPTS="-Djavax.net.ssl.trustStore=$truststore -Djavax.net.ssl.trustStorePassword=changeit"; \
    fi; \
    mvn -B -ntp -DskipTests package


FROM build AS migration

RUN groupadd --gid 10001 axms \
    && useradd --uid 10001 --gid axms --create-home --shell /usr/sbin/nologin axms \
    && mkdir -p /home/axms/.m2 \
    && cp -a /root/.m2/. /home/axms/.m2/ \
    && chown -R axms:axms /home/axms /workspace

USER 10001:10001
WORKDIR /workspace


# RTK is pinned independently of the application. No remote installer runs at startup.
FROM eclipse-temurin:21.0.11_10-jre-jammy AS rtk-amd64
ADD --checksum=sha256:7278231dfd7e6a730a4ab7f847b195bcf02289c2d57622b0dab75a6411100c8f \
    https://github.com/rtk-ai/rtk/releases/download/v0.49.0/rtk-x86_64-unknown-linux-musl.tar.gz /tmp/rtk.tar.gz
RUN mkdir /rtk && tar -xzf /tmp/rtk.tar.gz -C /rtk && /rtk/rtk --version

FROM eclipse-temurin:21.0.11_10-jre-jammy AS rtk-arm64
# Upstream ARM64 0.49.0 requires GLIBC_2.39, unavailable in Jammy.
# Keep the optional formatter absent so the adapter returns raw results on ARM64.
RUN mkdir /rtk

FROM rtk-${TARGETARCH} AS rtk-binary

FROM eclipse-temurin:21.0.11_10-jre-jammy AS runtime

COPY --from=build --chmod=0555 \
    /usr/local/bin/import-maven-build-extra-ca \
    /usr/local/bin/import-maven-build-extra-ca

RUN --mount=type=secret,id=maven_build_extra_ca,required=false \
    set -eu; \
    truststore=''; \
    cleanup() { if [ -n "$truststore" ]; then rm -f "$truststore"; fi; }; \
    trap cleanup EXIT; \
    if [ -f /run/secrets/maven_build_extra_ca ]; then \
      truststore="$(mktemp)"; \
      import-maven-build-extra-ca \
        /run/secrets/maven_build_extra_ca \
        "$truststore" \
        /usr/local/share/ca-certificates; \
      update-ca-certificates >/dev/null; \
      install -m 0644 "$truststore" "$JAVA_HOME/lib/security/cacerts"; \
    fi; \
    rm -f /usr/local/bin/import-maven-build-extra-ca

RUN groupadd --gid 10001 axms \
    && useradd --uid 10001 --gid axms --create-home --shell /usr/sbin/nologin axms \
    && mkdir -p /opt/axms \
    && chown -R axms:axms /opt/axms

COPY --from=build --chown=10001:10001 \
    /workspace/target/ax-module-studio-backend-0.1.0-SNAPSHOT.jar \
    /opt/axms/app.jar

COPY --from=rtk-binary --chmod=0555 /rtk/ /opt/axms/tools/

USER 10001:10001
WORKDIR /opt/axms

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/axms/app.jar"]
