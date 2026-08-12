# Stage 1 Backend Scaffold

## Purpose

This stage creates the first executable Backend source without starting the database, running Flyway, configuring a model provider, or implementing broad product features.

## Locked baseline

- Development JDK: 21
- Java release target: 17
- eGovFrame Boot parent: 5.0.0
- Spring Boot: 3.5.6, managed by the eGovFrame parent
- Embedded Tomcat / Spring MVC executable JAR
- Spring Batch present, with job launch and metadata initialization disabled
- Flyway and PostgreSQL support present, with Flyway disabled
- Spring AI Product Lane: 1.1.8 (default Maven profile `spring-ai-product`)
- Spring AI Control Lane: 1.0.1 (Maven profile `spring-ai-control`)

Only one Spring AI lane may be active for a build. The Stage 1 spike compares both lanes; it does not configure a provider or accept a secret.

## Implemented endpoints

- `GET /api/health`: returns contract version `1.0`, a trace ID, `UP`, and the check time.
- `GET /api/readiness`: returns `503 NOT_READY` until the required database, queue, and migration gates are implemented and enabled in a later approved stage.
- A valid incoming `X-Trace-Id` is preserved. An invalid supplied value is rejected with the public `INVALID_TRACE_ID` error envelope.

Spring Actuator remains an implementation adapter and is not the public API contract.

## Build commands

The Maven Wrapper is pinned to Maven 3.9.9 and verifies the Maven distribution SHA-256 before use.

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -Pspring-ai-control clean verify
```

Both lanes passed the Stage 1 build and five local tests on Temurin JDK 21.0.11. The resulting bytecode release remains Java 17 as required by the architecture decision.

## Explicitly deferred

- Provider SDK starters and model calls
- Authentication and business endpoints
- DataSource configuration and credentials
- Flyway migration SQL or migration execution
- Spring Batch jobs and metadata tables
- Docker/Compose
- Commit, push, PR, or merge
