# AX Module Studio Backend

Spring Backend repository and intended local development execution root for AX Module Studio.

## Current state

The repository owns the complete local-development integration root. Stage 0
machine-readable public and coding-agent contracts live under `contracts/` and
are checked by the offline contract gate.

The executable Spring/eGovFrame baseline includes:

- eGovFrame Boot parent 5.0.0 / Spring Boot 3.5.6 with embedded Tomcat
- Spring AI Product 1.1.8 and Control 1.0.1 build lanes
- Spring MVC API, Spring Batch, Flyway, PostgreSQL/pgvector and Valkey
- local Provider CMS for OpenAI, Anthropic and Google, with secret plaintext
  never returned by the API
- contract-aligned health/readiness, trace correlation, canonical errors and
  idempotent mutations

Stage 3 local P0 is implemented end to end:

- Project, Connector/version, Knowledge/version/rollback, Chatbot RAG and
  authoritative Agent Job public operations
- a deterministic `*.fixture.invalid` Connector adapter; the local acceptance
  path performs no real public-data call and needs no public API key
- transactional Connector sync and six-phase Knowledge build through Spring
  Batch, with Core DB job state remaining authoritative
- pgvector chunks/HNSW search, explicit active-version pointers, grounded
  answers with citations and fail-closed ungrounded refusal
- job list/get/cancel/retry plus single-worker interrupted-RUNNING recovery

Stage 4 local Coding Runtime integration is also implemented:

- Core DB transactional outbox to Valkey and Spring-owned claim, bounded lease,
  heartbeat, outcome, retry, expiry and idempotent replay
- a rotating local Spring-Orchestrator service credential without credential
  output
- Spring Model Turn and a strict Tool Gateway that permits only the approved
  classpath `README.md` fixture through `read_file`
- the sibling Python runtime's reliable Valkey processing list, one persistent
  LangGraph, encrypted PostgreSQL checkpoint, and interrupt/resume lifecycle
- Spring/Core DB retain Provider, job and tool authority; Python has no Provider
  key/selection, Core DB access, or direct tool-execution authority

Ten forward-only migrations are current at `20260811220000`. The Maven Wrapper
is pinned to Maven 3.9.9 with its distribution checksum. Docker builds are
locked and the Backend-owned Compose profile runs the seven required services,
the auxiliary loopback database gateway and Flyway as an Exited-0 one-shot.

See `docs/STAGE1_BACKEND_SCAFFOLD.md` for the original scaffold,
`docs/STAGE2_PROVIDER_CAPABILITY_SPIKE.md` for Provider capability work, and
`docs/STAGE4_MODEL_TURN_BRIDGE.md` for the Model Turn foundation. The current
integrated acceptance boundary is defined by the commands below.

## Full local profile

Run from this repository with PowerShell. The scripts create only approved
local files/credentials and never print their values.

```powershell
.\scripts\bootstrap-dev.ps1 -Profile full
.\scripts\health.ps1 -Profile full
.\scripts\verify-full-local-e2e.ps1 -Profile full
.\scripts\verify-full-local-restart.ps1 -ConfirmRestart
.\scripts\verify-full-local-failure-gates.ps1 -ConfirmFailureInjection
```

Normal internet environments use the Maven and Temurin default certificate
authorities and do not require any local build trust file. Only on a network
with explicit TLS interception, opt in to the ignored Windows CA bundle:

```powershell
.\scripts\bootstrap-dev.ps1 -Profile full -EnableHostBuildTrust
```

The opt-in flow passes the same local PEM bundle to the Maven and Node build
steps without displaying certificate contents or committing the bundle.

The canonical UI/API ingress is `http://127.0.0.1:18080/`. The read-only DBeaver
gateway is `127.0.0.1:15432`. Frontend, Spring, Core PostgreSQL, Valkey, Coding
Runtime and Checkpoint PostgreSQL do not publish host ports directly.

## Fast CMS local profile

Routine CMS startup uses one health-first command. It reuses an already healthy
`spring-core` profile and does not wait for the separate Coding Runtime.

```powershell
.\scripts\start-cms-local.ps1 -ApproveLocalMutation
```

If required images are missing, add `-ApproveNetwork`. After Source changes that
must be reflected in Docker images, add `-Rebuild -ApproveNetwork`. The script
uses the versioned bootstrap, local build-trust path, Flyway gate and CMS health
check; do not replace it with ad-hoc Docker commands.

The verified local acceptance covers Project replay, deterministic Connector
preview/sync, Batch Knowledge build/approval/activation, RAG citation/refusal,
Coding Job claim/Model Turn/Tool/checkpoint interrupt-resume, restart
idempotency and bounded Valkey/Checkpoint/Spring failure recovery.

## Repository-local documents

- Use this README and executable scripts for the current local-development runtime.
- Use `docs/DATABASE_MIGRATION_POLICY_v0.2.md` for Backend-specific schema and Flyway invariants.
- Use `docs/AX_Module_Studio_LLM_Function_Tool_Job_Harness_Design_v0.2.md` and current contracts for Backend-specific coding-agent authority and contract details.
- Architecture candidate, implementation handoff, team setup, and Git workflow v0.2 documents preserve historical design/bootstrap evidence where marked; they are not current team-policy authorities.

## Team policy authority

Cross-repository workflow, current Wave/Slice state, assignments, and Git/PR policy are owned by the
sibling Master repository. Start from the canonical parent workspace and follow
`../urizo-final-master/AGENTS.md`; this README contains only Backend runtime and verification facts.
