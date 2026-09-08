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

Forward-only Core migrations live under `src/main/resources/db/migration`; the
applied state is determined by Flyway rather than a count recorded here. The
Maven Wrapper is pinned to Maven 3.9.9 with its distribution checksum. Docker
builds are locked, and `compose.dev.yaml` owns the integrated services,
including MCP Server, plus Flyway as an Exited-0 one-shot.

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

The full-profile verification commands cover Project replay, deterministic Connector
preview/sync, Batch Knowledge build/approval/activation, RAG citation/refusal,
Coding Job claim/Model Turn/Tool/checkpoint interrupt-resume, restart
idempotency and bounded Valkey/Checkpoint/Spring failure recovery.

## Windows Coding Runner

`scripts/runner.ps1`은 Windows 호스트에서 Coding 작업과 독립 Guardrail의
`PREPARE_SCAN_WORKTREE` 작업을 처리한다. Git Pull은 스크립트만 가져온다.
Windows에서 공식 `full` 로컬 시작 또는 `full` bootstrap을 실행하면 컨테이너 준비 후
`scripts/start-coding-runner.ps1`이 Runner를 숨김 PowerShell 프로세스로 자동 시작한다.
LangGraph Coding/Natural CMS worker는 기존 `coding-runtime` 컨테이너 안에서 시작하며,
이 Windows Runner는 그와 별도로 Git·Docker 호스트 작업을 처리한다.

### 자동 시작

부모 워크스페이스에서 기존 공식 시작 명령을 사용한다. 별도 Runner 실행 요청은 필요 없다.

```powershell
.\urizo-final-master\scripts\start-local-cms.ps1 -Profile full -ApproveLocalMutation
```

- 컨테이너가 이미 healthy여도 실행기가 없으면 시작한다. 동일 스크립트·대상 주소·작업폴더·
  자격증명 경로로 시작된 기존 프로세스는 재사용한다. 다른 경로나 수동 실행 등으로 바인딩을
  확인할 수 없는 Runner는 종료하지 않고 새 실행을 차단한다. 동시 시작 호출도 직렬화한다.
  Runner 자체도 같은 대상 주소에 대한 잠금을 사용해 수동 실행과 자동 실행의 중복 claim을 막는다.
- canonical/Feature Worktree 위치에서 부모 워크스페이스를 찾고 `WorkRoot`를 그 아래
  `.worktrees`로 명시한다. `SecretsRoot`는 **현재 Coding Runtime 컨테이너에 실제 마운트된**
  토큰의 호스트 폴더를 사용하므로 다른 Worktree의 자격증명을 추측하지 않는다.
- 최초 정상 claim 응답 후 PID만 담은 `.local/runner/*.ready`를 기록하며, 자동 시작은 이를
  확인한다. 숨김 실행 로그는 같은 ignored 폴더의 `*.stdout.log`, `*.stderr.log`에 남는다.
  접속 실패·HTTP 401·조기 종료·중복 충돌은 성공으로 보고하지 않는다. 시작 확인 시간이
  지나도 이미 작업 중일 수 있으므로 프로세스를 임의 종료하지 않는다.
- `spring-core`는 Coding Runtime/MCP를 포함하지 않으므로 자동 시작하지 않는다.
  로컬 실행 승인이 없는 healthy 점검도 프로세스를 시작하지 않는다.
- Windows 로그인/부팅 서비스·예약 작업·상시 재시작 감시를 설치하는 기능은 아니다.
  Windows 재부팅 뒤에는 공식 `full` 시작 명령을 실행한다. 서버만 재시작하면 살아 있는
  Runner는 기존 주기로 재접속하고, Runner까지 종료됐으면 공식 시작 명령으로 다시 시작한다.
- 자동 시작 즉시 기존 실행 가능 작업을 가져갈 수 있다. 대기 작업을 보존해야 하는
  검증 환경에서는 시작 전에 처리 승인을 확인한다. 이 절차가 외부 Push·PR·배포 승인을
  대신하지 않는다.

### 실행 전 준비

- Windows PowerShell 5.1 또는 PowerShell 7을 사용한다. 같은 실행 창에서
  `git`과 `docker`를 찾을 수 있어야 하고 Docker Engine과 Compose v2가 동작해야 한다.
  Runner는 Docker Desktop 설치 경로를 자동 탐색하지 않는다.
  스크립트 실행 정책으로 차단되면 해당 PC의 정책을 확인하고, 승인된 프로세스 한정
  실행 방법을 사용한다. 시스템·사용자 실행 정책을 임의로 영구 변경하지 않는다.
- canonical Frontend와 Backend를 같은 부모 워크스페이스 아래 각각
  `urizo-final-frontend`, `urizo-final-backend` 이름으로 둔다. 스캔은 이미 받은
  로컬 `origin/dev`를 사용하므로 먼저 팀의 Git 최신화 절차를 완료한다.
- Coding 전체 흐름은 기존 `full` 로컬 환경이 준비돼 있어야 한다. Runner의 기본
  API 주소는 `http://127.0.0.1:18080`이고 MCP 작업공간은 기존
  `axms-spring-dev-mcp-workspaces` Volume과 `axms/mcp-server:dev` 이미지를 사용한다.
  BUILD/TEST/PREVIEW는 해당 Docker 이미지·Compose 설정을 사용하며, PR 생성·조회는
  `gh`와 Git/GitHub 인증이 추가로 필요하다. 이 안내만으로 외부 Push·PR·배포를 승인하지 않는다.
- 활성 로컬 환경의 `.local/secrets/coding_model_bridge_service_token`이 존재하고
  접속할 로컬 DB에 등록돼 있어야 한다. 파일은 Git 배포 대상이 아니다. 기존 승인된
  `full` bootstrap은 `initialize-dev-secrets.ps1`로 파일을 준비하고
  `coding_credential_registrar`로 등록한다. 토큰 값을 출력·공유하지 않는다.
  파일이 없거나 HTTP 401이면 기존 로컬 설정 절차로 해결하며 검증된 DB를 초기화하지 않는다.

### 수동 실행과 종료

canonical 다섯 저장소가 들어 있는 **부모 워크스페이스**에서 아래 명령을 실행한다.
`WorkRoot`와 `SecretsRoot`는 명시적으로 절대 경로를 계산한다. 현재 canonical Backend에서
`WorkRoot`를 생략하면 Source 저장소를 한 단계 위에서 찾으므로 생략하지 않는다.

```powershell
$runnerWorkspace = (Get-Location).Path
$runnerBackend = Join-Path $runnerWorkspace 'urizo-final-backend'
& (Join-Path $runnerBackend 'scripts/runner.ps1') `
    -BaseUri 'http://127.0.0.1:18080' `
    -WorkRoot (Join-Path $runnerWorkspace '.worktrees') `
    -SecretsRoot (Join-Path $runnerBackend '.local/secrets')
```

승인된 Backend Feature Worktree의 스크립트를 검증할 때는 호출할 스크립트 경로만 바꾼다.
`WorkRoot`는 같은 부모 워크스페이스의 `.worktrees`를 가리킨다. 위 예제의 `SecretsRoot`는
canonical 환경일 때만 사용하며, Worktree로 시작한 환경이면 그 환경에서 준비·등록된
자격증명 폴더로 바꾼다. Source 이름은 현재 `frontend`와 `backend`만 매핑한다.

Runner는 작업을 순서대로 처리하고 각 회차 뒤 기본 2초를 기다린다. `Ctrl+C`로 중지한다.
자동 실행은 숨김 창이므로 시작 출력의 PID와 프로세스 명령행을 대조한 뒤 해당 프로세스만
종료한다. 자동 재실행 시 기존 Runner를 일괄 종료하는 동작은 없다.
가능하면 현재 작업의 결과가 보고된 뒤 중지한다. 처리 도중 중지는 Job 취소나 실행한
부작용의 되돌리기가 아니며, 보고되지 않은 작업은 Lease 만료 후 회수 대상이 될 수 있다.

한 회차만 실행하려면 위 호출 마지막에 `-RunOnce`를 추가한다. 이는 읽기 전용 점검이나
특정 Job 선택 기능이 아니다. 오래된 실행 가능 작업부터 최대 한 건을 claim·처리하고
종료하며, 일이 없으면 조회 후 종료한다. 전체 Coding Job 완료를 뜻하지 않는다.
대기 작업 보존이 필요하면 Runner를 시작하기 전에 처리 범위를 확인한다.

시작 시 대상 주소·작업폴더를 확인하고, 실행 후 `할 일 없음` 또는 작업의 완료 결과를
확인한다. 연결 대기·HTTP 401·실패 보고가 있다면 성공으로 판단하지 않는다.
`-RunOnce`의 프로세스 종료 코드만으로 작업 성공을 판정하지 않는다.

### 외부 작업 없는 회귀 검증

Backend 저장소에서 다음 검증을 PowerShell 5.1과 7로 각각 실행할 수 있다.
Runner의 필요한 함수만 메모리에 읽고 작업 처리·HTTP 보고를 대체하므로 Secret,
실제 Job, Git, Docker와 API를 사용하지 않는다.

```powershell
.\scripts\verify-runner-powershell-compatibility.ps1
.\scripts\verify-runner-startup.ps1
```

숨김 Windows 프로세스까지 검증하려면 `verify-runner-startup.ps1 -ProcessSmoke`를 사용한다.
가짜 토큰과 빈 응답 전용 임시 loopback 서버로 실제 Runner 시작·동일 PID 재사용을 확인하고
테스트 프로세스만 정리한다. 제품 서버의 Job·자격증명은 사용하지 않는다.

이 검증은 자동 시작·경로·결과 표시의 회귀 검증이다. 다른 팀원 PC의 실제 Runner 실행이나
Coding의 승인 이후 전체 흐름을 검증한 것으로 간주하지 않는다.

## Repository-local documents

- Use this README and executable scripts for the current local-development runtime.
- Use `docs/DATABASE_MIGRATION_POLICY_v0.2.md` for Backend-specific schema and Flyway invariants.
- Use `docs/AX_Module_Studio_LLM_Function_Tool_Job_Harness_Design_v0.2.md` and current contracts for Backend-specific coding-agent authority and contract details.
- Architecture candidate, implementation handoff, team setup, and Git workflow v0.2 documents preserve historical design/bootstrap evidence where marked; they are not current team-policy authorities.

## Team policy authority

Cross-repository workflow, current Wave/Slice state, assignments, and Git/PR policy are owned by the
sibling Master repository. Start from the canonical parent workspace and follow
`../urizo-final-master/AGENTS.md`; this README contains only Backend runtime and verification facts.
