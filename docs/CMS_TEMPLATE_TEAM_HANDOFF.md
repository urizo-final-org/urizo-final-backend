# CMS 템플릿 보완 — 팀원 LLM 적용 안내

작업: `axms-template-banner-layouts` · 패키지: `cms-template-v1` · 2026-09-11

템플릿 1(BOLD)·2(CLASSIC)의 디자인과 사진별 제목·설명·순서를 같은 데모로 맞춘다.
Git Pull, 실행 이미지/Schema 반영, CMS 업무 데이터 가져오기는 각각 별도 단계다.
이 문서 전달은 네트워크·재빌드·마이그레이션·데이터 적용의 일괄 승인이 아니다.

## 팀원이 LLM에 전달할 요청

```text
내 로컬 AX Module Studio에 최신 dev의 템플릿 보완과 템플릿 데모 데이터를 적용해줘.

1. 현재 Workspace/Master/Backend AGENTS.md와 Backend
   docs/CMS_TEMPLATE_TEAM_HANDOFF.md를 끝까지 읽어라.
   문서나 demo/cms-template-v1/manifest.json이 없으면 Git 상태부터 확인하고
   네트워크 최신화 승인을 요청하라. 구형 가져오기 도구로 대신 적용하지 마라.

2. 먼저 Git·Docker·Runtime·Flyway·현재 템플릿·Natural CMS Job을 읽기 전용으로 점검하라.
   수정 중인 파일, 개인 브랜치, 미병합 Commit, 기존 CMS 자료와 Docker Volume을 보존하라.
   계정/권한, AI 설정/Profile/Job/승인/이력, RAG 문서/임베딩을 보존하라.
   Reset/Stash/Rebase, DB 초기화, Volume 삭제, Flyway Repair/Clean,
   Job 강제 종료·승인·반려·삭제로 문제를 우회하지 마라.

3. 승인된 Git 최신화는 공식 Master sync-workspace.ps1을 사용하라.
   기본 작업폴더의 clean dev를 동기화하고 dirty/diverged/local-only 작업을 보존하라.
   보호 브랜치 main 승격은 포함하지 않는다.
   이 작업의 Frontend/Backend PR이 실제 dev에 병합됐는지 GitHub와 ancestry로 확인하라.
   기존 Backend PR #96만 포함됐다고 이번 템플릿 보완 완료로 판단하지 마라.

4. 필요한 재빌드/마이그레이션은 활성 SourceRoot·영향을 설명하고 승인 후 수행하라.
   Runtime,Database,Git Profile의 모든 Chunk를 읽고 공식 full 흐름을 사용하라.
   본문의 두 Revision 성공과 현재 Source 기준 Flyway pending 0을 확인하라.
   Git 최신화와 실행 이미지/Schema/CMS 업무 데이터 반영을 구분해서 보고하라.

5. 이 문서의 전용 템플릿 dry-run을 먼저 실행하라. 예전 cms-tour-v1 전체를 재적용하지 마라.
   두 템플릿 before/after, 이미지 생성/재사용·ID 매핑, 제목/설명/순서,
   이 템플릿을 사용하는 사이트 영향, 보존 목록, blockers와 planHash를 보여라.
   사이트가 사용 중이면 그 영향에 대한 선택을 받은 뒤 --allow-template-site를 붙여 다시 계획하라.
   이 옵션은 사이트 설정 변경이나 데이터 적용 승인이 아니다.
   미종료 Natural CMS Job이 있으면 쓰지 말고 차단 사유를 보고하라.

6. 내가 해당 planHash의 계획을 승인하고 CMS 동시 쓰기가 없음을 확인한 뒤에만 적용하라.
   같은 조건의 dry-run에서 SKIP=7, CREATE=0, UPDATE=0을 확인하라.
   MINIMAL, 메인 적용값, 사이트명/공개 경로/사용 여부, 기존 CMS/계정/AI/RAG를 보존하라.
   실패하면 자동 재시도하거나 journal을 삭제하지 말고 본문의 읽기 전용 복구를 따르라.

7. 마지막에 상태 / 결과 / 변경 / 검증 / 남은 사항 / 승인 순서로 보고하라.

이 요청은 사전 점검부터 시작하라는 의미이며 네트워크·재빌드·마이그레이션·
CMS 데이터 적용을 일괄 승인한 것이 아니다. 필요한 승인 지점에서 확인해라.
```

## 1. 공유 범위

| 포함 | 내용 |
|---|---|
| Frontend | 템플릿 1·2의 보완된 배치, 이미지 최대 5개, 사진별 제목/작은 설명, 파일 드롭, 드래그 순서 이동, 연결 해제, 메인에 적용 버튼 |
| Backend | 이미지 배열·캡션 저장/조회, 기존 단일 이미지·URL 배열 호환, Forward Migration 2개 |
| 데이터 2개 | BOLD·CLASSIC의 색상/레이아웃, Header/Footer, 메인 문구/버튼, 각 5개 사진의 순서와 제목/설명 |
| 이미지 5개 | 중복 제거한 PNG 5개, 총 13,772,261 bytes. 파일별 SHA-256과 형식/크기 검증 |

패키지는 `demo/cms-template-v1/manifest.json`과 같은 폴더의 PNG 파일이다.
`schemaVersion=2`, `minimumFlyway=20260911104548836`이며 전체 항목은 **7개**다.
JSON을 정규화하여 계산한 packageHash는
`31283a526a697605f1a939cbed5f008ca8c878bff20bbb659d00ab480126c125`다.
이는 PC 상태에 따라 달라지는 승인용 `planHash`와 다르다.

원본 PC 이미지 ID/URL, 작성자 UUID, 등록/수정 시각, 계정/Secret은 배포물에 없다.
사진은 파일 해시로 찾거나 대상 PC의 CMS API로 업로드하고, 그 PC의 ID로 연결한다.
같은 파일이 여러 ID로 있으면 가장 작은 ID를 재사용하므로 숫자 ID 자체는 원본 PC와 다를 수 있다.

다음은 보존한다.

- 기존 메뉴·콘텐츠·게시판·게시글·코드, 템플릿 3(MINIMAL), 그 밖의 CMS 자료.
- `cms_site`의 이름·공개 경로·사용 여부·현재 메인 templateKey. 템플릿의 기존 siteName도 PC별로 유지한다.
- 계정/권한/Secret, AI Profile/Snapshot/Job/Preview/Approval/이력, RAG/Embedding, Queue/Checkpoint, Volume.
- 기존 이미지 행과 파일. 이번 데이터 적용은 연결을 맞추며 원본 이미지를 삭제하지 않는다.

사용 중인 BOLD/CLASSIC의 저장값을 바꾸면 그 템플릿을 쓰는 사이트의 표현도 바뀐다.
`templateSiteImpacts`와 승인 계획에 이 영향을 포함하며, 사이트 설정값 자체는 변경하지 않는다.
메인에 적용 버튼은 수동 기능으로 제공한다. 패키지가 그 버튼을 대신 실행하지 않는다.

후속 AI05-020의 템플릿 자연어 제어 기능은 별도 작업이며 이 배포의 완료 항목이 아니다.

## 2. Source·Runtime 준비

| 저장소 | 배포 PR | 검증된 제품/도구 Commit |
|---|---|---|
| Frontend | [#80](https://github.com/urizo-final-org/urizo-final-frontend/pull/80) | `b0bb05820bb4dc8e934808fe15ac11dc137995d9` |
| Backend | [#97](https://github.com/urizo-final-org/urizo-final-backend/pull/97) | `666056f037701ad1dd6266ca2710f55afe079d94` |

위 Commit은 코드 검증 기준이다. 해당 PR의 최신 Head에는 문서의 배포 정보 보완이 추가될 수 있다.
팀원 적용 시 실제 PR의 MERGED 상태와 최신 Head의 origin/dev 조상 포함을 함께 확인한다.

Backend PR #96의 `b130c37fdbbad4177f057468992aece2a670c91a`는 이전 여행 데모 배포다.
이번 변경의 실제 PR/Head는 `axms-template-banner-layouts` 작업으로 조회하고,
PR의 base=dev, state=MERGED 및 해당 Head의 origin/dev 조상 포함을 검증한다.
문서가 존재한다는 사실만으로 실행 이미지·DB 적용을 판단하지 않는다.

1. Workspace/Master/활성 Backend/Frontend 지침과 필수 Profile을 읽는다.
   Database의 BackendSourceRoot는 실제 사용할 Backend 절대 경로를 지정한다.
2. 네트워크 승인 후 Master `scripts/sync-workspace.ps1 -ApproveNetwork`를 사용한다.
   Dirty canonical은 보존하며 관련 없는 clean Source 동기화를 계속한다.
3. 이미지/Schema가 오래됐으면 사용자 승인 후 공식
   `start-local-cms.ps1 -Profile full -Rebuild -ApproveNetwork -ApproveLocalMutation`에
   네 활성 SourceRoot를 전달한다. DB/Volume을 삭제하지 않는다.
4. Migration `20260911095234061__add_cms_template_hero_images.sql`과
   `20260911104548836__add_cms_template_image_captions.sql`의 성공과 checksum,
   전체 현재 Source 기준 pending 0, Profile health/HTTP를 확인한다.
   이미 적용한 Migration의 이름·본문·History를 변경하지 않는다.

DDL은 Flyway, 데모 업무 값은 아래 명시적 가져오기다. 두 Migration은 기존 업무 데이터를
덮어쓰지 않는 nullable JSONB 추가이며 사진/문구 동기화를 대신하지 않는다.
Importer의 minimumFlyway 확인은 전체 Flyway 검증을 대신하지 않는다.

## 3. dry-run과 사용 중인 사이트 영향

Backend 루트에서 Python 3.12 이상과 Docker CLI를 사용한다. 추가 pip 의존성은 없다.
Windows Store 실행 별칭을 실제 Python으로 오인하지 말고 실행 파일을 확인한다.
macOS/Linux에서는 확인한 python3로 같은 인자를 사용한다. 설치가 필요하면 먼저 승인받는다.

```powershell
python scripts/cms-demo/import_demo.py --package demo/cms-template-v1
```

기본 대상은 `http://127.0.0.1:18080`, Compose project `axms-spring-dev`, DB `ax_module_studio`다.
다르면 확인한 로컬 값으로 `--base-url`, `--project`, `--database`를 지정한다.
실제 Compose ingress와 Spring이 사용하는 DB의 일치를 검사하며 원격/Cloud/운영 DB는 지원하지 않는다.

dry-run은 로그인 없이 DB SELECT를 수행하고 로컬 결과 파일만 쓴다. CMS 쓰기는 0건이다.

```text
.local/cms-demo-share/cms-template-v1/
  plan.json
  before-approved-cms.json
  journal.json
  verification.json
```

이 파일에는 대상 PC의 기존 CMS 값과 ID가 들어갈 수 있다. Git에 추가하거나 다른 PC로 복사하지 않는다.
기존 `cms-tour-v1` journal과 분리되며, journal은 삭제/초기화하지 않는다.

`UNSELECTED_SITE_REFERENCES_TEMPLATE`이면 plan의 `templateSiteImpacts`에서 영향을 확인한다.
그 사이트의 표현이 바뀌어도 되는지 사용자에게 확인한 뒤 해당 **대상 PC의 사이트 key**를 지정한다.
예를 들어 실제 main이 BOLD 또는 CLASSIC을 사용할 때:

```powershell
python scripts/cms-demo/import_demo.py --package demo/cms-template-v1 --allow-template-site main
```

여러 사이트가 있으면 실제 key마다 옵션을 반복한다. 이 옵션은 현재 사이트 설정을 그대로
보존한 채 템플릿 디자인 영향만 계획에 포함한다. 존재하지 않는 key나 구형 schemaVersion=1
패키지의 사이트 보호 우회 용도로는 사용할 수 없다.

계획의 각 `before/after`, 이미지 참조/매핑, `CREATE/UPDATE/SKIP`, blockers와 planHash를 검토한다.
미종료 Natural CMS Job이 하나라도 있으면 `NONTERMINAL_NATURAL_CMS_JOBS`로 차단한다.
이 패키지를 적용하려고 Job을 강제 종료/반려/삭제하지 않는다.
이미지 5개와 템플릿 2개의 첫 실행 건수는 PC의 기존 데이터에 따라 달라진다.

## 4. 승인 후 적용

사용자가 해당 계획을 승인하고, 해당 PC에서 CMS 수동 편집·다른 import·Natural CMS 신규 실행/
승인/재개가 없는 작업 시간을 확인한 뒤에만 수행한다. `--confirm-quiescent`는 실제 잠금이 아니다.
API 여러 요청은 하나의 트랜잭션이 아니며 요청 직전 확인과 HTTP 저장 사이의 동시 쓰기를
원자적으로 막지 못한다. 동시 쓰기를 배제할 수 없다면 적용하지 않는다.

계획 때 사용한 대상/사이트 옵션을 동일하게 유지한다.

```powershell
python scripts/cms-demo/import_demo.py --package demo/cms-template-v1 --apply --approve-plan <검토한-planHash> --confirm-quiescent
```

사이트 영향 옵션이 있었던 경우:

```powershell
python scripts/cms-demo/import_demo.py --package demo/cms-template-v1 --allow-template-site main --apply --approve-plan <검토한-planHash> --confirm-quiescent
```

관리자 인증은 대화형 프롬프트에 로컬 사용자가 직접 입력한다. 이미 구성된 로컬 데모 관리자
계정 사용을 별도로 승인한 경우에만 `--use-local-demo-account`를 추가한다. 비밀번호를 채팅/
명령 인자/파일/Git에 넣거나 계정 재설정·인증 우회를 하지 않는다.
정상 로그인은 해당 PC에 새 인증 세션을 만들 수 있지만 계정 자체를 변경하거나 복제하지 않는다.

계획 이후 CMS/Job/보존 대상이 바뀌면 `STALE_PLAN` 등으로 중단한다. 새 계획과 승인이 필요하다.
사진별 제목·설명·순서도 Snapshot, 승인 hash와 적용 후 비교에 포함된다.

## 5. 실패·복구·완료 검증

HTTP 응답을 못 받았어도 서버 저장이 완료됐을 수 있다. 자동 재전송하지 않는다.
`INFLIGHT_REQUEST`이면 journal/백업을 보존하고 동시 쓰기가 없음을 확인한 뒤 읽기 전용 복구를 실행한다.

```powershell
python scripts/cms-demo/import_demo.py --package demo/cms-template-v1 --recover --confirm-quiescent
```

복구는 기대 결과와 일치하는 행이 하나이며 기존 CMS/Job/이력의 보존 조건을 만족할 때만
로컬 journal을 정리한다. CMS 데이터는 쓰지 않는다. `RECOVERY_AMBIGUOUS_OR_NOT_COMMITTED`이면
추측하지 말고 담당자가 상태를 조사한다. journal 삭제·SQL 강제 덮어쓰기·전체 DB 초기화로 우회하지 않는다.
원인 해결 후 같은 조건으로 dry-run → 새 계획 승인 → apply를 수행한다. 자동 rollback/delete는 없다.

완료 조건:

1. `CMS_IMPORT_PASS`, `protectedData=PRESERVED` 확인.
2. 동일 패키지·DB·사이트 옵션·journal로 재 dry-run하여 **SKIP=7, CREATE=0, UPDATE=0** 확인.
   출력에 없는 CREATE/UPDATE 키는 0건이다.
3. BOLD/CLASSIC의 5개 이미지와 제목·설명·순서, 정상 이미지 GET과 관리자 Preview 확인.
4. MINIMAL, 사이트명/경로/사용 여부/메인 적용값, 기존 메뉴/콘텐츠/게시판/코드 불변 확인.
5. 계정·AI 설정/Job/이력·RAG 등 보호 테이블과 기존 CMS 이력 보존 확인.
   정상 로그인 세션과 해당 PC에서 새로 생성한 CMS 이력은 별도로 구분한다.
6. 실행하지 않은 팀원 PC/macOS/Linux, 외부 Langfuse/Checkpoint DB까지 검증했다고 보고하지 않는다.

## 6. 이전 여행 데모와의 차이

`cms-tour-v1`은 기존 메뉴/콘텐츠/게시판 등 **142개** 데모 항목이며 그대로 유지한다.
그 manifest에는 MINIMAL과 main.templateKey=MINIMAL이 포함되어 있으므로 이번 작업을 이유로
재적용하지 않는다. 이번 패키지에 `SKIP=142`를 요구하지 않는다.

`cms-template-v1`은 템플릿 2개+이미지 5개만 동기화한다. 기존 여행 데모에 없는 자료를 자동
생성하거나 사이트 적용값을 바꾸지 않는다. 두 패키지의 planHash/journal을 섞지 않는다.

## 개발 검증

2026-09-11 배포 후보 검증:

- 기존 가져오기 23개 + 템플릿 12개 계약 테스트 **35/35 PASS**.
- 격리 PostgreSQL 16에 현재 Flyway **46개**를 적용한 실제 Controller/Service/JPA 검증 PASS.
  기존 여행 데모 142개와 새 템플릿 7개 모두 재실행 시 CMS 쓰기 0건.
- 템플릿 2개/이미지 5개의 다른 PC ID 매핑, 실제 이미지 GET 해시, 사진별 캡션/순서,
  사이트 설정·MINIMAL·기존 CMS/계정/AI/RAG 보존, 템플릿 저장 응답 유실 후 읽기 전용 복구 PASS.
- Frontend **514/514**, 타입 검사와 production build PASS.
- 원본 PC dry-run은 SKIP=5 / UPDATE=2이며 미종료 Natural CMS Job 9개로 차단됐다.
  같은 이미지 바이트가 원본 DB의 여러 ID에 존재하므로 패키지는 재사용 ID로 연결을 정규화한다.
  원본 PC에 import 쓰기는 수행하지 않았다. 이것을 팀원 PC 적용 완료로 보고하지 않는다.

DB 쓰기 없는 계약 테스트:

```powershell
python -m unittest discover -s scripts/cms-demo -p 'test_*.py' -v
```

격리 DB 검증은 팀원 적용의 필수 단계가 아니다. 개발자가 승인받은 뒤 기존
`scripts/cms-demo/verify-fixture.ps1 -PythonExecutable <절대경로> -MavenExecutable <절대경로> -MavenRepository <캐시경로> -ApproveIsolatedDatabaseMutation`
을 사용한다. 임시 tmpfs DB만 생성·정리하며 공유 DB/Volume을 쓰지 않는다.
인증은 test principal이므로 실제 로그인/보안 필터 검증과 구분한다.
