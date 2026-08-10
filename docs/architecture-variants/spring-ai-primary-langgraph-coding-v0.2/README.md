# Spring AI Primary + Python LangGraph Coding 후보 문서

> 작성일: 2026-08-10  
> 상태: Conditional Go / Implementation Hold  
> 기준선: `../baseline-fastapi-python-v0.1-2026-08-10/snapshot/`

## 제안 한 줄

```text
P0 제품·AI·업무 기준
→ eGovFrame 5.0 + Spring Boot + Spring AI + Spring Batch + Flyway

P1 제한형 Coding Agent Orchestration
→ Python + LangGraph, Coding Agent 사용 시 필수 독립 Runtime

모델 Secret·업무 상태·Tool 실행·승인
→ Spring 단독 권한

FastAPI Fallback
→ 별도 Compose Project·별도 DB Volume·동일 외부 계약

Git 경계
→ 상위 Workspace는 .git 없음, React·Spring Backend·Python Orchestrator의 3 Repository
```

## 읽기 순서

1. `AX_Module_Studio_REQUIREMENTS_INHERITANCE_AND_PRECEDENCE_v0.2.md`
2. `AX_Module_Studio_SPRING_AI_FEASIBILITY_ADR_v0.2.md`
3. `AX_Module_Studio_FREE_TOY_LICENSE_AND_COMPATIBILITY_REVIEW_v0.2.md`
4. `AX_Module_Studio_SPRING_AI_TARGET_ARCHITECTURE_v0.2.md`
5. `AX_Module_Studio_THREE_REPOSITORY_GIT_COLLABORATION_v0.2.md`
6. `AX_Module_Studio_DUAL_RUNTIME_CONTAINER_AND_SPIKE_GATE_v0.2.md`

## 효력

이 문서는 기존 최상위 기획서를 덮어쓰지 않는 Overlay 후보다. Spike Gate 통과와 사용자 승인 전에는 다음을 수행하지 않는다.

- Backend Source Scaffold 전환
- FastAPI 설계 삭제
- Alembic 파일을 Flyway로 변환
- Repository 구조 변경
- Docker Image·Compose 생성 또는 실행
- Dependency Download·설치·로그인

## 정식 전환 승인 후 갱신 대상

정식 전환이 승인되면 원본을 직접 덧칠하기보다 새 Version 문서를 발행한다.

| 기존 문서 | 신규 발행 방향 |
|---|---|
| Project Spec v1.0 | 기술 스택·서비스 책임만 v1.1에서 변경 |
| Architecture Handoff v0.1 | Spring Primary 구조로 v0.2 발행 |
| Implementation Handoff·Start Gate v0.1 | Spike 결과와 새 Bootstrap 순서로 v0.2 발행 |
| Dev·Prod Bootstrap·Team Setup | Flyway·Spring App 내 Batch·선택형 Split Worker·LangGraph Profile 반영 |
| DB Migration Policy | Spring DB/Flyway, Checkpoint DB, Python Fallback/Alembic을 분리한 v0.2 발행 |
| Harness Design v0.1 | FastAPI Control Plane 명칭을 Spring Control Plane·Tool Gateway로 바꾼 v0.2 발행 |
| Git Workflow·팀 작업 가이드 | 3-Repository Routing, AI 작업 Scope 선언, Repository별 PR, Contract Test 추가 |

기능 요구, 승인 철학, Version/Active Pointer, Tool 최소권한을 유지한다. Repository 모델은 사용자가 생성한 Orchestrator Repository를 반영해 3개로 변경한다. 정확한 계승·변경·재검증 범위는 Requirements Matrix가 우선한다.

## 현재 확정한 해석

- 신규 문서는 기존 기획을 전부 복제한 독립 Spec이 아니라 기존 Spec을 참조하는 Overlay다.
- 제품 요구는 계승하지만 기술·Migration·내부 Contract·일정·Setup은 “아키텍처만”보다 넓게 변경된다.
- Python은 범용 AI Interface가 아니라 P1 `LangGraph Coding Runtime`이다.
- 공통 Model Interface는 Spring AI Model Gateway다.
- Git은 Frontend·Spring Backend·Python Orchestrator 3 Repository다. P0 기본은 Spring API와 Batch를 하나의 Backend Application·Container에서 실행하고 Migration만 One-shot으로 분리한다.
- FastAPI 기준선 전환은 Spring No-Go 뒤에만 시작하며 Repository나 Git History를 삭제하지 않고 Backend Runtime 전환 PR로 구현한다.
- Spring Backend는 Team Container Mode와 IDE Host Run·Debug Mode를 모두 지원한다.
- 무료 Toy 목적에 적합하지만 외부 Model API·Hosted Service·Model Weight·Docker 조직 사용은 별도 조건이다.
