# RAG 로컬 실행 가이드

이 저장소를 처음 받은 팀원이 관광·중기부 RAG(통합검색·챗봇)를 자기 PC에서 돌리기까지
필요한 것을 모은다. CMS 일반 기능은 이 문서 없이도 기동된다 — **RAG만 추가 준비물이 있다.**

> **실제 키 값은 이 문서에 없고, 앞으로도 넣지 않는다.** 변수명·파일명·입력 위치만 적는다.

## 사람이 직접 해야 하는 것

나머지 설치·실행은 이 문서만 있으면 그대로 따라갈 수 있다. 다만 아래 넷은 **자동화할 수
없다** — 외부 기관 가입·발급이거나 설치 권한이 필요하다.

| 사람이 할 일 | 왜 |
|---|---|
| 공공데이터포털 가입 후 관광·중기부 API 활용신청 | 기관 승인이 필요하다. 즉시 발급이 아닌 건도 있다 |
| Anthropic 키 발급 | 결제 수단이 걸린 계정 작업이다 (LLM 답변을 켤 때만) |
| Docker Desktop 설치·실행 | 관리자 권한이 필요하다 |
| Python 3.11 설치 | 설치 권한이 필요하다 |

이 넷을 마친 뒤에는 발급받은 키를 **파일에 넣고**(3장) **화면에 등록**(4장)하는 것까지가
사람 몫이고, 그 외에는 명령을 그대로 실행하면 된다.

## 준비물 한눈에

| 필요한 것 | 이름 | 쓰이는 기능 | Compose가 해주나 |
|---|---|---|---|
| PostgreSQL | — | 전부 | ✅ **해준다** |
| bge-m3 임베딩 서버 | `AXMS_EMBEDDING_BASE_URL` | **RAG 전부** (빌드·검색) | ❌ 호스트에서 직접 |
| 공공데이터포털 관광 키 | `.local/secrets/connector_tour_api` | 관광 수집 | ❌ 파일에 직접 |
| 공공데이터포털 중기부 키 | `.local/secrets/connector_sme_support_api` | 중기부 수집 | ❌ 파일에 직접 |
| Anthropic 키 | 환경변수 아님 — 관리 화면에서 등록 | LLM 산문 답변 (선택) | ❌ 화면에서 |

**PostgreSQL·Valkey·Spring·Frontend·nginx는 Compose가 전부 띄운다.** 따로 설치할 것은
임베딩 서버 하나뿐이다.

## 0. 무엇이 없어도 되는지 먼저

RAG 관련 LLM 스위치는 **전부 기본 꺼짐**이고, 꺼도 RAG가 돈다.

| 스위치 | 기본 | 끄면 |
|---|---|---|
| `AXMS_PUBLIC_CHAT_LLM` | `false` | 추출식 답변 (LLM 없이 동작) |
| `AXMS_CHUNKING_LLM` | `false` | 문서당 1청크 |
| `AXMS_EVAL_LLM` | `false` | 제목 자가검색 평가 |
| `AXMS_SOURCE_MONITOR` | `false` | 원천 변경 감지 안 함 |

즉 **Anthropic 키가 없어도 RAG 빌드·검색·챗봇이 전부 동작한다.** 키는 답변을 LLM 산문으로
다시 쓸 때만 필요하다.

커넥터 키도 기동을 막지 않는다 — `initialize-dev-secrets.ps1`이 빈 자리표시자를 만들고,
수집을 실제로 돌릴 때만 `Connector secret is empty`로 거절한다.

## 1. 임베딩 서버 (필수)

Python **3.11**이 필요하다. 설치·실행은 [`../embedding-server/README.md`](../embedding-server/README.md)에 있다.
요약하면 모델 2.3 GB를 고정 revision으로 받고, `embedding-server/`에서 다음을 띄운다.

```bash
venv/Scripts/python.exe -m uvicorn serve.embed_api:app --host 0.0.0.0 --port 8900 --workers 1
```

Backend는 기본값 `http://host.docker.internal:8900`으로 이 서버를 찾는다. 포트를 바꿨다면
`.env`에 `AXMS_EMBEDDING_BASE_URL`을 적는다.

> 이 서버가 죽어 있으면 검색 질의가 전부 `SERVICE_NOT_READY`로 떨어진다. Backend가 원인을
> 감추므로 먼저 `curl http://localhost:8900/health`를 본다.

## 2. 스택 기동

```powershell
.\scripts\bootstrap-dev.ps1 -Profile full
.\scripts\health.ps1 -Profile full
```

`bootstrap-dev.ps1`이 `initialize-dev-secrets.ps1`을 불러 `.local/secrets/`를 채운다.
DB 비밀번호·마스터키 같은 자체 생성 값은 여기서 자동으로 만들어진다.

접속 주소는 `http://127.0.0.1:18080/`이다. 데모 관리자 계정은 `compose.dev.yaml`에 있다.

이미 기동한 적이 있다면:

```powershell
..\urizo-final-master\scripts\start-local-cms.ps1 -Profile full -ApproveLocalMutation
```

## 3. 원천 API 키 (수집을 돌릴 때)

공공데이터포털에서 발급받은 키를 **파일 내용으로** 넣는다. 따옴표·줄바꿈 없이 값만 쓴다.

```
.local/secrets/connector_tour_api            ← 한국관광공사 TourAPI
.local/secrets/connector_sme_support_api     ← 중소벤처기업부 지원사업 API
```

두 파일은 `initialize-dev-secrets.ps1`이 **빈 파일로 미리 만들어 둔다.** 이미 값이 들어
있으면 다시 실행해도 덮어쓰지 않는다.

커넥터 설정에는 키가 아니라 `cms-secret://tour-api` 같은 **참조만** 저장된다. 참조 이름
`<name>`은 `connector_<name>` 파일로 해석되며, `-`는 `_`로 바뀐다.

> 이 파일들은 `.gitignore`에 걸려 있다. **커밋되지 않는다.**

## 4. Anthropic 키 (LLM 답변을 켤 때만)

환경변수가 아니라 **관리 화면에서 등록**한다.

1. `http://127.0.0.1:18080/admin/models` — 에이전트 설정 (**최고 관리자만** 접근 가능)
2. ANTHROPIC provider에 키를 등록
3. 연결 테스트로 확인

등록한 값은 `app.coding_service_credential`에 **AES 암호화되어 저장**되고, 복호화 키는
`.local/secrets/cms_master_key`다.

**이 키는 PC 밖으로 나가지 않는다.** DB가 PC마다 독립된 컨테이너이고(`database:5432`,
볼륨 `axms-spring-dev-core-db`), 마스터키도 PC마다 다르다. 그래서 **팀원끼리 공유할 수 없고,
공유할 필요도 없다** — 각자 자기 키를 등록한다.

> ⚠ 이 테이블은 LangGraph/코딩 기능과 **공유**한다. 화면에 이미 등록돼 보이는 ANTHROPIC
> 키가 다른 용도로 넣어 둔 것일 수 있으니 덮어쓰기 전에 확인한다.

## 5. RAG 기능 스위치

`.env`(저장소 루트, gitignore됨)에 필요한 것만 적는다. 전부 선택이다.

```
AXMS_PUBLIC_CHAT_LLM=true          # 챗봇·검색 요약을 LLM 산문으로
AXMS_CHUNKING_LLM=true             # 빌드에서 LLM이 청킹 규칙 결정
AXMS_EVAL_LLM=true                 # 골든 질문 평가셋 생성
AXMS_SOURCE_MONITOR=true           # 원천 변경 주기 감지 (감지만, 자동 빌드 없음)
AXMS_PUBLIC_CHATBOT_ID=<uuid>      # 공개 챗봇 API. 비면 /api/public/chat/query가 항상 404
```

`AXMS_PUBLIC_CHATBOT_ID`는 **RAG를 한 번 빌드한 뒤** 생성된 챗봇 UUID를 넣고 재기동한다.
비어 있으면 포털 챗봇이 404를 받는다.

## 6. 전체 실행 순서

```
1. 임베딩 서버를 띄운다                         (embedding-server/README.md)
   └ curl http://localhost:8900/health → model_loaded:true 확인

2. .\scripts\bootstrap-dev.ps1 -Profile full
   └ .\scripts\health.ps1 -Profile full 통과 확인

3. (수집하려면) .local/secrets/connector_*에 키를 넣는다

4. (LLM 답변을 쓰려면) /admin/models 에서 ANTHROPIC 키 등록

5. /admin/rag 에서 자료 출처를 등록하고 빌드 → 승인 → 활성화

6. 생성된 챗봇 UUID를 .env의 AXMS_PUBLIC_CHATBOT_ID에 넣고 spring-app 재기동

7. 포털에서 검색·챗봇 확인
```

3·4번은 건너뛸 수 있다. 건너뛰면 수집과 LLM 답변만 빠지고 나머지는 동작한다.

## 7. 정상 동작 확인

| 확인할 것 | 방법 | 정상 |
|---|---|---|
| 임베딩 서버 | `curl http://localhost:8900/health` | `model_loaded:true`, `dim:1024` |
| 스택 | `.\scripts\health.ps1 -Profile full` | 통과 |
| 관리 화면 | `http://127.0.0.1:18080/admin/rag` | 버전 목록이 보임 |
| 검색 | 포털에서 아무 질의 | 근거 카드가 뜸 |
| 챗봇 | 포털 우하단 위젯 | 답변이 옴 (404면 5번의 `AXMS_PUBLIC_CHATBOT_ID` 확인) |

## 8. 알아둘 제약

**임베딩 설정을 바꾸면 기존 벡터가 무효가 된다.** `embedding-server/rag/embed_bge.py`의
모델명·revision·`normalize_embeddings`·pooling·프리픽스·`batch_size`는 현재 DB에 저장된
문서 벡터를 만든 값이다. 바꾸면 기존 벡터와 새 질의 벡터가 다른 공간에 놓여 검색이
조용히 무너진다.

**팀원 PC에서 기준선 대조는 불가능하다.** 바이트 단위 대조에는 실험 작업실의 `vec.npy`가
필요한데 이 저장소에 없다. 실질적인 보증은 **고정 revision + `embed_bge.py` 무수정**이다.

**RAG 데이터는 PC마다 따로다.** DB가 로컬 컨테이너이므로 다른 사람이 만든 지식베이스·
버전·챗봇은 넘어오지 않는다. 각자 자기 PC에서 빌드해야 한다.
