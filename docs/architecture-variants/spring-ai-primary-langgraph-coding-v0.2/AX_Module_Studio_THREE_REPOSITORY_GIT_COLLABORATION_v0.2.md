# AX Module Studio 3-Repository Git·AI 협업 규칙 v0.2

> **HISTORICAL / NON-NORMATIVE:** Repository 분리 결정의 설계 근거만 보존한다. 아래의 개인 Repository URL, 빈 Repository 상태, Branch/PR/승인 절차와 AI 작업 규칙은 현재 정책이 아니며 workspace sibling `urizo-final-master` 문서만 따른다.
>
> 작성일: 2026-08-10  
> 상태: Spring 후보안 전용 Git Addendum  
> 관계: 기존 `GIT-WORKFLOW.md`의 Branch·PR·승인 원칙을 계승하고 Repository Routing만 확장

---

## 0. 결론

Spring 후보안은 비-Git 상위 Workspace 아래에 세 Git Repository를 sibling으로 둔다.

```text
AX-Module-Studio-Workspace/           # .git 생성 금지
├── urizo-final-frontend/             # React
├── urizo-final-backend/              # Spring Platform·통합 실행 Root
└── urizo-final-orchestrator/         # Python LangGraph Coding Orchestrator
```

2026-08-10 GitHub 확인 시 세 Repository는 모두 Public, 기본 Branch `main`, Source가 없는 빈 Repository다.

- Frontend: `https://github.com/tmdwns0531/urizo-final-frontend.git`
- Backend: `https://github.com/tmdwns0531/urizo-final-backend.git`
- Orchestrator: `https://github.com/tmdwns0531/urizo-final-orchestrator.git`

Repository가 세 개여도 하나의 기능 Slice를 함께 읽고 수정할 수 있다. 그러나 Commit·Push·PR·Merge는 각 `.git` 경계에서 독립적으로 수행한다.

---

## 1. Repository 책임

| Repository | 소유 Source·계약 | 소유하지 않는 것 |
|---|---|---|
| `urizo-final-frontend` | React UI, 생성 TypeScript Client, Browser Test, Nginx Front Route | DB Migration, Provider Secret, Coding Tool 실행 |
| `urizo-final-backend` | eGovFrame·Spring Boot API, Spring AI, Spring Batch, 업무 Domain, Flyway, Public OpenAPI, Java–Python 내부 Contract, Tool Gateway, 통합 Compose·Script | LangGraph Node 구현, Python Dependency Lock |
| `urizo-final-orchestrator` | Python LangGraph Coding Graph, Checkpoint·Interrupt·Resume, Spring 내부 API Client, Python Test·Lock·Image | Public API, Core DB DDL, Provider Key, Git·Docker 최종 실행 권한 |

Backend Repository가 통합 실행 Root다. Compose와 Bootstrap Script는 sibling Frontend·Orchestrator Build Context를 참조할 수 있지만 상위 Workspace 자체를 Git Repository로 만들지 않는다.

### 계약 원본

계약 원본은 Backend Repository가 소유한다.

```text
urizo-final-backend/contracts/
├── public/openapi.yaml
└── coding-agent/
    ├── job-event.schema.json
    ├── model-turn.openapi.yaml
    ├── tool-request.schema.json
    └── error-code.schema.json
```

- Frontend는 Public OpenAPI로 TypeScript Client를 생성한다.
- Orchestrator는 Coding Agent Contract로 Python Model·Client를 생성하거나 Contract Test로 동기화한다.
- 생성 결과만 고치지 않고 계약 원본을 먼저 변경한다.
- 모든 Event·Request에는 `schemaVersion`을 둔다.

---

## 2. AI 작업 시작 전 Repository Scope 선언

각 팀원의 Codex·Claude 등 AI는 Source 수정 전에 다음 표를 먼저 보고한다.

| 항목 | 필수 내용 |
|---|---|
| Slice ID | Issue 또는 합의한 작업 식별자 |
| 읽을 Repository | Context를 위해 조사할 범위 |
| 수정할 Repository | 실제 Write가 필요한 1~3개 Repository |
| 예상 파일 | Repository별 예상 변경 파일·폴더 |
| 공용 경계 | OpenAPI·JSON Schema·Flyway·Queue·Compose 변경 여부 |
| 예상 PR | Repository별 PR 수와 Merge 순서 |
| 검증 | Repository별 Test·Lint·Build·Contract Test |

AI는 세 Repository를 모두 읽을 수 있지만 승인된 Slice 밖의 Repository를 “함께 정리”한다는 이유로 수정하지 않는다. 작업 중 범위가 늘어나면 수정 전에 범위 확대와 추가 PR을 보고한다.

작업 종료 시 다음을 다시 보고한다.

```text
Repository별 실제 변경 파일
Repository별 git status·diff 요약
예상과 달라진 범위
Contract·Migration·Compose 영향
실행한 검증과 실패 항목
필요한 PR 목록과 Merge 순서
```

---

## 3. Branch·Commit·PR 규칙

기존 `GIT-WORKFLOW.md`의 `feature/<github-id>_<work-slug>_<version>` 규칙을 세 Repository에 동일하게 적용한다.

기본 흐름은 모든 Repository에서 동일하다.

```text
최신 origin/dev 동기화
→ 로컬 feature/<github-id>_<work-slug>_<version> Branch 생성
→ 로컬 Commit
→ 원격의 동일 Feature Branch로 Push
→ Feature Branch에서 dev를 대상으로 PR 생성
→ Review·Required Check 통과
→ dev에 Merge
```

로컬 Branch의 내용을 원격 `dev`에 직접 Push하는 방식은 기본 흐름이 아니다. `dev`·`main` 직접 Push와 자동 Merge는 최초 Bootstrap 또는 별도 승인된 긴급복구 외에는 금지한다.

하나의 Vertical Slice가 세 Repository를 바꾸는 예:

```text
Frontend
feature/member01_coding-job-status_v0.2

Backend
feature/member01_coding-job-status_v0.2

Orchestrator
feature/member01_coding-job-status_v0.2
```

### PR 수

- 한 Repository만 변경: PR 1개
- 두 Repository 변경: PR 2개
- 세 Repository 변경: PR 3개
- 상위 Workspace: Commit·PR 없음

각 PR 설명에는 연결 PR과 Merge 순서를 기록한다.

```text
Slice ID:
변경 Repository: frontend / backend / orchestrator
연결 PR:
Contract Version:
Migration 영향:
권장 Merge 순서:
독립 Merge 가능 여부:
Rollback 또는 Runtime 전환 영향:
```

자동 Merge, Force Push, 무승인 History Rewrite, 다른 Repository 변경을 하나의 Commit으로 가장하는 행위를 금지한다.

---

## 4. Java–Python Contract 변경 절차

Backend와 Orchestrator가 동시에 바뀌는 계약은 가능한 한 `expand → migrate → contract`로 변경한다.

```text
1. Expand
   Backend가 기존 Consumer와 호환되는 새 Field·Endpoint·Version 추가

2. Migrate
   Orchestrator가 새 Version을 소비하고 Contract Test 통과

3. Contract
   모든 Consumer 전환 확인 후 별도 PR에서 구 Version 제거
```

Breaking Change를 한 번의 동시 Merge 시각에 의존하지 않는다. 불가피하면 다음이 모두 필요하다.

- 동일 Slice의 Backend·Orchestrator PR 상호 링크
- 정확한 Merge·Deploy 순서
- Compatibility Matrix
- 구·신 Version Contract Test
- 실패 시 이전 Image·Contract로 복구하는 절차

Backend PR의 Required Check:

- OpenAPI·JSON Schema Validation
- Java DTO Serialization Golden Test
- 이전 Contract Compatibility Test

Orchestrator PR의 Required Check:

- Python Model Validation
- Golden Payload Read·Write Test
- Spring Mock Server Contract Test
- Unknown Version·Field 정책 Test

통합 Check:

- 실제 Spring Backend와 Orchestrator Container 간 Smoke Test
- 동일 `traceId`·`jobId` 전파
- Retry·Duplicate Event 멱등성

---

## 5. Repository별 검증

### Frontend

- Lint·Type Check·Unit Test
- Production Build
- 생성 Client Diff
- Backend Public OpenAPI Version 확인

### Backend

- Java Format·Static Analysis·Unit·Integration Test
- Maven Dependency Convergence
- Spring Context·Actuator Health
- Flyway 빈 DB·직전 Revision Upgrade·단일 History 검증
- Public·Internal Contract 생성과 Compatibility Test
- 기본 Spring App(API + Batch)·Migration 검증과 선택형 Split Worker Profile 검증

### Orchestrator

- Python Format·Lint·Type Check·Pytest
- Dependency Lock 일치
- LangGraph Checkpoint·Interrupt·Resume Test
- Spring Model·Tool Gateway Contract Test
- Provider Key·Git Token·Docker Socket 미보유 검사
- Python Image Build

변경된 모든 Repository의 Required Check가 통과하기 전에는 Slice 완료로 보고하지 않는다.

---

## 6. 상위 Workspace가 매 세션에 규칙을 주입하는 방법

Spring 구현 Bootstrap 시 Backend의 Version 관리 Template에서 다음 상위 파일을 생성한다.

```text
AX-Module-Studio-Workspace/
├── AGENTS.md
├── CLAUDE.md
└── AX-Module-Studio.code-workspace
```

상위 `AGENTS.md`와 `CLAUDE.md`에는 최소한 다음을 넣는다.

1. 세 Repository URL과 Path Routing
2. 상위 `.git` 생성 금지
3. 작업 전 Repository Scope 표 보고
4. 공용 Git 규칙과 이 Addendum 전체 읽기
5. Repository별 Branch·Status·Origin·Open PR Preflight
6. 변경 Repository마다 별도 Commit·Push·PR
7. 동일 Slice PR 상호 링크·Merge 순서
8. Contract·Flyway·Compose Owner 승인
9. 무승인 Reset·Stash·삭제·Volume 초기화 금지
10. Spring 후보와 FastAPI Baseline Runtime 전환 절차

각 Repository에도 짧은 `AGENTS.md`를 두어 자신의 책임과 금지 경계를 반복할 수 있다. 상위 규칙과 충돌하면 더 좁고 안전한 Repository 규칙을 따른다.

---

## 7. 자연어 Push·PR 요청 시 AI 행동

사용자가 `커밋하고 PR 만들어줘`, `세 Repository 작업 PR 올려줘`라고 요청하면 AI는 먼저 실제 변경 Repository를 판정한다.

```text
세 Repository git status·diff·branch·origin 확인
→ 각 origin/dev와 열린 PR 충돌 확인
→ 변경 Repository와 PR 수 보고
→ Repository별 검증
→ Secret·금지 파일 검사
→ Repository별 의도한 파일만 Commit
→ Repository별 Feature Push
→ 각 dev 대상 PR 생성
→ 연결 PR 상호 링크와 Merge 순서 기록
→ PR URL·Commit SHA·검증 결과 보고
```

변경이 없는 Repository에는 빈 Commit이나 형식적인 PR을 만들지 않는다. Push·PR 요청은 Merge·Repository 삭제·Branch Protection 변경·Runtime 전환 승인까지 포함하지 않는다.

---

## 8. 금지

- 상위 Workspace에 `.git` 생성
- 한 Repository에서 sibling Repository 파일을 Stage
- 세 Repository 변경을 한 PR이라고 보고
- Contract 원본 없이 Java·Python DTO를 각자 수정
- Orchestrator가 Core DB Migration 소유
- Orchestrator가 Provider·Git·Docker 최종 Credential 보유
- `dev`·`main` 직접 Push, Force Push, 자동 Merge
- Runtime 전환을 이유로 Repository·Git History 삭제
- 승인 없는 Source 대량 삭제, DB·Volume 초기화

FastAPI Baseline으로 전환할 때도 Repository를 삭제하지 않는다. 자세한 절차는 `AX_Module_Studio_DUAL_RUNTIME_CONTAINER_AND_SPIKE_GATE_v0.2.md`의 Runtime 전환 Runbook을 따른다.

---

## 9. 정식 문서 반영 시점

현재 최상위 `GIT-WORKFLOW.md`와 `# AX Module Studio 팀 작업 단위·Git 운영 가이드.md`는 FastAPI 기준선의 2-Repository 규칙이므로 원본 그대로 보존한다. Spring 후보의 구현 전환이 승인되면 이 Addendum을 기준으로 두 문서의 v0.2를 발행하고 다음 Bootstrap 문서도 함께 3-Repository로 갱신한다.

- 상위 `AGENTS.md`·`CLAUDE.md` Template
- `TEAM_DEV_SETUP.md`
- Implementation Handoff·Start Gate
- Dev·Prod Bootstrap Spec
- Coding Agent Harness의 Repository Job 규칙

문서 갱신만으로 원격 Repository·Branch·Ruleset이 변경되지는 않는다. 최초 Commit, `dev` 생성, Branch Protection, 팀원 권한은 별도 승인과 Git Preflight 뒤 수행한다.
