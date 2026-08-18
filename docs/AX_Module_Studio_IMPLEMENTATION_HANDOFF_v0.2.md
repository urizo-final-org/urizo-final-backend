# AX Module Studio Spring Primary 구현 인수인계 v0.2

> **HISTORICAL / NON-NORMATIVE:** 이 문서는 2026-08-10 구현 전 상태의 증거를 보존한다. 현재 구현 상태, Repository URL, Wave/Slice, 역할, 승인 경계, Git 정책은 workspace sibling `../../urizo-final-master/AGENTS.md`, 최신 Master handoff와 상태 snapshot만 따른다. 아래의 Scaffold Hold와 시작 Gate는 현재 작업 규칙이 아니다.
>
> 작성일: 2026-08-10  
> 상태: **Environment Bootstrap 진행 / Product Source Scaffold Hold**  
> 기준: Spring AI Primary + Python LangGraph Coding 후보안

## 1. 문서 우선순위

1. `architecture-variants/spring-ai-primary-langgraph-coding-v0.2/`의 후보 문서가 명시적으로 바꾼 항목
2. 바꾸지 않은 제품 요구는 기존 Project Spec v1.0
3. 이 문서와 `TEAM_DEV_SETUP_v0.2.md`, `DATABASE_MIGRATION_POLICY_v0.2.md`, `GIT-WORKFLOW_v0.2.md`
4. 충돌하며 신규 문서에 결정이 없으면 구현하지 않고 `OPEN_DECISION`으로 기록

기존 FastAPI 문서와 Snapshot은 보존한다. 실행 가능한 Source·Image·Compose·Migration 백업으로 간주하지 않는다.

## 2. 확정 Repository 경계

```text
AX-Module-Studio-Workspace/           # .git 금지
├── AGENTS.md
├── CLAUDE.md
├── AX-Module-Studio.code-workspace
├── urizo-final-frontend/             # React
├── urizo-final-orchestrator/         # Python LangGraph Coding Runtime
└── urizo-final-backend/              # Spring Platform·Flyway·통합 실행 Root
```

- Frontend: `https://github.com/tmdwns0531/urizo-final-frontend.git`
- Orchestrator: `https://github.com/tmdwns0531/urizo-final-orchestrator.git`
- Backend: `https://github.com/tmdwns0531/urizo-final-backend.git`

각 Repository는 별도 Branch·Commit·Push·PR을 사용한다. 하나의 Slice가 여러 Repository를 바꾸면 같은 `work-slug`를 사용하고 PR을 상호 링크한다.

## 3. 확정 Runtime

```text
Nginx
React
Valkey 우선 Queue Store
Spring Backend (API + Spring Batch)
Python LangGraph Coding Agent
PostgreSQL Core DB + pgvector
PostgreSQL LangGraph Checkpoint DB
Flyway Migration One-shot
```

- P0 기본은 Spring API와 Batch가 하나의 Spring Application·Container에서 실행된다.
- Flyway만 DDL Role을 가진 One-shot Process로 분리한다.
- Coding Agent 구현·사용·통합 Test·최종 시연에는 LangGraph Runtime이 필수다.
- P0 기반 Health는 Coding Agent가 꺼져 있어도 성공해야 한다.
- Batch Worker Process 분리는 성능·격리 측정 Gate가 발생할 때만 적용한다.

## 4. 현재 구현 상태

- 기획·Spring 목표 아키텍처·3-Repository 경계 문서화 완료
- Windows Dev 기반환경 Bootstrap 진행
- 세 원격 Repository는 최초 Source Commit 전 상태
- Product Source, Maven Wrapper, Python Lock, Dockerfile, Compose, Flyway SQL은 아직 없음
- Source Scaffold와 Migration 실행은 Stage 0·1 착수 승인 전 금지

## 5. 구현 시작 Gate

다음 순서만 허용한다.

```text
Environment Health
→ 3-Repository Owner Bootstrap
→ Stage 0 Public·Internal Contract
→ Stage 1 eGov/Spring AI Dependency Spike
→ Stage 2 Provider Capability Spike
→ Stage 3 RAG·Batch·Flyway Vertical Slice
→ Stage 4 LangGraph Bridge·Tool Gateway
→ Stage 5 2-PC Container Bootstrap
→ 최종 Spring Go/No-Go
```

Stage 0 이전에는 업무 기능을 병렬 Scaffold하지 않는다. Stage 1은 eGov 5.0.0·Boot 3.5.6·Spring AI 1.0.1 Control Lane과 검증 대상 1.1.x Product Lane을 비교한다.

## 6. 승인 경계

다음은 각각 별도 승인이 필요하다.

- 관리자 권한·Windows Feature·재부팅
- Network Download·Browser Login
- Repository·Branch·Ruleset·Collaborator 변경
- Source Scaffold·Dependency Lock 생성
- Docker Image Build·Compose 실행
- Flyway·Alembic Migration 실행
- Secret·Credential 등록
- DB·Volume 삭제·초기화
- Prod·Cloud·SSH·Merge

Secret 원문은 Prompt·채팅·명령행·Log·Commit·PR에 기록하지 않는다.

## 7. 완료 판정

Environment Bootstrap 완료와 Product 구현 완료를 구분한다.

Environment Bootstrap은 WSL2·Docker Engine·Compose·JDK·Git 인증·세 Repository `main/dev`·Ruleset 검증까지다. Product 구현 완료는 Stage 0~5 Gate와 P0 Acceptance Criteria가 통과한 뒤에만 선언한다.
