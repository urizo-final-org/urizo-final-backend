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

The canonical UI/API ingress is `http://127.0.0.1:18080/`. The read-only DBeaver
gateway is `127.0.0.1:15432`. Frontend, Spring, Core PostgreSQL, Valkey, Coding
Runtime and Checkpoint PostgreSQL do not publish host ports directly.

The verified local acceptance covers Project replay, deterministic Connector
preview/sync, Batch Knowledge build/approval/activation, RAG citation/refusal,
Coding Job claim/Model Turn/Tool/checkpoint interrupt-resume, restart
idempotency and bounded Valkey/Checkpoint/Spring failure recovery.

## Canonical documents

- Start with `docs/architecture-variants/spring-ai-primary-langgraph-coding-v0.2/README.md` and follow its reading order.
- Use `docs/AX_Module_Studio_IMPLEMENTATION_HANDOFF_v0.2.md` for implementation handoff state.
- Use `docs/TEAM_DEV_SETUP_v0.2.md` for local development setup.
- Use `docs/DATABASE_MIGRATION_POLICY_v0.2.md` for schema and Flyway work.
- Use `docs/GIT-WORKFLOW_v0.2.md` for branch, push, and pull-request rules.
- Use `docs/AX_Module_Studio_LLM_Function_Tool_Job_Harness_Design_v0.2.md` for the coding-agent tool and job harness.

## Workflow

Normal changes use `latest dev -> feature/<work-slug> -> pull request to dev`. Direct pushes to `dev` and `main` are reserved for explicitly approved owner bootstrap or emergency recovery.
