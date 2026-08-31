# I5 공개 챗봇 질의 API 설계

> 작성 2026-08-31 · 담당 민은지(`emilyjjang-jpg`) · **설계 승인 완료, 구현 완료(컴파일·단위 테스트까지)**
> Work ID `AI02-002` · slug `axms-ai02-002-public-chat-api`
> Branch `feature/emilyjjang-jpg_axms-ai02-002-public-chat-api_v0.1`
> Base `origin/dev` **0e0b6c4** (PR #33 머지 반영). 저장소 `urizo-final-backend` 단독

## 0. 승인된 결정

| 항목 | 결정 |
|---|---|
| 공개 chatbot 지정 | 설정 고정 UUID `AXMS_PUBLIC_CHATBOT_ID` |
| Rate limit | 컨트롤러 진입점 체크. 신규 Filter 없음. 60초/30회 |
| Rate limit 호출자 식별 | `X-Real-IP` 우선, 없으면 소켓 주소. **전역 forward-headers-strategy는 쓰지 않음** |
| 임베딩 단건 질의 타임아웃 | `query-request-timeout` 신설, 기본 **15s**. 이 브랜치에 포함 |
| Work ID | `AI02-002` |

## 1. 조건 8개 최종 판정

| # | 조건 | 결과 |
|---|---|---|
| 1 | 명시적 Public API | 신규 `PublicChatController`. 기존 관리자 규칙 무변경 |
| 2 | Public chatbot 식별자 | 설정 고정 UUID. 미설정이면 404 |
| 3 | 활성 Version 강제 | 기존 `RagStore.query`로 충족. 신규 코드 0줄 |
| 4 | 요청 크기 제한 | 상속 안 됨 → 공개 DTO에 `@Size(max = 4000)` 재선언 |
| 5 | Rate limit | in-memory 고정 윈도우 60초/30회, `X-Real-IP` 기준 |
| 6 | Timeout | 단건 질의 전용 15s 분리. §4.6 |
| 7 | CORS | 불필요 확정. nginx same-origin 프록시 |
| 8 | 관리자 데이터 비노출 | 전용 응답 계약 + 회귀 테스트 |

---

## 2. 엔드포인트 계약

```
POST /api/public/chat/query
Content-Type: application/json
인증 없음 · Idempotency-Key 요구하지 않음
```

**요청**

```json
{ "query": "속초 해수욕장 알려줘", "conversationId": "(선택) 이전 응답의 값" }
```

`query` 필수·공백 불가·최대 4000자. `schemaVersion`·`topK`는 받지 않는다.

**응답 200**

```json
{
  "schemaVersion": "1.0",
  "traceId": "...",
  "conversationId": "...",
  "outcome": "ANSWERED",
  "answer": "...",
  "citations": [ { "title": "...", "excerpt": "..." } ],
  "generatedAt": "2026-08-31T12:00:00Z"
}
```

**오류** — 기존 `ErrorEnvelope` 그대로

| HTTP | code | 조건 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `query` 누락·공백·4000자 초과 |
| 404 | `RESOURCE_NOT_FOUND` | 공개 chatbot 미설정, 또는 해당 chatbot 없음/`DISABLED` |
| 409 | `KNOWLEDGE_VERSION_NOT_ACTIVE` | 활성 Knowledge Version 없음 |
| 429 | `RATE_LIMITED` | 한도 초과. `retryAfterMs: 60000` |
| 503 | `SERVICE_NOT_READY` | DB 접근 실패 |

429·409 매핑은 `ProductApiExceptionHandler.publicCode()`에 이미 있어 신규 매핑 코드를 넣지 않았다.

---

## 3. 호출 경로

```
POST /api/public/chat/query
  -> nginx (same-origin, proxy_read_timeout 60s, X-Real-IP 주입)
  -> TraceIdFilter (기존, 전역)
  -> SecurityFilterChain: permitAll (1줄 추가)
  -> PublicChatController
       1. rateLimiter.check(request)   -> 초과 시 429
       2. 설정 chatbotId 확인           -> 미설정 시 404
       3. rag.publicQuery(...)         -> 멱등 래퍼 없음
       4. RagStore.query(...)          -> 기존 무변경
          └ EmbeddingClient /embed/query (connect 5s, request 15s)
       5. toPublic(...) 축소 매핑
```

관리자 경로 `POST /api/chatbots/{id}/query`는 라우팅·응답 계약 모두 그대로다. 임베딩 타임아웃 분리만 공유한다(§4.6).

---

## 4. 조건별 구현

### 4.1 명시적 Public API

`PublicChatController`를 `knowledge.controller` 패키지에 두어 기존 `@RestControllerAdvice(basePackageClasses = ProductApiController.class)`의 오류 정규화를 그대로 받는다. `@Profile("local-full")`로 기존 컨트롤러와 활성 조건을 맞췄다. 기존 `requestMatchers` 규칙은 한 줄도 수정하지 않고 `/api/public/**` POST permitAll 1줄만 `anyRequest()` 앞에 추가했다.

### 4.2 공개 chatbot 식별자 — 설정 고정 UUID

`ax.knowledge.public-chatbot-id`(env `AXMS_PUBLIC_CHATBOT_ID`). 비어 있으면 항상 404다.

1. Flyway·관리자 UI·신규 상태값 없이 "어느 chatbot이 공개인가"를 명시한다.
2. 경로에 chatbot UUID가 없어 익명 호출자가 내부 식별자를 알 수 없다 → 조건 8에도 유리하다.
3. DB Volume이 보존되므로 시연 데이터 1회 생성 후 값이 고정된다. 값 변경 시 `spring-core` 재기동 1회가 필요하며 이것이 유일한 비용이다.
4. 형식이 잘못된 값이면 `UUID.fromString`이 기동 시점에 실패한다. 설정 오류를 런타임까지 끌고 가지 않는다.
5. 승격 경로: 관리자가 화면에서 공개 대상을 바꿔야 하면 그때 `chatbot_config.is_public` 플래그로 간다.

`chatbot_config.status = 'ACTIVE'` 조건이 기존 `RagStore.query` SQL에 이미 있어, 관리자가 `DISABLED`로 바꾸면 공개 질의도 즉시 404가 된다.

### 4.3 활성 Knowledge Version 강제 — 기존 코드로 충족

`RagStore.query`의 첫 SQL이 `knowledge_base.active_version_id`만 읽고 `null`이면 `ACTIVE_KNOWLEDGE_REQUIRED`(409)로 끊는다. 검색 SQL도 그 버전으로 고정된다.

`active_version_id`에 값을 넣는 곳은 `KnowledgeStore.activateKnowledgeVersion`(`APPROVAL_PENDING`/`ACTIVE`만 허용)과 `rollbackKnowledgeVersion`(`ARCHIVED`/`ACTIVE`만 허용) 두 곳뿐이다. **빌드 중·비활성 Version은 구조적으로 활성값이 될 수 없다.** 공개 경로용 추가 검증을 넣지 않았다 — 같은 검증이 두 곳에 있으면 한 곳이 뒤처진다.

### 4.4 요청 크기 제한

`@Size(max = 4000)`은 `RagQueryRequest`에 붙은 것이라 상속되지 않는다. `PublicChatQueryRequest`에 재선언하고 컨트롤러 파라미터에 `@Valid`를 붙였다. 위반 시 기존 핸들러가 400 `VALIDATION_FAILED`로 매핑한다.

한계: `@Size`는 Jackson 파싱 이후 동작하므로 거대 본문 자체는 먼저 파싱된다. 로컬 시연 범위에서는 rate limit으로 충분하다고 보고 본문 바이트 상한은 두지 않았다.

### 4.5 Rate limit

- **60초 고정 윈도우 30회.** 질의 1건이 임베딩 호출 1회 + 검색 1회를 유발하므로 0.5 rps면 시연에 충분하고 반복 호출은 막는다.
- 윈도우가 넘어가면 `ConcurrentHashMap`을 통째로 교체한다. 만료 스캔·정리 스케줄러가 없어 항목이 쌓이지 않는다.
- 초과 시 `ProductApiException(..., TOO_MANY_REQUESTS, retryable = true, retryAfterMs = 60000)`. 기존 핸들러가 `RATE_LIMITED` + `ErrorEnvelope`를 만든다. 신규 Filter·등록·JSON 직렬화 코드 없음.
- 신규 의존성 없음.

**호출자 식별 — `X-Real-IP` 우선, 범위 한정**

nginx가 `X-Real-IP $remote_addr`로 실제 peer 주소를 덮어쓰므로 이 값을 우선 사용하고, 없으면 소켓 주소로 되돌아간다.

- `X-Forwarded-For`는 쓰지 않는다. nginx의 `$proxy_add_x_forwarded_for`는 클라이언트가 보낸 값 **뒤에** 실제 주소를 덧붙이므로 앞부분을 위조할 수 있다.
- 전역 `forward-headers-strategy`는 켜지 않는다. 그러면 관리자 인증과 로깅까지 헤더를 신뢰하게 된다. 헤더 신뢰를 `PublicChatRateLimiter.caller()` 안으로만 묶었다.
- 이 신뢰는 **spring-app 포트를 발행하지 않는다**(nginx만 `127.0.0.1:18080:80`)는 전제 위에 있다. 포트를 열거나 프록시를 바꾸면 전제부터 다시 본다. 코드에 `ponytail:` 주석으로 남겼다.
- 이 선택이 없으면 모든 방문자가 한 버킷을 공유해, 발표 중 여러 명이 동시에 만지면 다 같이 30회/분을 나눠 쓰게 된다.

### 4.6 Timeout — 단건 질의 15s 분리

`EmbeddingClient.send()`가 `/embed/query`와 `/embed/batch`에 같은 `request-timeout: 180s`를 썼다. 180s는 48건 묶음 기준값이고 단건 질의에 맞았던 적이 없다. 경로별로 나눈다.

| 설정 | 값 | 적용 |
|---|---|---|
| `connect-timeout` | 5s | `HttpClient` |
| `request-timeout` | 180s | `/embed/batch` 등 |
| **`query-request-timeout`** | **15s** | **`/embed/query`** |

**15s 근거**

1. 실측 단건 임베딩 107~233ms 대비 약 64배 여유다. GC·순간 지연은 흡수하고, 그 이상 걸리면 대기가 아니라 장애다.
2. **nginx `proxy_read_timeout`이 60s다.** 60s를 넘기면 nginx가 먼저 504를 내보내 `ErrorEnvelope` 형태가 깨진다. 15s는 그 안에서 끊어 익명 호출자에게 정상 오류 응답이 나가게 한다.
3. `connect-timeout: 5s`가 앞단이라 최악 대기는 약 20s로 묶인다.
4. 30s는 15s가 못 잡는 실패를 추가로 잡아 주지 않으면서 익명 노출 시간만 2배로 늘린다.
5. 설정값이므로 시연 환경이 느리면 코드 변경 없이 올릴 수 있다.

구현은 `send()`에서 경로 비교 1줄이다. 결과적으로 **관리자 질의 경로도 함께 정상화된다** — 관리자 화면의 단건 질의 역시 더 이상 180s를 기다리지 않는다.

### 4.7 CORS — 불필요 확정

- 코드·설정 전체에 CORS 항목이 없다(`grep` 0건).
- 실제 로컬 배포는 nginx가 정적 프런트와 `/api/`를 한 오리진에서 서빙한다(`location /api/ { proxy_pass http://spring-app:8080; }`). 브라우저가 보는 URL은 `http://127.0.0.1:18080/api/public/chat/query` — **same-origin이라 preflight 자체가 없다.** Vite dev server도 `changeOrigin: false`로 같은 성질이다.
- 필요해지는 조건은 하나뿐이다: 관광 포털이 프런트와 **다른 Origin**에서 서빙될 때. 그때 `/api/public/**`에만 한정한 설정을 별도 작업으로 연다.

### 4.8 관리자 데이터 비노출 — 필드 단위

`RagQueryResponse`를 재사용하지 않고 `PublicChatContract`로 축소했다.

| 원본 필드 | 공개 | 근거 |
|---|---|---|
| `schemaVersion` | O | 계약 버전 |
| `traceId` | O | 기존 `ErrorEnvelope`가 이미 비인증 응답에 포함. 문의 대응에 필요 |
| `queryId` | **X** | 내부 질의 식별자. 클라이언트가 쓸 곳 없음 |
| `conversationId` | O | 대화 연속성 |
| `outcome` | O | UI 분기 |
| `answer` | O | 본문 |
| `citations[].documentId` | **X** | 원천 데이터 내부 ID. 화면 미사용 |
| `citations[].title` | O | 출처 표시 |
| `citations[].sourceUrl` | **X** | 아래 |
| `citations[].excerpt` | O | 근거 발췌. 공개 관광 데이터 |
| `citations[].score` | **X** | 내부 벡터 유사도. 검색 튜닝 정보 노출 |
| `knowledgeVersionId` | **X** | 관리자 Version 관리 내부 UUID |
| `generatedAt` | O | 응답 시각 |

**`sourceUrl` 제외**: 현재 값은 원본 홈페이지 URL이 아니라 `TourismSampleDocumentLoader`가 만드는 합성 URL(`https://api-test.local/documents/...`)이다. 원본 홈페이지 URL이 `http://` 혼재라 `source_document`의 `^https://` CHECK를 통과하지 못해 합성한 값이라 공개 화면에서 죽은 링크가 된다. 보안 유출은 아니지만 내보낼 이유가 없다. 원본 URL은 본문 `[홈페이지]` 줄에 남아 `excerpt`로 전달된다. 커넥터가 실제 원천을 조회하게 되면 그때 다시 넣는다.

**요청 방향 축소**: `topK`를 받지 않는다. 익명 호출자가 `topK=20`으로 DB 작업량을 키울 수 없다. 서버가 기존 기본값(10)을 쓴다.

**회귀 방지**: `PublicChatResponseBoundaryTest`가 record 구성 자체를 검사해 `queryId`·`knowledgeVersionId`·`documentId`·`sourceUrl`·`score`가 공개 계약에 다시 들어오면 실패한다.

---

## 5. 변경 파일

| 파일 | 변경 |
|---|---|
| `knowledge/dto/PublicChatContract.java` | 신규 — 공개 요청·응답·인용 계약 |
| `knowledge/security/PublicChatRateLimiter.java` | 신규 — 고정 윈도우 한도, `X-Real-IP` 우선 식별 |
| `knowledge/controller/PublicChatController.java` | 신규 — 엔드포인트, 한도 확인, 설정 chatbot 확인, 축소 매핑 |
| `knowledge/service/RagOperations.java` | `publicQuery(...)` 선언 |
| `knowledge/service/ProductService.java` | `publicQuery(...)` 구현 (멱등 래퍼 없이 `store.query` 직접 호출) |
| `knowledge/config/EmbeddingProperties.java` | `queryRequestTimeout` 추가 |
| `knowledge/integration/EmbeddingClient.java` | `/embed/query` 경로 전용 타임아웃 분기 |
| `auth/security/SecurityConfig.java` | `/api/public/**` POST permitAll 1줄 |
| `resources/application-local-full.yml` | `public-chatbot-id`, `query-request-timeout` |
| `test/.../PublicChatRateLimiterTest.java` | 신규 (5건) |
| `test/.../PublicChatResponseBoundaryTest.java` | 신규 (3건) |

**설계 대비 줄인 것**

- `PublicChatProperties` 신규 파일 대신 `@Value` 1개. 설정값이 스칼라 하나라 전용 record와 등록이 불필요하다.
- `EmbeddingException → 503` 매핑을 넣지 않았다. 현재 `RagStore.query`는 예외를 그대로 올리고, 익명 경로에서 15s로 끊기면 500이 나간다. 오류 본문 형태 정리는 별도 작업으로 둔다(§7-3).

**변경하지 않은 것**: `RagStore`, `KnowledgeStore`, `ProductStore`, 기존 관리자 `requestMatchers` 규칙, Flyway 마이그레이션, Compose, `pom.xml`, 프런트엔드.

**멱등 처리 제외 근거**: 관리자 `query`는 `store.idempotent("QUERY_CHATBOT", key, ...)`로 `app.product_idempotency_command`에 행을 남긴다. 공개 트래픽을 태우면 (a) 익명 브라우저에 `Idempotency-Key` 헤더를 요구해야 하고 (b) 호출마다 테이블이 무한히 커진다. 공개 경로는 읽기 전용 조회라 멱등 보장이 필요 없다.

---

## 6. 검증 결과

| 항목 | 명령 | 결과 |
|---|---|---|
| 전체 컴파일 | `mvnw -o -B test-compile` | BUILD SUCCESS |
| 공개 API 단위 테스트 | `-Dtest='PublicChatRateLimiterTest,PublicChatResponseBoundaryTest'` | Tests run 8, Failures 0, Errors 0 |
| 임베딩 설정 영향 확인 | `-Dtest='ProductJobRecoveryTest'` | Tests run 3, Failures 0, Errors 0 |

**재빌드 후 확인 항목**

| # | 항목 |
|---|---|
| 1 | 인증 없이 `POST /api/public/chat/query` → 200 `ANSWERED` |
| 2 | `/api/cms/**` 401 유지 (기존 규칙 무영향) |
| 3 | 응답 본문에 `knowledgeVersionId`·`score`·`sourceUrl`·`queryId`·`documentId` 부재 |
| 4 | 31회째 → 429 `RATE_LIMITED`, `retryAfterMs` 60000 |
| 5 | 4001자 → 400 `VALIDATION_FAILED` |
| 6 | `AXMS_PUBLIC_CHATBOT_ID` 미설정 → 404 |
| 7 | 활성 Version 없는 KB 지정 → 409 `KNOWLEDGE_VERSION_NOT_ACTIVE` |
| 8 | 관리자 단건 질의 정상 동작(타임아웃 분리 회귀 없음) |

**선행 조건**: 재빌드 전에 §7-1 Flyway 이력 정리, 재빌드 후 지식 재빌드(마이그레이션이 임베딩을 NULL로 만든다), 그리고 chatbot 생성 후 그 UUID를 `AXMS_PUBLIC_CHATBOT_ID`에 넣고 `spring-core` 1회 재기동.

---

## 7. 남은 사항

**7-1. Flyway 이력 정리 (재빌드 선행 조건, 승인됨·미실행)**

`flyway_schema_history`에 `20260830063920634`("widen document chunk embedding to 1024", success=t)가 남아 있으나 해당 파일은 `36a43dd`에서 `20260831030833217`로 재발급되어 어느 브랜치에도 없다. `pom.xml`에 `ignoreMigrationPatterns`·`validateOnMigrate=false`가 없고 `spring-app`이 `flyway-migration: service_completed_successfully`에 의존하므로, 재빌드 시 migrate가 실패해 기동이 막힐 것으로 본다(설정 기반 예측, 미관측).

- 채택: `flyway:repair`로 옛 행 1개 정리. 원인이 확인된 재발급이고 사람이 승인했다.
- 불채택: `ignoreMigrationPatterns` — `pom.xml`은 팀 공용이라 문제를 팀 전체에서 가린다.
- 실패 시: 멈추고 보고. DB 볼륨 삭제 후 fresh 재구축 전환 여부는 사람이 판단한다(격리 검증에서 fresh 23/23 확인됨).

**7-2. Master 추적표** — `urizo-final-master/docs/product/ai-core/02_DOMAIN_RAG_REPLACEMENT.md` 하위 작업 표에 `AI02-002` 행 추가. Master 저장소 별도 브랜치·PR.

**7-3. 임베딩 실패 응답 형태** — `EmbeddingException`이 예외 핸들러에 없어 500 + 기본 본문이 나간다. 공개 경로에서도 `ErrorEnvelope`가 되도록 매핑 1건을 추가하는 별도 작업. 이번 범위에 넣지 않았다.

---

## 8. PR 본문 초안

```markdown
결과:
관리자 세션 없이 호출하는 공개 챗봇 질의 API를 추가했다.

변경:
- POST /api/public/chat/query 신설. 공개 대상 chatbot은 설정(AXMS_PUBLIC_CHATBOT_ID)으로 지정한다.
- 공개 전용 응답 계약으로 관리자 값(knowledgeVersionId, score, queryId, documentId, 합성 sourceUrl)을 제외했다.
- 익명 호출 한도 60초/30회를 컨트롤러 진입점에서 확인한다. 신규 의존성·Filter 없음.
- 임베딩 /embed/query 전용 타임아웃 15s를 분리했다. 배치용 180s를 단건 질의가 공유하던 문제를
  고친 것이며, 공개 경로의 익명 노출 시간 제한이 직접 근거다.
  **관리자 질의 경로도 함께 정상화된다** — 관리자 단건 질의 역시 더 이상 180s를 기다리지 않는다.

검증:
- mvnw -o -B test-compile : BUILD SUCCESS
- PublicChatRateLimiterTest, PublicChatResponseBoundaryTest : 8건 통과
- ProductJobRecoveryTest : 3건 통과 (임베딩 설정 변경 영향 확인)

연결·영향:
- Work ID AI02-002 / PR #33(AI02-001) 머지 이후 dev 0e0b6c4 기준
- 기존 관리자 requestMatchers 규칙, Flyway, Compose, pom.xml 무변경
- 재빌드 전 flyway_schema_history의 20260830063920634 행 정리 필요

확인:
- [ ] 인증 없이 200, /api/cms/** 401 유지
- [ ] 응답 본문에 관리자 필드 부재
- [ ] 31회째 429, 4001자 400
- [ ] 관리자 단건 질의 회귀 없음
```
