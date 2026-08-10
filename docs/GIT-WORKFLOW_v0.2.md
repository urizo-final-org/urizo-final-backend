# AX Module Studio 3-Repository Git Workflow v0.2

> 대상: Frontend·Backend·Orchestrator  
> 원칙: 최신 `dev` → Feature Branch → 동일 Remote Feature Push → `dev` PR

## 1. Repository

- `urizo-final-frontend`: React·Public OpenAPI Consumer
- `urizo-final-backend`: Spring·Flyway·Public/Internal Contract·통합 실행 Root
- `urizo-final-orchestrator`: Python LangGraph Coding Runtime
- 공통 상위 Workspace: 비-Git

## 2. Branch

```text
feature/<github-id>_<work-slug>_<version>
```

```text
origin/dev 최신화
→ Local Feature Branch
→ 검증·Commit
→ 동일 Remote Feature Branch Push
→ dev 대상 PR
→ Review·Required Check
→ Merge
```

일반 개발에서 `dev/main` 직접 Push, 자동 Merge, Force Push, 무승인 Rebase·History Rewrite를 금지한다.

## 3. Owner Bootstrap 예외

세 원격 Repository에 실제 Branch가 없을 때만 Owner/Admin이 다음을 수행할 수 있다.

1. Governance·README·Agent Rule을 포함한 최초 `main` Commit
2. `main`에서 `dev` 생성
3. Ruleset의 실제 Target·PR 차단·Admin Bypass 동작 검증
4. Collaborator 권한 확인

이후 Owner도 일반 개발에는 Feature PR을 사용한다. Bootstrap·긴급복구 Bypass는 사유와 SHA를 기록한다.

## 4. Slice와 PR 수

- 한 Repository 변경: PR 1개
- 두 Repository 변경: PR 2개
- 세 Repository 변경: PR 3개
- 상위 Workspace: Commit 없음

연결 PR에는 Slice ID, Contract Version, Migration 영향, Merge 순서, 독립 Merge 가능 여부를 기록한다.

## 5. Contract 변경

Backend가 Public OpenAPI와 Java–Python 내부 Contract의 원본을 소유한다.

```text
Expand → Consumer Migrate → Contract
```

Breaking Change를 동시 Merge 시각에 의존하지 않는다. Backend·Frontend·Orchestrator의 Golden Payload·Compatibility·Mock Server Test를 통과한다.

## 6. Repository별 Check

### Frontend

- Lint·Type Check·Unit Test·Production Build
- 생성 Client Diff·Public OpenAPI Version

### Backend

- Java Format·Static Analysis·Unit·Integration
- Dependency Convergence·Spring Context·Actuator
- Flyway 빈 DB·직전 Revision·단일 History
- Public/Internal Contract Compatibility
- Spring App·Migration Profile

### Orchestrator

- Format·Lint·Type·Pytest·Lock 일치
- Checkpoint·Interrupt·Resume
- Spring Model/Tool Gateway Contract
- Provider Key·Git Token·Docker Socket 미보유

## 7. 금지 파일

`.env*`, Secret, Token, DB URL, SSH Key, 개인 Log, Build Output, IDE Local 설정을 Commit하지 않는다. `git add .` 대신 의도한 파일만 Stage한다.
