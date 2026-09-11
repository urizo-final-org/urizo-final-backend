# CMS 여행 데모 데이터 — 팀원 LLM 적용 안내

작업: `axms-cms-demo-share` · 패키지: `cms-tour-v1` · 작성: 2026-09-11

목표는 **공개 CMS 메뉴·콘텐츠·게시판 샘플·코드·선택 사이트의 표현을 같은 데모로 맞추는 것**이다.
DB 전체 복제나 AI 2~6번의 데이터 동기화가 아니다. DDL은 기존 Flyway, 데모 업무 값은 이 문서의 명시적 가져오기로 분리한다.
이 문서의 전달만으로 해당 PC의 Git 네트워크·재빌드·DB 적용이 승인되는 것은 아니다. 로컬 사용자의 승인을 따른다.

## 팀원이 LLM에 전달할 요청

```text
현재 Workspace/Master/Backend AGENTS.md를 따르고,
Backend docs/CMS_DEMO_TEAM_HANDOFF.md를 끝까지 읽어라.

목표: dev 소스와 CMS 여행 데모를 내 로컬에 적용하되 내 계정, AI 2~6번 설정/Job/이력,
RAG 문서/임베딩, 기존 CMS 자료와 Docker Volume을 보존한다.

먼저 현재 Git/Runtime 상태를 읽기 전용으로 확인하고 필요한 승인을 요청하라.
전체 최신화가 승인되면 Master 공식 sync-workspace.ps1을 사용하고 dirty 상태를 보존하라.
실행 이미지/스키마 갱신이 필요하면 공식 Runtime/Database/Git Gate를 따라 승인 후 실행하라.

CMS 패키지는 scripts/cms-demo/import_demo.py의 dry-run부터 실행하라.
같은 제목이라는 이유로 기존 자료를 덮어쓰지 말고, 충돌이 있으면 이 PC의 ID로
bindings.json을 작성하여 생성/수정/보존 목록과 planHash를 한 번에 보여라.
미종료 Natural CMS Job은 자동 승인/반려/삭제하지 말고 차단 사유를 보고하라.

내가 계획을 승인하고 CMS 동시 쓰기가 없음을 확인한 뒤에만 --apply를 실행하라.
적용 후 같은 조건의 dry-run에서 CREATE=0, UPDATE=0을 확인하고,
이미지/메뉴/게시판을 재조회한 다음 계정·AI·RAG 보존 결과와 미검증 항목을 보고하라.
```

## 배포물과 경계

| 포함 | 개수 / 내용 |
|---|---|
| 콘텐츠 | 8개: 소개, 가치, 취향, 지역, 여행 찾기, 체크리스트, 이용 안내, FAQ |
| 게시판 / 게시글 | 게시판 3개, 이번 세션의 가상 샘플 각 30개(합계 90개) |
| 공개 메뉴 | 15개, 경로·부모·표시 순서·대상 연결 |
| 코드 | 4개 그룹, 코드 17개 |
| 이미지 | 본문/대표 이미지용 PNG 3개; AI 생성 시연 이미지 |
| 표현 설정 | `MINIMAL` 템플릿의 표시 필드, `main` 사이트의 이름·경로·템플릿 연결·사용 여부 |

- 원본 PC의 숫자 ID, 작성자 UUID, 등록/수정 시각, 개인 테스트 메뉴와 기존 게시글은 패키지에 없다.
- 계정/권한/세션, `ai_*`, `natural_cms_*`, `coding_*`, RAG/Connector/Knowledge/Embedding,
  Queue/Outbox/Checkpoint, Secret, 원본 `cms_change_history`, Flyway history는 가져오지 않는다.
- 새 행의 ID와 작성자는 대상 PC의 CMS API가 부여한다. 명시적으로 선택한 기존 행을 수정할 때
  그 PC의 ID·작성자·등록 시각을 유지한다. 변경 이력은 대상 PC의 기존 CMS API가 새로 기록한다.
- 다른 CMS 자료는 삭제하지 않는다. 따라서 팀원별 전체 목록 건수·기존 추가 게시글·작성자·시각과
  RAG 검색 결과는 다를 수 있다. 이것을 전체 DB 동일화 완료로 보고하지 않는다.
- 관리자 사이드바는 Frontend 소스다. `cms_menu`는 공개 사이트 메뉴이며 AI 운영 사이드바가 아니다.

## 1. Source와 Runtime 준비

1. Workspace/Master/Backend 지침과 `Runtime,Database,Git` Profile의 모든 Chunk를 읽는다.
   `BackendSourceRoot`에는 이 PC에서 실제 사용할 Backend의 절대 경로를 전달한다.
2. 승인된 전체 최신화는 Master `scripts/sync-workspace.ps1 -ApproveNetwork`를 사용한다.
   dirty/diverged/local-only 작업을 Reset/Stash/Rebase하거나 자동으로 흡수하지 않는다.
3. 이번 패키지가 있는 Backend `dev`를 확인한다. 최소 선행 구현은 Backend
   `bf9c7a7971329298a3cd48b133c2ae7b92c4e353`, Frontend
   `c5525c35167a747aa4049efa498f541a9acba009`다. SHA 문자열 비교가 아니라 해당 Commit의 조상 포함 여부를 확인한다.
4. 오래된 이미지나 스키마이면 로컬 담당자 승인 후 공식 `start-local-cms.ps1` 흐름을 사용한다.
   전체 소스 갱신/재기동이면 `full -Rebuild -ApproveNetwork -ApproveLocalMutation`과 네 활성 SourceRoot를 전달한다.
   기존 DB/Volume을 삭제하지 않는다. health와 Flyway pending 0을 확인한다.
5. 필요한 CMS Revision은 `20260911060503254`. 가져오기 스크립트는 이 Revision의 성공 여부만 점검하며,
   전체 Flyway checksum/현재 Source 동기화 검증이나 재빌드를 대신하지 않는다.
   history 불일치를 Repair/Clean/기존 Migration 수정으로 우회하지 않는다.

`127.0.0.1:18080`을 기본값으로 사용한다. 다른 포트이면 `--base-url`을 명시한다.
스크립트는 실행 중인 동일 Compose project의 nginx loopback 포트와 DB 컨테이너를 확인한다.
원격 서버·Cloud·운영 DB는 지원하지 않는다.

## 2. 실행 준비와 dry-run

Backend 루트에서 실행한다. 이미 설치된 Python 3.12 이상과 Docker CLI만 필요하며 pip 패키지는 없다.
LLM은 실제 실행 가능한 Python을 확인한다. Windows Store 실행 별칭을 실제 Python으로 오인하지 않는다.
Python이 없으면 설치/대체 실행 환경을 사용자에게 먼저 승인받는다. 임의 설치나 Docker socket의 다른 컨테이너 공유로 우회하지 않는다.

```powershell
python scripts/cms-demo/import_demo.py
```

macOS/Linux에서는 확인한 `python3` 실행 파일로 같은 인자를 사용한다.
기본 Compose project는 `axms-spring-dev`, DB는 `ax_module_studio`다.
다르면 이 PC의 확인된 값을 `--project`, `--database`로 전달하며 추측하지 않는다.

dry-run은 로그인하지 않고 **DB SELECT와 공개 이미지 GET만 수행**한다.
CMS/AI/계정/세션 쓰기는 0건이며, 결과 파일만 Backend의 Git 제외 경로 아래 생성한다.

```text
.local/cms-demo-share/cms-tour-v1/
  plan.json                  계획, 이전 대상 값, 변경할 값, 차단 사유, planHash
  before-approved-cms.json    적용 직전 승인된 기존 CMS 행 백업
  journal.json               이 PC의 DB 식별자·manifest 해시·ID 매핑·미확정 요청
  verification.json          마지막 성공 검증 결과
```

이 로컬 파일에는 기존 CMS 본문/작성자 정보가 포함될 수 있다. Git에 추가하거나 팀원에게 복사하지 않는다.
`journal.json`은 DB/패키지에 귀속되므로 삭제/초기화하지 않고 보관한다.
이 도구는 전체 DB 백업을 만들지 않는다. 별도 백업이 필요한 PC에서는 먼저 담당자가 승인한 방법으로 준비한다.

## 3. 충돌을 판단하고 대상 매핑하기

자동 식별은 메뉴의 유일한 경로, 코드 그룹 키+코드 값, 템플릿/사이트 키, 이미지 바이트 해시에 한정한다.
콘텐츠·게시판은 일치하는 제목/이름이 있더라도 자동 덮어쓰지 않는다.
같은 제목의 게시글도 내용·보드·분류·이미지까지 일치할 때만 건너뛴다.

`EXPLICIT_BINDING_REQUIRED`가 나오면 로컬 LLM이 해당 PC의 기존 자료와 참조를 확인하고
Git 제외 파일 `.local/cms-demo-share/bindings.json`을 만든다. 예시의 숫자를 다른 PC에 복사하면 안 된다.

```json
{
  "content:about": "new",
  "board:notices": "new",
  "board:announcements": "new",
  "board:travel": "new"
}
```

- `"new"`: 기존 동일 제목 자료를 보존하고 패키지용 새 행을 만든다는 명시적 선택이다.
  처음 적용 시 기본적으로 새 콘텐츠·게시판을 만드는 것이 기존 AI 이력의 대상 의미를 보존하기 쉽다.
- 양의 정수 ID: 담당자가 이 PC의 그 행을 데모 값으로 수정하기로 승인한 경우에만 사용한다.
  기존 콘텐츠/게시판을 가리키는 미선택 메뉴나 다른 사이트가 있으면 차단할 수 있다.
- 같은 대상에 두 항목을 매핑하거나 삭제된 ID를 재사용하지 않는다. 숫자 ID의 원본 PC 일치를 기대하지 않는다.
- 기존 코드의 같은 키에 다른 label/사용 여부/순서가 있으면 보존을 위해 차단한다.
  스크립트에서 차단을 제거하거나 SQL로 밀어 넣지 말고 별도 조정 방향을 담당자에게 확인한다.
- 이미 다른 자료에 쓰는 게시판의 분류 그룹 변경은 기존 CMS API 검증으로 거절될 수 있다.
  이 경우 기존 게시글 분류를 자동 해제하지 말고 새 게시판 선택을 검토한다.

```powershell
python scripts/cms-demo/import_demo.py --bindings .local/cms-demo-share/bindings.json
```

`plan.json`의 `CREATE / UPDATE / SKIP`, 각 `before / after`, ID/메뉴 연결과 `blockers`를 확인한다.
LLM은 전체 본문을 채팅에 노출하지 않고 대상 이름·개수·영향을 요약하여 로컬 사용자에게 계획 승인을 요청한다.

## 4. 적용 승인과 동시 작업 경계

다음 조건을 모두 만족해야 한다.

- `blockers`가 비어 있고 사용자가 바로 그 `planHash`의 변경 목록을 승인했다.
- 해당 PC에서 CMS 수동 편집·다른 import·Natural CMS 신규 실행/승인/재개를 하지 않는 작업 시간이 확보됐다.
- `ACTIVE`, `WAITING_APPROVAL` 등 미종료 Natural CMS Job이 **하나도 없다**.
  보수적인 v1 규칙으로 대상이 다른 Job도 차단한다. 종료는 담당자가 기존 업무 흐름에서 판단하며
  이 패키지를 적용하려고 강제 완료/반려/삭제하거나 Job 테이블을 수정하면 안 된다.

**API 여러 요청은 전체가 한 트랜잭션이 아니고 서버 전역 잠금도 아니다.**
각 요청 직전 CMS/Job snapshot을 다시 비교하고 요청 뒤 다른 행·기존 이력의 보존을 확인하지만,
다른 쓰기 주체가 비교와 HTTP 저장 사이에 끼어드는 경쟁을 원자적으로 막지는 못한다.
동시 쓰기를 배제할 수 없다면 `--confirm-quiescent`를 사용하지 말고 적용을 중단한다.
이 플래그는 실제 잠금/스케줄러 중지가 아니라 담당자의 확인이다.

```powershell
python scripts/cms-demo/import_demo.py --bindings .local/cms-demo-share/bindings.json --apply --approve-plan <확인한-planHash> --confirm-quiescent
```

인증은 기존 CMS 관리자 계정으로 한다. 대화형 로그인 프롬프트에는 로컬 사용자가 직접 입력한다.
이미 구성된 로컬 데모 최고관리자 계정 사용을 승인한 경우에만 `--use-local-demo-account`를 추가할 수 있다.
이 옵션은 현재 앱 컨테이너의 설정/Backend 기본 설정을 메모리에서 읽으며 값을 출력하거나 패키지에 저장하지 않는다.
계정이 변경됐거나 로그인이 실패하면 멈춘다. 비밀번호 재설정·새 계정 생성·인증 우회는 포함하지 않는다.

적용은 정상 로그인으로 이 PC에 인증 세션을 만들 수 있다. 계정 자체/다른 계정의 세션을 복사하거나 덮어쓰지 않는다.
`cmsWrites=0`은 로그인 세션 생성까지 포함한 DB 전체 쓰기 0건이라는 뜻이 아니다.

계획 뒤 데이터/Job/보존 대상이 달라졌으면 `STALE_PLAN`으로 중단한다. 새 dry-run과 새 승인이 필요하다.

## 5. 중간 실패와 재실행

- 성공한 요청마다 대상 PC의 ID와 검증 결과를 journal에 기록한다.
- HTTP 실패를 자동 재시도하지 않는다. 응답을 못 받았어도 서버 저장은 끝났을 수 있다.
- `INFLIGHT_REQUEST`이면 같은 PC에서 동시 쓰기가 없음을 확인하고 아래 **읽기 전용 복구**를 실행한다.

```powershell
python scripts/cms-demo/import_demo.py --recover --confirm-quiescent
```

기대 내용과 일치하는 결과가 하나이고 이전 snapshot의 다른 행이 보존됐을 때만 그 ID를 journal에 기록한다.
`RECOVERY_AMBIGUOUS_OR_NOT_COMMITTED`이면 변경 여부를 확정할 수 없다. journal/백업을 보존하고 담당자가 조사한다.
journal 삭제, 같은 POST 재전송, 새 DB 초기화로 우회하지 않는다.

복구 또는 원인 해결 뒤 동일 bindings로 dry-run → 변경된 계획 승인 → apply를 진행한다.
이미 완료된 항목은 건너뛴다. 자동 Rollback, DELETE, TRUNCATE, Cascade 정리, 시퀀스 재설정은 없다.
필요한 복구 쓰기는 백업과 현재 이력/참조를 확인한 뒤 별도 승인한다.

## 6. 적용 완료 판단

1. 출력 `CMS_IMPORT_PASS`, `protectedData=PRESERVED`를 확인한다.
2. 같은 패키지·DB·bindings·journal로 dry-run을 다시 실행해 `SKIP=142`, `CREATE=0`, `UPDATE=0`을 확인한다.
3. 로그인 계정과 기존 AI 설정/Job/이력, RAG 자료를 보존했는지 확인한다.
   스크립트는 CMS 9개 테이블·정상 로그인 세션·신규 CMS 이력을 제외한 `app` 기본 테이블들과 Flyway history를 전후 비교한다.
   기존 CMS 이력 행은 별도 비교하고 새 CMS 변경 이력 추가만 허용한다.
   외부 Langfuse/별도 Checkpoint DB는 읽거나 변경하지 않았으며, 이를 통합 검증했다고 보고하지 않는다.
4. 공개 사이트 `main`의 `/`, 콘텐츠 8개, 샘플 게시글 90개, 게시판 분류/대표 이미지, 메뉴의 부모와 대상 연결을 재조회한다.
5. 기존 개인 자료가 남아 있는 것은 정상이다. RAG 통합검색 결과까지 원본 PC와 같아야 하는 검증은 아니다.
6. macOS/Linux 등 해당 PC에서 실제 실행하지 않은 환경은 검증 완료라고 보고하지 않는다.

## 개발 검증 재현

2026-09-11 배포 전 검증 결과:

- Python 계약 테스트 23개 통과: ID 재매핑, no-op, 충돌/삭제 ID/기존 코드 보호, Job 차단,
  계획 뒤 및 요청 사이 상태 변경 감지, 응답 유실 복구, 이미지 무결성, 원격 URL 차단.
- 기존 Backend CMS 게시판/코드 서비스 테스트 15개 통과.
- 별도 PostgreSQL 16 fixture에 기존 Flyway 44개 적용 후 실제 CMS Controller/Service/JPA 저장 검증 통과.
  142개 항목, 게시글 90개, 두 번째 실행 쓰기 0건, 이미지 응답 유실 복구와 기존 계정·AI Profile/Job·Coding Job·RAG 문서/임베딩·CMS 이력 보존 확인.
- 원본 PC 읽기 전용 대조는 142개 모두 `SKIP`. 미종료 Natural CMS Job 9개에 의해 쓰기 적용은 차단됐으며 현재 PC에 import 쓰기를 실행하지 않았다.
- 실제 팀원 PC 적용, 해당 PC의 로그인/보안 필터와 macOS/Linux 실행은 별도 확인 대상이다.

빠른 스크립트 계약 테스트는 DB 쓰기 없이 실행한다.

```powershell
python -m unittest discover -s scripts/cms-demo -p 'test_*.py' -v
```

개발자용 격리 DB 검증은 일반 팀원 적용의 필수 단계가 아니다. 이미 준비된 로컬 PostgreSQL/Flyway 이미지,
JDK 21·Maven 3.9.9 캐시와 Python이 필요하다. 별도 승인 후에만 실행한다.

```powershell
./scripts/cms-demo/verify-fixture.ps1 -PythonExecutable <Python-절대경로> -MavenExecutable <Maven-절대경로> -MavenRepository <기존-Maven-캐시> -ApproveIsolatedDatabaseMutation
```

이 검증은 tmpfs 기반 새 PostgreSQL 컨테이너에 현재 Flyway를 적용하고 실제 CMS Controller/Service/JPA와
변경 이력 저장을 사용한다. 기존 full Compose를 재시작하지 않는다. 인증은 테스트 principal이므로
실제 로그인/보안 필터·전체 full E2E 검증과 구분한다. 종료 시 이 테스트 컨테이너만 제거한다.

## 금지된 우회

`cms_*` 통째 덤프/덮어쓰기, Flyway에 데모 DML 추가, 원본 PK/작성자 UUID 강제 복사,
AI Job/Preview/Approval 삭제, 계정·Provider Secret 복제, DB/Volume 초기화,
대기 Job 차단/planHash 검증 제거는 이 배포 범위에 포함하지 않는다.
