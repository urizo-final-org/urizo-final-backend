# AX Module Studio 무료 Toy License·Compatibility 심층 점검 v0.2

> 조사 기준일: 2026-08-10  
> 범위: 개인·팀의 비상업 Local Toy Project  
> 판정: **조건부 적합**  
> 주의: 기술적 License 검토 기록이며 법률 자문이 아니다.

---

## 0. 결론

eGovFrame 5.0, Spring Boot, Spring AI, Spring Batch, Eclipse Temurin JDK 21, LangGraph Core, PostgreSQL, pgvector를 사용하는 것 자체는 무료·비상업 Toy 목적과 충돌하지 않는다.

다만 “Open Source Framework 사용료가 0원”과 “프로젝트 총비용이 항상 0원”은 다르다. 다음은 별도 비용 또는 이용조건이 생길 수 있다.

- 외부 LLM·Embedding Provider API
- LangSmith·LangGraph Platform 같은 Hosted Service
- Cloud VM·Registry·Domain
- 선택한 Local Model Weight의 개별 License
- Docker Desktop의 조직·정부기관·대기업 업무 사용
- IntelliJ Ultimate 기능
- Network Download, Disk, RAM, GPU, 전력

현재 Toy 기준에서는 무료 구성으로 시작할 수 있지만, 상용화·납품·회사 업무 사용으로 범위가 바뀌면 다시 검토해야 한다.

---

## 1. Framework·Runtime License Matrix

| 구성요소 | 공식 License·정책 | 무료 Toy 판정 | 적용 조건 |
|---|---|---|---|
| eGovFrame 5.0 Runtime·공식 Template | 공식 GitHub Repository가 Apache-2.0으로 공개 | 적합 | 재배포 시 LICENSE·NOTICE·저작권 고지를 보존한다. “전자정부 공식 인증 제품”이라고 표현하지 않는다. |
| Spring Framework·Boot·AI·Batch·Data | Apache-2.0 | 적합 | Third-party Dependency의 License는 SBOM으로 별도 확인 |
| Eclipse Temurin JDK 21 | GPLv2 with Classpath Exception, Adoptium이 영구 무료 사용 가능하다고 안내 | 적합·권장 | Vendor 혼선을 피하기 위해 Build·Runtime Image와 개발 JDK를 Temurin 21로 고정 |
| LangGraph Open Source Core | MIT | 적합 | LangGraph Platform·Deployment·LangSmith Hosted 상품은 Core License와 별개 |
| PostgreSQL | PostgreSQL License | 적합 | 공식 License·고지 유지 |
| pgvector | PostgreSQL 계열의 허용적 License | 적합 | Version Pin·보안 Update 필요 |
| Valkey | BSD-3-Clause | 적합·Local Queue 후보 | Spring Data Redis는 Valkey를 호환되는 범위에서 best-effort Test한다고 명시하므로 정확한 Version 조합 Spike 필요 |
| Redis 8 | RSALv2·SSPLv1·AGPLv3 중 선택 | Local Toy 사용 가능 | License 선택과 향후 Network 제공·배포 의무가 복잡하다. Permissive-only 정책이면 Valkey를 우선 검증 |
| Docker Desktop | 개인, 교육, 비상업 Open Source, 그리고 기준 이하 소기업은 무료 | 현재 Toy에 적합 | 큰 조직의 업무 사용·정부기관·무료 Tier 초과 상업 사용은 유료 대상. 설치 시 약관 확인 필요 |
| Eclipse IDE | EPL-2.0 기반 Open Source | 적합 | eGov 전용 Eclipse Bundle은 개발 편의 도구이며 Runtime 필수요소가 아님 |
| IntelliJ IDEA | Java/Kotlin 핵심 기능 무료, Ultimate 기능은 Subscription | 무료 기능으로 실행 가능 | Spring 전용 고급 지원이 필요하면 Ultimate 조건 확인. 단순 Java Main Run·Debug에 특별 Plugin은 필수 아님 |

공식 근거:

- [eGovFrame 공식 GitHub](https://github.com/eGovFramework)
- [eGovFrame Simple Backend Template](https://github.com/eGovFramework/egovframe-template-simple-backend)
- [Spring AI Repository·Apache-2.0](https://github.com/spring-projects/spring-ai)
- [Spring Boot 3.5 License](https://github.com/spring-projects/spring-boot/blob/3.5.x/LICENSE.txt)
- [Eclipse Temurin 무료 사용 FAQ](https://adoptium.net/docs/faq)
- [LangGraph Core License](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)
- [PostgreSQL License](https://www.postgresql.org/about/licence/)
- [pgvector License](https://github.com/pgvector/pgvector/blob/master/LICENSE)
- [Valkey BSD-3-Clause](https://github.com/valkey-io/valkey/blob/unstable/COPYING)
- [Redis Version별 License](https://redis.io/legal/licenses/)
- [Docker Desktop License](https://docs.docker.com/subscription/desktop-license/)
- [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/)
- [IntelliJ Unified Product](https://www.jetbrains.com/help/idea/intellij-idea-single-distribution.html)

---

## 2. eGovFrame·Spring AI·JDK 호환성

### 2.1 고정 가능한 공통 축

전자정부 5.0 공식 기준은 Spring Framework 6.2.11, Runtime JDK 17 이상, 개발환경 JDK 21이다. Spring Boot 경로는 Servlet 6.0 이상이다. 공식 Simple Backend Template은 Spring Boot 3.5.6·Servlet 6.0을 사용한다.

따라서 후보 기준은 다음처럼 고정한다.

```text
JDK              Eclipse Temurin 21 LTS
eGov Parent      egovframe-boot-starter-parent 5.0.0
Spring Boot      Parent가 관리하는 3.5.6 기준
Servlet          6.0 / Jakarta namespace
Packaging        Executable JAR / Embedded Tomcat 10.1 계열
금지             javax.*, 외부 Tomcat 9, Boot 4 혼합
```

근거:

- [eGovFrame 5.0 Getting Started](https://www.egovframe.go.kr/docs/5.0/getting-started/)
- [eGovFrame Simple Backend](https://github.com/eGovFramework/egovframe-template-simple-backend)
- [Spring Boot 3.5 System Requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)

### 2.2 Spring AI Version은 아직 두 Lane이다

전자정부 5.0 공식 Spring AI RAG Sample은 `egovframe-boot-starter-parent 5.0.0`이 관리하는 Spring AI 1.0.1, Ollama, ONNX Embedding을 사용한다. Spring AI 공식 호환표는 1.1.x가 Spring Boot 3.5.x용이라고 안내한다.

```text
Official Control Lane
  eGov 5.0.0 + Boot 3.5.6 + Spring AI 1.0.1

Product Candidate Lane
  eGov 5.0.0 + Boot 3.5.6 + 검증 시점의 Spring AI 1.1.x Patch
```

- `1.0.1`은 전자정부 Parent가 직접 관리하는 가장 보수적인 기준이다.
- `1.1.x`는 Boot 3.5 공식 호환선이지만 전자정부 Parent 관리 범위를 벗어날 수 있으므로 Effective POM·Dependency Convergence·Provider 기능 Spike가 필요하다.
- Spring AI 2.x는 Boot 4.x 선이므로 eGovFrame 5.0 기준안에 혼합하지 않는다.

근거:

- [eGovFrame 5.0 Spring AI Sample](https://www.egovframe.go.kr/docs/5.0/egovframe-runtime/ai-layer/springai-layer/springai-sample-project/)
- [Spring AI Boot Compatibility](https://github.com/spring-projects/spring-ai)

---

## 3. 비용 0원에 가까운 Local Profile

### 3.1 Framework Profile

```text
JDK              Temurin 21
App              eGovFrame 5.0 + Boot 3.5.6 + Spring AI 검증 Version
DB / Vector      PostgreSQL + pgvector
Queue / Lock     Valkey 후보, 호환 Spike 실패 시 Redis License 선택 재검토
Chat Model       Local Ollama Adapter 후보
Embedding        Local ONNX Transformer 후보
Observability    Actuator + Micrometer + Local OTel, LangSmith Off
Coding Agent     기본 Off, P1에서 LangGraph Core만 On
```

전자정부 공식 Spring AI Sample도 Local Ollama와 ONNX Embedding 조합을 제시하므로 Local AI 경로 자체는 공식 가이드와 정합적이다. 다만 후보 프로젝트의 정확한 Model, Tokenizer, RAM·GPU 요구량과 Model License는 별도 결정이다.

### 3.2 무료를 보장하지 않는 선택

- OpenAI·Anthropic·Google 같은 외부 Provider는 Key·Quota·가격정책이 바뀔 수 있다.
- LangSmith는 선택형 Exporter로만 두고 미설정이어도 P0가 성공해야 한다.
- Ollama Application이 무료여도 내려받는 각 Model Weight License가 자동으로 동일해지는 것은 아니다.
- Public Docker Image Pull에는 Rate Limit이 있을 수 있고 Private Registry는 로그인·요금이 필요할 수 있다.

### 3.3 Model License Gate

Model을 등록할 때 다음 Metadata 없이는 Active로 전환하지 않는다.

- Model ID·정확한 Version 또는 Digest
- Source URL
- License ID·License URL
- Commercial Use 허용 여부
- Redistribution 허용 여부
- 입력 데이터·출력물 정책
- 최소 RAM·VRAM·Disk
- 검토자와 검토일

---

## 4. 충돌·주의 지점

### 4.1 현재 Toy 목적과 기존 사업 기획

기존 Spec의 구매·납품·구독형 가치는 미래 제품 가설로 보존한다. 현재 License 판정은 개인·팀의 비상업 Local Toy에 한정된다. 나중에 유료 서비스, 회사 업무, 공공기관 납품, Managed Service로 바뀌면 Docker Desktop, Redis/Valkey, Model, Provider, Cloud, Font·UI Asset까지 다시 검토한다.

### 4.2 전자정부 “사용”과 “인증”은 다르다

eGovFrame Open Source Runtime을 사용하는 것은 가능하지만, 이것만으로 표준프레임워크 호환성 확인·공공기관 납품 적합성·보안 인증을 받은 것은 아니다. README·발표에서는 “eGovFrame 5.0 기반/활용”이라고 표현하고 공식 인증을 암시하지 않는다.

### 4.3 Redis 계열 License

Redis 8은 AGPLv3 선택지가 있어 Open Source로 사용할 수 있지만 강한 Copyleft와 Network 제공 시 의무 검토가 필요하다. 이 프로젝트는 Queue·Event·Lock에 독점 Redis 기능을 요구하지 않으므로 `QueueStore` Port를 두고 Local 기본 후보를 Valkey로 검증한다. Spring Data Redis 공식 Repository는 Valkey를 best-effort로 Test한다고 명시한다.

근거:

- [Spring Data Redis·Valkey Compatibility](https://github.com/spring-projects/spring-data-redis)
- [Valkey License](https://github.com/valkey-io/valkey/blob/unstable/COPYING)
- [Redis License](https://redis.io/legal/licenses/)

### 4.4 Open Source Dependency는 계속 변한다

Framework License가 적합해도 Transitive Dependency·Container Base Image·Frontend Package License가 다를 수 있다. 구현 후 다음을 CI Gate로 둔다.

- Maven·Python·Node Dependency Lock
- SBOM 생성
- License Allowlist와 금지 License Review
- Container Base Image Digest Pin
- Model Artifact Manifest
- Release 전 Third-party Notice 생성

---

## 5. 최종 판정 Gate

다음이 충족되면 무료 Toy 목적에 적합하다고 확정한다.

1. Temurin 21·eGov 5.0·Boot 3.5.6·선택 Spring AI Version의 실제 Build 통과
2. Local Provider Profile에서 유료 API Key 없이 Base Health와 최소 RAG Slice 통과
3. QueueStore의 Valkey 호환 Test 통과 또는 Redis License 선택 문서화
4. LangSmith·Cloud·Private Registry 없이 Local Compose 통과
5. 모든 Model Weight의 License Manifest 작성
6. SBOM·Third-party License Report에 미승인 License 0건
7. Docker Desktop 사용자가 개인·교육·비상업·무료 소기업 범위인지 확인

현재 판정은 **구조상 가능, 구현 검증 전 Conditional Go**다. License 때문에 Spring 전환을 포기해야 할 충돌은 발견하지 못했지만, 외부 AI API와 Model Weight까지 무조건 무료라고 가정해서는 안 된다.
