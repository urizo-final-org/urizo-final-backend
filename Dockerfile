# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=secret,id=maven_build_truststore,required=false \
    if [ -f /run/secrets/maven_build_truststore ]; then \
      export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/maven_build_truststore"; \
    fi \
    && mvn -B -ntp -DskipTests dependency:go-offline

COPY src/ src/
COPY contracts/ contracts/
RUN --mount=type=secret,id=maven_build_truststore,required=false \
    if [ -f /run/secrets/maven_build_truststore ]; then \
      export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/maven_build_truststore"; \
    fi \
    && mvn -B -ntp -DskipTests package


FROM build AS migration

RUN groupadd --gid 10001 axms \
    && useradd --uid 10001 --gid axms --create-home --shell /usr/sbin/nologin axms \
    && mkdir -p /home/axms/.m2 \
    && cp -a /root/.m2/. /home/axms/.m2/ \
    && chown -R axms:axms /home/axms /workspace

USER 10001:10001
WORKDIR /workspace


FROM eclipse-temurin:21.0.11_10-jre-jammy AS runtime

RUN groupadd --gid 10001 axms \
    && useradd --uid 10001 --gid axms --create-home --shell /usr/sbin/nologin axms \
    && mkdir -p /opt/axms \
    && chown -R axms:axms /opt/axms

COPY --from=build --chown=10001:10001 \
    /workspace/target/ax-module-studio-backend-0.1.0-SNAPSHOT.jar \
    /opt/axms/app.jar

USER 10001:10001
WORKDIR /opt/axms

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/axms/app.jar"]
