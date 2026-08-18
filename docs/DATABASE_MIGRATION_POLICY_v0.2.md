# AX Module Studio Spring·Flyway DB Migration Policy v0.2

> Authority scope: 이 문서는 Backend 전용 Core/Flyway 불변조건만 규정한다. 공통 Git/PR, 역할, Wave/Slice, 승인 및 안전 정책은 sibling Master만 소유하며 이 문서가 복제하거나 변경하지 않는다.
>
> 상태: Spring Primary 후보의 Core DB Schema 정책

## 1. 소유권

```text
Core PostgreSQL              → Backend Git의 Flyway만 DDL 소유
LangGraph Checkpoint DB      → Orchestrator Checkpointer 전용 Migration
FastAPI Fallback DB          → 전환 승인 뒤 별도 Alembic Chain
```

세 Chain은 Database·Role·Volume·History를 공유하지 않는다.

## 2. Core DB 불변조건

- Runtime Role은 DML만 허용하고 DDL을 거부한다.
- Migration Role만 DDL을 가진다.
- `spring.jpa.hibernate.ddl-auto=validate|none`
- Spring AI PGvector 자동 초기화 비활성화
- Spring Batch Metadata 자동 초기화 비활성화
- Extension·Table·Index·Constraint·Batch Metadata·System Seed Version은 Flyway로 관리

## 3. Revision 규칙

파일 형식은 다음을 기본으로 한다.

```text
db/migration/VYYYYMMDDHHMMSS__lower_snake_description.sql
```

- 이미 `dev`에 Merge됐거나 다른 DB에 적용된 Revision은 수정하지 않는다.
- 변경이 필요하면 새 Forward Revision을 추가한다.
- 개인 Test Data·공공 API 결과·RAG Document·Embedding·CMS 콘텐츠를 Migration에 넣지 않는다.
- Reference/System Seed만 명시적 Version과 멱등 조건으로 관리한다.

## 4. PR 전 필수 검증

1. 빈 Core DB에서 Head까지 Upgrade
2. PR 직전 `origin/dev` Head에서 새 Head까지 Upgrade
3. `flyway_schema_history` 단일 성공 History
4. 반복 실행 시 미적용 Revision 0건·변경 0건
5. Runtime Role의 DDL 시도 실패
6. Hibernate·Spring AI·Spring Batch 자동 DDL 0건
7. 필요한 Extension·Index·Constraint 존재 확인

이 검증이 없으면 완료·Commit·Push·PR로 보고하지 않는다.

## 5. 팀 동기화

```text
Backend dev Pull
→ Flyway One-shot 실행
→ History와 비교해 미적용 SQL만 적용
→ 성공 종료
→ Spring Runtime 시작
```

Git은 Schema Code만 동기화한다. 팀원의 Local 업무/RAG/Test Data는 동기화하지 않는다.

## 6. DBeaver

- Host `127.0.0.1`의 Dev 전용 Port만 노출한다.
- `dbeaver_reader` SELECT 권한만 제공한다.
- Runtime·Migration Credential을 DBeaver Profile에 등록하지 않는다.
- DML·DDL은 권한과 운영 규칙 모두에서 금지한다.

## 7. 파괴적 작업

DB·Volume 삭제, Baseline 재작성, Repair, Clean, History 수정은 별도 명시적 승인과 복구 계획 없이는 실행하지 않는다.
