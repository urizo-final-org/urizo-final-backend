# AX Module Studio Spring AI 전환 타당성 조사·ADR v0.2

> **HISTORICAL ARCHITECTURE RECORD:** Spring 전환 판단 근거를 보존하는 ADR이다. 현재 구현 상태, Gate, 일정, 역할, Git 및 승인 규칙으로 사용하지 않으며 workspace sibling `urizo-final-master`가 현재 정책을 소유한다.
>
> 작성일: 2026-08-10  
> 상태: **Conditional Go / Implementation Hold**  
> 조사 기준: 전자정부 표준프레임워크·Spring·Docker·LangGraph 공식 문서  
> 기존 기준선: FastAPI·Python Worker 문서 Snapshot

---

## 0. 결론

### 아키텍처 판정

다음 구조는 충분히 실현 가능하고, 현재 팀 구성과 프로젝트 목적에도 타당하다.

```text
P0 제품 기능과 제품 AI
→ eGovFrame 5.0 + Spring Boot + Spring AI + Spring Batch

P1 제한형 Coding Agent의 장기 실행 그래프
→ Python + LangGraph

업무 상태·Secret·Model Registry·Tool 실행·승인
→ Spring Boot Control Plane이 최종 통제
```

특히 전자정부 표준프레임워크 5.0은 Spring AI의 RAG·ETL·Advisor·세션 관리와 Spring Batch 5.2를 공식 가이드하므로, 이 조합은 단순한 개인적 선호나 우회 설계가 아니다.

다만 **구현을 바로 시작해도 된다는 뜻은 아니다.** 전자정부 Parent가 공식 관리하는 Spring AI는 `1.0.1`이고, 제품에서 원하는 Google Gemini Developer API Key 방식은 Spring AI `1.1.x`에서 공식 도입됐다. 따라서 최종 기술 Version은 호환 Spike 통과 후 확정한다.

### 이번 의사결정의 수준

| 구분 | 판정 |
|---|---|
| Spring 중심 구조 자체 | Go |
| Python을 LangGraph Coding Runtime으로 제한 | Go |
| eGovFrame 5.0·Boot 3.5·JDK 21 | Go |
| Spring AI 정확한 Version | Hold — 1.0.1 대 1.1.x Spike 필요 |
| FastAPI 문서 폐기 | No |
| 실제 Source·Repository 구조 전환 | 사용자 승인 전 Hold |

---

## 1. 조사 질문

이번 조사는 다음 질문에 답한다.

1. eGovFrame 5.0, Spring Boot, Spring AI, Spring Batch, JDK가 공식적으로 맞물리는가?
2. RAG 외의 P0 AI 기능도 Spring AI에서 수행할 수 있는가?
3. Python을 LangGraph에만 제한해도 Coding Agent가 동작할 수 있는가?
4. Java 비전문 팀원도 IDE·외부 WAS 없이 Container로 전체 서비스를 실행할 수 있는가?
5. 기존 FastAPI·Python Worker 경로를 실패 대비용으로 독립 보존할 수 있는가?
6. 어떤 실패 조건에서 Spring 전환을 중단해야 하는가?

---

## 2. 공식 호환성 조사

### 2.1 전자정부 5.0 기준

전자정부 5.0 공식 Getting Started가 명시하는 기준은 다음과 같다.

- 실행환경: Spring Framework `6.2.11`
- 실행 JDK: `17 이상`
- 개발환경 5.0: `JDK 21 필요`
- Jakarta EE
- 일반 Servlet: `5.0 이상`
- Spring Boot 사용 시: `Servlet 6.0 이상`

공식 Boot Backend Sample은 eGovFrame Parent `5.0.0`, Spring Boot `3.5.6`, Servlet `6.0`, Embedded Tomcat `10.1` 계열을 사용한다. 공식 Spring AI RAG Sample은 Spring AI `1.0.1`을 사용하고 RAG, ETL, 문서 처리, Vector Store, Chat Memory를 설명한다.

근거:

- [eGovFrame 5.0 Getting Started](https://www.egovframe.go.kr/docs/5.0/getting-started/)
- [eGovFrame 5.0 실행환경](https://www.egovframe.go.kr/docs/5.0/egovframe-runtime/)
- [eGovFrame 공식 Boot Backend Sample](https://github.com/eGovFramework/egovframe-template-simple-backend)
- [eGovFrame Spring AI RAG Sample](https://www.egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-sample-project/)
- [eGovFrame Spring AI RAG 아키텍처](https://egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-rag-architecture/)
- [eGovFrame Spring AI ETL](https://egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-etl-guide/)
- [eGovFrame Spring Batch 5.2](https://egovframe.go.kr/docs/5.0/egovframe-runtime/batch-layer/)

### 2.2 Spring Boot·Spring AI 기준

Spring Boot 3.5는 Java 17 이상, Spring Framework 6.2, Embedded Tomcat 10.1·Servlet 6을 지원한다. Spring AI 공식 호환표는 `1.1.x → Spring Boot 3.5.x`, `2.x → Spring Boot 4.x`를 명시한다.

따라서 eGovFrame 5.0과의 교집합은 Boot 3.5·Spring 6.2·Servlet 6·Java 17 이상이다. Spring AI 2.x와 Boot 4는 이번 후보에서 제외한다.

근거:

- [Spring Boot 3.5 System Requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring AI 공식 호환표](https://github.com/spring-projects/spring-ai)

### 2.3 호환성 Matrix

| 조합 | 공식성 | 후보 판정 |
|---|---|---|
| eGovFrame 5.0 + JDK 21 | eGov 공식 개발환경 | 채택 |
| eGovFrame 5.0 + Spring Framework 6.2 | eGov 공식 실행환경 | 채택 |
| eGovFrame 5.0 + Boot 3.5.6 | eGov 공식 Sample | 채택 |
| Boot 3.5 + Tomcat 10.1 + Servlet 6 | Spring·eGov 공식 정합 | 채택 |
| eGov Parent 5.0.0 + Spring AI 1.0.1 | eGov 공식 관리 | 기준 Lane |
| Boot 3.5 + Spring AI 1.1.x | Spring 공식 호환 | 제품 후보 Lane, Spike 필수 |
| eGov Parent 5.0.0 + Spring AI 1.1.x | eGov 공식 Sample 밖 | 조건부 |
| Spring AI 2.x + Boot 4 | eGov 5.0 기준 밖 | 제외 |
| 외부 Tomcat 9 또는 `javax.*` | Jakarta·Servlet 6과 충돌 | 금지 |

### 2.4 JDK 기준

권장 초기 Spike 기준은 다음과 같다.

| 항목 | 값 | 이유 |
|---|---|---|
| Build Image | JDK 21 | eGov 5.0 개발환경 기준 |
| Runtime Image | JRE 21 | 팀 Runtime 단일화 |
| Java Source/Bytecode | 17 우선 | eGov 공식 Boot Sample과 가장 가까운 기준 |
| Embedded WAS | Tomcat 10.1 | Boot 3.5·Servlet 6 |

Java 21 문법을 반드시 써야 하는 요구가 생기면 Source Level 21 전환을 별도 검증한다. 현재 제품 기능에는 Source Level 17로 막히는 요구가 없다.

---

## 3. Spring AI Version 결정

### 3.1 공식성 우선 Lane

```text
JDK/JRE                 21
Java Source             17
eGovFrame Parent        5.0.0
Spring Boot             3.5.6
Spring AI               1.0.1
Spring Batch            Parent 관리 5.2.x
Tomcat / Servlet        10.1 / 6
```

장점:

- 전자정부 공식 Sample과 가장 가깝다.
- BOM Override와 Transitive Dependency 충돌 가능성이 가장 낮다.
- 전자정부 기반 프로젝트라고 설명하기 가장 명확하다.

제약:

- 최신 Spring AI 기능과 Provider SDK가 부족할 수 있다.
- 1.0 계열 Google 지원은 Vertex AI Gemini 방식이 중심이다.
- 현재 제품이 전제한 단순 Gemini Developer API Key 등록 방식과 다를 수 있다.

### 3.2 제품 기능 우선 Lane

```text
JDK/JRE                 21
Java Source             17
eGovFrame Parent        5.0.0
Spring Boot             3.5.6
Spring AI               1.1.x의 검증된 Patch
Spring Batch            Parent 관리 5.2.x
Tomcat / Servlet        10.1 / 6
```

Spring AI 1.1 GA는 Google GenAI SDK 통합을 새 기능으로 발표했고 Gemini Developer API Key와 Vertex AI 양쪽 인증을 지원한다. 2026-08-10 공식 문서의 1.1 안정 계열은 `1.1.8`이지만, 실제 POM에 넣을 Patch는 Spike 시점에 고정하고 변경하지 않는다.

근거:

- [Spring AI 1.1 GA 발표](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)
- [Spring AI 1.1 Google GenAI](https://docs.spring.io/spring-ai/reference/1.1/api/chat/google-genai-chat.html)

장점:

- OpenAI·Anthropic·Google GenAI를 하나의 Spring AI 계층에서 다루기 쉽다.
- Google AI Studio API Key 방식이 제품 Secret Form과 맞는다.
- 1.0 이후 Provider·RAG·Observability 개선을 활용할 수 있다.

제약:

- eGov Parent 5.0.0이 공식 관리하는 1.0.1 범위를 벗어난다.
- BOM Import 순서와 Spring/HTTP Client/Jackson/Micrometer 의존성 충돌을 실제 Build로 검증해야 한다.
- “전자정부 공식 조합 그대로”라고 표현할 수 없다.

### 3.3 권고

제품 요구상 OpenAI·Claude·Gemini API Key를 모두 유지하려면 **1.1.x 제품 Lane을 우선 검증**한다. 동시에 1.0.1 공식 Lane을 Control로 Build하여 충돌이 eGov Parent 때문인지 Spring AI 기능 때문인지 비교한다.

다음 중 하나가 최종 결과가 된다.

1. 1.1.x Lane이 모든 Gate 통과 → Spring Primary 채택
2. 1.1.x 실패, 1.0.1 + Vertex AI가 제품 요구를 수용 → 공식 Lane 채택
3. 두 Lane 모두 Provider·Tool·Schema 요구를 충족하지 못함 → FastAPI 기준선 유지

---

## 4. 제품 기능별 기술 배치

RAG만 Spring에 넣는 구조가 아니다. P0 제품 AI 전체를 Spring AI로 옮기고, 결정적 업무 처리와 장기 Batch를 Spring 계층에 둔다.

| 제품 기능 | 주 Runtime | Spring AI 사용 | 비고 |
|---|---|---|---|
| 인증·권한·Project·CMS | Spring Boot MVC | 없음 | 결정적 업무 로직 |
| Provider·Model Registry | Spring Boot + Spring AI | Chat·Embedding Model Adapter | Capability Allowlist 필수 |
| Connector 설정 제안 | Spring AI | Structured Output | 실제 API 호출은 Java HTTP Client |
| 공공 API 수집 | Spring Batch Worker | 필요 시 Mapping 보조 | AI보다 재시작·멱등성이 핵심 |
| 정규화·Chunk | Spring Batch + Spring AI ETL | Document Reader/Transformer | Mapping 검증은 Java |
| Embedding·Index | Spring Batch + Spring AI | EmbeddingModel·PGvector | Active Version 분리 |
| RAG Query | Spring Boot + Spring AI | Retriever·Advisor·ChatClient | Project/Version Filter 강제 |
| Retrieval Test·평가 | Spring Batch/Spring AI | 선택형 LLM Judge | 정답·Threshold는 업무 DB |
| Menu/Page/Template Composer | Spring AI | Structured Output·Tool Request | JSON Schema 재검증 |
| Validation | Java Validation Service | AI 보조만 | 권한·Schema·Binding은 코드 판정 |
| Publish·Rollback | Spring Domain Service | 없음 | 승인 Transaction |
| Coding Agent | Python LangGraph | Java Model Gateway 경유 | P1 |
| Coding Tool 실행 | Spring Tool Gateway | Tool Request 해석 | 실제 권한 판정은 Java |

### Spring AI가 직접 담당하지 않는 것

Spring AI를 채택해도 모든 것을 AI Framework에 맡기지 않는다.

- HTTP Connector 재시도·Pagination
- DB Transaction
- 권한과 승인
- Version 활성화
- JSON Schema 최종 판정
- 파일·Git·Shell 실행
- Batch 재시작 상태
- Migration

이 경계가 있어야 Spring AI 교체나 Provider 장애가 제품 무결성 장애로 번지지 않는다.

---

## 5. Python을 LangGraph로만 제한할 수 있는가

가능하다. LangGraph의 Node는 동기·비동기 Python 함수이고 외부 LLM API나 자체 서비스를 호출할 수 있다. 공식 Streaming 문서도 LangChain Chat Model Interface를 구현하지 않은 외부 LLM 서비스의 Custom Stream을 사용할 수 있다고 설명한다.

권장 호출 경로:

```text
Python LangGraph Node
→ Spring CodingModelGateway 내부 API
→ Spring AI Provider Registry
→ OpenAI / Anthropic / Google
→ 정규화된 Assistant·ToolCall 응답
→ LangGraph 다음 Node
→ Spring Tool Gateway
```

이 구조에서 Python은 다음만 가진다.

- Graph State와 고정 Node·Edge
- Checkpoint와 Interrupt
- 재시도·중단·재개
- Java 내부 API Client
- Coding 전용 Prompt Context 조립
- Event·Progress 보고

Python은 Provider Key, 업무 DB DDL, Git Token, Docker Socket, 최종 승인 권한을 가지지 않는다.

근거:

- [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)
- [LangGraph Streaming](https://docs.langchain.com/oss/python/langgraph/streaming)
- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [LangGraph Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)

### 차선책

Java Model Gateway의 Tool Call 왕복이 Provider별로 안정화되지 않을 때만 Python Coding Runtime에 전용 Provider SDK를 제한적으로 허용한다. 이 경우에도 업무 Provider Registry와 Key를 공유하지 않고 Coding 전용 Secret·Capability만 사용한다. 이는 기본안이 아니라 Spike 실패 시의 예외다.

---

## 6. 장점

### 6.1 프로젝트와 경력 정합성

- Java 개발자가 P0 핵심 AI 흐름을 익숙한 Controller–Application Service–Domain–Repository 경계에서 추적할 수 있다.
- 향후 eGovFrame 5.0과 Spring AI 업무에 직접 연결되는 경험이 된다.
- IntelliJ 또는 VS Code에서 Breakpoint, Transaction, Batch Step, Tool Gateway를 함께 관측할 수 있다.

### 6.2 제품 안정성

- 인증·승인·Version·Audit과 AI 결과 검증이 같은 Java Transaction 경계에 놓인다.
- Spring Batch의 Job·Step·JobRepository·Restart를 RAG Build에 사용할 수 있다.
- Java Record/DTO와 Bean Validation, JSON Schema로 Structured Output을 이중 검증하기 쉽다.
- Tool 실행 권한을 Spring Gateway에 일원화할 수 있다.

### 6.3 전자정부 활용도

- 단순히 Spring 버전만 맞추는 것이 아니라 `egovframe-boot-starter-parent`와 실제 `org.egovframe.rte` Module을 MVC·Data·Security 경로에서 사용한다.
- 전자정부 공식 Spring AI RAG·ETL·Spring Batch 가이드를 제품 기능에 적용한다.
- Boot Template을 사용하므로 외부 WAS 수동 설정 없이도 전자정부 기반 프로젝트를 구성할 수 있다.

### 6.4 팀 실행성

- Executable JAR와 Embedded Tomcat으로 외부 Tomcat Server 등록이 필요 없다.
- Multi-stage Container Build를 사용하면 일반 팀원 Host에 JDK·Maven·IntelliJ가 없어도 된다.
- P1 Python Agent를 Compose Profile로 끌 수 있어 P0 개발 시 자원을 줄일 수 있다.

---

## 7. 단점

### 7.1 AI 생태계 속도 차이

- 새로운 Model Provider 기능과 Agent 연구 도구는 Python에 먼저 나오는 경우가 많다.
- 전자정부가 관리하는 Spring AI 1.0.1은 최신 1.1 계열보다 뒤처져 있다.
- 문서 Loader, 평가 Framework, 실험 Notebook은 Python 생태계가 더 풍부하다.

### 7.2 분산 구조 증가

- Coding Agent에서 Java↔Python 내부 호출이 생긴다.
- Message·Tool Call·Error·Streaming 중립 계약을 별도로 유지해야 한다.
- Trace가 Java와 Python으로 나뉘므로 공통 `traceId`·`jobId`가 필수다.

### 7.3 개발 경험

- Java Cold Build와 Container Image가 Python보다 무겁다.
- Spring Context 기동과 Maven Dependency Download가 느릴 수 있다.
- Container 내부 Java Debug는 JDWP 설정이 필요하며 자동 원클릭을 약속할 수 없다.

### 7.4 이중 Fallback 비용

- Spring P0와 FastAPI P0를 모두 완성형으로 유지하면 Backend 일정과 유지보수 비용이 거의 두 배가 된다.
- Flyway와 Alembic을 같은 DB에 적용할 수 없으므로 데이터 자동 Failover가 아니다.
- 동일 OpenAPI를 두 구현이 계속 맞춰야 한다.
- 따라서 FastAPI Source를 사전에 완성형으로 병행 구현하지 않는다. 기준 문서·공통 Contract·전환 Runbook을 보존하고, Spring No-Go와 사용자 승인 뒤에만 FastAPI 최소 Slice 구현을 시작한다.

---

## 8. 핵심 Risk Register

| ID | 위험 | 수준 | 영향 | 완화·Gate |
|---|---|---:|---|---|
| R-01 | eGov Parent 5.0.0과 Spring AI 1.1.x BOM 충돌 | Critical | Build·Runtime 불안정 | Effective POM, Dependency Tree, Enforcer, Context Test |
| R-02 | Gemini API Key·Tool Calling·Structured Output 차이 | Critical | Provider Registry 요구 실패 | Provider별 Golden Contract Test |
| R-03 | Spring AI·Batch Library의 자동 DDL | Critical | Migration 정책 위반 | 모든 Initialize 비활성화, Flyway 단독 소유 |
| R-04 | LangGraph Checkpointer DDL과 Flyway 충돌 | High | DB 소유권 혼선 | 별도 Checkpoint Database |
| R-05 | Queue Publish와 DB Commit 이중 쓰기 | High | Job 유실·중복 | Transactional Outbox·Idempotency |
| R-06 | LangGraph Interrupt Resume 시 Node 재실행 | High | Tool 중복 실행 | Side Effect 분리·Idempotency Key |
| R-07 | Dynamic Provider Registry 구현 복잡도 | High | CMS Model 전환 실패 | 수동 Model Client Registry Spike |
| R-08 | Java/Python 내부 Contract Drift | High | Coding Job 장애 | OpenAPI·JSON Schema 단일 원본·Contract Test |
| R-09 | Spring AI Structured Output의 비결정성 | High | 잘못된 Spec 저장 | Schema 검증·Repair 1회·실패 격리 |
| R-10 | LangSmith 관측 Parity | Medium | P0 Trace 분석 저하 | Micrometer/OTel → LangSmith 검증 |
| R-11 | 비백엔드 PC 자원 부족 | High | Build·기동 실패 | 2-PC Cold Bootstrap·Resource 측정 |
| R-12 | 회사 Proxy·SSL Inspection·eGov Maven 접근 | Medium | Dependency Download 실패 | Preflight·Mirror/Certificate Runbook |
| R-13 | Docker Desktop 사용 조건·License | Medium | 팀 사용 제한 | 조직 성격·정책 확인 |
| R-14 | 완전한 두 Backend 병행 구현 | Critical | 일정 붕괴 | Spring 우선 검증, No-Go 시에만 FastAPI 최소 Slice 구현 후 하나만 P0 확장 |

### Structured Output 주의

Spring AI의 Structured Output Converter는 LLM 출력 변환을 돕지만 업무 무결성을 보장하는 권한 계층은 아니다. 모든 MenuSpec·PageSpec·ConnectorSpec은 Java JSON Schema·Policy Validation을 통과해야 저장 가능하다.

근거: [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)

### Tool Calling 주의

Spring AI 공식 문서도 Model은 Tool Call을 요청할 뿐 실제 실행은 애플리케이션 책임이라고 설명한다. 따라서 `@Tool`을 붙였다는 이유만으로 자동 실행하지 않고, 후보 Tool Call을 Spring Tool Gateway가 다시 검증한다.

근거: [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)

---

## 9. FastAPI Primary와 비교

| 평가축 | FastAPI + Python Worker | Spring Primary + LangGraph 제한 |
|---|---|---|
| AI 생태계 접근성 | 우세 | 보통 |
| LangGraph 직접 통합 | 우세 | Coding Agent에만 유지 |
| eGovFrame 5.0 활용 | 낮음 | 높음 |
| 사용자 Java 역량 활용 | 낮음 | 높음 |
| P0 Transaction·Approval Debug | Python 학습 필요 | 우세 |
| RAG Batch 재시작 | 별도 구현 필요 | Spring Batch 우세 |
| 단일 Runtime 단순성 | 우세 | Java/Python 계약 증가 |
| 비백엔드 실행 | Compose면 가능 | Compose면 가능 |
| Provider 신기능 속도 | 우세 | Version 추격 필요 |
| 장기 현업 학습효과 | 보통 | 사용자 상황에서 우세 |

현재는 구현 전이고 기존 Source를 버리는 비용이 없다. 사용자의 Java·전자정부 배경, 전자정부 5.0의 공식 Spring AI 지원, P0 AI 기능의 성격을 함께 보면 **FastAPI를 Primary로 고수할 이유보다 Spring Primary로 전환할 이유가 더 크다.**

단, Python 자체를 제거하지 않는다. LangGraph의 Checkpoint·Interrupt·재개는 Coding Agent에 가치가 크고, 이를 Java로 재작성할 실익이 현재 없다.

### 9.1 범용 Python AI Interface 안을 채택하지 않는 이유

모든 AI 요청을 `Python LangGraph Interface`가 먼저 받고 필요할 때 Spring AI를 호출하는 구조도 기술적으로 가능하다. 그러나 이 안은 다음 이유로 채택하지 않는다.

- RAG·Composer·Connector AI를 Debug하려면 다시 Python Graph를 먼저 이해해야 한다.
- Spring AI가 공통 Model Gateway가 아니라 Python 뒤의 보조 SDK가 되어 도입 효용이 낮아진다.
- Provider·Prompt·Retry·Structured Output·Trace 정책이 Python과 Java에 중복된다.
- Python Runtime 장애가 Coding Agent뿐 아니라 P0 제품 AI 장애로 확대된다.
- Spring Transaction·권한·Version과 AI 결과 검증 사이에 원격 호출 경계가 하나 더 생긴다.

따라서 “Interface”의 소유권을 다음처럼 구분한다.

```text
Model Provider Interface     Spring AI Model Gateway
Business/Public Interface    Spring Control Plane
Coding Graph Interface       LangGraph Coding Runtime
Tool/Approval Interface      Spring Tool Gateway
```

Python Runtime은 가치가 없는 잔여 Service가 아니다. P1 Coding Agent의 장시간 상태 Graph·Checkpoint·Interrupt·Resume라는 명확한 단일 책임을 가진다. P0 기반 개발 단계에는 없어도 Core Health가 살아야 하지만, Coding Agent를 구현·사용·통합 Test·최종 시연할 때는 필수 Container다.

### 9.2 무료 Toy 목적 판정

Framework·Runtime License 때문에 Spring 후보안을 중단해야 할 충돌은 확인되지 않았다. eGovFrame·Spring·Spring AI는 Apache-2.0 계열, Temurin 21은 GPLv2 with Classpath Exception으로 무료 사용 가능, LangGraph Core는 MIT, PostgreSQL·pgvector는 허용적 License다.

비용·조건이 남는 지점은 외부 LLM API, Hosted Observability, Model Weight, Cloud, Docker Desktop 조직 사용, Redis 계열 License다. Local 기본 Queue Store는 BSD-3-Clause인 Valkey를 우선 Spike하고, 실패 시 Redis Version·License를 명시적으로 선택한다. 상세 판정은 `AX_Module_Studio_FREE_TOY_LICENSE_AND_COMPATIBILITY_REVIEW_v0.2.md`를 따른다.

---

## 10. 최종 Decision

### 채택

- eGovFrame 5.0 Boot Track
- Spring Boot MVC·Embedded Tomcat
- P0 Spring AI
- RAG Build Spring Batch
- Spring Tool Gateway
- Python LangGraph Coding Runtime
- Core DB Flyway
- 별도 LangGraph Checkpoint DB
- Container 우선 온보딩
- IDE Host Run·Debug를 동등한 Java 개발 경로로 지원
- Git Repository는 Frontend·Spring Backend·Python Orchestrator 3개로 분리
- P0 기본은 Spring API·Batch를 Backend의 한 Application·Container로 실행하고 Flyway만 One-shot 분리
- API·Batch Worker 분리는 응답시간·Resource·독립 Scale·권한 격리 Gate가 실제 발생할 때 적용
- FastAPI 기준선의 별도 보존

### 보류

- Spring AI 1.0.1 또는 1.1.x 최종 선택
- Google 인증을 Gemini Developer API Key 또는 Vertex AI로 확정
- 실제 Backend Module Layout
- Valkey 우선 Queue Store 호환성, 실패 시 Redis Version·License 선택
- Java Trace의 LangSmith OTel Mapping

### 금지

- Spring AI 2.x·Boot 4를 eGovFrame 5.0 기준안에 혼합
- 외부 Tomcat 9·`javax.*` 혼합
- Spring AI PGvector 자동 Schema 생성
- Spring Batch 자동 Metadata Schema 생성
- Flyway와 Alembic의 동일 DB·Volume 공동 소유
- Python LangGraph의 Provider Secret·Git Token·Docker Socket 보유
- LLM Tool Call의 무검증 자동 실행
- Spike 전 기존 FastAPI 문서 삭제

---

## 11. 공식 참고자료

### 전자정부 표준프레임워크

- [5.0 Getting Started](https://www.egovframe.go.kr/docs/5.0/getting-started/)
- [5.0 실행환경](https://www.egovframe.go.kr/docs/5.0/egovframe-runtime/)
- [Spring AI Sample](https://www.egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-sample-project/)
- [Spring AI RAG](https://egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-rag-architecture/)
- [Spring AI ETL](https://egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-etl-guide/)
- [Spring AI 환경 설정](https://egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-setup-guide/)
- [Spring Batch 5.2](https://egovframe.go.kr/docs/5.0/egovframe-runtime/batch-layer/)
- [VS Code 구현 도구](https://www.egovframe.go.kr/docs/5.0/egovframe-development/vscode-implementation-tool/)

### Spring

- [Spring Boot 3.5 Requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring AI Project·Compatibility](https://github.com/spring-projects/spring-ai)
- [Spring AI 1.1 GA](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/)
- [Spring Batch 5.2 Domain](https://docs.spring.io/spring-batch/reference/5.2/domain.html)

### LangGraph

- [Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)
- [Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)
- [Streaming](https://docs.langchain.com/oss/python/langgraph/streaming)
