# AX Module Studio 독립 Fallback·Container·Spike Gate v0.2

> **HISTORICAL / NON-NORMATIVE:** Spring 전환 전 후보 Gate와 fallback runbook의 설계 증거다. 아래의 구현 Hold, Source/Repository 전환, Git, 승인 및 일정 규칙은 현재 지시가 아니며 workspace sibling `urizo-final-master`만 소유한다.
>
> 작성일: 2026-08-10  
> 상태: 실행 계약 후보  
> 전제: 아직 실제 Backend Source·Dockerfile·Compose·Migration은 없음

---

## 1. 먼저 구분해야 할 사실

현재 인수인계 문서에는 Source Code, Dockerfile, Compose, Migration이 아직 생성되지 않았다고 적혀 있다. 따라서:

```text
현재 문서 Snapshot
= FastAPI 설계 복구점
≠ 실행 가능한 FastAPI 서비스
```

FastAPI·Python Worker를 실제로 별도 기동하려면 다음 산출물이 추가로 필요하다.

- FastAPI Source
- Python Worker Source
- Dependency Lock
- Dockerfile/Image
- Compose
- Alembic Revision
- Seed
- Health Check
- 동일 외부 API Contract Smoke Test

이번 문서 작업은 이 요구를 숨기지 않고 향후 구현 계약으로 고정한다.

---

## 2. Fallback 수준 선택

### 선택지 A — 두 Backend 완전 동등 구현

Spring과 FastAPI가 모든 P0 기능을 동일하게 제공한다.

장점:

- 어느 Runtime으로도 전체 Demo 가능
- 기술 전환 위험이 가장 낮아 보임

단점:

- Backend 기능을 사실상 두 번 구현
- Bug Fix·보안·Schema·OpenAPI를 두 벌 유지
- 30일 MVP 일정에 부적합
- 데이터 변환과 회귀 Test 비용이 계속 증가

판정: **권장하지 않음**

### 선택지 B — No-Go 시 최소 Slice부터 FastAPI 구현

Spring 후보를 먼저 구현·검증한다. Spring이 No-Go가 되면 보존된 FastAPI 기준 문서와 공통 Contract를 사용해 핵심 Vertical Slice부터 Python으로 구현하고, 통과한 Runtime만 전체 P0로 확장한다. 두 Backend를 사전에 동시에 완성하지 않는다.

최소 Slice:

- Health·Readiness
- Project·Job 기본 API
- Connector 1종
- Knowledge Build `collect → chunk → embed → index`
- RAG Query
- Job 상태 조회
- Alembic 빈 DB Upgrade

Page Composer, 전체 CMS, Coding Agent까지 두 벌로 만들지 않는다. Spring 전환이 No-Go가 되면 이 Slice를 먼저 구현·검증한 뒤 기존 FastAPI 설계를 확장한다.

판정: **권장**

### 선택지 C — 문서만 보존

비용은 가장 낮지만 실제 기동 Fallback이 아니다.

판정: 사용자 요구만으로는 불충분

---

## 3. 두 Stack의 격리

### 3.1 논리 구성

```text
Spring Primary
  Compose Project  axms-spring
  Core DB Volume   axms-spring-core-db
  Checkpoint DB    axms-spring-langgraph-db
  Migration        Flyway
  Public Route     선택 시 /api

Python Fallback
  Compose Project  axms-python
  DB Volume        axms-python-core-db
  Migration        Alembic
  Public Route     선택 시 /api
```

### 3.2 절대 공유하지 않는 것

- PostgreSQL Database·Volume
- Migration History
- Runtime DB Role
- Queue Store Namespace
- Host Public Port
- Secret Store Namespace
- Active Version Pointer
- Job ID Namespace

Frontend Source와 외부 OpenAPI Contract는 공유할 수 있지만, Runtime 데이터는 공유하지 않는다.

### 3.3 Compose 후보

```text
deploy/
├── compose.infra.yml
├── compose.spring.yml
└── compose.python-fallback.yml
```

향후 Script의 사용자 인터페이스:

```powershell
.\scripts\dev.ps1 -Runtime spring
.\scripts\dev.ps1 -Runtime spring -EnableCodingAgent
.\scripts\dev.ps1 -Runtime python-fallback
```

앞의 두 명령은 Spring 후보 Branch의 계약 예시다. `python-fallback` 명령과 Compose는 No-Go 전환 승인 후 Stage 6의 Backend 전환 Branch에서 구현한다. 이번 문서 작업에서는 어느 명령도 실행하거나 구현하지 않는다.

### 3.4 동시 실행

두 Stack을 비교 Test 목적으로 동시에 띄울 때는 Host Port와 Compose Project Name을 다르게 한다. Nginx의 실제 `/api` Route는 한 Stack만 선택한다.

예시:

| Stack | Web/API 진입 Port | DB Host Port | Queue Store Host Port |
|---|---:|---:|---:|
| Spring | 18080 | 15432 | 16379 |
| Python Fallback | 28080 | 25432 | 26379 |

실제 Port는 Preflight에서 충돌을 확인한 뒤 확정한다.

---

## 4. Fallback은 Hot Failover가 아니다

Spring Stack에서 장애가 났다고 Python Stack이 같은 데이터를 즉시 이어받지 않는다.

전환 절차:

```text
현재 Stack 중지
→ 대상 Stack의 독립 DB Migration
→ System Seed
→ 필요한 Demo Seed 또는 Connector 재수집
→ RAG Rebuild
→ OpenAPI Smoke Test
→ Nginx Route 선택
```

다음은 제공하지 않는다.

- Flyway History를 Alembic으로 변환
- Core DB Volume 공유
- Active Version 자동 복제
- Secret 원문 자동 복사
- 실행 중 무중단 전환

데이터 이관이 나중에 필요하면 Migration 도구가 아닌 별도 Export/Import Contract로 설계하고 사용자 승인을 받는다.

### 4.1 Source·Repository Runtime 전환 Runbook

Spring No-Go 뒤 FastAPI 기준선으로 전환할 때도 Repository 자체나 Git History를 “싹 밀지” 않는다. **현재 Source를 Version 관리되는 PR로 교체**한다. 이 방식이면 Spring 실패 지점과 Python 전환 이유를 추적할 수 있고 필요할 때 이전 Commit·Tag를 다시 확인할 수 있다.

| Repository | 전환 원칙 |
|---|---|
| `urizo-final-frontend` | 삭제·전면 초기화하지 않는다. Public OpenAPI가 같으면 Source를 유지하고 필요한 Contract 차이만 별도 PR로 수정한다. |
| `urizo-final-backend` | Spring 후보의 마지막 상태를 Tag·ADR로 보존한 뒤 Feature Branch에서 Spring Source 삭제와 FastAPI·Worker·Alembic Source 추가를 하나의 검토 가능한 Runtime 전환 PR로 수행한다. Git History는 유지한다. |
| `urizo-final-orchestrator` | Repository를 삭제하지 않는다. Spring Tool·Model Gateway에 의존하는 LangGraph Runtime은 비활성화하고, 향후 Python 기준선에서 재사용할지 별도 ADR로 결정한다. |

전환 순서:

```text
1. Spring No-Go 판정과 실패 Gate·근거를 ADR에 기록
2. 마지막 Spring Commit SHA·검증 Log·Image Digest를 기록하고 보존 Tag 생성
3. dev 최신 상태에서 Runtime 전환 Feature Branch 생성
4. Backend에서 Spring Source를 Git 삭제로 기록하고 FastAPI·Worker·Alembic을 구현
5. Frontend는 Public Contract Diff가 있을 때만 별도 Feature Branch·PR 생성
6. Orchestrator는 기동 Profile에서 제외하고 Source·Repository는 보존
7. axms-python 전용 Compose Project·새 DB/Queue Volume에서 Alembic 빈 DB Upgrade
8. Seed·Connector 재수집·RAG Rebuild·공통 OpenAPI Smoke Test
9. 변경된 Repository별 PR, 연결 PR, Merge 순서 검토
10. 승인 후 전환하되 Spring Tag·Branch History·문서 Snapshot은 삭제하지 않음
```

다음은 전환 요청에 포함된 것으로 간주하지 않으며 별도 승인이 필요하다.

- 원격 Repository 삭제·Archive
- `dev`·`main` History Rewrite 또는 Force Push
- 기존 Branch·Tag 삭제
- 기존 DB·Docker Volume 삭제·초기화
- 대량 Source 교체 PR의 Merge

Spring과 FastAPI 구현을 같은 Backend `dev` Branch에서 장기간 공존시키지 않는다. 비교가 꼭 필요하면 Merge 전 Feature Branch Image와 서로 다른 Compose Project·Volume으로 일시 검증한다.

---

## 5. 외부 Contract 유지

두 Stack 비교 가능성을 위해 다음을 공통 Contract로 둔다.

- Public OpenAPI Version
- Error Envelope
- Job State Enum
- ConnectorSpec
- KnowledgeBuildSpec
- RAG Query·Citation Response
- Health Response

각 Runtime 내부 Model과 Migration은 독립이다. Contract CI는 동일 Test Vector를 Spring과 Python Endpoint 양쪽에 실행한다.

Breaking Change는 Frontend·Spring·Python Fallback 담당자가 함께 승인한다. 다만 Spring이 Primary로 확정된 뒤 Python 최소 Slice에 없는 신규 P0 Endpoint까지 무조건 구현하도록 강제하지 않는다.

---

## 6. 일반 팀원의 Container 실행 가능성

### 6.1 판정

**가능하다.** 조건은 전통 WAR·외부 Tomcat 방식이 아니라 Boot Track·Executable JAR·Embedded Tomcat을 사용하는 것이다.

일반 팀원의 Host 필수 항목:

- 지원되는 Windows
- Hardware Virtualization
- WSL2
- Docker Desktop·Docker Compose
- Git
- 충분한 Memory·Disk

일반 실행에 필수가 아닌 항목:

- Host JDK
- Host Maven
- IntelliJ
- Eclipse Server 설정
- 외부 Tomcat 설치
- Python 설치

Spring Boot는 Executable JAR와 Embedded Web Server로 실행할 수 있고 Container Image를 공식 지원한다.

근거:

- [Spring Boot 실행](https://docs.spring.io/spring-boot/reference/using/running-your-application.html)
- [Spring Boot Embedded Web Server](https://docs.spring.io/spring-boot/how-to/webserver.html)
- [Spring Boot Container Images](https://docs.spring.io/spring-boot/3.5/reference/packaging/container-images/index.html)
- [Docker Multi-stage Build](https://docs.docker.com/build/building/multi-stage/)

### 6.2 Boot Track 고정

전자정부 5.0에는 전통 WAS Template과 Boot/MSA Template이 함께 존재한다. 본 후보는 다음을 고정한다.

```text
사용      eGovFrame 5.0 Boot Track
사용      Embedded Tomcat 10.1 / Servlet 6
금지      외부 Tomcat 9
금지      javax.* 기반 WAR 혼합
금지      IDE Server 설정을 팀 공통 실행 절차로 사용
```

Java 개발자는 필요하면 IntelliJ에서 `SpringApplication.main()`을 실행하지만, 이는 팀 공통 Bootstrap의 전제조건이 아니다.

### 6.3 Multi-stage Build

```text
Builder Stage
  JDK 21
  Maven Wrapper
  Dependency Resolve
  Compile·Test·Package

Runtime Stage
  JRE 21
  Non-root User
  Executable JAR
  최소 Runtime Package
```

Maven Wrapper를 사용해 Maven Version을 Repository에서 고정한다. Build Cache를 사용하되 깨끗한 PC Cold Build도 반드시 측정한다.

### 6.4 Compose 기동 순서

```text
PostgreSQL HEALTHY
→ Flyway Migration COMPLETED
→ Queue Store HEALTHY
→ Spring App(API + Batch) START
→ Actuator Readiness
→ 선택형 Split Worker 또는 LangGraph·Checkpoint DB START
→ React·Nginx HEALTHY
```

단순 `depends_on`만으로 준비 완료를 가정하지 않는다. `service_healthy`와 `service_completed_successfully` 조건을 사용한다.

근거:

- [Docker Compose Startup Order](https://docs.docker.com/compose/how-tos/startup-order/)
- [docker compose up --wait](https://docs.docker.com/reference/cli/docker/compose/up/)
- [Spring Boot Actuator Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

### 6.5 단일 Script 계약

`bootstrap-dev.ps1`의 단계:

1. 읽기 전용 Preflight
2. 설치·Network·로그인·재부팅 필요 항목 보고
3. 사용자 승인
4. Version 관리된 Image Build
5. Compose Up
6. Migration
7. Health Check
8. 실패 시 `compose ps`와 제한된 Tail Log 수집
9. URL·상태·Resource 사용량 보고

LLM Key와 공공 API Key가 없어도 Web·API·DB·Queue Store·Worker Health는 성공해야 한다. AI 기능만 `LLM_NOT_CONFIGURED`로 차단한다.

---

## 7. Debug 전략

Spring Backend는 다음 두 방식 모두를 공식 개발 경로로 제공한다.

| 방식 | Spring Process | Infra | 대상 | 판정 |
|---|---|---|---|---|
| Team Container Mode | Docker Container의 Executable JAR·Embedded Tomcat | Compose | 비백엔드 팀원·통합 Test·Vibe Coding | 필수 |
| IDE Host Mode | Eclipse·IntelliJ·VS Code에서 `SpringApplication.main()` Run/Debug | DB·Queue Store 등은 Container 권장 | Java 개발·Breakpoint·Transaction/Batch 관측 | 필수 |

두 방식은 같은 Source, Maven Lock, Spring Profile, Flyway Revision, 외부 API Contract를 사용해야 한다. Container 전용 Code Path와 IDE 전용 Code Path를 따로 만들지 않는다.

중요한 구분:

- IDE Host Mode는 **Spring Web Application Process를 독립 실행**한다는 뜻이다.
- PostgreSQL·Queue Store·선택형 Local Model까지 자동으로 IDE Process 안에 포함된다는 뜻은 아니다.
- 모든 Infra를 Host에 개별 설치할 수도 있지만 팀 표준은 Infra Container + Host Spring이다.
- 외부 Tomcat Server 등록이나 WAR Deploy는 두 Mode 모두 필요 없다.

공식 Spring Boot 가이드는 IDE에서 일반 Java Application으로 실행·Debug할 수 있고 특별한 IDE Plugin이 필요하지 않다고 명시한다. 전자정부 공식 Simple Backend도 `mvn spring-boot:run`, IDE의 Spring Boot App 실행, `java -jar`를 함께 안내한다.

근거:

- [Spring Boot Running Your Application](https://docs.spring.io/spring-boot/reference/using/running-your-application.html)
- [eGovFrame Simple Backend 실행 안내](https://github.com/eGovFramework/egovframe-template-simple-backend)
- [eGovFrame MSA Boot Template·Target Runtime None](https://www.egovframe.go.kr/docs/5.0/egovframe-development/implementation-tool/ide/msa-template-wizard/)

### 7.1 권장 — Host Java Debug

```text
DB·Queue Store·지원 Service
→ Container

SpringApplication
→ Host Temurin JDK 21 + Eclipse, IntelliJ 또는 VS Code
```

장점:

- Breakpoint와 Hot Restart가 가장 단순
- Container JDWP·Source Mapping 문제 감소
- 사용자가 익숙한 Eclipse·IntelliJ Debug 사용 가능

이 Mode는 Java 담당자용이며 일반 팀원에게 요구하지 않는다.

### 7.2 선택 — Container Remote Debug

Spring Container에 JDWP Port를 명시적으로 열고 IDE가 Attach한다. 외부 Network에는 노출하지 않고 Localhost에만 Bind한다.

주의:

- Debug Port는 Dev Override에만 존재
- Prod Image·Compose에는 포함하지 않음
- 원클릭 Java Container Debug를 팀 공통 완료 조건으로 약속하지 않음

### 7.3 VS Code

전자정부 5.0은 VS Code 기반 eGovFrame Initializr와 Project·CRUD·Config Generation을 공식 제공한다. Spring Boot Dashboard도 Run·Debug를 지원한다. 그러나 일반 팀원의 단순 서비스 기동은 VS Code Extension 없이 Compose Script만으로 가능해야 한다.

근거:

- [eGovFrame VS Code 구현 도구](https://www.egovframe.go.kr/docs/5.0/egovframe-development/vscode-implementation-tool/)
- [VS Code Spring Boot](https://code.visualstudio.com/docs/java/java-spring-boot)

---

## 8. 현실적 팀 Risk

### 8.1 Memory·Disk

Docker Desktop 공식 최소 Memory는 전체 AX Module Studio Stack의 권장 사양이 아니다. Windows, IDE, Spring App, PostgreSQL, Queue Store, 선택형 Split Worker·Python Agent, Image Build를 합치면 8GB PC는 고위험이다.

대응:

- P0 기본에서 Coding Agent Profile Off
- Worker 동시 Job 1개
- JVM Memory Limit
- Local LLM을 기본 Stack에서 제외
- 최저 사양 팀원 PC에서 Peak Memory·Disk·Cold Build 시간 기록

### 8.2 Windows File System

Windows `C:` Bind Mount는 WSL Linux File System보다 대량 File I/O가 느릴 수 있다. 일반 실행은 Image Build 중심으로 유지하고, Java/Python Hot Reload가 느리면 WSL ext4 Workspace를 권장한다. 경로 정책은 팀 전체 검증 후 확정한다.

### 8.3 Network·Proxy

최초 Build에는 Maven Central, eGov Maven Repository, Docker Registry 접근이 필요하다. 회사 VPN, Proxy, SSL Inspection이 있으면 Certificate·Mirror 문제가 생길 수 있다. Preflight가 이를 보고하고 Network 변경은 승인 후 수행한다.

### 8.4 Docker Login

Public Image의 Local Build·Run 자체에는 Docker Desktop 개인 로그인이 필수가 아니다. Private Registry, 조직 정책, Pull Rate Limit이 있을 때만 필요하다.

따라서 신규 후보 Setup 문서에서는:

```text
Docker Desktop Login
OPTIONAL / REQUIRED_IF_PRIVATE_REGISTRY_OR_ORG_POLICY
```

로 바꾼다. 기존 기준선 문서는 Snapshot이므로 수정하지 않는다.

근거:

- [Docker Desktop Sign in](https://docs.docker.com/desktop/setup/sign-in/)
- [Docker Hub Pull Usage](https://docs.docker.com/docker-hub/usage/pulls/)

### 8.5 Docker Desktop License

개인·교육·일부 소규모 조직과 대규모 조직·정부기관의 업무 사용 조건이 다를 수 있다. 현재 팀 프로젝트의 이용 성격과 향후 회사 사용은 Docker 공식 License 조건을 별도 확인한다.

근거: [Docker Desktop License](https://docs.docker.com/subscription/desktop-license/)

---

## 9. 구현 전 Spike Plan

### Stage 0 — Contract·Skeleton

산출물:

- Public OpenAPI 최소 Slice
- Coding Model Turn JSON Schema
- Tool Request·Result JSON Schema
- Job Event JSON Schema
- Spring·Python Error Code
- 두 Runtime용 Contract Test

통과 기준:

- Java DTO와 Python Model이 동일 Golden Payload를 읽고 쓴다.
- Unknown Field·Version 불일치를 명시적으로 거부한다.

### Stage 1 — Dependency Compatibility

두 Lane을 독립 Build한다.

| Lane | eGov | Boot | Spring AI | 목적 |
|---|---|---|---|---|
| Official | 5.0.0 | 3.5.6 | 1.0.1 | 공식 기준 Control |
| Product | 5.0.0 | 3.5.6 | 검증 대상 1.1.x Patch | Google GenAI API Key·최신 기능 |

검사:

- Maven Effective POM
- Full Dependency Tree
- Dependency Convergence
- Duplicate/Conflicting Class
- eGov MVC·Data·Security Bean 기동
- Spring Context·Actuator
- JDK 21 Build·Runtime

통과 기준:

- 핵심 Spring Framework·Jackson·Netty/HTTP·Micrometer Version 충돌 0건
- eGov Runtime Module이 실제 요청·Transaction 경로에서 Test됨
- 대량 Version Override 없이 Build 가능

### Stage 2 — Provider·Product AI

대상:

- OpenAI Chat·Structured Output·Tool Call
- Anthropic Chat·Structured Output·Tool Call
- Google Gemini Developer API Key 또는 확정된 Vertex AI
- 지원 Provider의 Embedding
- PGvector Retrieval

각 선택 Model·Capability별 고정 Golden Case를 최소 20회 반복한다.

통과 기준:

- Capability Registry가 미지원 조합을 저장 단계에서 거부
- Structured Output은 허용된 1회 Repair 후 Schema Valid 100%
- Unauthorized Tool 실행 0건
- Provider Error가 공통 Error Envelope로 매핑
- Secret 원문이 Log·Trace에 나타나지 않음

비용이 발생하는 외부 Model Test는 사용자가 Key를 CMS에 등록하고 Test 예산을 승인한 뒤 실행한다.

### Stage 3 — RAG·Batch·Migration

검사:

- Flyway 빈 DB Upgrade
- 직전 Revision → Head Upgrade
- 단일 Flyway History
- Spring AI·Batch 자동 DDL 0건
- `COLLECT → NORMALIZE → CHUNK → EMBED → INDEX → EVALUATE`
- 의도적 실패 후 실패 Step부터 Restart
- 중복 Event 재전달
- Project·Active Version Filter

통과 기준:

- 동일 Input·Version의 중복 Chunk·Embedding 0건
- 비활성 Knowledge Version 검색 0건
- Runtime Role DDL 시도 실패
- 재시작 후 업무 Job과 Batch 상태 일치

### Stage 4 — LangGraph Bridge·Tool Gateway

검사:

- Queue Store Coding Job Start
- Python → Spring Model Gateway → Provider
- 정규화 Tool Call
- Python → Spring Tool Gateway
- Interrupt → 관리자 승인 → 동일 `thread_id` Resume
- Resume·중복 Event·Timeout
- 긴 Test의 비동기 Execution

통과 기준:

- Provider Key가 Python Process에 없음
- 승인 없는 Side Effect 0건
- Resume 반복에도 Tool Side Effect 중복 0건
- `traceId`·`jobId`로 Java·Python Trace 연결
- Checkpoint DB 장애가 Core 승인 상태를 변경하지 않음

### Stage 5 — 팀 Container Bootstrap

대상 PC:

1. Java Backend 개발자 PC
2. 비백엔드 팀원의 팀 내 최저 사양 PC

비백엔드 PC 조건:

- Host JDK 없음
- Host Maven 없음
- Host Python 없음
- IDE Server 설정 없음
- Docker Desktop만 사용

검사:

- Clean Build
- LLM Key 없는 Base Health
- 재부팅 후 DB 보존
- Script 멱등 재실행
- Coding Agent Profile Off에서 P0 기반 Health, On에서 Coding Agent E2E 모두 검증
- Cold Build 시간·Peak Memory·Disk
- 실패 Log 수집

통과 기준:

- 단일 Script로 Build → Migration → Health 성공
- 수동 외부 WAS 설정 0건
- 재실행 시 Volume 초기화 0건
- Resource 측정값을 팀 최소 사양 문서에 반영

### Stage 6 — Spring No-Go 시 Python Baseline 구현

이 Stage는 Spring Go를 위한 사전 이중 구현이 아니다. Stage 0~5의 Critical Gate에서 No-Go가 확정되고 사용자가 Runtime 전환을 승인했을 때만 시작한다.

검사:

- 별도 Compose Project
- 별도 DB·Queue Store Volume
- Alembic 빈 DB Upgrade
- 최소 RAG Vertical Slice
- Spring Stack과 OpenAPI Smoke Test
- 두 Stack 동시 기동 시 Port 충돌 없음

통과 기준:

- Flyway DB 접근 0건
- Spring Volume 접근 0건
- 독립 Stop/Start·Seed·RAG Rebuild 성공
- 문서가 아니라 실제 Container Health 성공

---

## 10. 최종 Go 조건

다음이 모두 충족되어야 Spring Primary 구현을 승인한다.

- Stage 0~5 필수 Test 통과
- Spring AI 최종 Version과 Patch Lock
- Provider별 Capability Matrix 확정
- eGov Runtime Module의 실사용 확인
- Flyway·Checkpoint DB 소유권 분리와 Python 전환 시 Alembic 격리 설계
- Java/Python Contract Test CI
- 비백엔드 PC Clean Bootstrap 성공
- FastAPI 기준 문서 Snapshot·전환 Runbook·공통 Contract 보존
- 일정·담당자·Review Owner 확정

### No-Go 조건

하나라도 충족하면 Spring 전환을 중단하거나 설계를 재검토한다.

- eGov Parent 유지에 핵심 Spring/BOM 다수 강제 Override가 필요
- Gemini를 포함한 선택 Provider의 Tool·Structured Output이 반복 Test에서 불안정
- Spring AI·Batch 자동 DDL을 완전히 차단하지 못함
- LangGraph가 Tool Gateway를 우회해 파일·Git·Docker 권한을 요구
- 동일 Side Effect가 Resume·Retry에서 중복 실행
- 비백엔드 PC가 IDE별 외부 WAS 설정 없이는 기동 불가
- Spring과 Python Fallback이 같은 DB·Volume·Migration Chain에 의존
- 완전한 두 Backend 병행 때문에 P0 일정이 성립하지 않음

No-Go 확정 시 `4.1 Source·Repository Runtime 전환 Runbook`의 승인 절차를 거쳐 Stage 6을 시작한다. FastAPI 구현과 Test가 통과하기 전에는 “Fallback 전환 완료”로 보고하지 않는다.

---

## 11. 단계적 구현 권고

```text
1. 기존 문서 Snapshot 유지
2. 공통 Contract 작성
3. Spring Compatibility Spike
4. RAG 1개 Vertical Slice
5. LangGraph ↔ Spring Gateway Spike
6. 비백엔드 2-PC Bootstrap
7. Spring 최종 Go/No-Go 회의
8-A. Go → Spring Runtime만 전체 P0 구현
8-B. No-Go + 사용자 승인 → Backend Runtime 전환 PR에서 FastAPI 최소 Runnable Slice 구현
9-B. Python 최소 Slice Test 통과 후 FastAPI Runtime만 전체 P0 구현
```

이 순서를 따르면 Spring 성공 시 두 Backend를 중복 구현하지 않는다. Spring 실패 시에는 보존된 기준 문서·Contract·Git History를 출발점으로 FastAPI를 실제 구현한다. 문서 Snapshot만으로 즉시 기동되는 것은 아니며 Stage 6 구현·Test가 필요하다.

---

## 12. 승인 경계

이번 문서 이후에도 다음은 별도 사용자 승인 전 수행하지 않는다.

- JDK·Maven·Python·Docker·WSL 설치 또는 변경
- Network Dependency Download
- 재부팅
- Docker·Git·Provider 로그인
- Repository 생성·Clone·구조 변경
- Source Scaffold
- Docker Image Build·Compose 실행
- Flyway·Alembic Migration 실행
- Secret 등록
- DB·Volume 생성·삭제·초기화
