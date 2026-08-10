# AX Module Studio Spring Primary 팀 Dev Setup v0.2

> 대상: Windows + WSL2 + Docker Desktop 기반 `dev`  
> 상태: Bootstrap 계약. Repository Script는 Source Bootstrap 뒤 Backend에서 구현한다.

## 1. 목표

팀원은 Host에 Maven·Python·외부 Tomcat을 설치하지 않고 Docker Compose로 기본 Stack을 실행할 수 있어야 한다. Java 담당자의 IDE Host Debug를 위해 Temurin JDK 21을 지원한다.

## 2. Workspace

```text
AX-Module-Studio-Workspace/           # .git 없음
├── AGENTS.md
├── CLAUDE.md
├── AX-Module-Studio.code-workspace
├── urizo-final-frontend/
├── urizo-final-orchestrator/
└── urizo-final-backend/
```

Backend가 통합 Compose·Bootstrap Script Root다. 상위 Workspace와 sibling Repository를 한 Git으로 합치지 않는다.

## 3. 읽기 전용 Preflight

- Windows Version·Architecture·Virtualization·SLAT
- WSL Version·기본 Version 2·Service 상태
- Docker Desktop·Engine·Compose·Linux Context
- Git·GitHub CLI·Identity·Auth
- Temurin JDK 21·`JAVA_HOME`
- CPU·Memory·Disk·Port
- Proxy·DNS·GitHub·Maven·Docker Registry 접근
- 세 Repository 경로·상위 `.git`·Local 변경

결과는 `PASS / WARN / BLOCKED`로 보고한다.

## 4. 승인 후 Setup 순서

```text
WSL·VirtualMachinePlatform
→ 필요 시 재부팅
→ 최신 WSL·기본 Version 2
→ Docker Desktop WSL2 Linux Backend
→ Temurin JDK 21(IDE Host Debug PC)
→ GitHub Owner/Member 인증
→ 비-Git Workspace·세 Repository Clone
→ Backend의 Version 관리 Script 실행
→ Local Image Build
→ Flyway One-shot
→ Compose Health
```

Docker 로그인은 Public Image만 사용할 때 기본 필수가 아니다. Private Registry·조직 정책·Rate Limit이 요구할 때만 별도 승인 후 로그인한다.

## 5. Host Tool 기준

| Tool | 일반 Container 실행 | Java Host Debug | Python Host Debug |
|---|---:|---:|---:|
| Git | 필수 | 필수 | 필수 |
| WSL2·Docker·Compose | 필수 | Infra에 필수 | Infra에 필수 |
| Temurin JDK 21 | 불필요 | 필수 | 불필요 |
| Maven | 불필요 | Maven Wrapper 사용 | 불필요 |
| Python | 불필요 | 불필요 | Orchestrator Lock이 정한 Version만 설치 |
| Node | 불필요 | Frontend Host Debug 시에만 Lock 기준 설치 | 불필요 |

## 6. 실행 Profile

- `spring-core`: Nginx·React·Spring App(API+Batch)·Core DB·Valkey
- `coding-agent`: LangGraph Runtime·Checkpoint DB 추가
- `full`: `spring-core` + `coding-agent`
- `debug-java-host`: Infra Container + Host Spring
- `spring-worker-split`: 측정 Gate 발생 시만 사용

`full`은 Coding Agent 통합 Test와 최종 시연의 필수 Profile이다.

## 7. 향후 Script 계약

Backend Repository가 다음 Script를 소유한다.

```text
scripts/bootstrap-workspace.ps1
scripts/bootstrap-dev.ps1
scripts/dev-sync.ps1
scripts/health.ps1
scripts/pr-preflight.ps1
```

현재 Script가 없으므로 문서의 예시 명령을 수동으로 대체 실행하지 않는다. Script 구현 PR이 Merge된 뒤 Version 관리된 Script만 사용한다.

## 8. 금지

- 상위 Workspace `.git`
- 자동 Stash·Reset·기존 Local 변경 삭제
- 기존 Docker Volume·DB 자동 초기화
- Runtime 계정 DDL
- Hibernate·Spring AI·Spring Batch 자동 Schema 생성
- DBeaver DML·DDL
- Secret 원문 출력
- 사용자가 명시하지 않은 Prod 실행
