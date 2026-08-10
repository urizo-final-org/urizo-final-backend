# AX Module Studio 요구사항 계승·우선순위 Matrix v0.2

> 작성일: 2026-08-10  
> 상태: Spring 후보안의 요구사항 경계 확정  
> 기준 원본: `../../baseline-fastapi-python-v0.1-2026-08-10/snapshot/`

---

## 0. 결론

Spring 후보안은 기존 기획의 전면 대체본이 아니라 **기존 제품 기획 위에 기술·Runtime 변경을 적용하는 Overlay**다.

- 문제 정의, 사용자, 기능 범위, 승인 철학, Version·Active Pointer, 최소권한, UI 방향, Demo 목적은 계승한다.
- FastAPI·Python Worker 중심 기술 배치, Alembic Core Migration, 내부 API 명칭, Backend 내부 구조는 변경한다.
- 30일 일정, 팀 역할, Bootstrap, 구현 인수인계, Dependency·License는 재검증 또는 재발행 대상이다.

따라서 “아키텍처만 바뀌고 나머지는 모두 동일하다”는 설명은 절반만 맞다. **제품 의도는 계승하지만, 구현 계약·Migration·Runtime·운영·일정에도 연쇄 변경이 생긴다.**

---

## 1. 문서 우선순위

Spring 후보안으로 질문·설계·구현 준비를 할 때 다음 순서로 해석한다.

1. 이 폴더의 Spring 후보 문서가 명시적으로 바꾼 항목
2. 후보 문서가 바꾸지 않은 항목은 Baseline Snapshot의 기존 문서
3. 두 문서가 충돌하지만 후보 문서에 명시적 결정이 없으면 구현하지 않고 `OPEN_DECISION`으로 기록
4. Spike 결과와 사용자 승인으로 확정된 뒤 정식 v0.2 문서를 발행

현재 Top-level 기존 문서와 Baseline Snapshot은 수정하지 않는다. Spring 후보 폴더만 변경 가능하다.

### 상태 정의

| 상태 | 의미 |
|---|---|
| `INHERITED` | 의미·수용 기준을 그대로 계승 |
| `INHERITED_WITH_MAPPING` | 의미는 유지하되 Spring 구성요소로 구현 주체 변경 |
| `OVERRIDDEN` | Spring 후보 문서가 기존 기술·계약을 대체 |
| `REBASE_REQUIRED` | 기존 의도는 유효하지만 일정·팀·실행 문서를 다시 산정해야 함 |
| `FUTURE_HYPOTHESIS` | 현재 무료 Toy 구현의 의무가 아니라 향후 사업화 가설 |

---

## 2. Project Spec v1.0 추적 Matrix

| 기존 Section | 상태 | Spring 후보안 적용 |
|---|---|---|
| 0. 문서 상태 | `OVERRIDDEN` | Spring 후보는 Conditional Go·Implementation Hold 상태이며 아직 정식 구현 기준이 아니다. |
| 1. 프로젝트 개요 | `INHERITED` | 문제 정의, 해결 방향, AI-native CMS 본질 유지 |
| 2. 구매·납품·재사용 가치 | `FUTURE_HYPOTHESIS` | 사업적 제품 가설은 보존하되 현재 실행 목적은 무료·비상업 Toy다. 상용화 전 License·Cloud·Provider 비용을 재검토한다. |
| 3. 이전 프로젝트 대비 포지셔닝 | `INHERITED` | 도메인 비종속, RAG·CMS·승인·자동화 차별성 유지 |
| 4. 30일 P0·P1·P2 | `INHERITED_WITH_MAPPING` + `REBASE_REQUIRED` | 기능 우선순위는 유지한다. P0 제품 AI는 Spring, P1 제한형 Coding Agent는 LangGraph로 배치하고 일정은 다시 산정한다. |
| 5. 제품 사용자와 권한 | `INHERITED` | Super Admin·Project Admin·Reviewer·End User·Developer 역할 유지 |
| 6. 핵심 사용자 시나리오 | `INHERITED` | Connector, Knowledge Build, 교체·복구, Composer, 게시 흐름 유지 |
| 7. 멀티에이전트 설계 | `INHERITED_WITH_MAPPING` | Agent 목적·Tool 승인 원칙은 유지한다. P0 흐름은 Spring Application Service·Spring AI, P1 Coding Graph만 LangGraph가 담당한다. |
| 8. 시스템 아키텍처 | `OVERRIDDEN` | FastAPI Control Plane·Python AI Worker 배치를 Spring Control Plane·Spring Batch·Spring AI와 Coding Agent 사용 시 필수인 LangGraph Runtime으로 대체 |
| 9. Connector | `INHERITED_WITH_MAPPING` | ConnectorSpec·Allowlist·Pagination·Retry 의미 유지, 구현 주체는 Spring/Batch |
| 10. RAG | `INHERITED_WITH_MAPPING` | Pipeline·Version·검색·평가 의미 유지, Spring AI·Batch·PGvector로 구현 |
| 11. Menu·Page·SiteTemplate | `INHERITED_WITH_MAPPING` | Spec·검증·Draft·승인·게시 유지, Spring AI Structured Output과 Java Validation 사용 |
| 12. DB 권한·AI 계정 | `INHERITED_WITH_MAPPING` | 최소권한·DBeaver read-only·Project 격리 유지. Core DDL은 Flyway, LangGraph Checkpoint와 Python Fallback은 별도 DB·Migration 사용 |
| 13. 보안·거버넌스 | `INHERITED` | Secret, SSRF, Prompt Injection, 승인, 감사 원칙 유지. 최종 Enforcement는 Spring Tool Gateway |
| 14. CI/CD·Cloud | `INHERITED_WITH_MAPPING` + `REBASE_REQUIRED` | 콘텐츠 게시와 앱 배포 분리 유지. Java/Python Image·Contract CI·License Gate를 추가하고 배포 비용은 재산정 |
| 15. 핵심 데이터 모델 | `INHERITED_WITH_MAPPING` | 업무 Entity 의미 유지. Spring Entity/DTO·Batch Metadata·Checkpoint 분리를 반영 |
| 16. 서비스 API | `INHERITED_WITH_MAPPING` | 외부 의미·Error Envelope·Job 상태를 유지하고 `FastAPI` 명칭을 Spring Public API로 변경. Java–Python 내부 계약 추가 |
| 17. 비기능 요구 | `INHERITED_WITH_MAPPING` | 성능·안정성·관측성 유지. JVM Resource, Actuator, Micrometer/OTel, Python Bridge 지표 추가 |
| 18. Definition of Done | `INHERITED_WITH_MAPPING` | 기존 E2E·품질 기준에 Dependency Convergence, Flyway, Contract Test, 2-PC Bootstrap Gate 추가 |
| 19. 30 Working Days | `REBASE_REQUIRED` | Spring/eGov Compatibility Spike와 Java–Python Bridge 비용 때문에 기존 날짜를 구현 약속으로 사용하지 않는다. |
| 20. 5명 팀 역할 | `REBASE_REQUIRED` | 기능 책임은 유지하되 Spring·Python Runtime·Contract 소유자를 다시 지정한다. |
| 21. 시연 시나리오 | `INHERITED` | 관광·정책/채용·도메인 비종속·보안 시연 유지 |
| 22. 정량 성과 | `INHERITED` | RAG 품질, 처리시간, 자동화·승인 지표 유지 |
| 23. 주요 위험 | `INHERITED_WITH_MAPPING` | 기존 Risk에 eGov/Spring AI Version, JVM Resource, 분산 Trace, Contract Drift, License Risk 추가 |
| 24. Local LLM·Fine-tuning | `INHERITED_WITH_MAPPING` | Roadmap 유지. 정확한 Model License·배포 조건·PC 사양을 Model Registry Gate로 추가 |
| 25. 제품 Roadmap | `INHERITED_WITH_MAPPING` | 단계 목적 유지, 기술 명칭과 구현 주체만 새 경계로 교체 |
| 26. Repository 구조 | `OVERRIDDEN` | 비-Git 상위 Workspace 아래 Frontend·Spring Backend·Python Orchestrator 3개 Repository를 둔다. P0는 Backend의 API·Batch를 한 Application·Container로 실행하고 Flyway만 One-shot 분리한다. Worker 분리는 측정 후 선택한다. |
| 27. 바이브 코딩 규칙 | `INHERITED_WITH_MAPPING` | 승인·작업단위·검증 원칙 유지. AI의 작업 전 Read/Write Repository Scope 선언, Maven/Python Lock, Contract Test, Repository별 Branch·PR·상호 링크를 추가 |
| 28. 시작 Prompt | `REBASE_REQUIRED` | FastAPI·Alembic 전제를 제거한 Spring v0.2 Prompt로 재발행 필요 |
| 29. 발표 문장 | `INHERITED_WITH_MAPPING` | 제품 메시지 유지. eGovFrame 사용을 공식 인증·보증으로 표현하지 않는다. |
| 30. 최종 성공 조건 | `INHERITED_WITH_MAPPING` | 기존 성공 조건에 두 실행 방식, License Profile, LangGraph Off 상태 P0 기반 Health와 Full Profile Coding Agent E2E를 모두 추가 |
| 31. 중간 반영 결정 | `INHERITED_WITH_MAPPING` | 공공데이터·RAG Harness·품질·React 협업은 유지. LangGraph/LangChain/LangSmith·Redis·Worker 책임은 새 경계로 변경 |
| 32. 디자인·템플릿 관리 | `INHERITED` | P0 경계와 안전한 Spec 기반 생성 원칙 유지 |
| 33. CMS UI 목업 | `INHERITED` | 시각·레이아웃·화면 재사용 기준 유지 |
| 34. 단일 MD 팀 Bootstrap | `INHERITED_WITH_MAPPING` + `REBASE_REQUIRED` | 자동화·승인 철학 유지. Spring/Flyway, P1에서 필수인 LangGraph Profile, IDE Debug 경로를 포함한 새 Setup 문서 필요 |

---

## 3. 기존 인수인계·운영 문서의 효력

| 기존 문서 | 현재 효력 |
|---|---|
| `AX_Module_Studio_ARCHITECTURE_HANDOFF_v0.1.md` | 제품 흐름 설명은 유효, FastAPI·Worker 책임 부분은 Spring 목표 아키텍처가 우선 |
| `AX_Module_Studio_IMPLEMENTATION_HANDOFF_v0.1.md` | Baseline FastAPI 구현 분기의 인수인계 문서. Spring 구현 착수 문서로 단독 사용 금지 |
| `TEAM_DEV_SETUP.md` | 승인 경계·Preflight 철학은 유효, 실행 명령·Migration·완료 조건은 Spring v0.2 발행 전까지 미확정 |
| `DATABASE_MIGRATION_POLICY.md` | 데이터와 DDL 분리 철학은 유효, Core DB의 Alembic 명령은 Flyway 정책으로 재발행 필요 |
| Coding Agent Harness Design | 승인·Tool 최소권한은 유효, Control Plane 명칭과 Java–Python Gateway 계약은 새 문서가 우선 |
| Git·PR 문서 | 기존 Branch·Review 원칙은 유효. Spring 후보에서는 Backend·Orchestrator를 별도 PR로 만들고 `AX_Module_Studio_THREE_REPOSITORY_GIT_COLLABORATION_v0.2.md`로 연결한다. |

---

## 4. 구현 착수 전 문서 Gate

다음이 발행되기 전에는 이 후보 문서만으로 전체 구현을 완료하려고 하지 않는다.

1. Spring 기준 Project Spec v1.1 또는 변경 부록
2. Spring Architecture/Implementation Handoff v0.2
3. Flyway 중심 DB Migration Policy v0.2
4. Spring·LangGraph 계약을 반영한 Harness Design v0.2
5. 3-Repository 규칙을 반영한 GIT-WORKFLOW·팀 작업 가이드 v0.2
5. Container·IDE 두 경로를 포함한 Team Dev Setup v0.2
6. 30일 일정과 팀 역할 Rebaseline

이 Gate는 기존 기획을 버리기 위한 것이 아니라, 계승된 요구가 구현 과정에서 조용히 누락되는 것을 막기 위한 것이다.
